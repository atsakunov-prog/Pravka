// Память веба: всё, что отдаёт /api/sync, по номеру изменения, и её копия в браузере (IndexedDB) — открывается и без
// сети. Правки — операциями /api/ops (как у телефона и Claude): сначала на экран и в очередь (экран отзывается сразу,
// как у Todoist), потом на сервер, когда есть сеть; отказ сервера — откат синком и тост.

import { useSyncExternalStore } from 'react';
import { ApiError, api, inApp, uuid } from './api.ts';
import { cacheLoad, cacheSave, type Mem, type Meta } from './cache.ts';
import { cacheDrop, idbOpen, idbQueue } from './idb.ts';
import { applyEffects, effectsOf, mskToday, type Maps, type Op } from './local.ts';
import { flushOnce, memStore, unreachable, type Flushed, type Queued, type QueueStore, type Result } from './outbox.ts';
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
  queue: 0, // правок ждут отправки (шапка: «Нет сети · 3 правки ждут»)
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

// ── База в браузере и очередь правок (19.10.2026, как у Todoist) ─────────
// Копия синка лежит в IndexedDB (cache.ts): открыл — экран сразу из неё, потом догон «после seq». Правка сначала
// ложится в очередь (outbox.ts) и сразу меняет экран (local.ts), потом уходит, когда есть сеть: при открытии, при
// возвращении на вкладку, по событию online, после каждой правки и раз в минуту, пока страница открыта.

let db: IDBDatabase | null = null;
let box: QueueStore = memStore();
let pending: Queued[] = []; // правки этой вкладки, что ещё не ушли: их след — поверх каждого синка
const awaited = new Set<string>(); // op_id, ответа на которые ждёт вызвавший ops() — отказ он покажет сам
const got = new Map<string, Result>();
const maps = (): Maps => ({ tasks: S.tasks, sections: S.sections, projects: S.projects, comments: S.comments });
const reapply = () => {
  for (const q of pending) if (q.fx) applyEffects(maps(), q.fx);
};

const dirty = new Set<string>();
let saveT: ReturnType<typeof setTimeout> | null = null;
/** Сохранить копию через секунду (поменялись names); saveNow — сразу (вкладку прячут, телефон может её закрыть). */
export function saveSoon(...names: string[]) {
  names.forEach((n) => dirty.add(n));
  if (!saveT) saveT = setTimeout(saveNow, 1000);
}
export function saveNow() {
  if (saveT) clearTimeout(saveT);
  saveT = null;
  if (!db || !S.me) return;
  const names = [...dirty];
  dirty.clear();
  cacheSave(db, S as unknown as Mem, names).catch((e) => log('error', 'копия в браузере не легла: ' + String(e)));
}
const ALL = ['tasks', 'projects', 'deals', 'people', 'orgs', 'sections', 'sugs', 'comments', 'users'];

/** Открытие страницы: копия из браузера — на экран сразу, потом вход и догон. Ответ — текст для экрана входа или ''. */
export async function boot(): Promise<string> {
  db = await idbOpen();
  if (db) box = idbQueue(db);
  let cached: Meta | null = null;
  if (db) {
    try {
      cached = await cacheLoad(db, S as unknown as Mem);
    } catch (e) {
      log('error', 'копия в браузере не прочиталась: ' + String(e));
    }
  }
  if (cached) {
    pending = await box.all(cached.user).catch(() => []);
    reapply();
    if (S.today < mskToday()) S.today = mskToday(); // копия вчерашняя, а сервер без сети не скажет
    S.queue = pending.length;
    S.offline = !navigator.onLine;
    S.ready = true;
    log('cache', `копия от ${cached.at}, seq ${cached.seq}, в очереди ${pending.length}`);
    emit();
  }
  try {
    const me = await api<Me>('/api/me');
    const changed = !!cached && me.user !== cached.user;
    if (changed) {
      for (const m of [S.tasks, S.projects, S.deals, S.people, S.orgs, S.sections, S.sugs, S.comments, S.users, S.krugProj]) m.clear();
      pending = await box.all(me.user).catch(() => []);
    }
    S.me = me;
    await sync(!cached || changed);
    refresh();
    return '';
  } catch (e) {
    const st = e instanceof ApiError ? e.status : -1;
    if (cached && unreachable(st)) {
      S.offline = true; // без сети: работаем с копией, правки — в очередь
      emit();
      return '';
    }
    if (st === 401) {
      // Вход кончился — копию из браузера долой (очередь остаётся: уйдёт, когда этот человек войдёт снова).
      if (db && cached) await cacheDrop(db, cached.user).catch(() => {});
      if (inApp()) return 'Правка не подключена к Делам: Настройки → Дела → подключить по QR с компа.';
      // Вход — паролем Ковчега на главной Дел (22.10.2026); после входа она вернёт сюда же.
      location.replace('/?next=t');
      return 'Открываю вход…';
    }
    if (cached) {
      fail(e);
      return '';
    }
    return 'Не открылось: ' + (e instanceof Error ? e.message : String(e));
  }
}

