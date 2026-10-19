// Быстрый ввод одной строкой — как Quick Add у Todoist: «Позвонить Ивану завтра в 15 #Альфа @звонок p1». Распознанное
// подсвечивается прямо в строке и уходит из названия в поля дела. Свой разбор, без Claude: мгновенно и бесплатно.
//
// Понимает (русский и английский):
//   сроки — сегодня, завтра, послезавтра, в пятницу, к среде, до пт, в следующий вт, на следующей неделе, на выходных,
//           через 3 дня, через три дня, через несколько дней, через неделю, в течение недели, через час, через 30 минут,
//           25 окт, 25 октября 2027, 25.10, 25/10, 25 число, до 5 числа, в начале / середине / в конце ноября,
//           конец месяца, в конце года, в следующем месяце, когда-нибудь, за 3 дня до 8 марта, праздники (в день победы,
//           к новому году, 8 марта), без срока; время — в 15, в 15:30, в 9 утра, в 7 вечера, утром, днём, вечером,
//           ночью, 15:30;
//   повторы — каждый день, ежедневно, каждое утро, каждый рабочий день, по будням, пн-пт, каждый пн и чт, по пятницам,
//           каждую неделю, каждые 2 недели по пт, каждые 2 пятницы, каждый месяц, раз в месяц, дважды в месяц,
//           каждое 15 число, каждый последний день, каждое 2 апреля, каждый год, каждые 3 дня, каждые! 3 дня и после
//           3 дней (от выполнения), каждую вторую неделю, … до 30 дек, … с 5 по 10 ноября, … в течение 3 недель;
//   #проект или сделка, /раздел, @метка или %метка, +человек, p1…p4, {дедлайн}, !сейчас, « // описание» в конце.
//
// Сознательно НЕ понимает — фраза остаётся в названии: N-й и последний день недели месяца («каждый 3 понедельник»,
// «каждый последний пн», «в последнюю пятницу»), разовый промежуток «с 5 по 10 ноября». Модель повтора их не держит, а
// узнать неправильно хуже, чем не узнать: дело молча уедет не туда.

import { addDays, addMonths, daysInMonth, mondayOf, nextWeekday, weekday, ymd } from './dates.ts';
import type { Recur } from './types.ts';

export type TokKind = 'date' | 'project' | 'section' | 'label' | 'priority' | 'person' | 'deadline' | 'desc' | 'now' | 'remind';

export interface Tok {
  start: number;
  end: number;
  kind: TokKind;
}

export interface Named {
  id: string;
  name: string;
  short?: string | null;
  project_id?: string;
  deal?: boolean;
}

export interface Ctx {
  today: string;
  now?: string; // ЧЧ:ММ — время без даты: сегодня, а если уже прошло — завтра
  projects: Named[]; // проекты и сделки (deal: true, project_id — клиент)
  sections: Named[]; // project_id
  labels: string[];
  people: Named[];
  places?: string[]; // места напоминаний с телефона («дом», «Летово») — «!дома»
}

export interface When {
  due_date: string | null;
  due_time: string | null;
  recur: Recur | null;
  due_rule: string | null;
  clear?: boolean; // «без срока»
}

export interface Parsed extends Partial<When> {
  title: string;
  tokens: Tok[];
  priority?: number;
  project_id?: string | null;
  deal_id?: string | null;
  section_id?: string | null;
  labels: string[];
  person_id?: string | null;
  deadline?: string | null;
  notes?: string | null;
  now?: boolean;
  remind_when?: { date: string | null; time: string }; // «!15:00», «!завтра в 9» — день решает тот, кто заводит
  remind_place?: string | null; // «!дома»
}

// ── Слова ────────────────────────────────────────────────────────────────

interface W {
  s: string; // как написано
  w: string; // нижним регистром, ё → е, без хвостовой пунктуации
  start: number;
  end: number;
}

