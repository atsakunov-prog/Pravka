// Быстрые варианты планировщика срока и дедлайна и «Отложить» — по правилам Todoist (вынуты из его кода 19.10, опись
// поведения Todoist, раздел 7). Неделя — с понедельника, выходные — с субботы, «следующая неделя» — понедельник.
// Подписи — из русского словаря Todoist. Время варианты не ставят: остаётся, какое было.

import { addDays, addMonths, daysInMonth, diffDays, mondayOf, weekday, ymd } from './dates.ts';
import type { Recur } from './types.ts';

export type QuickKey = 'today' | 'tomorrow' | 'later' | 'weekend' | 'nextweekend' | 'nextweek' | 'postpone' | 'none';

export interface Quick {
  key: QuickKey;
  label: string;
  date: string | null; // у «Отложить» с несколькими делами — null: дата своя у каждого
}

export interface Due {
  due_date: string | null;
  due_time?: string | null;
  recur?: Recur | null;
}

const LABEL: Record<QuickKey, string> = {
  today: 'Сегодня', tomorrow: 'Завтра', later: 'Позднее на неделе', weekend: 'На выходных', nextweekend: 'След. выходные',
  nextweek: 'След. неделя', postpone: 'Отложить', none: 'Без срока',
};

/** Варианты планировщика срока под выбранные дела и сегодняшний день. fresh — дело ещё не заведено (items — его срок).
 *  Сегодня — если хоть одно дело не на сегодня; Завтра — если хоть одно не на завтра или дело новое; Позднее на неделе
 *  (+2 дня) — если сегодня и послезавтра будни и в списке нет сразу «Сегодня» и «Завтра»; На выходных (суббота этой
 *  недели) — в пн–чт, в пт–вс вместо него След. выходные; След. неделя — кроме воскресенья (там «Завтра» и есть пн);
 *  Отложить — только если все дела повторяющиеся; Без срока — если хоть у одного срок есть. */
export function dueOptions(today: string, items: Due[], fresh = false): Quick[] {
  const out: Quick[] = [];
  const add = (key: QuickKey, date: string | null) => out.push({ key, label: LABEL[key], date });
  const tom = addDays(today, 1), later = addDays(today, 2), wd = weekday(today), mon = mondayOf(today);
  const none = !items.length;
  const showToday = none || items.some((t) => t.due_date !== today);
  const showTom = fresh || none || items.some((t) => t.due_date !== tom);
  if (showToday) add('today', today);
  if (showTom) add('tomorrow', tom);
  if (wd < 5 && weekday(later) < 5 && !(showToday && showTom)) add('later', later);
  if (wd <= 3) add('weekend', addDays(mon, 5));
  else add('nextweekend', addDays(mon, 12));
  if (wd !== 6) add('nextweek', addDays(mon, 7));
  if (!fresh && !none && items.every((t) => t.recur)) add('postpone', items.length === 1 ? postponed(items[0], today).due_date : null);
  if (items.some((t) => t.due_date)) add('none', null);
  return out;
}

/** Варианты планировщика дедлайна: есть срок — от срока (+3 дня, +1 неделя, +1 месяц), нет — Завтра, След. неделя,
 *  След. месяц (+1 месяц от сегодня, как «следующий месяц» у Todoist); Без дедлайна — если он стоит. */
export function deadlineOptions(today: string, due: string | null, deadline: string | null): { label: string; date: string | null }[] {
  const out: { label: string; date: string | null }[] = due
    ? [{ label: '+3 дня', date: addDays(due, 3) }, { label: '+1 неделя', date: addDays(due, 7) }, { label: '+1 месяц', date: addMonths(due, 1) }]
    : [{ label: 'Завтра', date: addDays(today, 1) }, { label: 'След. неделя', date: addDays(mondayOf(today), 7) }, { label: 'След. месяц', date: addMonths(today, 1) }];
  if (deadline) out.push({ label: 'Без дедлайна', date: null });
  return out;
}

/** «Отложить». Обычное дело: срок ещё не прошёл — на день позже срока, прошёл (или его нет) — на завтра. Повторяющееся —
 *  следующий раз по правилу, прошедшие разы пропускаются (как выполнение повтора на сервере, только без счёта
 *  выполнений); «каждые!» — от сегодня. Время остаётся. */
export function postponed(t: Due, today: string): { due_date: string; due_time: string | null } {
  const due_time = t.due_time ?? null;
  if (!t.recur) return { due_date: t.due_date && t.due_date >= today ? addDays(t.due_date, 1) : addDays(today, 1), due_time };
  return { due_date: nextDue(t.recur, t.due_date, today), due_time };
}

/** Следующий раз повтора, если выполнить его сегодня. Копия `next_due` и `_step` из server/pravka_dela/todo.py — правишь
 *  одно, правь и другое. Конец повтора (until) не смотрим: «Отложить» дело не закрывает. */
export function nextDue(r: Recur, due: string | null, today: string): string {
  const rule = { ...r, n: r.n ?? 1 };
  const base = due || today;
  if (rule.from === 'done' || (rule.every === 'day' && rule.n === 1)) return step(rule, today, rule.from === 'done' ? today : base);
  let nxt = step(rule, base, base);
  for (let k = 0; k < 2000 && nxt <= today; k++) nxt = step(rule, nxt, base);
  return nxt;
}

/** Будущие разы повтора после срока и до to включительно — для календаря («Будущие повторы» у Todoist). Шаг — тот же,
 *  что у сервера; «каждые!» (от выполнения) вперёд не знает, когда выполнят, — их разы считаются от срока. Конец
 *  повтора (until) соблюдается; не больше limit разов. */
export function occurrences(r: Recur, due: string, to: string, limit = 400): string[] {
  const rule = { ...r, n: r.n ?? 1 };
  const out: string[] = [];
  for (let d = step(rule, due, due); d <= to && out.length < limit; d = step(rule, d, due)) {
    if (r.until && d > r.until) break;
    out.push(d);
  }
  return out;
}

/** Следующий раз после d. anchor — исходный срок: от него число месяца и неделя отсчёта «каждые 2 недели по пн». */
function step(r: Recur & { n: number }, d: string, anchor: string): string {
  const n = r.n;
  if (r.every === 'day') return addDays(d, n);
  if (r.every === 'workday') {
    let x = d;
    for (let left = n; left; ) {
      x = addDays(x, 1);
      if (weekday(x) < 5) left--;
    }
    return x;
  }
  if (r.every === 'week') {
    if (!r.days?.length) return addDays(d, 7 * n);
    const week0 = mondayOf(anchor);
    let x = d;
    for (let k = 0; k < 7 * n + 7; k++) {
      x = addDays(x, 1);
      const weeks = Math.floor(diffDays(mondayOf(x), week0) / 7);
      if (r.days.includes(weekday(x) + 1) && weeks % n === 0) return x;
    }
    return addDays(d, 7 * n);
  }
  if (r.every === 'month') {
    const months = (+d.slice(0, 4) - +anchor.slice(0, 4)) * 12 + +d.slice(5, 7) - +anchor.slice(5, 7) + n;
    return addMonths(anchor, months, r.monthday === -1 ? 31 : r.monthday);
  }
  // год: 29 февраля в невисокосный — 28-е
  const y = +d.slice(0, 4) + n, m = +anchor.slice(5, 7);
  return ymd(y, m, Math.min(+anchor.slice(8, 10), daysInMonth(y, m)));
}
