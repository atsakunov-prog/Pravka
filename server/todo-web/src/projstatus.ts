// «Статус проекта» (Insights у Todoist, опись §10.2): статус одним словом и дела, которые его портят, доля сделанного,
// сделанное на этой неделе по дням. У Todoist статус считает сервер по своему правилу; здесь правило своё и простое:
// - Критично — просрочено 3 дела и больше или прошёл дедлайн у открытого дела;
// - Под угрозой — просрочено 1–2 дела или дедлайн сегодня-завтра у открытого;
// - Отлично — ничего не горит и на этой неделе что-то сделано;
// - По плану — ничего не горит, но на этой неделе пока ничего не сделано.

import { addDays, mondayOf } from './dates.ts';
import type { Task } from './types.ts';

export type Level = 'great' | 'on_track' | 'at_risk' | 'critical';
export const LEVEL_NAME: Record<Level, string> = { great: 'Отлично', on_track: 'По плану', at_risk: 'Под угрозой', critical: 'Критично' };

export interface ProjStatus {
  level: Level;
  spoilers: Task[]; // что портит статус: просроченное и горящие дедлайны
  progress: { done: number; total: number; pct: number };
  week: { day: string; n: number }[]; // пн…вс этой недели
  weekDone: number;
}

const msk = (iso: string) => new Date(new Date(iso).toLocaleString('en-US', { timeZone: 'Europe/Moscow' }));
/** День выполнения по Москве (как везде в Делах). */
export const doneDay = (t: Task): string | null => {
  if (!t.completed_at) return null;
  const d = msk(t.completed_at);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

/** tasks — все дела проекта (открытые, сделанные, отменённые), today — сегодня по Москве. */
export function projStatus(tasks: Task[], today: string): ProjStatus {
  const live = tasks.filter((t) => t.status !== 'cancelled' && (t.kind ?? 'task') === 'task' && !t.goal);
  const open = live.filter((t) => t.status === 'open');
  const overdue = open.filter((t) => t.due_date && t.due_date < today);
  const deadPast = open.filter((t) => t.deadline && t.deadline < today);
  const deadNear = open.filter((t) => t.deadline && t.deadline >= today && t.deadline <= addDays(today, 1));
  const mon = mondayOf(today);
  const week = Array.from({ length: 7 }, (_, i) => ({ day: addDays(mon, i), n: 0 }));
  let weekDone = 0;
  for (const t of live) {
    const d = t.status === 'done' ? doneDay(t) : null;
    const w = d ? week.find((x) => x.day === d) : undefined;
    if (w) {
      w.n++;
      weekDone++;
    }
  }
  const level: Level = overdue.length >= 3 || deadPast.length ? 'critical'
    : overdue.length || deadNear.length ? 'at_risk'
      : weekDone ? 'great' : 'on_track';
  const spoilers = [...new Map([...deadPast, ...overdue, ...deadNear].map((t) => [t.id, t])).values()];
  const done = live.filter((t) => t.status === 'done').length;
  return { level, spoilers, progress: { done, total: live.length, pct: live.length ? Math.round((100 * done) / live.length) : 0 }, week, weekDone };
}
