// Быстрый ввод одной строкой — как Quick Add у Todoist: «Позвонить Ивану завтра в 15 #Альфа @звонок p1». Распознанное
// подсвечивается прямо в строке и уходит из названия в поля дела. Свой разбор, без Claude: мгновенно и бесплатно.
//
// Понимает (русский и английский):
//   сроки — сегодня, завтра, послезавтра, в пятницу, к среде, до пт, в следующий вт, на следующей неделе, на выходных,
//           через 3 дня, через неделю, 25 окт, 25 октября 2027, 25.10, 25/10, конец месяца, без срока; время — в 15,
//           в 15:30, в 9 утра, в 7 вечера, утром, днём, вечером, ночью, 15:30;
//   повторы — каждый день, ежедневно, каждое утро, каждый рабочий день, каждый пн и чт, каждую неделю, каждые 2 недели,
//           каждый месяц, каждое 15 число, каждый последний день, каждый год, каждые 3 дня, каждые! 3 дня (от выполнения),
//           каждую вторую неделю, … до 30 дек;
//   #проект или сделка, /раздел, @метка или %метка, +человек, p1…p4, {дедлайн}, !сейчас, « // описание» в конце.

import { addDays, addMonths, daysInMonth, mondayOf, nextWeekday, weekday, ymd } from './dates.ts';
import type { Recur } from './types.ts';

export type TokKind = 'date' | 'project' | 'section' | 'label' | 'priority' | 'person' | 'deadline' | 'desc' | 'now';

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

const WEEKDAY_FORMS: [RegExp, number][] = [
  [/^(понедельник[аеу]?|пн|пон|mon|monday)$/, 0],
  [/^(вторник[аеу]?|вт|вто|tue|tues|tuesday)$/, 1],
  [/^(сред[аеуы]|ср|wed|wednesday)$/, 2],
  [/^(четверг[аеу]?|чт|чет|thu|thur|thurs|thursday)$/, 3],
  [/^(пятниц[аеуы]|пт|пят|fri|friday)$/, 4],
  [/^(суббот[аеуы]|сб|суб|sat|saturday)$/, 5],
  [/^(воскресень[еяю]|вс|вск|воскр|sun|sunday)$/, 6],
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

const DATE_HEAD = /^(сегодня|сег|завтра|послезавтра|today|tod|tomorrow|tom|через|in|следующ|след|next|на|в|во|к|до|выходн|weekend|конец|end|без|no|\d)/;

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
  // через N дней / через неделю / in 3 days
  if (w === 'через' || w === 'in') {
    let n = 1, j = i + 1;
    if (b && /^\d{1,3}$/.test(b)) {
      n = +b;
      j = i + 2;
    } else if (b && /^(пару|два|две)$/.test(b)) {
      n = 2;
      j = i + 2;
    }
    const u = ws[j]?.w;
    if (!u) return null;
    if (/^(день|дня|дней|day|days)$/.test(u)) return { len: j - i + 1, date: addDays(today, n) };
    if (/^(неделю|недели|недель|week|weeks)$/.test(u)) return { len: j - i + 1, date: addDays(today, 7 * n) };
    if (/^(месяц|месяца|месяцев|month|months)$/.test(u)) return { len: j - i + 1, date: addMonths(today, n) };
    if (/^(год|года|лет|year|years)$/.test(u)) return { len: j - i + 1, date: addMonths(today, 12 * n) };
    return null;
  }
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
    if (/^(выходн(ые|ых)|weekend)$/.test(b)) return { len: 2, date: weekday(today) >= 5 ? today : nextWeekday(today, 5) };
    return null;
  }
  if (/^(выходн(ые|ых)|weekend)$/.test(w)) return { len: 1, date: weekday(today) >= 5 ? today : nextWeekday(today, 5) };
  if ((w === 'конец' || w === 'end') && b && /^(месяца|month)$/.test(b === 'of' ? c ?? '' : b)) {
    const y = +today.slice(0, 4), m = +today.slice(5, 7);
    return { len: b === 'of' ? 3 : 2, date: ymd(y, m, daysInMonth(y, m)) };
  }
  const wd = wdOf(w);
  if (wd !== null) return { len: 1, date: nextWeekday(today, wd, true) };
  // 25.10, 25.10.2026, 25/10, 2026-10-25
  let m = w.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (m) return valid(+m[1], +m[2], +m[3], 1);
  m = w.match(/^(\d{1,2})[./](\d{1,2})(?:[./](\d{2}|\d{4}))?$/);
  if (m) return withYear(+m[1], +m[2], m[3], today, 1);
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

// ── Повтор ───────────────────────────────────────────────────────────────

const EVERY = /^(кажд(ый|ую|ое|ые|ой|ого|ая)?|every|ev)(!?)$/;
const EVERY_SHORT: Record<string, Recur['every']> = { ежедневно: 'day', daily: 'day', еженедельно: 'week', weekly: 'week',
  ежемесячно: 'month', monthly: 'month', ежегодно: 'year', yearly: 'year', annually: 'year' };

interface RecurHit {
  len: number;
  recur: Recur;
  time?: string;
}

function unitOf(w: string): Recur['every'] | null {
  if (/^(день|дня|дней|сутки|day|days)$/.test(w)) return 'day';
  if (/^(неделю|недели|недель|неделя|week|weeks)$/.test(w)) return 'week';
  if (/^(месяц|месяца|месяцев|month|months)$/.test(w)) return 'month';
  if (/^(год|года|лет|year|years)$/.test(w)) return 'year';
  return null;
}

