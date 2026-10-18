// Какие дела где: Сейчас, Входящие, Сегодня, Предстоящее, проект, сделка, метка, цель, поиск. Считает веб из памяти
// (как Todoist и как прежний веб): открывается мгновенно и без сети.

import { addDays, diffDays, nowHm } from './dates.ts';
import { S, nowOrder } from './store.ts';
import type { Deal, Id, Project, Section, Task } from './types.ts';

export const isOpen = (t: Task) => t.status === 'open';
/** Дело для списков: открытое, не цель дерева и не напоминание захвата. */
export const isListed = (t: Task) => isOpen(t) && !t.goal && (t.kind ?? 'task') === 'task';
export const mine = (t: Task) => t.owner_id === S.me?.user;

export function listed(): Task[] {
  return [...S.tasks.values()].filter(isListed);
}

/** Дело внутри дела: родитель — обычное дело (не цель дерева). */
export const parentOf = (t: Task): Id | null => (t.why_id && !t.why_goal ? t.why_id : null);

export function childrenOf(id: Id): Task[] {
  return [...S.tasks.values()].filter((x) => x.why_id === id && !x.why_goal && (x.kind ?? 'task') === 'task' && !x.goal);
}

// ── Порядок ─────────────────────────────────────────────────────────────

/** Ручной порядок в проекте: место (pos), потом время создания. */
export function byPos(a: Task, b: Task): number {
  const pa = a.pos ?? 1e9, pb = b.pos ?? 1e9;
  return pa - pb || a.created_at.localeCompare(b.created_at);
}

/** Умный порядок Todoist для «Сегодня»: время → приоритет → дедлайн → порядок → создание. */
export function smart(a: Task, b: Task): number {
  const ta = a.due_time || '99', tb = b.due_time || '99';
  return ta.localeCompare(tb) || (a.priority ?? 4) - (b.priority ?? 4)
    || (a.deadline || '9999').localeCompare(b.deadline || '9999') || byPos(a, b);
}

/** По дате, без даты — в конце. */
export function byDue(a: Task, b: Task): number {
  return (a.due_date || '9999').localeCompare(b.due_date || '9999') || smart(a, b);
}

// ── Виды ────────────────────────────────────────────────────────────────

export function inbox(): Task[] {
  return listed().filter((t) => !t.project_id && mine(t)).sort(byPos);
}

export function nowList(): Task[] {
  const order = nowOrder();
  const at = (id: Id) => {
    const i = order.indexOf(id);
    return i < 0 ? 1e6 : i;
  };
  return listed().filter((t) => t.focus_on === S.today && mine(t)).sort((a, b) => at(a.id) - at(b.id) || smart(a, b));
}

/** Просроченное — срок раньше сегодняшнего дня (сегодняшнее с прошедшим временем стоит в «Сегодня», красным). */
export function overdue(): Task[] {
  return listed().filter((t) => mine(t) && t.due_date && t.due_date < S.today).sort(byDue);
}
export { nowHm };

export function dayList(day: string): Task[] {
  return listed().filter((t) => mine(t) && (t.due_date === day || (t.deadline === day && t.due_date !== day && !(t.due_date && t.due_date < day))))
    .sort(smart);
}

export function upcomingDays(from: string, n: number): { day: string; tasks: Task[] }[] {
  const all = listed().filter((t) => mine(t) && (t.due_date || t.deadline));
  const by = new Map<string, Task[]>();
  for (const t of all) {
    const d = t.due_date || t.deadline!;
    if (d < from) continue;
    if (!by.has(d)) by.set(d, []);
    by.get(d)!.push(t);
  }
  const out = [];
  for (let i = 0; i < n; i++) {
    const day = addDays(from, i);
    out.push({ day, tasks: (by.get(day) || []).sort(smart) });
  }
  return out;
}

export function projectTasks(pid: Id): Task[] {
  return listed().filter((t) => t.project_id === pid && !t.deal_id).sort(byPos);
}
export function dealTasks(did: Id): Task[] {
  return listed().filter((t) => t.deal_id === did).sort(byPos);
}
export function labelTasks(name: string): Task[] {
  const n = name.toLowerCase();
  return listed().filter((t) => (t.labels || []).some((l) => l.toLowerCase() === n)).sort((a, b) => (a.priority ?? 4) - (b.priority ?? 4) || byDue(a, b));
}
export function personTasks(pid: Id): Task[] {
  return listed().filter((t) => t.person_id === pid || t.requested_by === pid).sort(byDue);
}

export function sectionsOf(pid: Id): Section[] {
  return [...S.sections.values()].filter((s) => s.project_id === pid && !s.archived)
    .sort((a, b) => (a.pos ?? 1e9) - (b.pos ?? 1e9) || String(a.created_at).localeCompare(String(b.created_at)));
}

