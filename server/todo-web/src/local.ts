// Что правка меняет на экране до ответа сервера (19.10.2026, как у Todoist): экран отзывается сразу, а без сети правка
// лежит в очереди (outbox.ts) и её след повторяется поверх каждого синка, пока она не ушла. След — «поставить поля» или
// «завести строку», поэтому повтор безвреден: второй раз ставит те же значения. Без React и без памяти страницы.

import { diffDays } from './dates.ts';
import { nextDue } from './schedule.ts';

export type Op = Record<string, any> & { op: string };
export type Coll = 'tasks' | 'sections' | 'projects' | 'comments';
/** След правки: patch — поля строки (строки нет — ничего), row — новая строка (уже есть с сервера — ничего). */
export interface Effect {
  coll: Coll;
  id: string;
  patch?: Record<string, unknown>;
  row?: Record<string, unknown>;
}
export type Maps = Record<Coll, Map<string, any>>;
export interface Ctx {
  user: string;
  today: string;
  now: string; // ISO — время правки
}

/** След одной правки; extra — то, что ответил бы сервер (recurred — новый срок повтора), для тоста без сети. */
export function effectsOf(o: Op, m: Maps, c: Ctx): { fx: Effect[]; extra?: Record<string, unknown> } {
  const id = o.id ? String(o.id) : '';
  const cur = id ? m.tasks.get(id) : undefined;
  switch (o.op) {
    case 'task.create': {
      const t = o.task || {};
      if (!t.id) return { fx: [] };
      return { fx: [{ coll: 'tasks', id: t.id, row: {
        num: 0, notes: null, project_id: null, deal_id: null, owner_id: c.user, ball: 'mine', person_id: null, requested_by: null,
        due_date: null, due_time: null, estimate_min: null, focus_on: null, labels: [], status: 'open', source: 'web', source_ref: null,
        created_by: c.user, created_at: c.now, updated_at: c.now, completed_at: null, remind_at: null, remind_place: null, pos: null,
        priority: 4, kind: 'task', ...t, _pending: true } }] };
    }
    case 'task.set':
      return { fx: cur && o.set ? [{ coll: 'tasks', id, patch: { ...o.set } }] : [] };
    case 'task.done': {
      if (!cur) return { fx: [] };
      if (cur.recur && !o.forever) {
        // Повтор: новый срок, +1 раз, «Сейчас» гаснет, напоминание едет вместе со сроком — как todo.done_recurring сервера.
        const next = nextDue(cur.recur, cur.due_date, c.today);
        if (!(cur.recur.until && next > cur.recur.until)) {
          const patch: Record<string, unknown> = { due_date: next, done_count: (cur.done_count ?? 0) + 1, focus_on: null };
          if (cur.remind_at && cur.due_date) {
            patch.remind_at = new Date(Date.parse(cur.remind_at) + diffDays(next, cur.due_date) * 86400000).toISOString();
          }
          return { fx: [{ coll: 'tasks', id, patch }], extra: { recurred: next } };
        }
      }
      return { fx: [{ coll: 'tasks', id, patch: { status: 'done', completed_at: c.now } }] };
    }
    case 'task.reopen':
      return { fx: cur ? [{ coll: 'tasks', id, patch: { status: 'open', completed_at: null } }] : [] };
    case 'task.cancel':
      return { fx: cur ? [{ coll: 'tasks', id, patch: { status: 'cancelled', completed_at: c.now } }] : [] };
    case 'section.create': {
      const d = o.data || {};
      return { fx: d.id ? [{ coll: 'sections', id: d.id, row: { collapsed: false, archived: false, pos: null, ...d, _pending: true } }] : [] };
    }
    case 'section.set':
      return { fx: m.sections.has(id) && o.set ? [{ coll: 'sections', id, patch: { ...o.set } }] : [] };
    case 'project.create': {
      const d = o.data || {};
      return { fx: d.id ? [{ coll: 'projects', id: d.id, row: { owner_id: c.user, archived_at: null, status: null, ...d, _pending: true } }] : [] };
    }
    case 'project.set':
      return { fx: m.projects.has(id) && o.set ? [{ coll: 'projects', id, patch: { ...o.set } }] : [] };
    case 'comment.add': {
      const x = o.comment || {};
      return { fx: x.id ? [{ coll: 'comments', id: x.id, row: { author_id: c.user, created_at: c.now, deleted_at: null, ...x, body: x.text, _pending: true } }] : [] };
    }
    default:
      return { fx: [] };
  }
}

/** Наложить след: поля — на строку, если она есть; новую строку — если сервер её ещё не прислал. */
export function applyEffects(m: Maps, fx: Effect[]): void {
  for (const e of fx) {
    const map = m[e.coll];
    const cur = map.get(e.id);
    if (e.row) {
      if (!cur || cur._pending) map.set(e.id, e.row);
    } else if (e.patch && cur) {
      map.set(e.id, { ...cur, ...e.patch });
    }
  }
}

/** Сегодня по Москве с часов устройства — без сети сервер не скажет (в Москве нет перевода часов). */
export const mskToday = (ms = Date.now()): string => new Date(ms + 3 * 3600_000).toISOString().slice(0, 10);

