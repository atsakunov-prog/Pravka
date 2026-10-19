// Порядок задач как у Todoist — из его кода целиком (19.10, опись поведения Todoist §12.3). У каждого вида и у каждой
// выбранной сортировки своя цепочка признаков: при равенстве — следующий, последним всегда номер дела. Без срока и без
// дедлайна — всегда в конце, даже при обратном направлении (оно разворачивает только первый признак).
// Без React и без памяти: проект, раздел и родителя подсказывает OrderCtx (в вебе — views.orderCtx).

import type { Task } from './types.ts';

export interface OrderCtx {
  /** Место проекта как в боковой панели; Входящие (null) — первыми. */
  project: (id: string | null) => number;
  /** Место внутри проекта: без раздела — выше разделов, разделы по порядку, сделки — после. */
  section: (t: Task) => number;
  /** Родитель (дело внутри дела) — для пути в дереве. */
  parent: (t: Task) => Task | undefined;
  /** Ручной порядок дня (day_order у Todoist); не заданный — после заданных. В Делах его пока нет. */
  day?: (t: Task) => number | null;
}

export type Key = 'open' | 'due' | 'dueOrDeadline' | 'prio' | 'deadline' | 'day' | 'added' | 'project' | 'section' | 'tree' | 'name' | 'pos' | 'id';

export const CHAINS = {
  // по умолчанию — по виду
  day: ['open', 'due', 'prio', 'deadline', 'day', 'added', 'project', 'section', 'tree', 'id'], // «Сегодня», день «Предстоящего»
  overdue: ['dueOrDeadline', 'prio', 'day', 'deadline', 'added', 'project', 'section', 'tree', 'id'],
  filter: ['prio', 'due', 'deadline', 'project', 'section', 'tree', 'id'], // фильтр и метка
  project: ['pos', 'added', 'id'], // проект, раздел, дела внутри — ручной порядок
  search: ['open', 'prio', 'project', 'section', 'tree', 'id'],
  // выбранная сортировка
  manual: ['pos', 'day', 'added', 'id'],
  date: ['due', 'prio', 'day', 'deadline', 'added', 'project', 'section', 'tree', 'id'],
  deadline: ['deadline', 'due', 'prio', 'day', 'added', 'project', 'section', 'tree', 'id'],
  priority: ['prio', 'due', 'deadline', 'project', 'section', 'tree', 'id'],
  name: ['name', 'prio', 'due', 'deadline', 'project', 'section', 'tree', 'id'],
  added: ['added', 'prio', 'day', 'project', 'section', 'tree', 'id'],
  byProject: ['project', 'section', 'tree', 'id'],
} satisfies Record<string, Key[]>;

export type Chain = keyof typeof CHAINS;

/** Название без разметки (у Todoist алфавит — по чистому тексту): **жирное**, _курсив_, `код`, [ссылка](адрес). */
export function plainTitle(s: string): string {
  return s.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/(\*\*|__|\*|_|`|~~)(.+?)\1/g, '$2').replace(/^#+\s*/, '').trim();
}

// Пустое — в конце при любом направлении: сравнение «есть / нет» от направления не зависит.
const blankLast = (a: string | null | undefined, b: string | null | undefined, cmp: (x: string, y: string) => number, sign: number) =>
  a && b ? sign * cmp(a, b) : a ? -1 : b ? 1 : 0;
const str = (x: string, y: string) => (x < y ? -1 : x > y ? 1 : 0);
/** В один день дела со временем выше и по времени. */
const timeCmp = (a: Task, b: Task) => blankLast(a.due_time, b.due_time, str, 1);
/** Срок или дедлайн: дедлайн раньше срока — считается он. */
const eff = (t: Task) => (t.deadline && (!t.due_date || t.deadline < t.due_date) ? t.deadline : t.due_date);

function treePath(t: Task, ctx: OrderCtx): number[] {
  const out: number[] = [];
  let x: Task | undefined = t;
  for (let i = 0; i < 20 && x; i++) {
    out.unshift(x.pos ?? 1e9);
    x = ctx.parent(x);
  }
  return out;
}

function cmpKey(k: Key, ctx: OrderCtx, sign: number): (a: Task, b: Task) => number {
  switch (k) {
    case 'open': return (a, b) => sign * (Number(a.status !== 'open') - Number(b.status !== 'open'));
    case 'due': return (a, b) => blankLast(a.due_date, b.due_date, str, sign) || timeCmp(a, b);
    case 'dueOrDeadline': return (a, b) => blankLast(eff(a), eff(b), str, sign) || timeCmp(a, b);
    case 'prio': return (a, b) => sign * ((a.priority ?? 4) - (b.priority ?? 4));
    case 'deadline': return (a, b) => blankLast(a.deadline, b.deadline, str, sign);
    case 'day': return (a, b) => {
      const x = ctx.day?.(a) ?? null, y = ctx.day?.(b) ?? null;
      return x === null || y === null ? Number(x === null) - Number(y === null) : sign * (x - y);
    };
    case 'added': return (a, b) => sign * str(a.created_at, b.created_at);
    case 'project': return (a, b) => sign * (ctx.project(a.project_id) - ctx.project(b.project_id));
    case 'section': return (a, b) => sign * (ctx.section(a) - ctx.section(b));
    case 'tree': return (a, b) => {
      const x = treePath(a, ctx), y = treePath(b, ctx);
      for (let i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return sign * (x[i] - y[i]);
      return sign * (x.length - y.length); // родитель выше своих дел
    };
    case 'name': return (a, b) => sign * plainTitle(a.title).localeCompare(plainTitle(b.title), 'ru');
    case 'pos': return (a, b) => sign * ((a.pos ?? 1e9) - (b.pos ?? 1e9));
    case 'id': return (a, b) => sign * ((a.num ?? 0) - (b.num ?? 0) || str(a.id, b.id));
  }
}

/** Сравнитель по цепочке. desc — обратное направление первого признака. */
export function orderBy(chain: Chain, ctx: OrderCtx, desc = false): (a: Task, b: Task) => number {
  const cmps = CHAINS[chain].map((k, i) => cmpKey(k, ctx, i === 0 && desc ? -1 : 1));
  return (a, b) => {
    for (const c of cmps) {
      const r = c(a, b);
      if (r) return r;
    }
    return 0;
  };
}