function words(text: string, from = 0, to = text.length): W[] {
  const out: W[] = [];
  const re = /\S+/g;
  const part = text.slice(from, to);
  let m: RegExpExecArray | null;
  while ((m = re.exec(part))) {
    let s = m[0];
    let end = from + m.index + s.length;
    // Хвостовая пунктуация — не часть слова («в пятницу,»), но «!» у «каждые!» — часть.
    const tail = s.match(/[,;:.?)»"]+$/);
    if (tail && !/^\d{1,2}[:.]\d{2}$/.test(s) && !/^\d{1,2}[./]\d{1,2}([./]\d{2,4})?$/.test(s)) {
      s = s.slice(0, -tail[0].length);
      end -= tail[0].length;
    }
    if (!s) continue;
    out.push({ s, w: s.toLowerCase().replace(/ё/g, 'е'), start: from + m.index, end });
  }
  return out;
}

// «…ам» — «по понедельникам»: его переписывания превращают в «каждый понедельникам», дальше обычный повтор.
const WEEKDAY_FORMS: [RegExp, number][] = [
  [/^(понедельник(а|е|у|ам)?|пн|пон|mon|monday)$/, 0],
  [/^(вторник(а|е|у|ам)?|вт|вто|tue|tues|tuesday)$/, 1],
  [/^(сред(а|е|у|ы|ам)|ср|wed|wednesday)$/, 2],
  [/^(четверг(а|е|у|ам)?|чт|чет|thu|thur|thurs|thursday)$/, 3],
  [/^(пятниц(а|е|у|ы|ам)|пт|пят|fri|friday)$/, 4],
  [/^(суббот(а|е|у|ы|ам)|сб|суб|sat|saturday)$/, 5],
  [/^(воскресень(е|я|ю|ям)|вс|вск|воскр|sun|sunday)$/, 6],
];
export function wdOf(w: string): number | null {
  for (const [re, n] of WEEKDAY_FORMS) if (re.test(w)) return n;
  return null;
}

const MONTH_FORMS: [RegExp, number][] = [
  [/^(январ[яьюе]|янв|jan|january)$/, 1], [/^(феврал[яьюе]|фев|feb|february)$/, 2], [/^(март[ае]?|мар|mar|march)$/, 3],
  [/^(апрел[яьюе]|апр|apr|april)$/, 4], [/^(ма[йяюе]|may)$/, 5], [/^(июн[яьюе]|июн|jun|june)$/, 6],
  [/^(июл[яьюе]|июл|jul|july)$/, 7], [/^(август[ае]?|авг|aug|august)$/, 8], [/^(сентябр[яьюе]|сен|сент|sep|sept|september)$/, 9],
  [/^(октябр[яьюе]|окт|oct|october)$/, 10], [/^(ноябр[яьюе]|ноя|нояб|nov|november)$/, 11], [/^(декабр[яьюе]|дек|dec|december)$/, 12],
];
function monthOf(w: string): number | null {
  for (const [re, n] of MONTH_FORMS) if (re.test(w)) return n;
  return null;
}

const PREP = new Set(['в', 'во', 'на', 'к', 'ко', 'до', 'by', 'on', 'с', 'со']);
const NEXT = /^(следующ(ий|ую|ая|ее|ей|его|им)|след|next)$/;
const THIS = /^(эт(от|у|а|ой|ом)|this|ближайш(ий|ую|ая|ее|ей))$/;

// ── Переписывания перед разбором ─────────────────────────────────────────
//
// Как у Todoist (его движок дат сперва переписывает фразу, потом ищет короткое правило), но правила свои: падежи,
// синонимы, числа словами и праздники сводятся к немногим формам, которые понимают правила ниже. «по будням» →
// «каждый рабочий день», «через три дня» → «через 3 дня», «в день победы» → «в 9 мая». Новое слово помнит место
// исходной фразы в строке: подсветка и название идут по тому, что человек написал, и в due_rule уходят его слова.

type Test = RegExp | ((w: string) => boolean);
type Rule = (ws: W[], i: number) => { len: number; to: string[] } | null;

const fits = (w: W | undefined, t: Test): boolean => !!w && (typeof t === 'function' ? t(w.w) : t.test(w.w));

/** Слова подряд → новые слова; look — что должно стоять следом (само не заменяется). */
function rw(at: Test[], to: string[] | ((m: string[]) => string[]), look: Test[] = []): Rule {
  return (ws, i) => {
    if (!at.every((t, k) => fits(ws[i + k], t)) || !look.every((t, k) => fits(ws[i + at.length + k], t))) return null;
    return { len: at.length, to: typeof to === 'function' ? to(ws.slice(i, i + at.length).map((x) => x.w)) : to };
  };
}

const NUM: Record<string, number> = {
  один: 1, одна: 1, одну: 1, одного: 1, одной: 1, два: 2, две: 2, двух: 2, пару: 2, пара: 2, пары: 2, три: 3, трех: 3,
  четыре: 4, четырех: 4, пять: 5, пяти: 5, шесть: 6, шести: 6, семь: 7, семи: 7, восемь: 8, восьми: 8, девять: 9,
  девяти: 9, десять: 10, десяти: 10, одиннадцать: 11, одиннадцати: 11, двенадцать: 12, двенадцати: 12, пятнадцать: 15,
  пятнадцати: 15, двадцать: 20, двадцати: 20, тридцать: 30, тридцати: 30, несколько: 3, нескольких: 3,
};
const HOUR_UNIT = /^(час|часа|часов|ч|минут|минуты|минуту|мин)$/;

/** Число словами → цифрой, но только перед единицей: «три дня», «две пятницы», «через пару часов». «В два часа» не
 *  трогаем — 2:00 это или 14:00, гадать не будем. «Несколько» — 3, как у Todoist. */
const numberWord: Rule = (ws, i) => {
  const n = NUM[ws[i].w];
  const nx = ws[i + 1]?.w;
  if (n === undefined || !nx) return null;
  const ok = unitOf(nx) !== null || /^квартал(а|ов)?$/.test(nx) || wdOf(nx) !== null
    || (HOUR_UNIT.test(nx) && /^(через|течение|in)$/.test(ws[i - 1]?.w ?? ''));
  return ok ? { len: 1, to: [String(n)] } : null;
};

const isMonth = (w: string) => monthOf(w) !== null;
const DAY = /^(день|дню|дня|дне)$/;
const WORKDAYS = ['каждый', 'рабочий', 'день'];
const PLURAL_WD = /^(понедельник|вторник|сред|четверг|пятниц|суббот)ам$|^воскресеньям$/;

/** «по пятницам», «по понедельникам и четвергам» → «каждый …». «По пятницу» (до пятницы) — не повтор, не трогаем. */
const byWeekdays: Rule = (ws, i) => {
  if (ws[i].w !== 'по' || !fits(ws[i + 1], PLURAL_WD)) return null;
  const to = ['каждый'];
  let k = i + 1;
  while (fits(ws[k], PLURAL_WD)) {
    to.push(ws[k].w);
    k++;
    if (fits(ws[k], /^(и|and)$/) && fits(ws[k + 1], PLURAL_WD)) {
      to.push('и');
      k++;
    }
  }
  return { len: k - i, to };
};

/** «раз в месяц», «один раз в неделю», «раз в 2 месяца» → «каждый месяц», «каждые 2 месяца». */
const onceIn: Rule = (ws, i) => {
  let k = i;
  if (fits(ws[k], /^(один|1)$/) && fits(ws[k + 1], /^раз$/)) k++;
  if (!fits(ws[k], /^раз$/) || !fits(ws[k + 1], /^в$/)) return null;
  k += 2;
  const n = fits(ws[k], /^\d{1,3}$/) ? ws[k++].w : '';
  const u = ws[k]?.w ?? '';
  if (unitOf(u) === null && !/^квартал$/.test(u)) return null;
  return { len: k - i + 1, to: n ? ['каждые', n, u] : ['каждый', u] };
};

/** «дважды в месяц», «два раза в месяц» — каждые 15 дней, как у Todoist; «дважды в год» — каждые 6 месяцев.
 *  «Дважды в неделю» ровным шагом не выразить — не узнаём. */
const twiceIn: Rule = (ws, i) => {
  let k = i;
  if (fits(ws[k], /^дважды$/)) k++;
  else if (fits(ws[k], /^(два|2)$/) && fits(ws[k + 1], /^раза$/)) k += 2;
  else return null;
  if (!fits(ws[k], /^в$/)) return null;
  const u = unitOf(ws[k + 1]?.w ?? '');
  const to = u === 'month' ? ['каждые', '15', 'дней'] : u === 'year' ? ['каждые', '6', 'месяцев'] : null;
  return to ? { len: k - i + 2, to } : null;
};

const STAGES: Rule[][] = [
  [numberWord],
  [
    // Праздники. «С новым годом» (поздравить) и «в новом году» не трогаем — это не срок.
    rw([/^канун[ае]?$/, /^нового$/, /^года$/], ['31', 'декабря']),
    rw([/^нов(ый|ому|ого)$/, /^(год|году|года)$/], ['1', 'января']),
    rw([/^рождеств[оуа]$/], ['7', 'января']),
    rw([DAY, /^святого$/, /^валентина$/], ['14', 'февраля']),
    rw([DAY, /^валентина$/], ['14', 'февраля']),
    rw([DAY, /^защитника$/, /^отечества$/], ['23', 'февраля']),
    rw([/^международн(ый|ому|ого)$/, /^женск(ий|ому|ого)$/, DAY], ['8', 'марта']),
    rw([/^женск(ий|ому|ого)$/, DAY], ['8', 'марта']),
    rw([DAY, /^космонавтики$/], ['12', 'апреля']),
    rw([/^первома[йяю]$/], ['1', 'мая']),
    rw([DAY, /^победы$/], ['9', 'мая']),
    rw([DAY, /^россии$/], ['12', 'июня']),
    rw([DAY, /^знаний$/], ['1', 'сентября']),
    rw([/^(хэллоуин|хеллоуин)[ау]?$/], ['31', 'октября']),
    rw([DAY, /^народного$/, /^единства$/], ['4', 'ноября']),
    // Будни и выходные
    rw([/^по$/, /^(будним|рабочим)$/, /^дням$/], WORKDAYS),
    rw([/^по$/, /^будням$/], WORKDAYS),
    rw([/^в$/, /^(будние|рабочие)$/, /^дни$/], WORKDAYS),
    rw([/^в$/, /^будни$/], WORKDAYS),
    rw([/^пн[-–—]пт$/], WORKDAYS),
    rw([/^пн$/, /^[-–—]$/, /^пт$/], WORKDAYS),
    rw([/^(с|со)$/, /^(пн|понедельника)$/, /^(по|до)$/, /^(пт|пятницу|пятницы)$/], WORKDAYS),
    rw([/^по$/, /^выходным$/], ['каждую', 'сб', 'и', 'вс']),
    byWeekdays,
    // Частота
    onceIn,
    twiceIn,
    // «после 3 недель» — как у Todoist, повтор от выполнения: «каждые! 3 недели»
    rw([/^после$/, /^\d{1,3}$/, (w) => unitOf(w) !== null], (m) => ['каждые!', m[1], m[2]]),
    // Примерные сроки
    rw([/^когда-?нибудь$/], ['через', '2', 'месяца']),
    rw([/^когда$/, /^-?нибудь$/], ['через', '2', 'месяца']),
    rw([/^следующем$/, /^месяце$/], ['через', '1', 'месяц']),
    rw([/^начал[оеа]$/, isMonth], (m) => ['1', m[1]]),
    rw([/^середин[аеуы]$/, isMonth], (m) => ['15', m[1]]),
  ],
];

function rewrite(ws: W[]): W[] {
  for (const stage of STAGES) {
    const out: W[] = [];
    for (let i = 0; i < ws.length; ) {
      let hit: ReturnType<Rule> = null;
      for (const r of stage) if ((hit = r(ws, i))) break;
      if (!hit) {
        out.push(ws[i++]);
        continue;
      }
      const part = ws.slice(i, i + hit.len);
      const s = part.map((x) => x.s).filter(Boolean).join(' ');
      hit.to.forEach((w, k) => out.push({ s: k ? '' : s, w, start: part[0].start, end: part[part.length - 1].end }));
      i += hit.len;
    }
    ws = out;
  }
  return ws;
}

/** Слова срока, как их сказали (у переписанной фразы — исходный текст один раз). */
const said = (ws: W[], i: number, j: number): string => ws.slice(i, j).map((x) => x.s).filter(Boolean).join(' ');

// ── Время ────────────────────────────────────────────────────────────────

const PART_OF_DAY: Record<string, string> = { утром: '09:00', днем: '12:00', днём: '12:00', вечером: '19:00', ночью: '22:00',
  morning: '09:00', afternoon: '12:00', evening: '19:00', night: '22:00', полдень: '12:00', noon: '12:00' };

function hm(h: number, m: number): string | null {
  if (h < 0 || h > 23 || m < 0 || m > 59) return null;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/** Время с позиции i: «в 15», «в 15:30», «в 9 утра», «в 7 вечера», «15:30», «утром», «at 3pm». */
function matchTime(ws: W[], i: number, standalone: boolean): { len: number; time: string } | null {
  const a = ws[i];
  if (!a) return null;
  if (PART_OF_DAY[a.w]) return { len: 1, time: PART_OF_DAY[a.w] };
  if ((a.w === 'в' || a.w === 'at' || a.w === 'к') && ws[i + 1]) {
    const pod = PART_OF_DAY[ws[i + 1].w];
    if (pod && a.w !== 'к') return { len: 2, time: pod };
    const t = timeWord(ws, i + 1, true);
    if (t) return { len: 1 + t.len, time: t.time };
    return null;
  }
  const t = timeWord(ws, i, false);
  if (t && (!standalone || t.strong)) return t;
  return null;
}

function timeWord(ws: W[], i: number, afterPrep: boolean): { len: number; time: string; strong: boolean } | null {
  const a = ws[i];
  if (!a) return null;
  let m = a.w.match(/^(\d{1,2})[:.](\d{2})$/);
  if (m) {
    const t = hm(+m[1], +m[2]);
    if (!t) return null;
    const suf = suffix(ws[i + 1]);
    return { len: suf ? 2 : 1, time: suf ? shift(t, suf) : t, strong: true };
  }
  m = a.w.match(/^(\d{1,2})(am|pm)$/);
  if (m) {
    const h = +m[1] % 12 + (m[2] === 'pm' ? 12 : 0);
    const t = hm(h, 0);
    return t ? { len: 1, time: t, strong: true } : null;
  }
  m = a.w.match(/^(\d{1,2})(ч|h)?$/);
  if (m && afterPrep) {
    const h = +m[1];
    if (h > 23) return null;
    const next = ws[i + 1];
    const suf = suffix(next);
    if (suf) return { len: 2, time: shift(hm(h, 0)!, suf), strong: true };
    if (next && /^(час(а|ов)?|ч)$/.test(next.w)) return { len: 2, time: hm(h, 0)!, strong: true };
    // «в 15» в конце или перед другим токеном — время; «в 3 банка» — нет.
    if (!next || /^[#@%+/{!]|^p[1-4]$/.test(next.w) || wdOf(next.w) !== null || DATE_HEAD.test(next.w)) {
      return { len: 1, time: hm(h, 0)!, strong: true };
    }
  }
  return null;
}

function suffix(w?: W): string | null {
  if (!w) return null;
  if (/^(утра|am)$/.test(w.w)) return 'am';
  if (/^(вечера|дня|pm)$/.test(w.w)) return 'pm';
  if (/^ночи$/.test(w.w)) return 'night';
  return null;
}
function shift(t: string, suf: string): string {
  let h = +t.slice(0, 2);
  const m = t.slice(3);
  if (suf === 'pm' && h < 12) h += 12;
  if (suf === 'am' && h === 12) h = 0;
  if (suf === 'night' && h === 12) h = 0;
  return `${String(h).padStart(2, '0')}:${m}`;
}

// ── Дата ─────────────────────────────────────────────────────────────────

// С чего начинается срок — «в 10 каждый пн», «в 10 по будням» (после переписываний — «каждый»): «в 10» тогда время.
const DATE_HEAD = /^(сегодня|сег|завтра|послезавтра|today|tod|tomorrow|tom|через|in|следующ|след|next|на|в|во|к|до|выходн|weekend|конец|end|без|no|кажд|ежедн|еженед|ежемес|ежегод|ежекварт|every|daily|weekly|monthly|yearly|\d)/;

interface DateHit {
  len: number;
  date: string | null;
  clear?: boolean;
}

/** Дата с позиции i (без времени). */
function matchDate(ws: W[], i: number, today: string): DateHit | null {
  const a = ws[i];
  if (!a) return null;
  const w = a.w;
  const b = ws[i + 1]?.w, c = ws[i + 2]?.w;
  if (/^(сегодня|сег|today|tod)$/.test(w)) return { len: 1, date: today };
  if (/^(завтра|tomorrow|tom)$/.test(w)) return { len: 1, date: addDays(today, 1) };
  if (w === 'послезавтра') return { len: 1, date: addDays(today, 2) };
  if ((w === 'без' && b && /^(срока|даты)$/.test(b)) || (w === 'no' && b === 'date')) return { len: 2, date: null, clear: true };
  // через 3 дня / через неделю / in 3 days / в течение недели (разовое — к концу срока) / через 3 дня после 8 марта.
  // Числа словами и «пару», «несколько» сюда приходят уже цифрами — из переписываний.
  if (w === 'через' || w === 'in' || w === 'течение') {
    let n = 1, j = i + 1;
    if (b && /^\d{1,3}$/.test(b)) {
      n = +b;
      j = i + 2;
    }
    const u = unitOf(ws[j]?.w ?? '');
    if (!u) return null;
    if (ws[j + 1]?.w === 'после') {
      const base = matchDate(ws, j + 2, today);
      if (base && base.date) return { len: j - i + 2 + base.len, date: shiftBy(base.date, u, n) };
    }
    return { len: j - i + 1, date: shiftBy(today, u, n) };
  }
  // за 3 дня до 8 марта, 3 дня до 8 марта, 2 недели после 1 мая
  const off = offsetDate(ws, i, today);
  if (off) return off;
  // предлог перед датой: в пятницу, к среде, до пт, на 25 окт
  if (PREP.has(w) && ws[i + 1]) {
    const inner = matchDate(ws, i + 1, today);
    if (inner && !inner.clear) return { ...inner, len: inner.len + 1 };
    // на следующей неделе, на выходных, на этой неделе
    return null;
  }
  // следующая неделя, следующий пн
  if (NEXT.test(w) && b) {
    const wd = wdOf(b);
    if (wd !== null) return { len: 2, date: addDays(mondayOf(today), 7 + wd) };
    if (/^(недел[яеию]|week)$/.test(b)) return { len: 2, date: addDays(mondayOf(today), 7) };
    if (/^(месяц[ае]?|month)$/.test(b)) return { len: 2, date: addMonths(today.slice(0, 8) + '01', 1) };
    if (/^(выходн(ые|ых)|weekend)$/.test(b)) return { len: 2, date: addDays(mondayOf(today), 12) };
    return null;
  }
  if (THIS.test(w) && b) {
    const wd = wdOf(b);
    if (wd !== null) return { len: 2, date: nextWeekday(today, wd, true) };
    if (/^(выходн(ые|ых)|weekend)$/.test(b)) return { len: 2, date: nextWeekday(today, 5, true) };
    return null;
  }
  // На выходных — ближайшая суббота: в субботу сегодня, в воскресенье — через 6 дней, как у Todoist (слово владельца
  // 19.10: выходные в воскресенье уже кончаются, дело на них — следующие).
  if (/^(выходн(ые|ых)|weekend)$/.test(w)) return { len: 1, date: nextWeekday(today, 5, true) };
  // конец месяца, в конце ноября, в конце года
  if (/^(конец|конце|конца|концу|end)$/.test(w) && b) {
    const x = b === 'of' ? c ?? '' : b;
    const len = b === 'of' ? 3 : 2;
    const y = +today.slice(0, 4), mo = +today.slice(5, 7);
    if (/^(месяца|month)$/.test(x)) return { len, date: ymd(y, mo, daysInMonth(y, mo)) };
    if (/^(года|year)$/.test(x)) return { len, date: ymd(y, 12, 31) };
    const mon = monthOf(x);
    if (mon) {
      const here = ymd(y, mon, daysInMonth(y, mon));
      return { len, date: here >= today ? here : ymd(y + 1, mon, daysInMonth(y + 1, mon)) };
    }
  }
  const wd = wdOf(w);
  if (wd !== null) return { len: 1, date: nextWeekday(today, wd, true) };
  // 25.10, 25.10.2026, 25/10, 2026-10-25
  let m = w.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (m) return valid(+m[1], +m[2], +m[3], 1);
  m = w.match(/^(\d{1,2})[./](\d{1,2})(?:[./](\d{2}|\d{4}))?$/);
  if (m) return withYear(+m[1], +m[2], m[3], today, 1);
  // 25 число, до 5 числа — ближайшее такое число: в этом месяце или, если прошло, в следующем (31 в коротком — последнее)
  m = w.match(/^(\d{1,2})(-?(го|е))?$/);
  if (m && b && /^(число|числа|числу)$/.test(b)) {
    const d = +m[1];
    if (d < 1 || d > 31) return null;
    const y = +today.slice(0, 4), mo = +today.slice(5, 7);
    const here = ymd(y, mo, Math.min(d, daysInMonth(y, mo)));
    return { len: 2, date: here >= today ? here : addMonths(here, 1, d) };
  }
  // 25 окт [2027], 25-го октября, oct 25
  m = w.match(/^(\d{1,2})(-?(го|е|th|st|nd|rd))?$/);
  if (m && b) {
    const mon = monthOf(b);
    if (mon) {
      const y = c && /^\d{4}(г\.?)?$/.test(c) ? c.slice(0, 4) : undefined;
      return withYear(+m[1], mon, y, today, y ? 3 : 2);
    }
  }
  const mon = monthOf(w);
  if (mon && b && /^\d{1,2}$/.test(b)) return withYear(+b, mon, undefined, today, 2);
  return null;
}

function valid(y: number, m: number, d: number, len: number): DateHit | null {
  if (m < 1 || m > 12 || d < 1 || d > daysInMonth(y, m)) return null;
  return { len, date: ymd(y, m, d) };
}
function withYear(d: number, m: number, y: string | undefined, today: string, len: number): DateHit | null {
  if (y) return valid(y.length === 2 ? 2000 + +y : +y, m, d, len);
  const ty = +today.slice(0, 4);
  const hit = valid(ty, m, d, len);
  if (hit && hit.date! < today) return valid(ty + 1, m, d, len);
  return hit;
}

function shiftBy(d: string, u: Recur['every'], n: number): string {
  if (u === 'day' || u === 'workday') return addDays(d, n);
  if (u === 'week') return addDays(d, 7 * n);
  if (u === 'month') return addMonths(d, n);
  return addMonths(d, 12 * n);
}

/** «за 3 дня до 8 марта», «3 дня до 8 марта», «за день до пятницы», «2 недели после 1 мая» — разовая дата со сдвигом. */
function offsetDate(ws: W[], i: number, today: string): DateHit | null {
  let k = i, n = 1;
  if (ws[k]?.w === 'за') k++;
  if (/^\d{1,3}$/.test(ws[k]?.w ?? '')) n = +ws[k++].w;
  else if (k === i) return null; // без «за» число обязательно: «дня до пятницы» — не срок
  const u = unitOf(ws[k]?.w ?? '');
  const rel = ws[k + 1]?.w ?? '';
  if (!u || !/^(до|перед|после)$/.test(rel)) return null;
  const base = matchDate(ws, k + 2, today);
  if (!base || !base.date) return null;
  return { len: k + 2 - i + base.len, date: shiftBy(base.date, u, rel === 'после' ? n : -n) };
}

/** Промежуток «5 ноября по 10 ноября», «5 по 10 ноября», «5.11 до 10.11» (после «с»): конец — дата с числом. */
function matchRange(ws: W[], k: number, today: string): { len: number; start: string; end: string } | null {
  let len: number, start: string | null = null, day = 0;
  const d = matchDate(ws, k, today);
  if (d && d.date) {
    start = d.date;
    len = d.len;
  } else if (/^\d{1,2}$/.test(ws[k]?.w ?? '')) {
    day = +ws[k].w;
    len = 1;
  } else return null;
  if (!/^(по|до|-|–|—)$/.test(ws[k + len]?.w ?? '') || !/^\d/.test(ws[k + len + 1]?.w ?? '')) return null;
  const e = matchDate(ws, k + len + 1, today);
  if (!e || !e.date) return null;
  if (!start) {
    // «с 5 по 10 ноября»: месяц и год — у конца; «с 25 по 5 ноября» — с 25 октября
    if (day < 1 || day > 31) return null;
    const y = +e.date.slice(0, 4), mo = +e.date.slice(5, 7);
    start = day <= +e.date.slice(8, 10) ? ymd(y, mo, day) : addMonths(ymd(y, mo, 1), -1, day);
  }
  if (start > e.date) return null;
  return { len: len + 1 + e.len, start, end: e.date };
}

/** Через час, через 2 часа, через 30 минут, через полчаса — от «сейчас»; без часов (ctx.now) не узнаём. */
function matchIn(ws: W[], i: number, ctx: Ctx): { len: number; date: string; time: string } | null {
  if (!ctx.now || !/^(через|in)$/.test(ws[i]?.w ?? '')) return null;
  let k = i + 1, n = 1, per: number;
  if (ws[k]?.w === 'полчаса') per = 30;
  else {
    if (/^\d{1,3}$/.test(ws[k]?.w ?? '')) n = +ws[k++].w;
    const u = ws[k]?.w ?? '';
    if (/^(час|часа|часов|ч|hour|hours|h)$/.test(u)) per = 60;
    else if (/^(минут|минуты|минуту|мин|minute|minutes|min|mins)$/.test(u)) per = 1;
    else return null;
  }
  const total = +ctx.now.slice(0, 2) * 60 + +ctx.now.slice(3, 5) + n * per;
  return { len: k - i + 1, date: addDays(ctx.today, Math.floor(total / 1440)), time: hm(Math.floor(total / 60) % 24, total % 60)! };
}

// ── Чего не узнаём ───────────────────────────────────────────────────────

const ORD = /^(перв(ый|ая|ую|ое|ого|ой)|втор(ой|ая|ую|ое|ого)|трет(ий|ья|ью|ье|ьего|ьей)|четверт(ый|ая|ую|ое|ого|ой)|пят(ый|ая|ую|ое|ого|ой)|последн(ий|яя|юю|ее|его|ей)|first|second|third|fourth|fifth|last)$/;
const ORD_NUM = /^\d{1,2}-?(й|я|ю|е|ое|ий|ья|ье|го|ой|th|st|nd|rd)$/;

/** N-й или последний день недели (месяца): «3 понедельник», «3-й пн», «последний пн месяца», «вторую пятницу». Модель
 *  повтора такого не держит, а «каждый 3 пн» как «каждое 3-е число» молча портит дело — такую фразу не узнаём вовсе.
 *  digits 'all' — годится и голое число (после «каждый»); 'suffix' — только «3-й» (в «в 10 пн» 10 — время). */
function ordWeekday(ws: W[], k: number, digits: 'all' | 'suffix'): number {
  const a = ws[k]?.w ?? '';
  if (!ORD.test(a) && !ORD_NUM.test(a) && !(digits === 'all' && /^\d{1,2}$/.test(a))) return 0;
  if (wdOf(ws[k + 1]?.w ?? '') === null) return 0;
  const x = ws[k + 2]?.w, y = ws[k + 3]?.w;
  if (x && /^(месяца|month)$/.test(x)) return 3;
  if (x === 'каждого' && y === 'месяца') return 4;
  return 2;
}

/** Сколько слов пропустить без срока: «в последнюю пятницу», «третий понедельник», разовое «с 5 по 10 ноября». */
function skipAt(ws: W[], i: number, today: string): number {
  const k = PREP.has(ws[i]?.w ?? '') ? i + 1 : i;
  const ow = ordWeekday(ws, k, 'suffix');
  if (ow) return k - i + ow;
  if (/^(с|со)$/.test(ws[i]?.w ?? '')) {
    const r = matchRange(ws, i + 1, today);
    if (r) return 1 + r.len;
  }
  return 0;
}

/** Время сразу после пропущенного («каждый последний пн в 10») — тоже не срок: иначе дело встанет разово на 10:00. */
const timeLen = (ws: W[], k: number): number => matchTime(ws, k, false)?.len ?? 0;

// ── Повтор ───────────────────────────────────────────────────────────────

const EVERY = /^(кажд(ый|ую|ое|ые|ой|ого|ая)?|every|ev)(!?)$/;
const EVERY_SHORT: Record<string, Recur['every']> = { ежедневно: 'day', daily: 'day', еженедельно: 'week', weekly: 'week',
  ежемесячно: 'month', monthly: 'month', ежегодно: 'year', yearly: 'year', annually: 'year' };

// skip — фраза повтора, которую сознательно не узнаём (её слова остаются в названии); start — первый срок («каждое
// 2 апреля» — с ближайшего 2 апреля).
type RecurHit = { len: number; skip: true } | { len: number; skip?: false; recur: Recur; time?: string; start?: string };

function unitOf(w: string): Recur['every'] | null {
  if (/^(день|дня|дней|сутки|day|days)$/.test(w)) return 'day';
  if (/^(неделю|недели|недель|неделя|week|weeks)$/.test(w)) return 'week';
  if (/^(месяц|месяца|месяцев|month|months)$/.test(w)) return 'month';
  if (/^(год|года|лет|year|years)$/.test(w)) return 'year';
  return null;
}

/** Дни недели с позиции k: «пн и чт», «пн, ср, пт»; con — можно с предлогом: «по пт», «во вторник», «каждый пт». */
function weekDays(ws: W[], k: number, con: boolean): { len: number; days: number[] } | null {
  let q = k;
  if (con && ws[q] && /^(по|в|во|on|каждый|каждую|каждое)$/.test(ws[q].w)) q++;
  const days: number[] = [];
  while (ws[q]) {
    const wd = wdOf(ws[q].w);
    if (wd === null) break;
    days.push(wd + 1);
    q++;
    if (ws[q] && /^(и|and|&)$/.test(ws[q].w) && ws[q + 1] && wdOf(ws[q + 1].w) !== null) q++;
  }
  return days.length ? { len: q - k, days } : null;
}

function matchRecur(ws: W[], i: number, today: string): RecurHit | null {
  const a = ws[i];
  if (!a) return null;
  if (EVERY_SHORT[a.w]) {
    const every = EVERY_SHORT[a.w];
    const d = every === 'week' ? weekDays(ws, i + 1, true) : null; // еженедельно по пт
    return { len: 1 + (d?.len ?? 0), recur: { every, n: 1, ...(d ? { days: d.days } : {}) } };
  }
  if (a.w === 'ежеквартально' || a.w === 'quarterly') return { len: 1, recur: { every: 'month', n: 3 } };
  const m = a.w.match(EVERY);
  if (!m) return null;
  const from = m[3] === '!' ? 'done' : 'due';
  // «каждые 2 пятницы» — раз в 2 недели; «каждый 2 пн» — второй понедельник месяца, такого не держим.
  const plural = m[2] === 'ые' || !m[1].startsWith('кажд');
  const ow = ordWeekday(ws, i + 1, plural ? 'suffix' : 'all');
  if (ow) return { len: 1 + ow, skip: true };
  let j = i + 1;
  let n = 1;
  const b = ws[j]?.w;
  if (!b) return null;
  if (/^(втор(ой|ую|ое|ые)|other)$/.test(b)) {
    n = 2;
    j++;
  } else if (/^(трет(ий|ью|ье))$/.test(b)) {
    n = 3;
    j++;
  } else if (/^\d{1,3}$/.test(b) && ws[j + 1] && (unitOf(ws[j + 1].w) || /^(рабочих|будних)$/.test(ws[j + 1].w) || wdOf(ws[j + 1].w) !== null)) {
    n = +b;
    j++;
  }
  const u = ws[j]?.w;
  if (!u) return null;
  const R = (r: Recur, len: number, time?: string, start?: string): RecurHit =>
    ({ len, recur: { ...r, n: r.n ?? n, ...(from === 'done' ? { from } : {}) }, time, start });
  if (/^(утро)$/.test(u)) return R({ every: 'day' }, j - i + 1, '09:00');
  if (/^(вечер)$/.test(u)) return R({ every: 'day' }, j - i + 1, '19:00');
  if (/^(ночь)$/.test(u)) return R({ every: 'day' }, j - i + 1, '22:00');
  if (/^(полдень)$/.test(u)) return R({ every: 'day' }, j - i + 1, '12:00');
  if (/^(рабочий|будний|рабочих|будних|workday|weekday|workdays|weekdays|будни)$/.test(u)) {
    const tail = ws[j + 1] && /^(день|дня|дней|days?)$/.test(ws[j + 1].w) ? 1 : 0;
    return R({ every: 'workday' }, j - i + 1 + tail);
  }
  const unit = unitOf(u);
  if (unit) {
    // каждые 2 недели по пт, каждую неделю во вторник и четверг
    const d = unit === 'week' ? weekDays(ws, j + 1, true) : null;
    return d ? R({ every: 'week', days: d.days }, j - i + 1 + d.len) : R({ every: unit }, j - i + 1);
  }
  if (/^(квартал)$/.test(u)) return R({ every: 'month', n: 3 }, j - i + 1);
  // дни недели: каждый пн / каждый пн и чт / каждые пн, ср, пт / каждые 2 пятницы
  const d = weekDays(ws, j, false);
  if (d) return R({ every: 'week', days: d.days }, j - i + d.len);
  // каждое 15 число / каждое 15-е / каждый последний день
  const md = u.match(/^(\d{1,2})(-?(е|го|th|st|nd|rd))?$/);
  if (md && +md[1] >= 1 && +md[1] <= 31) {
    // каждое 2 апреля — раз в год, первый срок — ближайшее 2 апреля (год сервер шагает от даты срока)
    const mon = monthOf(ws[j + 1]?.w ?? '');
    if (mon) {
      const y = ws[j + 2] && /^\d{4}$/.test(ws[j + 2].w) ? ws[j + 2].w : undefined;
      const hit = withYear(+md[1], mon, y, today, y ? 3 : 2);
      return hit ? R({ every: 'year' }, j - i + hit.len, undefined, hit.date!) : null;
    }
    const tail = ws[j + 1] && /^(число|числа|день)$/.test(ws[j + 1].w) ? 1 : 0;
    return R({ every: 'month', monthday: +md[1] }, j - i + 1 + tail);
  }
  if (/^(последн(ий|ее)|last)$/.test(u) && ws[j + 1] && /^(день|число|day)$/.test(ws[j + 1].w)) {
    return R({ every: 'month', monthday: -1 }, j - i + 2);
  }
  return null;
}

/** Первый срок повтора: ближайший подходящий день, начиная с сегодняшнего. */
export function firstDue(r: Recur, today: string, start?: string | null): string {
  const base = start || today;
  if (r.every === 'workday') return weekday(base) < 5 ? base : nextWeekday(base, 0);
  if (r.every === 'week' && r.days?.length) {
    for (let k = 0; k < 7; k++) {
      const d = addDays(base, k);
      if (r.days.includes(weekday(d) + 1)) return d;
    }
  }
  if (r.every === 'month' && r.monthday) {
    const y = +base.slice(0, 4), m = +base.slice(5, 7);
    const dd = r.monthday === -1 ? daysInMonth(y, m) : Math.min(r.monthday, daysInMonth(y, m));
    const here = ymd(y, m, dd);
    return here >= base ? here : addMonths(here, 1, r.monthday === -1 ? 31 : r.monthday);
  }
  return base;
}

// ── Срок целиком: повтор или дата, плюс время ────────────────────────────

type Hit = { len: number; when: When | null }; // when null — фраза пропущена сознательно, остаётся в названии

/** Повтор с позиции i и его хвост: время, «до 30 дек», «по 10 ноября», «с 5 ноября», «с 5 по 10 ноября»,
 *  «в течение 3 недель»; у годового — дата («каждый год 9 мая»). pre — время, сказанное перед повтором («в 10 по будням»). */
function recurWhen(ws: W[], i: number, ctx: Ctx, pre?: { at: number; time: string }): Hit | null {
  const rec = matchRecur(ws, i, ctx.today);
  if (!rec) return null;
  const at = pre ? pre.at : i;
  if (rec.skip) return { len: i + rec.len + timeLen(ws, i + rec.len) - at, when: null };
  let j = i + rec.len;
  let time = rec.time ?? pre?.time ?? null;
  let start = rec.start ?? null;
  let until: string | undefined;
  let span: { n: number; u: Recur['every'] } | null = null;
  for (let guard = 0; guard < 4; guard++) {
    const w = ws[j]?.w;
    // в течение 3 недель / for 3 weeks — конец повтора через столько от первого дня
    let k = w === 'в' && ws[j + 1]?.w === 'течение' ? j + 2 : w === 'течение' || w === 'for' ? j + 1 : 0;
    if (k) {
      let n = 1;
      if (/^\d{1,3}$/.test(ws[k]?.w ?? '')) n = +ws[k++].w;
      const u = unitOf(ws[k]?.w ?? '');
      if (u) {
        span = { n, u };
        j = k + 1;
        continue;
      }
    }
    const t = matchTime(ws, j, false);
    if (t && !time) {
      time = t.time;
      j += t.len;
      continue;
    }
    // до 30 дек; «по» — только с числом: «по 10 ноября» конец, а «по пт» — день недели
    if ((w === 'до' || w === 'until' || w === 'ending' || (w === 'по' && /^\d/.test(ws[j + 1]?.w ?? ''))) && ws[j + 1]) {
      const d = matchDate(ws, j + 1, ctx.today);
      if (d && d.date) {
        until = d.date;
        j += 1 + d.len;
        continue;
      }
    }
    if ((w === 'с' || w === 'со' || w === 'from' || w === 'starting' || (w === 'начиная' && ws[j + 1]?.w === 'с')) && ws[j + 1]) {
      const skip = w === 'начиная' ? 2 : 1;
      const r = matchRange(ws, j + skip, ctx.today);
      if (r) {
        start = r.start;
        until = r.end;
        j += skip + r.len;
        continue;
      }
      const d = matchDate(ws, j + skip, ctx.today);
      if (d && d.date) {
        start = d.date;
        j += skip + d.len;
        continue;
      }
    }
    if (rec.recur.every === 'year' && !start) {
      const d = matchDate(ws, j, ctx.today);
      if (d && d.date) {
        start = d.date;
        j += d.len;
        continue;
      }
    }
    break;
  }
  if (span && !until) until = shiftBy(start ?? ctx.today, span.u, span.n);
  const recur: Recur = { ...rec.recur, ...(until ? { until } : {}) };
  return { len: j - at, when: { due_date: firstDue(recur, ctx.today, start), due_time: time, recur, due_rule: said(ws, at, j) } };
}

/** Срок с позиции i: повтор или дата и время в любом порядке («завтра в 15», «в 15 завтра», «каждый пн в 9 до 30 дек»). */
function matchWhen(ws: W[], i: number, ctx: Ctx): Hit | null {
  const sk = skipAt(ws, i, ctx.today);
  if (sk) return { len: sk + timeLen(ws, i + sk), when: null };
  const rec = recurWhen(ws, i, ctx);
  if (rec) return rec;
  const inn = matchIn(ws, i, ctx);
  if (inn) return { len: inn.len, when: { due_date: inn.date, due_time: inn.time, recur: null, due_rule: null } };
  const d = matchDate(ws, i, ctx.today);
  if (d) {
    if (d.clear) return { len: d.len, when: { due_date: null, due_time: null, recur: null, due_rule: null, clear: true } };
    const t = matchTime(ws, i + d.len, false);
    return { len: d.len + (t ? t.len : 0), when: { due_date: d.date, due_time: t ? t.time : null, recur: null, due_rule: null } };
  }
  const t = matchTime(ws, i, true);
  if (t) {
    const k = i + t.len;
    const sk2 = skipAt(ws, k, ctx.today);
    if (sk2) return { len: t.len + sk2, when: null };
    const r2 = recurWhen(ws, k, ctx, { at: i, time: t.time });
    if (r2) return r2;
    const d2 = matchDate(ws, k, ctx.today);
    if (d2 && d2.date) return { len: t.len + d2.len, when: { due_date: d2.date, due_time: t.time, recur: null, due_rule: null } };
    const passed = ctx.now && t.time <= ctx.now;
    return { len: t.len, when: { due_date: passed ? addDays(ctx.today, 1) : ctx.today, due_time: t.time, recur: null, due_rule: null } };
  }
  return null;
}

/** Срок из поля планировщика («введите срок»): вся строка — одна фраза. null — не понял. */
export function parseWhen(text: string, ctx: Ctx): When | null {
  const ws = rewrite(words(text));
  if (!ws.length) return null;
  const hit = matchWhen(ws, 0, ctx);
  return hit && hit.when && hit.len === ws.length ? hit.when : null;
}

// ── Строка целиком ───────────────────────────────────────────────────────

function longest(text: string, at: number, items: Named[], key: (x: Named) => string[]): { item: Named; len: number } | null {
  const low = text.slice(at).toLowerCase().replace(/ё/g, 'е');
  let best: { item: Named; len: number } | null = null;
  for (const it of items) {
    for (const name of key(it)) {
      const n = name.toLowerCase().replace(/ё/g, 'е');
      if (!n || !low.startsWith(n)) continue;
      const after = low.charAt(n.length);
      if (after && !/[\s,;.!?)]/.test(after)) continue;
      if (!best || n.length > best.len) best = { item: it, len: n.length };
    }
  }
  return best;
}

export function parseQuick(text: string, ctx: Ctx, opts: { dates?: boolean } = {}): Parsed {
  const out: Parsed = { title: '', tokens: [], labels: [] };
  let body = text;
  // « // описание» в конце
  const slash = text.search(/\s\/\/\s/);
  if (slash >= 0) {
    out.notes = text.slice(slash + 4).trim() || null;
    out.tokens.push({ start: slash + 1, end: text.length, kind: 'desc' });
    body = text.slice(0, slash);
  }
  const taken: Tok[] = [];
  const free = (s: number, e: number) => !taken.some((t) => s < t.end && e > t.start);
  const take = (start: number, end: number, kind: TokKind) => {
    taken.push({ start, end, kind });
  };
  // {дедлайн}
  for (const m of body.matchAll(/\{([^{}]+)\}/g)) {
    const w = parseWhen(m[1], ctx);
    if (w && w.due_date && !w.recur) {
      out.deadline = w.due_date;
      take(m.index!, m.index! + m[0].length, 'deadline');
    }
  }
  // #проект или сделка, /раздел, +человек, @метка %метка, p1…p4, !сейчас
  for (let i = 0; i < body.length; i++) {
    const ch = body[i];
    if (!/[#/+@%pP!]/.test(ch) || (i > 0 && !/\s/.test(body[i - 1])) || !free(i, i + 1)) continue;
    if (ch === '#') {
      const hit = longest(body, i + 1, ctx.projects, (x) => [x.name]);
      if (hit) {
        if (hit.item.deal) {
          out.deal_id = hit.item.id;
          out.project_id = hit.item.project_id ?? null;
        } else {
          out.project_id = hit.item.id;
          out.deal_id = null;
        }
        take(i, i + 1 + hit.len, 'project');
        i += hit.len;
      }
    } else if (ch === '/' && body[i + 1] !== '/') {
      const pid = out.project_id;
      const pool = ctx.sections.filter((s) => !pid || s.project_id === pid);
      const hit = longest(body, i + 1, pool, (x) => [x.name]);
      if (hit) {
        out.section_id = hit.item.id;
        out.project_id = hit.item.project_id ?? out.project_id;
        take(i, i + 1 + hit.len, 'section');
        i += hit.len;
      }
    } else if (ch === '+') {
      const hit = longest(body, i + 1, ctx.people, (x) => [x.name, x.short || ''].concat(firstName(x)));
      if (hit) {
        out.person_id = hit.item.id;
        take(i, i + 1 + hit.len, 'person');
        i += hit.len;
      }
    } else if (ch === '@' || ch === '%') {
      const m = body.slice(i + 1).match(/^[^\s#@%+{}]+/);
      if (m && !/^\d/.test(m[0])) {
        const known = ctx.labels.find((l) => l.toLowerCase() === m[0].toLowerCase());
        const name = known || m[0].replace(/[,;.!?]+$/, '');
        if (name && !out.labels.includes(name)) out.labels.push(name);
        take(i, i + 1 + name.length, 'label');
        i += name.length;
      }
    } else if (ch === 'p' || ch === 'P') {
      const m = body.slice(i).match(/^[pP]([1-4])(?=$|[\s,;.])/);
      if (m) {
        out.priority = +m[1];
        take(i, i + 2, 'priority');
        i += 1;
      }
    } else if (ch === '!') {
      const m = body.slice(i).match(/^!(!([1-4])|сейчас|now)(?=$|[\s,;.])/i);
      if (m) {
        if (m[2]) out.priority = +m[2];
        else out.now = true;
        take(i, i + m[0].length, m[2] ? 'priority' : 'now');
        i += m[0].length - 1;
        continue;
      }
      // Напоминание (наше, у Todoist «!» — тоже напоминание): «!дома» — место телефона, «!15:00», «!в 9», «!завтра в 9».
      const rest = body.slice(i + 1);
      const word = (rest.match(/^[^\s#@%+{}!]+/) || [''])[0].toLowerCase().replace(/ё/g, 'е');
      const place = (ctx.places || []).find((p) => {
        const n = p.toLowerCase().replace(/ё/g, 'е');
        return word === n || (word.startsWith(n) && word.length - n.length <= 2);
      });
      if (word && place) {
        out.remind_place = place;
        take(i, i + 1 + word.length, 'remind');
        i += word.length;
        continue;
      }
      const ws = words(body, i + 1).slice(0, 4);
      for (let k = ws.length; k >= 1; k--) {
        if (ws[0].start !== i + 1) break;
        const w = parseWhen(body.slice(ws[0].start, ws[k - 1].end), ctx);
        if (w && w.due_time && !w.recur) {
          out.remind_when = { date: /^(в|at|к)$/i.test(ws[0].w) || /^\d/.test(ws[0].w) || PART_OF_DAY[ws[0].w] ? null : w.due_date, time: w.due_time };
          take(i, ws[k - 1].end, 'remind');
          i = ws[k - 1].end - 1;
          break;
        }
      }
    }
  }
  // Срок — первое распознанное по словам, не задевая уже взятое.
  if (opts.dates !== false) {
    const ws = rewrite(words(body)).filter((w) => free(w.start, w.end));
    for (let i = 0; i < ws.length; i++) {
      const hit = matchWhen(ws, i, ctx);
      if (!hit) continue;
      if (!hit.when) {
        i += hit.len - 1; // не узнаём сознательно: и слова в названии, и дальше по ним не ищем
        continue;
      }
      Object.assign(out, hit.when);
      // «завтра #Альфа в 15»: срок может обходить другой токен — подсвечиваем кусками, без чужого.
      let s = ws[i].start, e = ws[i].end;
      for (let k = i + 1; k < i + hit.len; k++) {
        if (free(e, ws[k].start)) e = ws[k].end;
        else {
          take(s, e, 'date');
          s = ws[k].start;
          e = ws[k].end;
        }
      }
      take(s, e, 'date');
      break;
    }
  }
  out.tokens.push(...taken);
  out.tokens.sort((a, b) => a.start - b.start);
  // Название — строка без распознанного.
  let title = '';
  let at = 0;
  for (const t of out.tokens) {
    if (t.start < at) continue;
    title += text.slice(at, t.start);
    at = t.end;
  }
  title += text.slice(at);
  out.title = title.replace(/\s+/g, ' ').replace(/\s+([,;.!?])/g, '$1').trim();
  return out;
}

/** Строка без распознанного вида kind — крестик «Убрать» на чипе быстрого ввода: «Позвонить завтра p1» без приоритета →
 *  «Позвонить завтра». У метки — только она (word — имя метки). Пробелы на стыках схлопываются. */
export function cutTokens(text: string, parsed: Parsed, kind: TokKind, word?: string): string {
  const low = (s: string) => s.toLowerCase().replace(/ё/g, 'е');
  const drop = parsed.tokens.filter((t) => t.kind === kind && (word === undefined || low(text.slice(t.start + 1, t.end)) === low(word)))
    .sort((a, b) => b.start - a.start);
  if (!drop.length) return text;
  let s = text;
  for (const t of drop) s = s.slice(0, t.start) + s.slice(t.end);
  return s.replace(/[ \t]{2,}/g, ' ').replace(/^[ \t]+|[ \t]+$/gm, '');
}

function firstName(x: Named): string[] {
  const parts = (x.name || '').split(/\s+/);
  return parts.length > 1 ? [parts[0]] : [];
}

/** Повтор словами для показа, если слов не сохранили. */
export function recurWords(r: Recur | null | undefined): string {
  if (!r) return '';
  const n = r.n ?? 1;
  const bang = r.from === 'done' ? '!' : '';
  const WD = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'];
  if (r.every === 'day') return n === 1 ? 'каждый день' : `каждые${bang} ${n} дн.`;
  if (r.every === 'workday') return 'каждый рабочий день';
  if (r.every === 'week') {
    const days = r.days?.length ? ' ' + r.days.map((d) => WD[d - 1]).join(', ') : '';
    return (n === 1 ? 'каждую неделю' : `каждые${bang} ${n} нед.`) + days;
  }
  if (r.every === 'month') {
    const md = r.monthday === -1 ? ' в последний день' : r.monthday ? ` ${r.monthday}-го` : '';
    return (n === 1 ? 'каждый месяц' : `каждые${bang} ${n} мес.`) + md;
  }
  return n === 1 ? 'каждый год' : `каждые${bang} ${n} г.`;
}
