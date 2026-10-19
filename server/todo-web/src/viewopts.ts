// «Отображение» вида (Display у Todoist, 19.10): раскладка и выполненные, сортировать (группировка, сортировка), фильтр
// (срок, дедлайн, приоритет, метка). Личное у каждого вида — crm.users.settings.todo.views. Здесь — без React: что
// показать и как подписать кнопку.

import { addDays, mondayOf } from './dates.ts';
import type { Task } from './types.ts';

export interface ViewOpts {
  layout?: 'list' | 'board';
  group?: 'none' | 'date' | 'added' | 'deadline' | 'priority' | 'label' | 'project';
  sort?: 'manual' | 'date' | 'added' | 'deadline' | 'priority' | 'name' | 'project';
  done?: boolean;
  fdue?: 'all' | 'today' | 'over' | 'week' | 'next7' | 'none'; // фильтр по сроку
  fdl?: 'all' | 'has' | 'next7' | 'none'; // по дедлайну
  fprio?: 'all' | '1' | '2' | '3' | '4';
  flabel?: string; // '' — все
}

export const GROUP_BY: Record<string, string> = { date: 'сроку', added: 'дате добавления', deadline: 'дедлайну', priority: 'приоритету', label: 'меткам', project: 'проекту' };
export const SORT_BY: Record<string, string> = { date: 'сроку', added: 'дате добавления', deadline: 'дедлайну', priority: 'приоритету', name: 'алфавиту', project: 'проекту' };

/** Что поменяно против умолчаний — для подписи кнопки и числа рядом. */
export function changed(o: ViewOpts): (keyof ViewOpts)[] {
  const out: (keyof ViewOpts)[] = [];
  if (o.layout && o.layout !== 'list') out.push('layout');
  if (o.group && o.group !== 'none') out.push('group');
  if (o.sort && o.sort !== 'manual') out.push('sort');
  if (o.done) out.push('done');
  if (o.fdue && o.fdue !== 'all') out.push('fdue');
  if (o.fdl && o.fdl !== 'all') out.push('fdl');
  if (o.fprio && o.fprio !== 'all') out.push('fprio');
  if (o.flabel) out.push('flabel');
  return out;
}

/** Подпись кнопки, как у Todoist: одна сортировка — «Отсортировано по…», одна группировка — «Сгруппировано по…», больше
 *  (или другое) — «Свой вид»; ничего — «Отображение». n — сколько настроек изменено. */
export function viewLabel(o: ViewOpts): { text: string; n: number } {
  const ch = changed(o);
  if (!ch.length) return { text: 'Отображение', n: 0 };
  if (ch.length === 1 && ch[0] === 'sort') return { text: 'Отсортировано по ' + SORT_BY[o.sort!], n: 1 };
  if (ch.length === 1 && ch[0] === 'group') return { text: 'Сгруппировано по ' + GROUP_BY[o.group!], n: 1 };
  return { text: 'Свой вид', n: ch.length };
}

/** Фильтр вида: срок (сегодня, просрочено, эта неделя, 7 дней, без срока), дедлайн, приоритет, метка. */
export function filterTasks(ts: Task[], o: ViewOpts, today: string): Task[] {
  const in7 = addDays(today, 6), sun = addDays(mondayOf(today), 6);
  return ts.filter((t) => {
    const d = t.due_date;
    if (o.fdue === 'today' && d !== today) return false;
    if (o.fdue === 'over' && !(d && d < today)) return false;
    if (o.fdue === 'week' && !(d && d <= sun)) return false; // эта неделя — до воскресенья, просроченное тоже
    if (o.fdue === 'next7' && !(d && d >= today && d <= in7)) return false;
    if (o.fdue === 'none' && d) return false;
    const dl = t.deadline;
    if (o.fdl === 'has' && !dl) return false;
    if (o.fdl === 'none' && dl) return false;
    if (o.fdl === 'next7' && !(dl && dl <= in7)) return false;
    if (o.fprio && o.fprio !== 'all' && String(t.priority ?? 4) !== o.fprio) return false;
    if (o.flabel && !(t.labels || []).includes(o.flabel)) return false;
    return true;
  });
}
