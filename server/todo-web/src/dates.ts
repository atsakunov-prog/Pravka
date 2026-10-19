// Даты — строками ГГГГ-ММ-ДД, сутки — московские: «сегодня» отдаёт сервер (crm.today()), а не часы браузера.

export const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
export const MONTHS_SHORT = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
export const MONTHS_NOM = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'];
export const WD = ['понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота', 'воскресенье'];
export const WD_SHORT = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'];

const MS = 86400e3;

export function toMs(s: string): number {
  const [y, m, d] = s.slice(0, 10).split('-').map(Number);
  return Date.UTC(y, m - 1, d);
}
export const iso = (ms: number): string => new Date(ms).toISOString().slice(0, 10);
export const addDays = (s: string, n: number): string => iso(toMs(s) + n * MS);
export const diffDays = (a: string, b: string): number => Math.round((toMs(a) - toMs(b)) / MS);
/** День недели: 0 — понедельник … 6 — воскресенье. */
export const weekday = (s: string): number => (new Date(toMs(s)).getUTCDay() + 6) % 7;
export const ymd = (y: number, m: number, d: number): string => iso(Date.UTC(y, m - 1, d));
export const daysInMonth = (y: number, m: number): number => new Date(Date.UTC(y, m, 0)).getUTCDate();

export function addMonths(s: string, n: number, day?: number): string {
  const y = +s.slice(0, 4), m = +s.slice(5, 7) - 1 + n;
  const yy = y + Math.floor(m / 12), mm = ((m % 12) + 12) % 12 + 1;
  const dd = Math.min(day ?? +s.slice(8, 10), daysInMonth(yy, mm));
  return ymd(yy, mm, dd);
}

/** Ближайший такой день недели после s (wd 0 — пн); сам s не считается. */
export function nextWeekday(s: string, wd: number, includeToday = false): string {
  let n = (wd - weekday(s) + 7) % 7;
  if (n === 0 && !includeToday) n = 7;
  return addDays(s, n);
}

export const mondayOf = (s: string): string => addDays(s, -weekday(s));

/** «19 окт», с годом — если не этот. */
export function shortDate(s: string, today: string): string {
  const out = `${+s.slice(8, 10)} ${MONTHS_SHORT[+s.slice(5, 7) - 1]}`;
  return s.slice(0, 4) === today.slice(0, 4) ? out : `${out} ${s.slice(0, 4)}`;
}
export function longDate(s: string): string {
  return `${+s.slice(8, 10)} ${MONTHS_GEN[+s.slice(5, 7) - 1]}`;
}

const cap = (x: string) => x.charAt(0).toUpperCase() + x.slice(1);

/** Срок словами, как в строке задачи Todoist: Сегодня, Завтра, Вчера, день недели на 6 дней вперёд, иначе «19 окт». */
export function dueWords(s: string, today: string): string {
  const n = diffDays(s, today);
  if (n === 0) return 'Сегодня';
  if (n === 1) return 'Завтра';
  if (n === -1) return 'Вчера';
  if (n > 1 && n < 7) return cap(WD[weekday(s)]);
  return shortDate(s, today);
}

/** Класс цвета срока — как у Todoist: просрочено красным, сегодня зелёным, завтра коричневым, неделя фиолетовым. */
export function dueTone(s: string, today: string, time?: string | null, nowHm?: string): string {
  const n = diffDays(s, today);
  if (n < 0) return 'd-over';
  if (n === 0) return time && nowHm && time.slice(0, 5) < nowHm ? 'd-over' : 'd-today';
  if (n === 1) return 'd-tom';
  if (n < 7) return 'd-week';
  return 'd-later';
}

/** Заголовок дня в «Предстоящем»: «19 окт · Сегодня · понедельник». */
export function dayHead(s: string, today: string): string[] {
  const n = diffDays(s, today);
  const rel = n === 0 ? 'Сегодня' : n === 1 ? 'Завтра' : null;
  return [shortDate(s, today), rel, WD[weekday(s)]].filter(Boolean) as string[];
}

export function nowHm(): string {
  const d = new Date(new Date().toLocaleString('en-US', { timeZone: 'Europe/Moscow' }));
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** Форма множественного числа по-русски — одна на весь веб, как у Todoist (4 формы): 0 — 1, 21, 101…; 1 — 2–4, 22–24…;
 *  2 — 5–20, 25–30, 11–14, 111…; 3 — дробное. */
export function pluralForm(n: number): 0 | 1 | 2 | 3 {
  if (!Number.isInteger(n)) return 3;
  const m10 = Math.abs(n) % 10, m100 = Math.abs(n) % 100;
  if (m10 === 1 && m100 !== 11) return 0;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return 1;
  return 2;
}
/** Фраза из четырёх форм с {n}: plural4(3, ['{n} задача выполнена', '{n} задачи выполнено', '{n} задач выполнено', …]). */
export function plural4(n: number, forms: [string, string, string, string]): string {
  return forms[pluralForm(n)].replace('{n}', String(n));
}
export function plural(n: number, one: string, few: string, many: string): string {
  return `${n} ${[one, few, many, few][pluralForm(n)]}`;
}

/** Дедлайн: обратный отсчёт за неделю («осталось 3 дня»), иначе дата. */
export function deadlineWords(s: string, today: string): string {
  const n = diffDays(s, today);
  if (n < 0) return `просрочен ${shortDate(s, today)}`;
  if (n === 0) return 'дедлайн сегодня';
  if (n < 7) return n === 1 ? 'остался 1 день' : `осталось ${plural(n, 'день', 'дня', 'дней')}`;
  return shortDate(s, today);
}
