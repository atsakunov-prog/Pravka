// Каркас /t без сети (19.10.2026, как Workbox у Todoist): страница, todo.js и todo.css — в кэше браузера, поэтому Дела
// открываются и без интернета (телефон в метро, в самолёте), а данные страница берёт из своей базы в IndexedDB
// (todo-web/src/cache.ts). Отдаётся адресом /t?sw=1 — у сайта-посредника на Aeza закрытый список путей, /t в нём уже
// есть; свой файл /static/todo-sw.js был бы ещё одной правкой списка. Работает только на https (или 127.0.0.1).
//
// Всё — «сначала сеть»: есть связь — свежая сборка и она же в кэш (новая сборка подменяет старую сама, без номеров
// версий); нет связи или сервер молчит дольше TIMEOUT — из кэша. API (/api/…) воркер не трогает: без сети страница сама
// показывает своё из базы и копит правки в очереди.
const CACHE = 'dela-t-1';
const SHELL = ['/t', '/static/todo.js', '/static/todo.css', '/static/icon.svg'];
const TIMEOUT = 4000; // мс: сеть «есть», но ответа нет (лифт, роутер перезагружается) — не ждать дольше

self.addEventListener('install', (e) => {
  // Каркас — сразу при установке, по одному: не легла иконка — страница всё равно откроется.
  e.waitUntil(caches.open(CACHE).then((c) => Promise.all(SHELL.map((u) => c.add(u).catch(() => {})))).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k.startsWith('dela-t-') && k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const u = new URL(req.url);
  if (u.origin !== self.location.origin) return;
  // Страница /t (с любым #маршрутом — его браузер не шлёт) и три файла сборки; остальное — мимо воркера.
  const key = u.pathname === '/t' && req.mode === 'navigate' ? '/t' : SHELL.includes(u.pathname) && u.pathname !== '/t' ? u.pathname : null;
  if (!key) return;
  e.respondWith(fresh(req, key));
});

async function fresh(req, key) {
  const cache = await caches.open(CACHE);
  try {
    const r = await Promise.race([fetch(req), new Promise((_, no) => setTimeout(() => no(new Error('timeout')), TIMEOUT))]);
    if (r.ok) await cache.put(key, r.clone());
    return r;
  } catch (err) {
    const hit = await cache.match(key);
    if (hit) return hit;
    throw err;
  }
}
