// Память веба: всё, что отдаёт /api/sync, по номеру изменения. Правки — операциями /api/ops (как у телефона и Claude):
// сначала в памяти (экран отзывается сразу, как у Todoist), потом на сервер; отказ сервера — откат синком и подсказка.

import { useSyncExternalStore } from 'react';
import { ApiError, api, uuid } from './api.ts';
import { T } from './toastText.ts';
import type { Comment, Deal, Direction, Id, Me, Person, Project, Section, Suggestion, Task, TodoSettings } from './types.ts';

export const S = {
  me: null as Me | null,
  seq: 0,
  today: '',
  features: [] as string[],
  tasks: new Map<Id, Task>(),
  projects: new Map<Id, Project>(),
  deals: new Map<Id, Deal>(),
  people: new Map<Id, Person>(),
  orgs: new Map<Id, any>(),
  sections: new Map<Id, Section>(),
  sugs: new Map<Id, Suggestion>(),
  comments: new Map<Id, Comment>(),
  users: new Map<string, any>(),
  labels: [] as { name: string; color: string | null }[],
  krugProj: new Map<Id, { directions: string[]; why_id: Id | null }>(),
  dirs: [] as Direction[],
  ready: false,
  offline: false,
  version: 0,
  debug: null as null | { on: boolean; new: number; last: number },
};

// Последние действия страницы — для записки «Что не так?» (как у прежнего веба: что ушло операциями, что сказал Claude,
// тосты и ошибки). Только в памяти, 40 последних.
export const trail: { at: string; kind: string; text: string; data?: unknown }[] = [];
export function log(kind: string, text: string, data?: unknown) {
  trail.push({ at: new Date().toISOString(), kind, text: text.slice(0, 500), ...(data !== undefined ? { data } : {}) });
  if (trail.length > 40) trail.shift();
}

const subs = new Set<() => void>();
let queued = false;
/** Перерисовать всех, кто смотрит в память, — одним кадром, сколько бы правок ни пришло. */
export function emit() {
  if (queued) return;
  queued = true;
  queueMicrotask(() => {
    queued = false;
    S.version++;
    subs.forEach((f) => f());
  });
}
const subscribe = (f: () => void) => {
  subs.add(f);
  return () => subs.delete(f);
};
export function useStore(): number {
  return useSyncExternalStore(subscribe, () => S.version);
}

// ── Подсказки внизу экрана (тост Todoist: текст и «Отменить») ─────────────

export interface Toast {
  id: number;
  text: string;
  sub?: string[]; // строки ниже — «Следующий раз: …» у повтора
  undo?: () => void;
  error?: boolean;
}
export const toasts: Toast[] = [];
let toastN = 0;
/** Тост снизу слева; с «Отменить» висит 10 с, как у Todoist (умолчание его библиотеки), без — 4 с. */
export function toast(text: string, undo?: () => void, error = false, sub?: string[]) {
  const t = { id: ++toastN, text, sub, undo, error };
  log(error ? 'error' : 'toast', text);
  toasts.push(t);
  if (toasts.length > 3) toasts.shift();
  emit();
  setTimeout(() => dropToast(t.id), undo ? 10000 : 4000);
}
export function dropToast(id: number) {
  const i = toasts.findIndex((t) => t.id === id);
  if (i >= 0) {
    toasts.splice(i, 1);
    emit();
  }
}
export const fail = (e: unknown) => toast(e instanceof Error ? e.message : String(e), undefined, true);

// ── Синк ─────────────────────────────────────────────────────────────────

export async function sync(full = false): Promise<void> {
  const d = await api<any>('/api/sync?since=' + (full ? 0 : S.seq));
  if (d.full) {
    for (const m of [S.tasks, S.projects, S.deals, S.people, S.orgs, S.sections, S.sugs, S.comments, S.users, S.krugProj]) m.clear();
  }
  for (const t of d.tasks || []) S.tasks.set(t.id, t);
  for (const p of d.projects || []) S.projects.set(p.id, p);
  for (const x of d.deals || []) S.deals.set(x.id, x);
  for (const p of d.people || []) S.people.set(p.id, p);
  for (const o of d.orgs || []) S.orgs.set(o.id, o);
  for (const x of d.sections || []) S.sections.set(x.id, x);
  for (const s of d.suggestions || []) S.sugs.set(s.id, s);
  for (const c of d.comments || []) S.comments.set(c.id, c);
  for (const u of d.users || []) S.users.set(u.id, u);
  for (const k of d.krug || []) S.krugProj.set(k.id, { directions: k.directions || [], why_id: k.why_id || null });
  S.labels = d.labels || [];
  for (const r of d.svod || []) {
    if (r.key === 'krug.directions' && Array.isArray(r.value)) {
      S.dirs = r.value.filter((x: Direction) => x && x.key).sort((a: Direction, b: Direction) => ((+(a.pos ?? 0)) % 12) - ((+(b.pos ?? 0)) % 12));
    }
  }
  S.features = d.features || S.features;
  if (d.debug !== undefined) S.debug = d.debug;
  S.seq = d.seq;
  S.today = d.today;
  S.offline = false;
  S.ready = true;
  emit();
}

let syncing: Promise<void> | null = null;
export function syncSoon(full = false): Promise<void> {
  if (!syncing) {
    syncing = sync(full)
      .catch((e) => {
        if (e instanceof ApiError && e.status === 0) {
          S.offline = true;
          emit();
        } else throw e;
      })
      .finally(() => {
        syncing = null;
      });
  }
  return syncing;
}

