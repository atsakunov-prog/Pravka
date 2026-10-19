// IndexedDB страницы /t (19.10.2026): своя база в браузере, как у Todoist. Два хранилища: outbox — очередь правок по
// порядку (outbox.ts), cache — копия всего, что отдал /api/sync, по ключам «человек:что» (cache.ts). Нет IndexedDB
// (старый браузер, запрет хранилища) — idbOpen отвечает null, и страница живёт как раньше: только память и сеть.

import type { Queued, QueueStore } from './outbox.ts';

const NAME = 'dela-t';
let dbp: Promise<IDBDatabase | null> | null = null;

export function idbOpen(): Promise<IDBDatabase | null> {
  if (!dbp) {
    dbp = new Promise((ok) => {
      try {
        if (typeof indexedDB === 'undefined') return ok(null);
        const r = indexedDB.open(NAME, 1);
        r.onupgradeneeded = () => {
          const db = r.result;
          if (!db.objectStoreNames.contains('outbox')) db.createObjectStore('outbox', { keyPath: 'n', autoIncrement: true });
          if (!db.objectStoreNames.contains('cache')) db.createObjectStore('cache');
        };
        r.onsuccess = () => ok(r.result);
        r.onerror = () => ok(null);
        r.onblocked = () => ok(null);
      } catch {
        ok(null);
      }
    });
  }
  return dbp;
}

const done = (t: IDBTransaction) =>
  new Promise<void>((ok, no) => {
    t.oncomplete = () => ok();
    t.onerror = () => no(t.error);
    t.onabort = () => no(t.error || new Error('IndexedDB: отмена'));
  });
const req = <T>(r: IDBRequest<T>) =>
  new Promise<T>((ok, no) => {
    r.onsuccess = () => ok(r.result);
    r.onerror = () => no(r.error);
  });

/** Очередь правок в IndexedDB: каждая правка — своя запись, порядок — по номеру; вкладки не затирают друг друга. */
export function idbQueue(db: IDBDatabase): QueueStore {
  return {
    async all(user) {
      const rows = await req(db.transaction('outbox').objectStore('outbox').getAll() as IDBRequest<Queued[]>);
      return rows.filter((q) => q.user === user);
    },
    async add(items) {
      const t = db.transaction('outbox', 'readwrite');
      const s = t.objectStore('outbox');
      for (const q of items) {
        const { n: _, ...row } = q;
        void _;
        s.add(row).onsuccess = (e) => {
          q.n = (e.target as IDBRequest<number>).result;
        };
      }
      await done(t);
    },
    async drop(ns) {
      const t = db.transaction('outbox', 'readwrite');
      for (const n of ns) t.objectStore('outbox').delete(n);
      await done(t);
    },
    async put(q) {
      const t = db.transaction('outbox', 'readwrite');
      t.objectStore('outbox').put(q);
      await done(t);
    },
  };
}

export async function cacheGet<T = unknown>(db: IDBDatabase, key: string): Promise<T | undefined> {
  return req(db.transaction('cache').objectStore('cache').get(key) as IDBRequest<T | undefined>);
}

export async function cachePut(db: IDBDatabase, rows: [string, unknown][]): Promise<void> {
  const t = db.transaction('cache', 'readwrite');
  const s = t.objectStore('cache');
  for (const [k, v] of rows) s.put(v, k);
  await done(t);
}

/** Стереть копию человека (сменился вход). Очередь не трогаем: его правки уйдут, когда он войдёт снова. */
export async function cacheDrop(db: IDBDatabase, user: string): Promise<void> {
  const t = db.transaction('cache', 'readwrite');
  t.objectStore('cache').delete(IDBKeyRange.bound(user + ':', user + ':￿'));
  await done(t);
}
