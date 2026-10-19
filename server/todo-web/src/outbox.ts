// Очередь правок (19.10.2026, как commandsQueue у Todoist): правка сначала ложится сюда — с op_id, переживает
// перезагрузку и закрытый телефон, — потом уходит на сервер, когда есть сеть. op_id не меняется при повторе, поэтому
// пачка, ответ на которую потерялся, не задвоится: сервер отдаст прежние ответы из crm.ops_seen. Новые дела — со своим
// uuid клиента, таблица «временный → настоящий» не нужна. Без браузера: хранилище — IndexedDB (idb.ts), в тестах —
// память (memStore).

import type { Effect, Op } from './local.ts';

export interface Queued {
  n?: number; // номер в хранилище — порядок отправки
  user: string;
  op_id: string;
  op: Op;
  at: string;
  tries: number; // сколько раз сервер падал (5xx) на пачке, где эта правка первая
  fx?: Effect[]; // след на экране — повторяется поверх синка, пока правка не ушла
}

export interface QueueStore {
  all(user: string): Promise<Queued[]>;
  add(items: Queued[]): Promise<void>; // ставит n
  drop(ns: number[]): Promise<void>;
  put(q: Queued): Promise<void>; // tries
}

export interface Result {
  op_id?: string;
  ok: boolean;
  error?: string;
  [k: string]: unknown;
}
export type Send = (ops: Op[]) => Promise<{ results: Result[] }>;

export interface Flushed {
  results: Map<string, { q: Queued; r: Result }>; // ответ сервера по op_id — и «да», и «нет»
  rejected: { q: Queued; r: Result }[];
  offline: boolean; // до сервера не достучались — правки ждут
  auth: boolean; // нужен вход — правки ждут
  left: number; // сколько ещё в очереди
}

export const CHUNK = 100;
export const MAX_TRIES = 5;

/** Нет связи (status 0) или посредник не достучался до дома (502–504) — правка не отклонена, а ждёт. */
export const unreachable = (status: number): boolean => status === 0 || status === 502 || status === 503 || status === 504;

/** Отправить всё, что ждёт, по порядку пачками. Отказ сервера по правке — в rejected (и из очереди вон: сервер — правда);
 *  отказ всей пачке (4xx) — по одной, чтобы плохая не держала остальные; сервер падает (5xx) — ждать, а пять раз на
 *  одной и той же — отказ ей. */
export async function flushOnce(store: QueueStore, user: string, send: Send): Promise<Flushed> {
  const out: Flushed = { results: new Map(), rejected: [], offline: false, auth: false, left: 0 };
  let items = await store.all(user);
  let size = CHUNK;
  const reject = async (q: Queued, error: string) => {
    const r = { op_id: q.op_id, ok: false, error };
    out.results.set(q.op_id, { q, r });
    out.rejected.push({ q, r });
    await store.drop([q.n!]);
    items = items.filter((x) => x !== q);
  };
  while (items.length) {
    const chunk = items.slice(0, size);
    let res: { results: Result[] };
    try {
      res = await send(chunk.map((q) => ({ ...q.op, op_id: q.op_id })));
    } catch (e) {
      const st = Number((e as { status?: number })?.status ?? 0);
      const msg = e instanceof Error ? e.message : String(e);
      if (unreachable(st)) {
        out.offline = true;
        break;
      }
      if (st === 401) {
        out.auth = true;
        break;
      }
      if (st >= 500) {
        const head = chunk[0];
        head.tries++;
        if (head.tries >= MAX_TRIES) {
          await reject(head, `сервер падает на этой правке (${msg})`);
          continue;
        }
        await store.put(head);
        break;
      }
      if (chunk.length > 1) {
        size = 1;
        continue;
      }
      await reject(chunk[0], msg);
      size = CHUNK;
      continue;
    }
    const got = new Map<string, Result>();
    for (const r of res.results || []) if (r && r.op_id) got.set(String(r.op_id), r);
    const done = chunk.filter((q) => got.has(q.op_id));
    for (const q of done) {
      const r = got.get(q.op_id)!;
      out.results.set(q.op_id, { q, r });
      if (!r.ok) out.rejected.push({ q, r });
    }
    if (done.length) await store.drop(done.map((q) => q.n!));
    if (!done.length) break; // сервер не ответил ни про одну — не крутиться, попробуем позже
    items = items.filter((q) => !got.has(q.op_id));
    size = CHUNK;
  }
  out.left = (await store.all(user)).length;
  return out;
}

/** Очередь в памяти — для тестов и на случай, когда IndexedDB нет (приватное окно старого браузера). */
export function memStore(): QueueStore & { rows: Queued[] } {
  const rows: Queued[] = [];
  let n = 0;
  return {
    rows,
    async all(user) {
      return rows.filter((q) => q.user === user).map((q) => ({ ...q }));
    },
    async add(items) {
      for (const q of items) {
        q.n = ++n;
        rows.push({ ...q });
      }
    },
    async drop(ns) {
      for (const k of ns) {
        const i = rows.findIndex((q) => q.n === k);
        if (i >= 0) rows.splice(i, 1);
      }
    },
    async put(q) {
      const i = rows.findIndex((x) => x.n === q.n);
      if (i >= 0) rows[i] = { ...q };
    },
  };
}
