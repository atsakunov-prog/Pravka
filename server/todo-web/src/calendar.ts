// Календарь как у Todoist (замер 19.10, окно 1400×900): режимы 3 дня / неделя / месяц, ряд «Весь день» для дел без
// времени, час — 68 пкс, дела со временем — блоками по времени, высота — длительность; будущие разы повторов. Без React:
// какие дни видны, что в какой день и где стоит блок.

import { addDays, addMonths, daysInMonth, mondayOf, ymd } from './dates.ts';
import { occurrences } from './schedule.ts';
import type { Task } from './types.ts';

export type CalMode = '3d' | 'week' | 'month';
export const HOUR = 68; // пкс на час, как у Todoist
export const DEFAULT_MIN = 30; // блок дела без длительности

/** Видимые дни: неделя — пн…вс недели якоря, 3 дня — с якоря, месяц — целые недели, накрывающие месяц якоря. */
export function calDays(mode: CalMode, anchor: string): string[] {
  if (mode === '3d') return [0, 1, 2].map((i) => addDays(anchor, i));
  if (mode === 'week') return Array.from({ length: 7 }, (_, i) => addDays(mondayOf(anchor), i));
  const y = +anchor.slice(0, 4), m = +anchor.slice(5, 7);
  const first = mondayOf(ymd(y, m, 1)), last = ymd(y, m, daysInMonth(y, m));
  const out: string[] = [];
  for (let d = first; d <= last || out.length % 7; d = addDays(d, 1)) out.push(d);
  return out;
}

/** Стрелки ‹ ›: неделя — на 7 дней, 3 дня — на 3, месяц — на месяц (якорь — 1-е число). */
export function shiftAnchor(mode: CalMode, anchor: string, dir: 1 | -1): string {
  if (mode === 'month') return addMonths(anchor.slice(0, 8) + '01', dir);
  return addDays(anchor, dir * (mode === '3d' ? 3 : 7));
}

export interface CalItem {
  task: Task;
  day: string;
  time: string | null; // ЧЧ:ММ; нет — в ряд «Весь день»
  mins: number; // длительность
  ghost: boolean; // будущий раз повтора («Будущие повторы»)
}

/** Что в какие дни: срок в видимых днях; будущие разы повторов (repeats); сделанные — если done. */
export function calItems(tasks: Task[], days: string[], opts: { repeats?: boolean; done?: boolean } = {}): CalItem[] {
  if (!days.length) return [];
  const from = days[0], to = days[days.length - 1];
  const out: CalItem[] = [];
  for (const t of tasks) {
    if (!t.due_date || (t.status !== 'open' && !(opts.done && t.status === 'done'))) continue;
    const item = (day: string, ghost: boolean): CalItem => ({ task: t, day, time: t.due_time ? t.due_time.slice(0, 5) : null, mins: t.estimate_min || DEFAULT_MIN, ghost });
    if (t.due_date >= from && t.due_date <= to) out.push(item(t.due_date, false));
    if (opts.repeats && t.recur && t.status === 'open') {
      for (const d of occurrences(t.recur, t.due_date, to)) if (d >= from) out.push(item(d, true));
    }
  }
  return out;
}

export interface Block {
  item: CalItem;
  top: number;
  height: number;
  lane: number;
  lanes: number;
}

const mins = (hm: string) => +hm.slice(0, 2) * 60 + +hm.slice(3, 5);

/** Блоки одного дня: сверху — по времени (час — HOUR), высота — длительность (не меньше 15 мин); пересекающиеся — рядом,
 *  в дорожках, как в календаре. */
export function dayBlocks(items: CalItem[]): Block[] {
  const timed = items.filter((x) => x.time).sort((a, b) => mins(a.time!) - mins(b.time!) || b.mins - a.mins);
  const blocks: Block[] = [];
  let group: Block[] = [];
  let groupEnd = -1;
  const close = () => {
    const lanes = Math.max(1, ...group.map((b) => b.lane + 1));
    for (const b of group) b.lanes = lanes;
    group = [];
  };
  for (const it of timed) {
    const start = mins(it.time!), end = start + Math.max(it.mins, 15);
    if (start >= groupEnd) {
      close();
      groupEnd = -1;
    }
    // первая дорожка, где предыдущий блок уже кончился
    const used = group.filter((b) => (b.top + b.height) / HOUR * 60 > start).map((b) => b.lane);
    let lane = 0;
    while (used.includes(lane)) lane++;
    const b: Block = { item: it, top: (start / 60) * HOUR, height: (Math.max(it.mins, 15) / 60) * HOUR, lane, lanes: 1 };
    group.push(b);
    blocks.push(b);
    groupEnd = Math.max(groupEnd, end);
  }
  close();
  return blocks;
}

/** Где красная линия «сейчас»: пкс от начала суток. */
export const nowTop = (hm: string) => (mins(hm) / 60) * HOUR;