// ── Правки ───────────────────────────────────────────────────────────────

export type Op = Record<string, any> & { op: string };

const OPEN_FIELDS = ['status', 'completed_at'];

/** Правка дела в памяти — до ответа сервера: экран отзывается сразу. */
function optimistic(o: Op) {
  if (o.op === 'task.create' && o.task?.id) {
    const t = o.task;
    const now = new Date().toISOString();
    S.tasks.set(t.id, {
      num: 0, notes: null, project_id: null, deal_id: null, owner_id: S.me?.user || '', ball: 'mine', person_id: null,
      requested_by: null, due_date: null, due_time: null, estimate_min: null, focus_on: null, labels: [], status: 'open',
      source: 'web', source_ref: null, created_by: S.me?.user || '', created_at: now, updated_at: now, completed_at: null,
      remind_at: null, remind_place: null, pos: null, priority: 4, kind: 'task', ...t, _pending: true,
    } as Task);
  }
  const cur = o.id ? S.tasks.get(o.id) : undefined;
  if (!cur) return;
  if (o.op === 'task.set') S.tasks.set(cur.id, { ...cur, ...o.set });
  if (o.op === 'task.done' && !(cur.recur && !o.forever)) S.tasks.set(cur.id, { ...cur, status: 'done', completed_at: new Date().toISOString() });
  if (o.op === 'task.reopen') S.tasks.set(cur.id, { ...cur, status: 'open', completed_at: null });
  if (o.op === 'task.cancel') S.tasks.set(cur.id, { ...cur, status: 'cancelled', completed_at: new Date().toISOString() });
  void OPEN_FIELDS;
}

export async function ops(items: Op[], { quiet = false } = {}): Promise<any[]> {
  for (const o of items) optimistic(o);
  log('ops', items.map((o) => o.op + (o.id ? ' ' + String(o.id).slice(0, 8) : '')).join(', '), items.slice(0, 5));
  emit();
  let d: any;
  try {
    d = await api('/api/ops', { ops: items.map((o) => ({ op_id: uuid(), ...o })) });
  } catch (e) {
    await syncSoon(true).catch(() => {});
    if (!quiet) fail(e);
    throw e;
  }
  for (const r of d.results) {
    if (r.ok && r.task) S.tasks.set(r.task.id, r.task);
    if (r.ok && r.row && items.find((o) => o.op.startsWith('section.'))) S.sections.set(r.row.id, r.row);
  }
  emit();
  const bad = d.results.filter((r: any) => !r.ok);
  syncSoon().catch(() => {});
  if (bad.length) {
    const e = new Error(bad[0].error + (bad.length > 1 ? ` (и ещё ${bad.length - 1})` : ''));
    await syncSoon(true).catch(() => {});
    if (!quiet) fail(e);
    throw e;
  }
  return d.results;
}
export const op1 = async (o: Op) => (await ops([o]))[0];

/** Поля дела, которые вернуть «как было» (отмена правки тостом). */
export function before(t: Task, set: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const k of Object.keys(set)) out[k] = (t as any)[k] ?? null;
  return out;
}

export async function setTask(t: Task, set: Record<string, unknown>, said?: string) {
  const was = before(t, set);
  await ops([{ op: 'task.set', id: t.id, set }]);
  if (said) toast(said, () => ops([{ op: 'task.set', id: t.id, set: was }]).catch(() => {}));
}

export async function completeTask(t: Task, forever = false) {
  const res = await ops([{ op: 'task.done', id: t.id, ...(forever ? { forever: true } : {}) }]);
  const r = res[0];
  if (r?.recurred) {
    // Повтор: «Задача выполнена», ниже «Следующий раз: …» (и сброшенные дела внутри, когда сервер их пришлёт — reset).
    // «Отменить» возвращает срок, «Сейчас», напоминание и снова отмечает сброшенное. Счётчик раз сервер не отдаёт.
    const back: Record<string, unknown> = { due_date: t.due_date, focus_on: t.focus_on ?? null };
    if (t.remind_at) back.remind_at = t.remind_at;
    const reset: string[] = r.reset || [];
    const sub = [T.next(r.recurred, S.today), ...(reset.length ? [T.reset(reset.length, r.subtasks ?? reset.length)] : [])];
    toast(T.done(), () => ops([{ op: 'task.set', id: t.id, set: back }, ...reset.map((id) => ({ op: 'task.set', id, set: { status: 'done' } }))]).catch(() => {}), false, sub);
  } else {
    toast(T.done(), () => ops([{ op: 'task.reopen', id: t.id }]).catch(() => {}));
  }
}

// ── Личные настройки нового веба: crm.users.settings.todo ────────────────

export function todoSettings(): TodoSettings {
  return (S.me?.settings?.todo as TodoSettings) || {};
}
export async function saveTodo(patch: Partial<TodoSettings>) {
  const next = { ...todoSettings(), ...patch };
  if (S.me) S.me.settings = { ...(S.me.settings || {}), todo: next };
  emit();
  await op1({ op: 'user.settings', settings: { todo: next } });
}
export function nowOrder(): Id[] {
  return (S.me?.settings?.now_order as Id[]) || [];
}
export async function saveNowOrder(ids: Id[]) {
  if (S.me) S.me.settings = { ...(S.me.settings || {}), now_order: ids };
  emit();
  await op1({ op: 'user.settings', settings: { now_order: ids } });
}
