// Лента дня (23.11.2026). Владелец прислал настоящий день: утром отправить документы в печать, в 9:00 уехать за город и
// успеть вернуться, чтобы к 13:30 на велике быть на встрече в центре; назначить встречу клиенту; проверить, что человек в
// банке задал вопрос; рандаун по деньгам клиентов; подумать про финансирование; на вечерней встрече задать два вопроса;
// перечитать сделанное для клиента; понять, как учиться музыке — «и всё уместить в один день с календарём», а потом —
// «чтобы всё было под рукой, когда еду на машине, когда на велике, когда зашёл за печатью». Отсюда — одна лента вместо
// «встречи отдельно, дела отдельно»:
//   • встречи календаря и дела со своим часом — по порядку, у дела — его длительность (estimate_min);
//   • накладки — у чего с чем (тренировка в 13:00 ложится на дорогу и на встречу);
//   • окна между ними — свободное время, куда бросить дело без часа;
//   • под блоком — дела внутри (вопросы к встрече) и «заодно» — дела с той же меткой-местом без часа («машина»: в
//     дороге позвонить, подумать вслух) — их время уже внутри блока;
//   • ёмкость: сумма окон против того, что осталось без часа.
// Без React и без хранилища: что в ленте, где окна и что не влезает.

import { hmMin, minHm, spanOn, type CalEvent } from './calev.ts';
import type { Task } from './types.ts';

export const DAY_FROM = 8 * 60; // окна считаем с 8:00
export const DAY_TO = 22 * 60; // до 22:00
export const GAP_MIN = 20; // окно короче — не окно
export const TASK_MIN = 15; // дело со своим часом без длительности
export const WAIT_MIN = 5; // «Мониторить»: проверить, что человек сделал, — пять минут

export interface PlanItem {
  key: string;
  from: number; // минуты от полуночи; -1 — «весь день»
  to: number;
  ev?: CalEvent;
  task?: Task;
  busy: boolean;
  clash: string[]; // с чем накладывается
  inside: Task[]; // дела внутри (вопросы к встрече)
  along: { label: string; tasks: Task[] }[]; // «заодно» по метке
}

export interface Gap {
  from: number;
  to: number;
}

export interface Plan {
  items: PlanItem[];
  gaps: Gap[];
  untimed: Task[]; // без часа и не внутри блоков
  free: number; // минут в окнах
  need: number; // минут нужно делам без часа
}

const parentOf = (t: Task) => (t.why_id && !t.why_goal ? t.why_id : null);
const need1 = (t: Task) => (t.ball === 'waiting' ? Math.min(t.estimate_min || WAIT_MIN, WAIT_MIN) : t.estimate_min || TASK_MIN);
const titleOf = (x: PlanItem) => (x.ev ? x.ev.title : x.task!.title);

/** Лента дня. events — встречи этого дня без близнецов открытых дел; dayTasks — открытые свои дела со сроком в этот
 *  день; open — все открытые свои (для «внутри» и «заодно»: дела без срока с меткой блока тоже идут в дорогу);
 *  now — минуты «сейчас», если день — сегодня (окна — только впереди). */
export function buildPlan(day: string, events: CalEvent[], dayTasks: Task[], open: Task[], now: number | null = null): Plan {
  const items: PlanItem[] = [];
  for (const e of events) {
    const s = spanOn(e, day);
    items.push({ key: 'ev:' + e.id, from: s ? s.from : -1, to: s ? s.to : -1, ev: e, busy: !!s && e.busy, clash: [], inside: [], along: [] });
  }
  const timed = dayTasks.filter((t) => t.due_date === day && t.due_time);
  for (const t of timed) {
    const from = hmMin(t.due_time!);
    items.push({ key: t.id, from, to: Math.min(from + (t.estimate_min || TASK_MIN), 1440), task: t, busy: true, clash: [], inside: [], along: [] });
  }
  items.sort((a, b) => a.from - b.from || a.to - b.to || titleOf(a).localeCompare(titleOf(b), 'ru'));
  // Накладки: два занятых куска пересекаются.
  const busy = items.filter((x) => x.busy && x.from >= 0);
  for (let i = 0; i < busy.length; i++) {
    for (let j = i + 1; j < busy.length; j++) {
      const a = busy[i], b = busy[j];
      if (b.from >= a.to) break;
      if (a.from < b.to && b.from < a.to) {
        a.clash.push(titleOf(b));
        b.clash.push(titleOf(a));
      }
    }
  }
  // Внутри блока — его дела (вопросы к встрече); заодно — дела с той же меткой без своего часа, срок не позже дня.
  const timedIds = new Set(timed.map((t) => t.id));
  const used = new Set<string>();
  for (const it of items) {
    const t = it.task;
    if (!t) continue;
    it.inside = open.filter((k) => parentOf(k) === t.id && k.status === 'open');
    it.inside.forEach((k) => used.add(k.id));
  }
  for (const it of items) {
    const t = it.task;
    if (!t || !(t.labels || []).length) continue;
    for (const label of t.labels) {
      const ts = open.filter((k) => k.id !== t.id && !timedIds.has(k.id) && !used.has(k.id) && !k.due_time && (k.labels || []).includes(label)
        && (!k.due_date || k.due_date <= day) && !(parentOf(k) && timedIds.has(parentOf(k)!)));
      if (ts.length) {
        it.along.push({ label, tasks: ts });
        ts.forEach((k) => used.add(k.id));
      }
    }
  }
  const untimed = dayTasks.filter((t) => !t.due_time && !used.has(t.id) && !(parentOf(t) && timedIds.has(parentOf(t)!)));
  // Окна: от 8:00 (или «сейчас») до 22:00 между занятыми кусками.
  const gaps: Gap[] = [];
  let at = Math.max(DAY_FROM, now ?? 0);
  for (const b of busy) {
    if (b.to <= at) continue;
    if (b.from - at >= GAP_MIN && b.from <= DAY_TO) gaps.push({ from: at, to: Math.min(b.from, DAY_TO) });
    at = Math.max(at, b.to);
  }
  if (DAY_TO - at >= GAP_MIN) gaps.push({ from: at, to: DAY_TO });
  const free = gaps.reduce((n, g) => n + g.to - g.from, 0);
  const need = untimed.reduce((n, t) => n + need1(t), 0);
  return { items, gaps, untimed, free, need };
}

/** «2 ч 30 мин», «45 мин», «1 ч». */
export function durWords(m: number): string {
  const h = Math.floor(m / 60), mm = m % 60;
  return h ? (mm ? `${h} ч ${mm} мин` : `${h} ч`) : `${mm} мин`;
}

/** «09:00–10:15». */
export const spanWords = (from: number, to: number) => (from < 0 ? 'весь день' : to > from ? `${minHm(from)}–${minHm(to)}` : minHm(from));

/** Куда встанет дело, брошенное на окно: начало окна, по 15 минут вверх. */
export const gapSlot = (g: Gap) => minHm(Math.ceil(g.from / 15) * 15);
