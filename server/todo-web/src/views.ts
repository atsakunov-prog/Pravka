// Какие дела где: Сейчас, Входящие, Сегодня, Предстоящее, проект, сделка, метка, цель, поиск. Считает веб из памяти
// (как Todoist и как прежний веб): открывается мгновенно и без сети.

import { addDays, diffDays, nowHm } from './dates.ts';
import { orderBy, type Chain, type OrderCtx } from './order.ts';
import { S, nowOrder, todoSettings } from './store.ts';
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

// Индексы — раз на изменение памяти (S.version), а не на каждую строку: на настоящих данных тысячи дел и комментариев,
// а строка задачи спрашивает «сколько внутри» и «сколько комментариев» — без индекса это перебор всего на каждую строку.
let idxAt = -1;
let kidsIdx = new Map<Id, Task[]>();
let comIdx = new Map<Id, number>();
function idx() {
  if (idxAt === S.version) return;
  idxAt = S.version;
  kidsIdx = new Map();
  comIdx = new Map();
  for (const x of S.tasks.values()) {
    if (!x.why_id || x.why_goal || x.goal || (x.kind ?? 'task') !== 'task') continue;
    const arr = kidsIdx.get(x.why_id);
    if (arr) arr.push(x);
    else kidsIdx.set(x.why_id, [x]);
  }
  for (const c of S.comments.values()) if (!c.deleted_at) comIdx.set(c.task_id, (comIdx.get(c.task_id) || 0) + 1);
}
export function childrenOf(id: Id): Task[] {
  idx();
  return kidsIdx.get(id) || [];
}
export function commentCount(id: Id): number {
  idx();
  return comIdx.get(id) || 0;
}

// ── Порядок ─────────────────────────────────────────────────────────────

/** Ручной порядок в проекте: место (pos), потом время создания. */
export function byPos(a: Task, b: Task): number {
  const pa = a.pos ?? 1e9, pb = b.pos ?? 1e9;
  return pa - pb || a.created_at.localeCompare(b.created_at);
}

// Порядок Todoist (order.ts): проект — как в боковой панели (Входящие первыми), внутри проекта — без раздела, разделы,
// сделки; дерево — по местам родителей. Раз на изменение памяти.
let ctxAt = -1;
let ctx: OrderCtx;
export function orderCtx(): OrderCtx {
  if (ctxAt === S.version) return ctx;
  ctxAt = S.version;
  const proj = new Map(projectsSorted((todoSettings() as { porder?: Id[] }).porder).map((p, i) => [p.id, i + 1]));
  const deals = new Map([...S.deals.values()].sort((a, b) => a.name.localeCompare(b.name, 'ru')).map((d, i) => [d.id, i]));
  ctx = {
    project: (id) => (id ? proj.get(id) ?? 1e6 : 0),
    section: (t) => (t.section_id ? S.sections.get(t.section_id)?.pos ?? 1e5 : t.deal_id ? 1e6 + (deals.get(t.deal_id) ?? 0) : -1),
    parent: (t) => (parentOf(t) ? S.tasks.get(parentOf(t)!) : undefined),
  };
  return ctx;
}
/** Список в порядке цепочки Todoist (новый массив). */
export function sorted(ts: Task[], chain: Chain, desc = false): Task[] {
  return [...ts].sort(orderBy(chain, orderCtx(), desc));
}
/** Порядок дня (как у «Сегодня») — для сравнения по одному. */
export const smart = (a: Task, b: Task): number => orderBy('day', orderCtx())(a, b);
/** По сроку, без срока — в конце. */
export const byDue = (a: Task, b: Task): number => orderBy('date', orderCtx())(a, b);

// ── Виды ────────────────────────────────────────────────────────────────

/** Лежит ли дело в дереве целей: под целью само или внутри дела, которое под целью. У такого дела место уже есть —
 *  во «Входящих» (неразобранное) его нет, как нет там задач проекта. */
export function underGoal(t: Task): boolean {
  let x: Task | undefined = t;
  for (let i = 0; i < 20 && x; i++) {
    if (!x.why_id) return false;
    if (x.why_goal) return true;
    x = S.tasks.get(x.why_id);
  }
  return false;
}

export function inbox(): Task[] {
  return sorted(listed().filter((t) => !t.project_id && mine(t) && !underGoal(t)), 'project');
}

export function nowList(): Task[] {
  const order = nowOrder();
  const at = (id: Id) => {
    const i = order.indexOf(id);
    return i < 0 ? 1e6 : i;
  };
  const day = orderBy('day', orderCtx());
  return listed().filter((t) => t.focus_on === S.today && mine(t)).sort((a, b) => at(a.id) - at(b.id) || day(a, b));
}

/** Просроченное — срок раньше сегодняшнего дня (сегодняшнее с прошедшим временем стоит в «Сегодня», красным). */
export function overdue(): Task[] {
  return sorted(listed().filter((t) => mine(t) && t.due_date && t.due_date < S.today), 'overdue');
}
export { nowHm };

export function dayList(day: string): Task[] {
  return sorted(listed().filter((t) => mine(t) && (t.due_date === day || (t.deadline === day && t.due_date !== day && !(t.due_date && t.due_date < day)))), 'day');
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
    out.push({ day, tasks: sorted(by.get(day) || [], 'day') });
  }
  return out;
}

export function projectTasks(pid: Id): Task[] {
  return sorted(listed().filter((t) => t.project_id === pid && !t.deal_id), 'project');
}
export function dealTasks(did: Id): Task[] {
  return sorted(listed().filter((t) => t.deal_id === did), 'project');
}
export function labelTasks(name: string): Task[] {
  const n = name.toLowerCase();
  return sorted(listed().filter((t) => (t.labels || []).some((l) => l.toLowerCase() === n)), 'filter');
}
export function personTasks(pid: Id): Task[] {
  return sorted(listed().filter((t) => t.person_id === pid || t.requested_by === pid), 'date');
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
    if (!t.project_id && me && !underGoal(t)) inboxN++;
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
    tasks: sorted([...S.tasks.values()].filter((t) => !t.goal && (has(t.title) || has(t.notes))), 'search').slice(0, 80),
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
  return sorted(listed().filter((t) => t.why_id === id && t.why_goal), 'date');
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
