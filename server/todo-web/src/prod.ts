// «Продуктивность» как у Todoist (опись поведения Todoist §12.5): цель дня и недели, серии по дням и по неделям (текущая
// и лучшая), выходные серию не рвут, режим отпуска — пропуски после последнего выполненного дня тоже. Столбики
// выполненного по дням и неделям — кусками проектов. Карму не считаем: у Дел свои очки (вид stats).
// Данные — вид productivity (выполненное по дням и проектам), настройки — settings.todo.prod.

import { addDays, mondayOf, weekday } from './dates.ts';

export interface ProdPrefs {
  daily?: number;
  weekly?: number;
  days_off?: number[]; // 1 — пн … 7 — вс, как у Todoist
  vacation?: boolean;
}
export const PROD_DEFAULT: Required<ProdPrefs> = { daily: 5, weekly: 30, days_off: [6, 7], vacation: false }; // у Саши в Todoist 5/30

export interface ProdDay {
  day: string;
  by_project: Record<string, number>; // '' — Входящие
}

type Period = { key: string; met: boolean; off: boolean };

/** Серия по порядку периодов (дни или недели): выполнил цель — +1; выходной — не в счёт и не рвёт; текущий период ещё
 *  не кончился — не рвёт; в отпуске пропуски после последнего выполненного — не рвут. */
export function streak(periods: Period[], current: string, vacation: boolean): { current: number; best: number } {
  const lastMet = [...periods].reverse().find((p) => p.met)?.key ?? '';
  let run = 0, best = 0;
  for (const p of periods) {
    if (p.met) {
      run++;
      best = Math.max(best, run);
    } else if (p.off || p.key === current || (vacation && p.key > lastMet)) {
      continue;
    } else run = 0;
  }
  return { current: run, best };
}

export interface ProdSummary {
  today: { done: number; goal: number };
  week: { done: number; goal: number };
  dayStreak: { current: number; best: number };
  weekStreak: { current: number; best: number };
  last7: { day: string; parts: [string, number][] }[]; // [проект, сколько]
  last4w: { week: string; parts: [string, number][] }[];
  total: number;
  since: string | null;
}

const sum = (m: Record<string, number>) => Object.values(m).reduce((a, b) => a + b, 0);
const merge = (into: Map<string, number>, m: Record<string, number>) => {
  for (const [k, v] of Object.entries(m)) into.set(k, (into.get(k) || 0) + v);
};

export function prodSummary(days: ProdDay[], today: string, prefs: ProdPrefs = {}): ProdSummary {
  const p = { ...PROD_DEFAULT, ...prefs };
  const by = new Map(days.map((d) => [d.day, d.by_project]));
  const since = days.length ? days[0].day : null;
  // дни — с первого выполненного до сегодня
  const dayPeriods: Period[] = [];
  for (let d = since ?? today; d <= today; d = addDays(d, 1)) {
    dayPeriods.push({ key: d, met: sum(by.get(d) || {}) >= p.daily, off: p.days_off.includes(weekday(d) + 1) });
  }
  // недели с понедельника
  const weekOf = new Map<string, Map<string, number>>();
  for (const d of days) {
    const w = mondayOf(d.day);
    if (!weekOf.has(w)) weekOf.set(w, new Map());
    merge(weekOf.get(w)!, d.by_project);
  }
  const wsum = (w: string) => [...(weekOf.get(w)?.values() || [])].reduce((a, b) => a + b, 0);
  const thisWeek = mondayOf(today);
  const weekPeriods: Period[] = [];
  for (let w = mondayOf(since ?? today); w <= thisWeek; w = addDays(w, 7)) weekPeriods.push({ key: w, met: wsum(w) >= p.weekly, off: false });
  const parts = (m: Map<string, number> | Record<string, number> | undefined): [string, number][] =>
    (m ? [...(m instanceof Map ? m.entries() : Object.entries(m))] : []).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1]);
  return {
    today: { done: sum(by.get(today) || {}), goal: p.daily },
    week: { done: wsum(thisWeek), goal: p.weekly },
    dayStreak: streak(dayPeriods, today, p.vacation),
    weekStreak: streak(weekPeriods, thisWeek, p.vacation),
    last7: Array.from({ length: 7 }, (_, i) => addDays(today, i - 6)).map((day) => ({ day, parts: parts(by.get(day)) })),
    last4w: Array.from({ length: 4 }, (_, i) => addDays(thisWeek, 7 * (i - 3))).map((week) => ({ week, parts: parts(weekOf.get(week)) })),
    total: days.reduce((a, d) => a + sum(d.by_project), 0),
    since,
  };
}