/** Отправить очередь и догнать сервер — при возвращении на вкладку, по online и раз в минуту. */
export function refresh(): Promise<void> {
  return pump().then(() => syncSoon()).catch(() => {});
}

// ── Синк ─────────────────────────────────────────────────────────────────

export async function sync(full = false): Promise<void> {
  const d = await api<any>('/api/sync?since=' + (full ? 0 : S.seq));
  // Сервер позади копии (база поднята из резервной, тестовая копия пересоздана) — «после seq» ничего не придёт, а копия
  // так и жила бы старой: берём всё заново.
  if (!full && !d.full && typeof d.seq === 'number' && d.seq < S.seq) return sync(true);
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
  reapply(); // правки, что ещё не ушли, — поверх того, что прислал сервер
  const names: Record<string, unknown[]> = { tasks: d.tasks, projects: d.projects, deals: d.deals, people: d.people, orgs: d.orgs,
    sections: d.sections, sugs: d.suggestions, comments: d.comments, users: d.users };
  saveSoon(...(d.full ? ALL : Object.keys(names).filter((k) => names[k]?.length)));
  emit();
}

let syncing: Promise<void> | null = null;
export function syncSoon(full = false): Promise<void> {
  if (!syncing) {
    syncing = sync(full)
      .catch((e) => {
        if (e instanceof ApiError && unreachable(e.status)) {
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

export type { Op };

/** Как назвать правку в тосте отказа: «выполнение «Купить хлеб»». */
function opWords(o: Op): string {
  const kind: Record<string, string> = { 'task.create': 'новая задача', 'task.set': 'правка', 'task.done': 'выполнение', 'task.reopen': 'возврат',
    'task.cancel': 'удаление', 'comment.add': 'комментарий', 'section.create': 'новый раздел', 'section.set': 'раздел',
    'project.create': 'новый проект', 'project.set': 'проект', 'user.settings': 'настройки', 'debug.add': 'записка' };
  const title = (o.id && S.tasks.get(String(o.id))?.title) || o.task?.title || o.data?.name || (o.id && S.sections.get(String(o.id))?.name) || '';
  return (kind[o.op] || o.op) + (title ? ` «${String(title).slice(0, 60)}»` : '');
}

/** Правка из очереди не встала, а того, кто её делал, уже нет (ушла позже, без сети): не терять молча — тост, след в
 *  записке «Что не так?» (владельцу в режиме отладки) и в последних действиях. Сервер — правда: потом полный синк. */
function lateReject(q: Queued, r: Result) {
  const what = opWords(q.op);
  const why = r.error || 'сервер не принял';
  toast(`Не встало: ${what} — ${why}`, undefined, true);
  log('error', `очередь: не встало ${q.op.op} — ${why}`, { op: q.op, at: q.at, tries: q.tries });
  if (S.debug?.on && q.op.op !== 'debug.add') {
    ops([{ op: 'debug.add', note: { id: uuid(), text: `Правка из очереди не встала: ${what} — ${why}`, source: 'web', version: 'todo-web',
      screen: location.hash || '#/now', snapshot: { v: 1, app: 'todo-web', kind: 'outbox', route: location.hash || '#/now', op: q.op, error: why,
        queued_at: q.at, tries: q.tries }, actions: trail.slice(-30) } }], { quiet: true }).catch(() => {});
  }
}

/** Один проход очереди (по одному за раз — и между вкладками, если браузер умеет locks). */
async function flushStep(): Promise<void> {
  const user = S.me?.user;
  if (!user) return;
  const run = () => flushOnce(box, user, (items) => api('/api/ops', { ops: items }));
  const locks = (navigator as Navigator & { locks?: LockManager }).locks;
  const f: Flushed = locks ? await locks.request('dela-outbox', run) : await run();
  for (const [id, { q, r }] of f.results) {
    if (awaited.has(id)) got.set(id, r);
    if (!r.ok) continue;
    if (r.task) S.tasks.set((r.task as Task).id, r.task as Task);
    if (r.comment) S.comments.set((r.comment as Comment).id, r.comment as Comment);
    if (r.row && q.op.op.startsWith('section.')) S.sections.set((r.row as Section).id, r.row as Section);
    if (r.row && q.op.op.startsWith('project.')) S.projects.set((r.row as Project).id, r.row as Project);
  }
  if (f.results.size) {
    pending = pending.filter((q) => !f.results.has(q.op_id));
    reapply();
    saveSoon('tasks', 'sections', 'projects', 'comments');
  }
  S.queue = f.left;
  if (f.offline) S.offline = true;
  else if (f.results.size) S.offline = false;
  if (f.auth) log('error', 'очередь ждёт входа (401)');
  for (const { q, r } of f.rejected) if (!awaited.has(q.op_id)) lateReject(q, r);
  emit();
  if (f.rejected.length) await syncSoon(true).catch(() => {});
  else if (f.results.size) syncSoon().catch(() => {});
}

let chain: Promise<void> = Promise.resolve();
/** Отправить очередь: проходы встают друг за другом, правка, положенная до вызова, уйдёт в этом или прежнем проходе. */
export function pump(): Promise<void> {
  chain = chain.then(flushStep, flushStep).catch((e) => log('error', 'очередь: ' + String(e)));
  return chain;
}

/** Правки: сразу на экран и в очередь, потом на сервер. Есть сеть — ответ сервера как раньше (отказ — исключение и тост);
 *  нет — ответ «ждёт» (queued) с тем, что знаем сами (recurred — новый срок повтора), и правка уйдёт сама.
 *  online — только при сети, мимо очереди (надиктовка: её разбирает сервер, без сети ей нечего делать). */
export async function ops(items: Op[], { quiet = false, online = false } = {}): Promise<any[]> {
  log('ops', items.map((o) => o.op + (o.id ? ' ' + String(o.id).slice(0, 8) : '')).join(', '), items.slice(0, 5));
  if (online) return direct(items, quiet);
  const now = new Date().toISOString();
  const ctx = { user: S.me?.user || '', today: S.today, now };
  const qs: Queued[] = [];
  const extra: (Record<string, unknown> | undefined)[] = [];
  for (const o of items) {
    const e = effectsOf(o, maps(), ctx);
    applyEffects(maps(), e.fx);
    qs.push({ user: ctx.user, op_id: uuid(), op: o, at: now, tries: 0, fx: e.fx });
    extra.push(e.extra);
  }
  pending.push(...qs);
  S.queue += qs.length;
  for (const q of qs) awaited.add(q.op_id);
  emit();
  saveSoon('tasks', 'sections', 'projects', 'comments');
  try {
    await box.add(qs);
  } catch (e) {
    // IndexedDB отказала (место кончилось): правка всё равно уйдёт — из памяти этой вкладки
    log('error', 'очередь в браузере не легла: ' + String(e));
    box = memStore();
    await box.add(qs);
  }
  await pump();
  const res = qs.map((q, i) => got.get(q.op_id) ?? { op_id: q.op_id, ok: true, queued: true, ...extra[i] });
  for (const q of qs) {
    awaited.delete(q.op_id);
    got.delete(q.op_id);
  }
  const bad = res.filter((r) => !r.ok);
  if (bad.length) {
    const e = new Error(bad[0].error + (bad.length > 1 ? ` (и ещё ${bad.length - 1})` : ''));
    if (!quiet) fail(e);
    throw e;
  }
  return res;
}

/** Мимо очереди — только при сети (надиктовка). */
async function direct(items: Op[], quiet: boolean): Promise<any[]> {
  let d: any;
  try {
    d = await api('/api/ops', { ops: items.map((o) => ({ op_id: uuid(), ...o })) });
  } catch (e) {
    const err = e instanceof ApiError && unreachable(e.status) ? new ApiError('нет сети — это работает только с сетью', 0) : e;
    if (!quiet) fail(err);
    throw err;
  }
  for (const r of d.results) if (r.ok && r.task) S.tasks.set(r.task.id, r.task);
  emit();
  syncSoon().catch(() => {});
  const bad = d.results.filter((r: any) => !r.ok);
  if (bad.length) {
    const e = new Error(bad[0].error + (bad.length > 1 ? ` (и ещё ${bad.length - 1})` : ''));
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

/** Дела, у которых кружок уже заливается, а строка ещё на месте (см. completeSoon). */
export const ticking = new Set<Id>();

/** Выполнение, как у Todoist (замер 19.10): кружок сразу заливается и рисует галочку, строка уходит через 290 мс — без
 *  сворачивания, просто исчезает; дольше — кажется, что тормозит. Один путь для кружка, свайпа и клавиши E. */
export function completeSoon(t: Task, forever = false) {
  if (ticking.has(t.id)) return;
  ticking.add(t.id);
  emit();
  setTimeout(() => {
    ticking.delete(t.id);
    completeTask(t, forever).catch(() => {});
  }, 290);
}

export async function completeTask(t: Task, forever = false) {
  const recurring = !!t.recur && !forever;
  // Обычному делу — тост сразу, вместе с уходом строки (у Todoist ~0,3 с от нажатия), не дожидаясь ответа сервера.
  if (!recurring) toast(T.done(), () => ops([{ op: 'task.reopen', id: t.id }]).catch(() => {}));
  const res = await ops([{ op: 'task.done', id: t.id, ...(forever ? { forever: true } : {}) }]);
  const r = res[0];
  if (!recurring) return;
  if (r?.recurred) {
    // Повтор: «Задача выполнена», ниже «Следующий раз: …» и, если повтор снова открыл сделанное внутри (reset, как у
    // Todoist), — «Выполнено и снова добавлено N подзадач». «Отменить» возвращает срок, «Сейчас», напоминание и счётчик
    // раз (книга выполнений снимет этот раз), а сброшенное внутри — снова сделанным (restore: выполнение то же, без дубля).
    const back: Record<string, unknown> = { due_date: t.due_date, focus_on: t.focus_on ?? null, done_count: t.done_count ?? 0 };
    if (t.remind_at) back.remind_at = t.remind_at;
    const reset: string[] = r.reset || [];
    for (const id of reset) {
      const x = S.tasks.get(id);
      if (x) S.tasks.set(id, { ...x, status: 'open', completed_at: null }); // экран — сразу, синк подтвердит
    }
    if (reset.length) emit();
    const sub = [T.next(r.recurred, S.today), ...(reset.length ? [T.reset(reset.length, r.subtasks ?? reset.length)] : [])];
    toast(T.done(), () => ops([{ op: 'task.set', id: t.id, set: back },
      ...reset.map((id) => ({ op: 'task.set', id, set: { status: 'done' }, restore: true }))]).catch(() => {}), false, sub);
  } else {
    // повтор кончился (until позади) — дело закрыто как обычное
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
