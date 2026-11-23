// Календарь Google рядом с делами (23.11.2026, dela_0040, сервер — cal.py). Владелец: «мне дела просто некуда
// записывать… записываю в календарь, а это ужас» и утром — «доделаем календарь». Зеркало кладёт служба встреч раз в
// 5 минут (окно: неделя назад — восемь недель вперёд), веб читает вид cal и рисует встречи в «Сегодня», «Предстоящем»
// и сетке календаря. Правда — Google: здесь событие только видно, клик открывает его там.
// Здесь — загрузка окна и обёртки над своей копией; правила дней и времени — calev.ts.

import { api } from './api.ts';
import { eventsOn, nextEvent, type CalEvent } from './calev.ts';
import { addDays } from './dates.ts';
import { emit, S } from './store.ts';

export type { CalEvent };

export const CAL = {
  events: [] as CalEvent[],
  sync: null as null | { at: string; stale: boolean },
  loaded: false,
};

const BACK = 7;
const AHEAD = 56;
const EVERY = 5 * 60_000;
let timer = 0;
let busy = false;

/** Сервер умеет календарь (флаг cal в синке) — грузим окно; нет — тихо ничего. */
export async function loadCal(): Promise<void> {
  if (busy || !S.features.includes('cal') || !S.today) return;
  busy = true;
  try {
    const d = await api<{ events: CalEvent[]; sync: typeof CAL.sync }>(
      `/api/view/cal?since=${addDays(S.today, -BACK)}&until=${addDays(S.today, AHEAD)}`);
    CAL.events = d.events || [];
    CAL.sync = d.sync || null;
    CAL.loaded = true;
    emit();
  } catch {
    /* нет сети или старый сервер — остаётся последнее */
  } finally {
    busy = false;
  }
}

/** Раз в 5 минут, пока страница открыта, и при возвращении на вкладку — как зеркало у службы встреч. */
export function startCal(): () => void {
  loadCal();
  clearInterval(timer);
  timer = window.setInterval(() => { if (!document.hidden) loadCal(); }, EVERY);
  const vis = () => { if (!document.hidden) loadCal(); };
  document.addEventListener('visibilitychange', vis);
  return () => { clearInterval(timer); document.removeEventListener('visibilitychange', vis); };
}

/** События дня из своей копии; близнецы открытых дел — нет. */
export const dayEvents = (day: string): CalEvent[] => eventsOn(day, CAL.events, S.tasks);
/** Ближайшая встреча в пределах трёх часов (или идёт). */
export const soonEvent = (now: string) => (S.today ? nextEvent(S.today, now, 180, CAL.events, S.tasks) : null);
