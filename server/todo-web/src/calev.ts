// События календаря Google рядом с делами (23.11.2026, dela_0040) — без React и без хранилища: что в какой день, кусок
// на сетке, подписи времени, ближайшая встреча. Загрузка и окно — cal.ts.

export interface CalEvent {
  id: string;
  title: string;
  all_day: boolean;
  day: string; // первый день по Москве
  last_day: string; // последний, включительно
  start: string | null; // ЧЧ:ММ по Москве
  end: string | null;
  end_day: string | null;
  mins: number | null;
  location: string;
  link: string; // созвон
  url: string; // событие в Google
  task_id: string | null; // поставлено из дела службой встреч
  busy: boolean;
}

type Tasks = Map<string, { status: string }>;

/** Событие, поставленное из открытого дела, — само дело и так в списке: второй строкой его не рисуем. Закрытое дело —
 *  событие видно (созвон был). */
export function twin(e: CalEvent, tasks?: Tasks): boolean {
  if (!e.task_id || !tasks) return false;
  const t = tasks.get(e.task_id);
  return !!t && t.status === 'open';
}

/** События дня: «весь день» сверху, продолжение вчерашнего, потом по началу. Близнецы открытых дел — нет. */
export function eventsOn(day: string, events: CalEvent[], tasks?: Tasks): CalEvent[] {
  return events
    .filter((e) => e.day <= day && e.last_day >= day && !twin(e, tasks))
    .sort((a, b) => Number(b.all_day) - Number(a.all_day) || (a.day < day ? -1 : 0) - (b.day < day ? -1 : 0)
      || (a.start || '').localeCompare(b.start || '') || a.title.localeCompare(b.title, 'ru'));
}

export const hmMin = (hm: string) => +hm.slice(0, 2) * 60 + +hm.slice(3, 5);
export const minHm = (m: number) => `${String(Math.floor(m / 60) % 24).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;

/** Кусок события в этот день, минуты от полуночи. «Весь день» и середина многодневного — null (ряд «Весь день»).
 *  last_day сервер считает по концу минус миг: событие до 00:00 следующего дня кончается в свой день (to = 1440). */
export function spanOn(e: CalEvent, day: string): { from: number; to: number } | null {
  if (e.all_day || !e.start || day < e.day || day > e.last_day) return null;
  if (e.day < day && day < e.last_day) return null;
  const from = day === e.day ? hmMin(e.start) : 0;
  const to = day < e.last_day ? 1440 : e.end && (e.end_day || e.day) === day ? hmMin(e.end) : 1440;
  return { from, to: Math.max(to, from) };
}

/** «15:00–15:45», «весь день», «с 22:00», «до 11:00» — подпись времени события в этот день. */
export function timeWords(e: CalEvent, day: string): string {
  const s = spanOn(e, day);
  if (!s) return 'весь день';
  if (e.day < day) return 'до ' + minHm(s.to);
  if (e.last_day > day) return 'с ' + minHm(s.from);
  return s.to > s.from ? `${minHm(s.from)}–${minHm(s.to)}` : minHm(s.from);
}

/** Где событие относительно «сейчас»: прошло, идёт, впереди. «Весь день» — без подсветки. */
export function phase(e: CalEvent, day: string, today: string, now: string): 'past' | 'live' | 'next' {
  if (day < today) return 'past';
  if (day > today) return 'next';
  const s = spanOn(e, day);
  if (!s) return 'next';
  const n = hmMin(now);
  return s.to <= n && s.to > s.from ? 'past' : s.from <= n ? 'live' : 'next';
}

/** Ближайшая занятая встреча сегодня в пределах horizon минут (или та, что идёт): подсказка над «Сейчас». */
export function nextEvent(today: string, now: string, horizon: number, events: CalEvent[], tasks?: Tasks): { e: CalEvent; inMin: number } | null {
  const n = hmMin(now);
  for (const e of eventsOn(today, events, tasks)) {
    const s = spanOn(e, today);
    if (!s || !e.busy) continue;
    if (s.from <= n && s.to > n) return { e, inMin: 0 };
    if (s.from > n && s.from - n <= horizon) return { e, inMin: s.from - n };
  }
  return null;
}
