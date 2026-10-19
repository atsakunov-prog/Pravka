// Копия базы в браузере (19.10.2026, как IndexedDB у Todoist): всё, что отдал /api/sync, и номер последнего синка. При
// открытии экран рисуется из неё сразу — и без сети тоже, — потом синк догоняет «что изменилось после seq». Ключи —
// «человек:что» (tasks, projects, …, meta) и «last» — кто входил последним: без сети /api/me не спросишь.
// Сохраняется только то, что поменялось (dirty), через секунду после правки или синка и сразу, когда вкладку прячут.

import { cacheGet, cachePut } from './idb.ts';

export const COLLS = ['tasks', 'projects', 'deals', 'people', 'orgs', 'sections', 'sugs', 'comments', 'users'] as const;
export type CollName = (typeof COLLS)[number];
export const CACHE_V = 1; // поменялся вид копии — старая не читается, страница берёт полный синк

export interface Meta {
  v: number;
  user: string;
  seq: number;
  today: string;
  at: string; // когда сохранено
  me: unknown;
  features: string[];
  labels: unknown[];
  dirs: unknown[];
  krug: [string, unknown][];
  debug: unknown;
}

// Память страницы (S в store.ts) — здесь только нужные поля, без импорта store.ts.
export interface Mem {
  me: { user: string } | null;
  seq: number;
  today: string;
  features: string[];
  labels: unknown[];
  dirs: unknown[];
  krugProj: Map<string, unknown>;
  debug: unknown;
  [k: string]: unknown;
}

/** Что записать: поменявшиеся собрания (как они на экране — с правками, что ещё не ушли: очередь при загрузке наложит их
 *  снова, это безвредно) и всегда meta и last. */
export function rowsToSave(S: Mem, names: Iterable<string>): [string, unknown][] {
  const u = S.me?.user;
  if (!u) return [];
  const out: [string, unknown][] = [];
  for (const n of new Set(names)) {
    if (!(COLLS as readonly string[]).includes(n)) continue;
    out.push([`${u}:${n}`, [...(S[n] as Map<string, unknown>).values()]]);
  }
  const meta: Meta = { v: CACHE_V, user: u, seq: S.seq, today: S.today, at: new Date().toISOString(), me: S.me, features: S.features,
    labels: S.labels, dirs: S.dirs, krug: [...S.krugProj.entries()], debug: S.debug };
  out.push([`${u}:meta`, meta], ['last', u]);
  return out;
}

export async function cacheSave(db: IDBDatabase, S: Mem, names: Iterable<string>): Promise<void> {
  const rows = rowsToSave(S, names);
  if (rows.length) await cachePut(db, rows);
}

/** Копия последнего входа — в память страницы. Нет копии или она другого вида — false (нужен полный синк). */
export async function cacheLoad(db: IDBDatabase, S: Mem): Promise<Meta | null> {
  const u = await cacheGet<string>(db, 'last');
  if (!u) return null;
  const meta = await cacheGet<Meta>(db, `${u}:meta`);
  if (!meta || meta.v !== CACHE_V || meta.user !== u) return null;
  for (const n of COLLS) {
    const rows = (await cacheGet<{ id: string }[]>(db, `${u}:${n}`)) || [];
    const m = S[n] as Map<string, unknown>;
    m.clear();
    for (const r of rows) m.set(r.id, r);
  }
  Object.assign(S, { me: meta.me, seq: meta.seq, today: meta.today, features: meta.features, labels: meta.labels, dirs: meta.dirs,
    debug: meta.debug });
  S.krugProj.clear();
  for (const [k, v] of meta.krug || []) S.krugProj.set(k, v);
  return meta;
}