function matchRecur(ws: W[], i: number): RecurHit | null {
  const a = ws[i];
  if (!a) return null;
  if (EVERY_SHORT[a.w]) return { len: 1, recur: { every: EVERY_SHORT[a.w], n: 1 } };
  if (a.w === 'ежеквартально' || a.w === 'quarterly') return { len: 1, recur: { every: 'month', n: 3 } };
  const m = a.w.match(EVERY);
  if (!m) return null;
  const from = m[3] === '!' ? 'done' : 'due';
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
  } else if (/^\d{1,3}$/.test(b) && ws[j + 1] && (unitOf(ws[j + 1].w) || /^(рабочих|будних)$/.test(ws[j + 1].w))) {
    n = +b;
    j++;
  }
  const u = ws[j]?.w;
  if (!u) return null;
  const R = (r: Recur, len: number, time?: string): RecurHit => ({ len, recur: { ...r, n: r.n ?? n, ...(from === 'done' ? { from } : {}) }, time });
  if (/^(утро)$/.test(u)) return R({ every: 'day' }, j - i + 1, '09:00');
  if (/^(вечер)$/.test(u)) return R({ every: 'day' }, j - i + 1, '19:00');
  if (/^(ночь)$/.test(u)) return R({ every: 'day' }, j - i + 1, '22:00');
  if (/^(полдень)$/.test(u)) return R({ every: 'day' }, j - i + 1, '12:00');
  if (/^(рабочий|будний|рабочих|будних|workday|weekday|workdays|weekdays|будни)$/.test(u)) {
    const tail = ws[j + 1] && /^(день|дня|дней|days?)$/.test(ws[j + 1].w) ? 1 : 0;
    return R({ every: 'workday' }, j - i + 1 + tail);
  }
  const unit = unitOf(u);
  if (unit) return R({ every: unit }, j - i + 1);
  if (/^(квартал)$/.test(u)) return R({ every: 'month', n: 3 }, j - i + 1);
  // дни недели: каждый пн / каждый пн и чт / каждые пн, ср, пт
  const days: number[] = [];
  let k = j;
  while (ws[k]) {
    const wd = wdOf(ws[k].w);
    if (wd === null) break;
    days.push(wd + 1);
    k++;
    if (ws[k] && /^(и|and|&)$/.test(ws[k].w) && ws[k + 1] && wdOf(ws[k + 1].w) !== null) k++;
  }
  if (days.length) return R({ every: 'week', days }, k - i);
  // каждое 15 число / каждое 15-е / каждый последний день
  const md = u.match(/^(\d{1,2})(-?(е|го|th|st|nd|rd))?$/);
  if (md && +md[1] >= 1 && +md[1] <= 31) {
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

/** Срок с позиции i: повтор или дата и время в любом порядке («завтра в 15», «в 15 завтра», «каждый пн в 9 до 30 дек»). */
function matchWhen(ws: W[], i: number, ctx: Ctx): { len: number; when: When } | null {
  const rec = matchRecur(ws, i);
  if (rec) {
    let j = i + rec.len;
    let time = rec.time ?? null;
    let start: string | null = null;
    let until: string | undefined;
    for (let guard = 0; guard < 3; guard++) {
      const t = matchTime(ws, j, false);
      if (t && !time) {
        time = t.time;
        j += t.len;
        continue;
      }
      const w = ws[j]?.w;
      if ((w === 'до' || w === 'until' || w === 'ending') && ws[j + 1]) {
        const d = matchDate(ws, j + 1, ctx.today);
        if (d && d.date) {
          until = d.date;
          j += 1 + d.len;
          continue;
        }
      }
      if ((w === 'с' || w === 'со' || w === 'from' || w === 'starting' || (w === 'начиная' && ws[j + 1]?.w === 'с')) && ws[j + 1]) {
        const skip = w === 'начиная' ? 2 : 1;
        const d = matchDate(ws, j + skip, ctx.today);
        if (d && d.date) {
          start = d.date;
          j += skip + d.len;
          continue;
        }
      }
      break;
    }
    const recur: Recur = { ...rec.recur, ...(until ? { until } : {}) };
    const text = ws.slice(i, j).map((x) => x.s).join(' ');
    return { len: j - i, when: { due_date: firstDue(recur, ctx.today, start), due_time: time, recur, due_rule: text } };
  }
  const d = matchDate(ws, i, ctx.today);
  if (d) {
    if (d.clear) return { len: d.len, when: { due_date: null, due_time: null, recur: null, due_rule: null, clear: true } };
    const t = matchTime(ws, i + d.len, false);
    return { len: d.len + (t ? t.len : 0), when: { due_date: d.date, due_time: t ? t.time : null, recur: null, due_rule: null } };
  }
  const t = matchTime(ws, i, true);
  if (t) {
    const d2 = matchDate(ws, i + t.len, ctx.today);
    if (d2 && d2.date) return { len: t.len + d2.len, when: { due_date: d2.date, due_time: t.time, recur: null, due_rule: null } };
    const passed = ctx.now && t.time <= ctx.now;
    return { len: t.len, when: { due_date: passed ? addDays(ctx.today, 1) : ctx.today, due_time: t.time, recur: null, due_rule: null } };
  }
  return null;
}

/** Срок из поля планировщика («введите срок»): вся строка — одна фраза. null — не понял. */
export function parseWhen(text: string, ctx: Ctx): When | null {
  const ws = words(text);
  if (!ws.length) return null;
  const hit = matchWhen(ws, 0, ctx);
  return hit && hit.len === ws.length ? hit.when : null;
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
      }
    }
  }
  // Срок — первое распознанное по словам, не задевая уже взятое.
  if (opts.dates !== false) {
    const ws = words(body).filter((w) => free(w.start, w.end));
    for (let i = 0; i < ws.length; i++) {
      const hit = matchWhen(ws, i, ctx);
      if (!hit) continue;
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