/** Проекты боковой панели: живые, свои порядком, потом клиенты по имени. */
export function projectsSorted(order?: Id[]): Project[] {
  const at = (id: Id) => {
    const i = (order || []).indexOf(id);
    return i < 0 ? 1e6 : i;
  };
  const kind = (p: Project) => (p.kind === 'client' ? 0 : p.kind === 'internal' ? 1 : 2);
  return [...S.projects.values()].filter((p) => !p.archived_at)
    .sort((a, b) => at(a.id) - at(b.id) || kind(a) - kind(b) || a.name.localeCompare(b.name, 'ru'));
}

/** Живые сделки клиента — подпроекты в боковой панели. */
export function liveDeals(pid: Id): Deal[] {
  return [...S.deals.values()].filter((d) => d.project_id === pid && d.stage !== 'archive' && !d.outcome)
    .sort((a, b) => a.name.localeCompare(b.name, 'ru'));
}

export function counts(): { inbox: number; today: number; now: number; byProject: Map<Id, number>; byDeal: Map<Id, number> } {
  const byProject = new Map<Id, number>(), byDeal = new Map<Id, number>();
  let inboxN = 0, todayN = 0, nowN = 0;
  for (const t of S.tasks.values()) {
    if (!isListed(t)) continue;
    const me = mine(t);
    if (!t.project_id && me) inboxN++;
    if (me && t.due_date && t.due_date <= S.today) todayN++;
    if (me && t.focus_on === S.today) nowN++;
    if (t.deal_id) byDeal.set(t.deal_id, (byDeal.get(t.deal_id) || 0) + 1);
    else if (t.project_id) byProject.set(t.project_id, (byProject.get(t.project_id) || 0) + 1);
  }
  return { inbox: inboxN, today: todayN, now: nowN, byProject, byDeal };
}

export function search(q: string): { tasks: Task[]; projects: Project[]; people: any[] } {
  const n = q.toLowerCase().replace(/ё/g, 'е').trim();
  if (!n) return { tasks: [], projects: [], people: [] };
  const has = (s?: string | null) => (s || '').toLowerCase().replace(/ё/g, 'е').includes(n);
  return {
    tasks: [...S.tasks.values()].filter((t) => !t.goal && (has(t.title) || has(t.notes)))
      .sort((a, b) => Number(!isOpen(a)) - Number(!isOpen(b)) || byDue(a, b)).slice(0, 80),
    projects: [...S.projects.values()].filter((p) => has(p.name)).slice(0, 10),
    people: [...S.people.values()].filter((p) => !p.merged_into && (has(p.name) || has(p.short))).slice(0, 10),
  };
}

export function completedRecent(days = 14): Task[] {
  const since = Date.now() - days * 86400e3;
  return [...S.tasks.values()].filter((t) => t.status === 'done' && t.completed_at && Date.parse(t.completed_at) > since && !t.goal)
    .sort((a, b) => (b.completed_at || '').localeCompare(a.completed_at || ''));
}

// ── Дерево целей (Круг жизни) ───────────────────────────────────────────

export const goals = () => [...S.tasks.values()].filter((t) => t.goal && t.status === 'open');

/** Главное направление цели — у корня цепочки «Зачем». */
export function goalDir(g: Task): string | null {
  let x: Task | undefined = g;
  for (let i = 0; i < 20 && x; i++) {
    if (!x.why_id) return (x.dirs && x.dirs[0]) || (x.directions && x.directions[0]) || null;
    x = S.tasks.get(x.why_id);
  }
  return null;
}
export function goalChildren(id: Id | null, dir?: string): Task[] {
  return goals().filter((g) => (id ? g.why_id === id : !g.why_id && goalDir(g) === dir))
    .sort((a, b) => byPos(a, b));
}
/** Дела, висящие прямо на цели (не цели). */
export function goalTasks(id: Id): Task[] {
  return listed().filter((t) => t.why_id === id && t.why_goal).sort(byDue);
}
/** Цепочка «Зачем» вверх: цель › цель › … */
export function whyPath(t: Task): Task[] {
  const out: Task[] = [];
  let x = t.why_id ? S.tasks.get(t.why_id) : undefined;
  for (let i = 0; i < 12 && x; i++) {
    out.unshift(x);
    x = x.why_id ? S.tasks.get(x.why_id) : undefined;
  }
  return out;
}
export function dirName(key: string | null | undefined): string {
  if (!key) return '';
  const d = S.dirs.find((x) => x.key === key);
  return (d && (d.name || d.title || d.short)) || key;
}

export const daysUntil = (d: string) => diffDays(d, S.today);
export type { Section };
