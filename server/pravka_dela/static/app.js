// Дела — веб. Одна страница поверх API службы, без библиотек.
// Все видимые дела, проекты, люди и сделки держатся в памяти (синк «всё после seq»),
// поэтому виды, группировка и переключения считаются тут же, без запросов.
// Меняет всё только операциями /api/ops — тем же путём, что телефон и Claude.
'use strict';

const $app = document.getElementById('app');
const LS = {
  get(k, d) { try { const v = localStorage.getItem('dela.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem('dela.' + k, JSON.stringify(v)); } catch (e) { /* без памяти браузера — и так работает */ } },
};
const S = {
  me: null, today: null, seq: 0,
  tasks: new Map(), projects: new Map(), people: new Map(), deals: new Map(), sugs: new Map(), labels: [],
  orgs: new Map(), pays: new Map(), dealId: null, // CRM: организации, оплаты, открытая сделка
  crmList: LS.get('crmList', false), crmMine: false, crmStale: false, clientQ: '', clientArch: false, clientTab: 'tasks',
  sphere: LS.get('sphere', ''), groups: LS.get('groups', {}), favs: LS.get('favs', []), closed: LS.get('closed', {}),
  showDone: false, upNoDate: false, upMineOnly: false, dealFilter: null,
  sel: new Set(), order: [], lastPick: null, cardId: null, draft: null, sideOpen: false,
  parse: null, quickDraft: null, // идёт разбор Claude; текст, вернувшийся после неудачи
  askTask: null, askText: '', asking: null, // микрофон у дела: какое открыто, текст, идёт правка
};
const BALL = { mine: 'моё', waiting: 'жду', agenda: 'повестка' };
const KIND = { client: 'Клиенты', internal: 'Внутреннее', personal: 'Личное' };
const STAGE = { lead: 'лид', proposal: 'КП', mandate: 'мандат', active: 'в работе', closing: 'закрытие', archive: 'архив' };
const WD = ['понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота', 'воскресенье'];
const WD_SHORT = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'];
const MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];

// ── Сеть ────────────────────────────────────────────────────────────────
async function api(path, body) {
  const opt = { credentials: 'same-origin', headers: { 'X-Dela': '1' } };
  if (body !== undefined) {
    opt.method = 'POST';
    opt.headers['Content-Type'] = 'application/json';
    opt.body = JSON.stringify(body);
  }
  const r = await fetch(path, opt);
  let d = {};
  try { d = await r.json(); } catch (e) { /* пусто */ }
  if (r.status === 401) { S.me = null; renderLogin(); throw new Error('нужен вход'); }
  // 404 без нашего JSON — путь не пропустил сайт-посредник (закрытый список путей), а не сама служба.
  if (!r.ok || d.ok === false) throw new Error(d.error || (r.status === 404 ? 'сервер не знает этот адрес (404) — нужен путь в настройках сайта' : 'ошибка ' + r.status));
  return d;
}
const uuid = () => (crypto.randomUUID ? crypto.randomUUID()
  : 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => (Math.random() * 16 | 0).toString(16)));

/** Пачка операций; ответные дела сразу ложатся в память, остальное доедет синком. */
async function ops(items) {
  const d = await api('/api/ops', { ops: items.map((o) => ({ op_id: uuid(), ...o })) });
  for (const r of d.results) if (r.ok && r.task) S.tasks.set(r.task.id, r.task);
  crmDirty(); // виды CRM считает сервер: после правки — заново (старое видно, пока грузится)
  const bad = d.results.filter((r) => !r.ok);
  await sync();
  if (bad.length) throw new Error(bad[0].error + (bad.length > 1 ? ` (и ещё ${bad.length - 1})` : ''));
  return d.results;
}
const op1 = async (item) => (await ops([item]))[0];

async function sync(full) {
  const d = await api('/api/sync?since=' + (full ? 0 : S.seq));
  if (d.full) { S.tasks.clear(); S.projects.clear(); S.people.clear(); S.deals.clear(); S.sugs.clear(); S.orgs.clear(); S.pays.clear(); }
  for (const t of d.tasks) S.tasks.set(t.id, t);
  for (const p of d.projects) S.projects.set(p.id, p);
  for (const p of d.people) S.people.set(p.id, p);
  for (const x of d.deals) S.deals.set(x.id, x);
  for (const s of d.suggestions) S.sugs.set(s.id, s);
  for (const o of d.orgs || []) S.orgs.set(o.id, o);
  for (const x of d.payments || []) S.pays.set(x.id, x);
  S.labels = d.labels.map((l) => l.name);
  S.seq = d.seq;
  S.today = d.today;
}

// ── Даты (сутки — московские, их отдаёт сервер) ─────────────────────────
const D = {
  parse: (s) => { const [y, m, d] = s.split('-').map(Number); return Date.UTC(y, m - 1, d); },
  iso: (ms) => new Date(ms).toISOString().slice(0, 10),
  add: (s, n) => D.iso(D.parse(s) + n * 86400e3),
  diff: (a, b) => Math.round((D.parse(a) - D.parse(b)) / 86400e3),
  wd: (s) => (new Date(D.parse(s)).getUTCDay() + 6) % 7,
  next: (wd) => { let n = (wd - D.wd(S.today) + 7) % 7; if (n === 0) n = 7; return D.add(S.today, n); },
  ddmm: (s) => s.slice(8, 10) + '.' + s.slice(5, 7) + (s.slice(0, 4) !== S.today.slice(0, 4) ? '.' + s.slice(2, 4) : ''),
  long: (s) => `${WD[D.wd(s)][0].toUpperCase() + WD[D.wd(s)].slice(1)}, ${+s.slice(8, 10)} ${MONTHS[+s.slice(5, 7) - 1]}`,
};
function dueLabel(t) {
  if (!t.due_date) return null;
  const n = D.diff(t.due_date, S.today);
  const time = t.due_time ? ' ' + t.due_time.slice(0, 5) : '';
  if (n < 0 && t.status === 'open') return { text: `${D.ddmm(t.due_date)} · ${-n} дн. назад`, cls: 'late' };
  if (n === 0) return { text: 'сегодня' + time, cls: 'today' };
  if (n === 1) return { text: 'завтра' + time, cls: '' };
  if (n > 1 && n < 7) return { text: `${WD_SHORT[D.wd(t.due_date)]} ${D.ddmm(t.due_date)}` + time, cls: '' };
  return { text: D.ddmm(t.due_date) + time, cls: '' };
}

// ── Правила (как в store.py) ─────────────────────────────────────────────
const isOpen = (t) => t.status === 'open';
const isMine = (t) => t.owner_id === S.me.user;
const project = (id) => (id ? S.projects.get(id) : null);
const person = (id) => (id ? S.people.get(id) : null);
const sphereOf = (t) => (project(t.project_id) ? project(t.project_id).sphere : 'inbox');
const inSphere = (t) => !S.sphere || sphereOf(t) === S.sphere || sphereOf(t) === 'inbox';
const isLate = (t) => isOpen(t) && t.due_date && t.due_date < S.today;
const isNow = (t) => t.focus_on === S.today;
const personName = (p) => (p ? p.short || p.name : '');
function sortTasks(a, b) {
  return (a.due_date || '9999').localeCompare(b.due_date || '9999')
    || (a.due_time || '99').localeCompare(b.due_time || '99')
    || a.num - b.num;
}
const all = () => [...S.tasks.values()];
const openMine = () => all().filter((t) => isOpen(t) && isMine(t) && inSphere(t));

const VIEWS = {
  morning: {
    title: 'Утро', icon: 'sun', color: '#f5b544',
    sections() {
      const m = openMine();
      const from = new Date(Date.now() - 3 * 86400e3).toISOString();
      // [заголовок, дела, значок, цвет значка]
      return [
        ['Сейчас', m.filter(isNow), 'bolt', 'var(--now)'],
        ['На сегодня и просроченное', m.filter((t) => t.ball === 'mine' && t.due_date && t.due_date <= S.today && !isNow(t)), 'date', '#f2706a'],
        ['Пора напомнить', m.filter((t) => t.ball === 'waiting' && ((t.nudge_on && t.nudge_on <= S.today) || (t.due_date && t.due_date <= S.today))), 'hourglass', 'var(--waiting)'],
        ['Поставили другие', m.filter((t) => t.created_by !== t.owner_id && t.created_at > from), 'person', '#a78bfa'],
      ];
    },
    count: () => openMine().filter((t) => (t.ball === 'mine' && t.due_date && t.due_date <= S.today) || isNow(t)).length,
  },
  // У каждого вида свой цвет значка — как у умных списков Things: глаз находит пункт раньше, чем читает.
  upcoming: { title: 'Предстоящее', icon: 'cal', color: '#f2706a', count: () => openMine().filter((t) => t.due_date && t.due_date > S.today && t.due_date <= D.add(S.today, 7)).length },
  new: { title: 'Новое', icon: 'inbox-in', color: '#5b9dff', count: () => pendingSugs().length },
  waiting: { title: 'Жду', icon: 'hourglass', color: '#6fb8ff', count: () => openMine().filter((t) => t.ball === 'waiting').length },
  week: { title: 'Неделя', icon: 'broom', color: '#3fbf8f' },
  inbox: { title: 'Входящие', icon: 'tray', color: '#8b93ff', count: () => openMine().filter((t) => !t.project_id).length },
  all: { title: 'Все дела', icon: 'list', color: '#9aa3b2' },
};
const pendingSugs = () => [...S.sugs.values()].filter((s) => s.status === 'pending' && s.for_user === S.me.user);
/** Очевидные закрытия, которые сервер принял сам (store._auto_close), — за days дней, свежие сверху. */
const autoClosed = (days) => [...S.sugs.values()].filter((s) => s.kind === 'close' && s.status === 'accepted' && (s.payload || {}).auto
  && s.for_user === S.me.user && s.decided_at && Date.now() - Date.parse(s.decided_at) < days * 86400e3)
  .sort((a, b) => b.decided_at.localeCompare(a.decided_at));

// ── Иконки (свой штрих, без шрифтов и эмодзи) ───────────────────────────
const ICONS = {
  sun: 'M12 4V2M12 22v-2M4 12H2M22 12h-2M5 5l1.5 1.5M17.5 17.5L19 19M5 19l1.5-1.5M17.5 6.5L19 5M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z',
  cal: 'M4 6h16v14H4zM4 10h16M8 3v4M16 3v4',
  'inbox-in': 'M4 13l3-8h10l3 8v6H4zM4 13h5l1 2h4l1-2h5M12 3v7M9 7l3 3 3-3',
  hourglass: 'M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9',
  broom: 'M14 4l6 6M4 20l7-7M9 11l4 4 5-5-4-4z',
  tray: 'M4 13l3-8h10l3 8v6H4zM4 13h5l1 2h4l1-2h5',
  list: 'M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01',
  person: 'M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM4 21c1-4 4-6 8-6s7 2 8 6',
  folder: 'M3 6h6l2 2h10v11H3z',
  date: 'M4 6h16v14H4zM4 10h16M8 3v4M16 3v4M9 15h2',
  bolt: 'M13 2L4 14h7l-1 8 9-12h-7z',
  x: 'M6 6l12 12M18 6L6 18',
  chat: 'M5 5h14v10H10l-5 4z',
  mic: 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3',
  gear: 'M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM12 2.5l1.6 2.6 3-.6.6 3 2.6 1.6-1.4 2.9 1.4 2.9-2.6 1.6-.6 3-3-.6L12 21.5l-1.6-2.6-3 .6-.6-3-2.6-1.6 1.4-2.9-1.4-2.9 2.6-1.6.6-3 3 .6z',
  send: 'M21 3L3 10.5l7 2.5 2.5 7zM21 3L10 13',
  search: 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4',
  check: 'M5 12.5l4.5 4.5L19 7.5',
};
function icon(name, size = 15) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.8');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  const p = document.createElementNS(ns, 'path');
  p.setAttribute('d', ICONS[name] || ICONS.list);
  svg.append(p);
  return svg;
}

// ── Помощники ───────────────────────────────────────────────────────────
function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else if (k === 'value') e.value = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat(Infinity)) if (k != null && k !== false) e.append(k.nodeType ? k : String(k));
  return e;
}
const plural = (n, one, few, many) => {
  const a = n % 10, b = n % 100;
  return `${n} ${a === 1 && b !== 11 ? one : a >= 2 && a <= 4 && (b < 12 || b > 14) ? few : many}`;
};
function toast(text, undo) {
  document.querySelectorAll('.toast').forEach((t) => t.remove());
  const t = el('div', { class: 'toast' }, el('span', {}, text),
    undo ? el('button', { onclick: async () => { t.remove(); try { await undo(); render(); } catch (e) { fail(e); } } }, 'Вернуть') : null);
  document.body.append(t);
  setTimeout(() => t.remove(), 6000);
}
const fail = (e) => toast('Не вышло: ' + e.message);
const norm = (s) => (s || '').toLowerCase().replace(/ё/g, 'е').trim();

// ── Цвета: у проекта и человека свой оттенок — по id, чтобы не менялся от переименования ──
const HUES = ['#f87171', '#fb923c', '#f5b544', '#a3d65c', '#34d399', '#2dd4bf', '#38bdf8', '#7c9cff', '#a78bfa', '#e879f9', '#f472b6'];
const hueOf = (id) => { let h = 0; for (const c of String(id)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return HUES[h % HUES.length]; };
/** Цвет — переменной --c (CSSOM, CSP не мешает); в светлой теме CSS сам темнит его. */
const tint = (node, c) => { node.classList.add('tinted'); node.style.setProperty('--c', c); return node; };
const dot = (id) => tint(el('span', { class: 'pdot' }), hueOf(id));
function avatar(p, cls) {
  const name = (p && (p.name || p.short)) || '?';
  const ini = name.split(/\s+/).map((w) => (w.match(/[\p{L}\p{N}]/u) || [''])[0]).filter(Boolean).slice(0, 2).join('').toUpperCase();
  return tint(el('span', { class: 'ava' + (cls ? ' ' + cls : ''), title: name, 'aria-hidden': 'true' }, ini || '?'), hueOf(p ? p.id : name));
}

// ── Маршрут ─────────────────────────────────────────────────────────────
function route() {
  const h = location.hash.replace(/^#\/?/, '');
  const [kind, id] = h.split('/');
  if (kind === 'p' && id) return { kind: 'project', id };
  if (kind === 'h' && id) return { kind: 'person', id };
  if (kind === 'search') return { kind: 'search', q: decodeURIComponent(id || '') };
  if (kind === 'new' && id) return { kind: 'new', batch: decodeURIComponent(id) }; // ссылка из Telegram — одна пачка
  if (kind === 'settings') return { kind: 'settings' };
  if (kind === 'stats') return { kind: 'stats' };
  if (CRM_VIEWS[kind]) return { kind };
  return { kind: VIEWS[kind] ? kind : 'morning' };
}
const go = (hash) => { location.hash = hash; };
window.addEventListener('hashchange', () => { S.sel.clear(); S.dealFilter = null; S.sideOpen = false; render(); });

// ── Вход ────────────────────────────────────────────────────────────────
function renderLogin(msg) {
  $app.replaceChildren(el('div', { class: 'login' }, el('img', { class: 'login-logo', src: '/static/icon.svg', alt: '' }), el('h1', {}, 'Дела'),
    el('p', { class: 'muted' }, msg || 'Вход — по ссылке-приглашению. Попроси её у Саши: ссылка одноразовая, дальше страница помнит вход полгода.')));
}

async function boot() {
  const m = location.hash.match(/invite=([\w-]+)/);
  if (m) {
    history.replaceState(null, '', '/');
    try { await api('/auth/redeem', { code: m[1] }); } catch (e) {
      // Ссылка уже использована, но вход с этого устройства жив — просто открываемся.
      try { S.me = await api('/api/me'); } catch (e2) { renderLogin(e.message); return; }
    }
  }
  if (!S.me) { try { S.me = await api('/api/me'); } catch (e) { return; } }
  await sync(true);
  render();
  // Пока человек набирает новое дело, фон только подтягивает данные, а не перерисовывает.
  const typing = () => rec || S.askTask || (document.activeElement?.id === 'quick' && document.activeElement.value);
  const refresh = () => sync().then(() => { if (!typing()) render(); }).catch(() => {});
  setInterval(() => { if (!document.hidden) refresh(); }, 30000);
  window.addEventListener('focus', refresh);
}

// ── Каркас ──────────────────────────────────────────────────────────────
function render() {
  if (!S.me) return;
  const r = route();
  const ae = document.activeElement;
  const q0 = ae && (ae.id === 'quick' || ae.classList?.contains('ask-input')) ? ae : null;
  const focused = q0 ? { id: q0.id, value: q0.value, a: q0.selectionStart, b: q0.selectionEnd } : null;
  const shell = el('div', { class: 'shell' + (S.cardId || S.draft || S.dealId ? ' with-card' : '') + (S.sideOpen ? ' side-open' : '') + (S.sel.size ? ' selecting' : '') });
  shell.append(renderSide(r), el('main', { class: 'list' }, renderMain(r)));
  if (S.dealId && !S.cardId && !S.draft) shell.append(renderDealCard());
  else if (S.cardId || S.draft) shell.append(renderCard());
  const scroll = document.querySelector('main.list')?.scrollTop || 0;
  const old = document.querySelector('section.card-pane');
  const cardScroll = old ? [old.dataset.key, old.scrollTop] : null;
  $app.replaceChildren(shell);
  const pane = shell.querySelector('section.card-pane');
  if (pane && cardScroll && pane.dataset.key === cardScroll[0]) pane.scrollTop = cardScroll[1];
  if (S.sideOpen) $app.append(el('div', { class: 'scrim', onclick: () => { S.sideOpen = false; render(); } }));
  shell.querySelector('main.list').scrollTop = scroll;
  if (focused) {
    const q = document.getElementById(focused.id);
    if (q && !q.disabled) {
      q.value = focused.value; q.focus(); q.setSelectionRange(focused.a, focused.b);
      if (focused.id === 'quick') q.dispatchEvent(new Event('input'));
    }
  }
  renderBulk();
}

function navItem(hash, ico, label, count, on, extra, hot) {
  return el('button', { class: 'nav-item' + (on ? ' on' : ''), onclick: () => go(hash) },
    el('span', { class: 'ico' }, ico), el('span', { class: 'label' }, label), extra || null,
    count ? el('span', { class: 'n' + (hot ? ' hot' : ''), title: hot ? 'есть просроченные' : null }, count) : null);
}

function renderSide(r) {
  const search = el('input', { class: 'side-search', type: 'search', placeholder: 'Поиск  /', value: r.kind === 'search' ? r.q : '' });
  search.addEventListener('keydown', (e) => { if (e.key === 'Enter') go('#/search/' + encodeURIComponent(search.value.trim())); });
  const sphere = el('div', { class: 'seg' }, [['work', 'Работа'], ['home', 'Дом'], ['', 'Всё']].map(([v, t]) =>
    el('button', { class: S.sphere === v ? 'on' : '', onclick: () => { S.sphere = v; LS.set('sphere', v); render(); } }, t)));
  const nav = Object.entries(VIEWS).map(([k, v]) => {
    const n = v.count ? v.count() : 0;
    const badge = k === 'new' && n ? el('span', { class: 'badge-new' }, n) : null;
    return navItem('#/' + k, tint(icon(v.icon), v.color), v.title, k === 'new' ? null : n, r.kind === k, badge);
  });

  // Проекты: избранные сверху, дальше по видам; число открытых и точка просрочки.
  const openBy = new Map(), lateBy = new Set();
  for (const t of all()) {
    if (!isOpen(t) || !t.project_id) continue;
    openBy.set(t.project_id, (openBy.get(t.project_id) || 0) + 1);
    if (isLate(t)) lateBy.add(t.project_id);
  }
  const live = [...S.projects.values()].filter((p) => !p.archived_at && (!S.sphere || p.sphere === S.sphere))
    .sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  // Цвет проекта — точкой; просрочка — красным числом (красная точка рядом с цветной путала бы).
  const projItem = (p) => navItem('#/p/' + p.id, dot(p.id), p.name, openBy.get(p.id), r.kind === 'project' && r.id === p.id, null, lateBy.has(p.id));
  const group = (key, title, items) => {
    if (!items.length) return null;
    return el('div', { class: 'side-group' + (S.closed[key] ? ' closed' : '') },
      el('button', { class: 'gh', onclick: () => { S.closed[key] = !S.closed[key]; LS.set('closed', S.closed); render(); } },
        el('span', { class: 'caret' }, '▾'), title, el('span', { class: 'n' }, items.length)),
      el('div', { class: 'items' }, items));
  };
  const favs = live.filter((p) => S.favs.includes(p.id));
  const crmNav = crmOn() ? Object.entries(CRM_VIEWS).filter(([, v]) => !v.money || S.me.money)
    .map(([k, v]) => navItem('#/' + k, tint(icon(v.icon), v.color), v.title, null, r.kind === k)) : [];
  const groups = [group('crm', 'CRM', crmNav), group('fav', 'Избранное', favs.map(projItem))];
  for (const [kind, title] of Object.entries(KIND)) {
    groups.push(group('k-' + kind, title, live.filter((p) => p.kind === kind && !S.favs.includes(p.id)).map(projItem)));
  }
  // Люди: с кем больше всего открытых дел.
  const byPerson = new Map();
  for (const t of all()) if (isOpen(t) && t.person_id) byPerson.set(t.person_id, (byPerson.get(t.person_id) || 0) + 1);
  const people = [...byPerson.entries()].sort((a, b) => b[1] - a[1]).map(([id, n]) => [person(id), n]).filter(([p]) => p);
  groups.push(group('people', 'Люди', people.map(([p, n]) => navItem('#/h/' + p.id, avatar(p), personName(p), n, r.kind === 'person' && r.id === p.id))));
  const archived = [...S.projects.values()].filter((p) => p.archived_at).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  if (S.closed.arch === undefined) S.closed.arch = true;
  groups.push(group('arch', 'Архив', archived.map(projItem)));

  const me = person(S.me.person_id) || { id: S.me.user, name: S.me.name };
  return el('aside', { class: 'side' },
    el('div', { class: 'brand' }, el('img', { src: '/static/icon.svg', alt: '' }), el('b', {}, 'Дела'),
      el('span', { class: 'who' }, S.me.name), avatar(me)),
    gameCard(),
    search, sphere, nav, groups,
    el('div', { class: 'side-foot' },
      el('button', { class: 'settings-btn' + (r.kind === 'settings' ? ' on' : ''), onclick: () => go('#/settings') }, icon('gear', 14), 'Настройки'),
      el('button', { onclick: async () => { await api('/auth/logout', {}); location.reload(); } }, 'Выйти')),
    el('div', { class: 'side-keys' }, el('span', { class: 'kbd' }, 'n'), ' новое · ', el('span', { class: 'kbd' }, '/'), ' поиск'));
}

// ── Основная колонка ────────────────────────────────────────────────────
/** Значок перед заголовком: цвет вида, проекта или аватар человека — по маршруту. */
function headIcon() {
  const r = route();
  const v = VIEWS[r.kind] || CRM_VIEWS[r.kind];
  const box = (name, c) => tint(el('span', { class: 'h-ico' }, icon(name, 18)), c);
  if (v) return box(v.icon, v.color);
  if (r.kind === 'project' && project(r.id)) return box('folder', hueOf(r.id));
  if (r.kind === 'person' && person(r.id)) return avatar(person(r.id), 'big');
  if (r.kind === 'search') return box('search', '#9aa3b2');
  if (r.kind === 'settings') return box('gear', '#9aa3b2');
  if (r.kind === 'stats') return box('chart', '#7c9cff');
  return null;
}

function head(title, sub, extra) {
  const lead = headIcon();
  return el('div', { class: 'list-head' + (lead ? ' has-ico' : '') },
    el('div', { class: 'row1' },
      el('button', { class: 'burger', onclick: () => { S.sideOpen = true; render(); } }, '☰'),
      el('h1', {}, lead, el('span', { class: 'h-t' }, title)), extra || null),
    sub ? el('div', { class: 'sub' }, [].concat(sub).filter(Boolean).map((x) => (x.nodeType ? x : el('span', {}, x)))) : null);
}

function groupPicker(key, def, options) {
  const cur = S.groups[key] || def;
  const sel = el('select', {}, options.map(([v, t]) => el('option', { value: v, selected: v === cur }, t)));
  sel.addEventListener('change', () => { S.groups[key] = sel.value; LS.set('groups', S.groups); render(); });
  return [el('label', {}, 'Группировать', sel), cur];
}
const GROUPS = [['date', 'по датам'], ['project', 'по проектам'], ['person', 'по людям'], ['ball', 'по мячу'], ['deal', 'по сделкам'], ['none', 'без групп']];

function renderMain(r) {
  S.order = [];
  S.sugOrder = []; // предложения «Нового» в порядке экрана: П1, П2… — так их видит и Claude
  if (r.kind === 'project') return renderProject(r.id);
  if (r.kind === 'person') return renderPerson(r.id);
  if (r.kind === 'search') return renderSearch(r.q);
  if (r.kind === 'new') return renderNew(r.batch);
  if (r.kind === 'settings') return renderSettings();
  if (r.kind === 'stats') return renderStats();
  if (r.kind === 'week') return renderWeek();
  if (r.kind === 'crm') return renderPipeline();
  if (r.kind === 'clients') return renderClients();
  if (r.kind === 'ties') return renderTies();
  if (r.kind === 'money') return renderMoney();
  const v = VIEWS[r.kind];
  if (r.kind === 'morning') {
    const secs = v.sections();
    const body = el('div', { class: 'body' }, quickAdd({}),
      secs.map(([t, items, ic, c]) => (items.length ? groupBox(t, items.sort(sortTasks), { lead: tint(icon(ic, 14), c) }) : null)));
    if (!secs.some(([, i]) => i.length)) body.append(el('div', { class: 'empty' }, 'На сегодня пусто. Загляни в «Предстоящее».'));
    const n = pendingSugs().length, self = autoClosed(1).length;
    return [head('Утро', [D.long(S.today), n ? el('a', { class: 'link', href: '#/new' }, `в «Новом» ждут решения: ${n}`) : null,
      self ? el('a', { class: 'link', href: '#/new' }, `закрылось само за сутки: ${self}`) : null]), body];
  }
  if (r.kind === 'upcoming') {
    let items = openMine();
    if (S.upMineOnly) items = items.filter((t) => t.ball === 'mine');
    if (!S.upNoDate) items = items.filter((t) => t.due_date);
    const tb = el('div', { class: 'toolbar' },
      el('button', { class: 'chip-btn' + (S.upMineOnly ? ' on' : ''), onclick: () => { S.upMineOnly = !S.upMineOnly; render(); } }, 'Только мяч у меня'),
      el('button', { class: 'chip-btn' + (S.upNoDate ? ' on' : ''), onclick: () => { S.upNoDate = !S.upNoDate; render(); } }, 'И без даты'));
    return [head('Предстоящее', [plural(items.length, 'дело', 'дела', 'дел')]),
      el('div', { class: 'body' }, tb, quickAdd({}), items.length ? grouped(items, 'date') : el('div', { class: 'empty' }, 'Впереди пусто.'))];
  }
  if (r.kind === 'waiting') {
    const items = openMine().filter((t) => t.ball === 'waiting');
    return [head('Жду', ['что должны другие — по людям, с давностью']),
      el('div', { class: 'body' }, quickAdd({}), items.length ? grouped(items, 'person') : el('div', { class: 'empty' }, 'Ни от кого ничего не ждём.'))];
  }
  if (r.kind === 'inbox' || r.kind === 'all') {
    const key = r.kind;
    const [picker, g] = groupPicker(key, key === 'all' ? 'project' : 'date', GROUPS);
    let items = all().filter((t) => isMine(t) && inSphere(t) && (S.showDone || isOpen(t)));
    if (key === 'inbox') items = items.filter((t) => !t.project_id);
    const tb = el('div', { class: 'toolbar' }, picker, doneToggle());
    return [head(v.title, [plural(items.filter(isOpen).length, 'открытое', 'открытых', 'открытых')]),
      el('div', { class: 'body' }, tb, quickAdd({}), items.length ? grouped(items, g) : el('div', { class: 'empty' }, 'Пусто.'))];
  }
  return [head('Дела'), el('div', { class: 'body' })];
}

const doneToggle = () => el('button', { class: 'chip-btn' + (S.showDone ? ' on' : ''), onclick: () => { S.showDone = !S.showDone; render(); } }, 'Сделанные');

/** Разложить дела по группам: даты (как «Предстоящее» в Vikunja), проекты, люди, мяч, сделки. */
function grouped(items, by) {
  items = [...items].sort(sortTasks);
  if (by === 'none') return groupBox(null, items);
  const buckets = new Map();
  const put = (key, title, t, extra) => {
    if (!buckets.has(key)) buckets.set(key, { title, items: [], ...extra });
    buckets.get(key).items.push(t);
  };
  const day = (main, iso) => [main, el('span', { class: 'gsub' }, D.long(iso))];
  for (const t of items) {
    if (by === 'date') {
      if (!isOpen(t)) put('9done', 'Сделано', t);
      else if (!t.due_date) put('8none', 'Без даты', t);
      else {
        const n = D.diff(t.due_date, S.today);
        if (n < 0) put('0late', 'Просрочено', t, { late: true });
        else if (n === 0) put('1today', day('Сегодня', S.today), t);
        else if (n === 1) put('2tomorrow', day('Завтра', t.due_date), t);
        else if (n < 7) put('3' + t.due_date, D.long(t.due_date), t);
        else if (n < 14) put('5next', 'Следующая неделя', t);
        else put('6later', 'Позже', t);
      }
    } else if (by === 'project') {
      const p = project(t.project_id);
      put(p ? '1' + p.name : '0', p ? p.name : 'Входящие', t, { href: p ? '#/p/' + p.id : '#/inbox', lead: () => (p ? dot(p.id) : tint(icon('tray', 14), VIEWS.inbox.color)) });
    } else if (by === 'person') {
      const p = person(t.person_id);
      put(p ? '1' + personName(p) : '2', p ? personName(p) : 'Без человека', t, { href: p ? '#/h/' + p.id : null, lead: p ? () => avatar(p) : null });
    } else if (by === 'ball') {
      put({ mine: '1', agenda: '2', waiting: '3' }[t.ball], { mine: 'Моё', agenda: 'Повестка', waiting: 'Жду' }[t.ball], t);
    } else if (by === 'deal') {
      const dl = t.deal_id ? S.deals.get(t.deal_id) : null;
      put(dl ? '1' + dl.name : '2', dl ? dl.name : 'Без сделки', t);
    }
  }
  return [...buckets.entries()].sort((a, b) => a[0].localeCompare(b[0], 'ru')).map(([, g]) => groupBox(g.title, g.items, g, by));
}

function groupBox(title, items, opts = {}, by) {
  const lead = typeof opts.lead === 'function' ? opts.lead() : opts.lead;
  const h = title ? el('h2', { class: opts.late ? 'late' : '' }, lead || null,
    opts.href ? el('a', { class: 'link', href: opts.href }, title) : title, el('span', { class: 'n' }, items.length),
    opts.late && items.length > 1 ? el('span', { class: 'act' },
      el('button', { class: 'chip-btn', onclick: (e) => reschedulePop(e.currentTarget, items.map((t) => t.id)) }, 'Перенести все')) : null) : null;
  return el('div', { class: 'group' }, h, items.map((t) => taskRow(t, by)));
}

// Строка дела. Слева — выбор, «Сейчас» и галка: их места заняты всегда, при наведении они только
// проявляются, поэтому строка не прыгает. Номер — справа, бледно: на него ссылаются, но читать не мешает.
function taskRow(t, by) {
  S.order.push(t.id);
  const p = project(t.project_id);
  const who = person(t.person_id);
  const due = dueLabel(t);
  const now = isNow(t);
  const stop = (e) => e.stopPropagation();
  const m = (cls, ic, ...kids) => el('span', { class: 'm' + (cls ? ' ' + cls : '') }, ic ? icon(ic, 12) : null, ...kids);
  const chips = [];
  if (due && (by !== 'date' || due.cls === 'late')) chips.push(m(due.cls, 'date', due.text));
  if (by !== 'project' && route().kind !== 'project') {
    chips.push(p ? el('a', { class: 'm link', href: '#/p/' + p.id, onclick: stop }, dot(p.id), p.name) : m('', 'tray', 'Входящие'));
  }
  const dl = t.deal_id ? S.deals.get(t.deal_id) : null;
  if (dl && by !== 'deal') chips.push(m('', 'funnel', dl.name));
  if (t.ball !== 'mine' && by !== 'ball') {
    chips.push(m('ball-' + t.ball, t.ball === 'waiting' ? 'hourglass' : 'chat',
      BALL[t.ball] + (who ? ' ' + personName(who) : '') + (t.ball === 'waiting' && t.waiting_since ? ' · ' + D.diff(S.today, t.waiting_since) + ' дн.' : '')));
  } else if (who && by !== 'person') {
    chips.push(el('a', { class: 'm link', href: '#/h/' + who.id, onclick: stop }, '@' + personName(who)));
  }
  if (t.estimate_min) chips.push(m('', 'clock', t.estimate_min + ' мин'));
  for (const l of t.labels || []) chips.push(el('span', { class: 'tag' }, l));
  const pick = el('input', { type: 'checkbox', class: 'pick', title: 'Выбрать (Shift — диапазон)' });
  pick.checked = S.sel.has(t.id);
  pick.addEventListener('click', (e) => { e.stopPropagation(); togglePick(t.id, e.shiftKey); });
  return el('div', {
    class: 'task ball-' + t.ball + (isOpen(t) ? '' : ' done') + (now ? ' now' : '') + (S.cardId === t.id ? ' open-now' : '') + (S.sel.has(t.id) ? ' sel' : ''),
    'data-id': t.id,
    onclick: () => openCard(t.id),
  },
  pick,
  el('div', { class: 'lead' },
    el('button', { class: 'now-btn' + (now ? ' on' : ''), title: now ? 'Убрать из «Сейчас»' : 'В «Сейчас» — на сегодня',
      onclick: (e) => { e.stopPropagation(); setFields([t.id], { focus_on: now ? null : S.today }); } }, icon('bolt', 15)),
    el('button', { class: 'mic-btn' + (S.askTask === t.id ? ' on' : ''), title: 'Сказать Claude, что сделать с этим делом',
      onclick: (e) => { e.stopPropagation(); if (S.askTask === t.id && rec) stopListening(); else openAsk(t); } }, icon('mic', 14))),
  el('button', { class: 'tick' + (isOpen(t) ? '' : ' done'), title: isOpen(t) ? 'Сделано' : 'Вернуть', onclick: (e) => { e.stopPropagation(); toggleDone([t]); } }),
  el('div', { class: 'main' }, el('div', { class: 'title' }, t.title), chips.length ? el('div', { class: 'chips' }, chips) : null,
    S.askTask === t.id ? askBox(t) : null),
  el('span', { class: 'num' }, '#' + t.num));
}

// ── Действия ────────────────────────────────────────────────────────────
// Сделанное сразу получает галку и зачёркивание, а из списка уходит через секунду:
// видно, что отметил именно то. Щелчок по галке в эту секунду — передумал, вернуть.
const DONE_LINGER = 1000;
const finishing = new Set();
const inflight = new Map(); // id → отправленная «сделано»: «вернуть» ждёт её, а не обгоняет
const rowOf = (id) => document.querySelector(`.task[data-id="${id}"]`);
function markDone(id, on) {
  const r = rowOf(id);
  if (!r) return;
  r.classList.toggle('done', on);
  r.classList.toggle('finishing', on);
  r.querySelector('.tick')?.classList.toggle('done', on);
}

async function toggleDone(list) {
  const again = list.filter((t) => finishing.has(t.id));
  if (again.length) {
    again.forEach((t) => { finishing.delete(t.id); markDone(t.id, false); });
    await Promise.allSettled(again.map((t) => inflight.get(t.id)));
    try {
      await ops(again.map((t) => ({ op: 'task.reopen', id: t.id })));
      toast('Вернул в работу');
    } catch (e) { fail(e); }
    render();
    return;
  }
  const open = list.filter(isOpen);
  const target = open.length ? open : list;
  const opName = open.length ? 'task.done' : 'task.reopen';
  const started = Date.now();
  if (open.length) target.forEach((t) => { finishing.add(t.id); markDone(t.id, true); });
  try {
    const sent = ops(target.map((t) => ({ op: opName, id: t.id })));
    target.forEach((t) => inflight.set(t.id, sent));
    await sent.finally(() => target.forEach((t) => { if (inflight.get(t.id) === sent) inflight.delete(t.id); }));
    if (open.length && !target.some((t) => finishing.has(t.id))) return; // передумал, пока сервер отвечал
    S.sel.clear();
    renderBulk();
    const pts = open.length ? target.reduce((sum, t) => sum + pointsFor(t), 0) : 0;
    toast((open.length ? (target.length > 1 ? `Сделано: ${target.length}` : 'Сделано: ' + target[0].title) : 'Вернул в работу') + (pts ? ` · +${pts}` : ''),
      () => { target.forEach((t) => finishing.delete(t.id)); return ops(target.map((t) => ({ op: open.length ? 'task.reopen' : 'task.done', id: t.id }))); });
    if (!open.length) { render(); return; }
    setTimeout(() => {
      const gone = target.filter((t) => finishing.has(t.id));
      if (!gone.length) return; // успел передумать
      gone.forEach((t) => rowOf(t.id)?.classList.add('leaving'));
      setTimeout(() => { gone.forEach((t) => finishing.delete(t.id)); render(); }, 250);
    }, Math.max(0, DONE_LINGER - (Date.now() - started)));
  } catch (e) {
    target.forEach((t) => { finishing.delete(t.id); markDone(t.id, false); });
    fail(e);
    render();
  }
}

async function setFields(ids, set, msg) {
  const before = ids.map((id) => ({ ...S.tasks.get(id) }));
  try {
    await ops(ids.map((id) => ({ op: 'task.set', id, set, was: Object.fromEntries(Object.keys(set).map((k) => [k, S.tasks.get(id)[k] ?? null])) })));
    if (msg) S.sel.clear();
    render();
    if (msg) {
      toast(msg, () => ops(before.map((t) => ({ op: 'task.set', id: t.id, set: Object.fromEntries(Object.keys(set).map((k) => [k, t[k] ?? null])) }))));
    }
  } catch (e) { fail(e); render(); }
}

function closePop() { document.querySelectorAll('.pop').forEach((p) => p.remove()); }
document.addEventListener('click', (e) => { if (!e.target.closest('.pop')) closePop(); });

function popAt(anchor, children) {
  closePop();
  const pop = el('div', { class: 'pop' }, children);
  document.body.append(pop);
  const r = anchor.getBoundingClientRect();
  const w = pop.offsetWidth, h = pop.offsetHeight;
  pop.style.left = Math.max(8, Math.min(window.innerWidth - w - 8, r.right - w)) + 'px';
  pop.style.top = (r.bottom + h + 8 > window.innerHeight ? Math.max(8, r.top - h - 4) : r.bottom + 4) + 'px';
  return pop;
}

function reschedulePop(anchor, ids) {
  setTimeout(() => {
    const n = ids.length > 1 ? ` (${ids.length})` : '';
    const pick = (d, what) => { closePop(); setFields(ids, { due_date: d }, `Перенёс${n}: ${what}`); };
    const dateIn = el('input', { type: 'date' });
    dateIn.addEventListener('change', () => dateIn.value && pick(dateIn.value, D.ddmm(dateIn.value)));
    popAt(anchor, [
      el('button', { onclick: () => pick(S.today, 'на сегодня') }, 'Сегодня', el('span', { class: 'k' }, WD_SHORT[D.wd(S.today)])),
      el('button', { onclick: () => pick(D.add(S.today, 1), 'на завтра') }, 'Завтра', el('span', { class: 'k' }, WD_SHORT[D.wd(D.add(S.today, 1))])),
      el('button', { onclick: () => pick(D.next(0), 'на понедельник') }, 'Понедельник', el('span', { class: 'k' }, D.ddmm(D.next(0)))),
      el('button', { onclick: () => pick(D.add(S.today, 7), 'через неделю') }, 'Через неделю', el('span', { class: 'k' }, D.ddmm(D.add(S.today, 7)))),
      el('button', { onclick: () => pick(D.add(S.today, 30), 'через месяц') }, 'Через месяц', el('span', { class: 'k' }, D.ddmm(D.add(S.today, 30)))),
      el('button', { onclick: () => pick(null, 'без даты') }, 'Без даты'),
      el('div', { class: 'sep' }), dateIn,
    ]);
  }, 0);
}

function projectPop(anchor, ids) {
  setTimeout(() => {
    const live = [...S.projects.values()].filter((p) => !p.archived_at).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
    popAt(anchor, [el('button', { onclick: () => { closePop(); setFields(ids, { project_id: null, deal_id: null }, 'Во «Входящие»'); } }, 'Входящие'),
      ...live.map((p) => el('button', { onclick: () => { closePop(); setFields(ids, { project_id: p.id, deal_id: null }, 'В проект ' + p.name); } }, p.name))]);
  }, 0);
}

function togglePick(id, range) {
  if (range && S.lastPick && S.order.includes(S.lastPick)) {
    const a = S.order.indexOf(S.lastPick), b = S.order.indexOf(id);
    for (const x of S.order.slice(Math.min(a, b), Math.max(a, b) + 1)) S.sel.add(x);
  } else if (S.sel.has(id)) S.sel.delete(id);
  else S.sel.add(id);
  S.lastPick = id;
  render();
}

function renderBulk() {
  document.querySelector('.bulk')?.remove();
  if (!S.sel.size) return;
  const ids = [...S.sel].filter((id) => S.tasks.has(id));
  const bar = el('div', { class: 'bulk' },
    el('b', {}, plural(ids.length, 'дело', 'дела', 'дел')),
    el('button', { onclick: () => toggleDone(ids.map((id) => S.tasks.get(id))) }, 'Сделано'),
    el('button', { onclick: (e) => reschedulePop(e.currentTarget, ids) }, 'Перенести'),
    el('button', { onclick: (e) => projectPop(e.currentTarget, ids) }, 'В проект'),
    el('button', { onclick: () => setFields(ids, { focus_on: S.today }, 'В «Сейчас»') }, 'В «Сейчас»'),
    el('button', {
      onclick: async () => {
        if (!confirm(`Отменить ${plural(ids.length, 'дело', 'дела', 'дел')}? Они останутся в журнале.`)) return;
        try { await ops(ids.map((id) => ({ op: 'task.cancel', id }))); S.sel.clear(); render(); toast('Отменено: ' + ids.length); } catch (e) { fail(e); }
      },
    }, 'Отменить'),
    el('button', { title: 'Снять выбор (Esc)', onclick: () => { S.sel.clear(); render(); } }, icon('x', 14)));
  document.body.append(bar);
}

// ── Быстрый ввод (как Quick Add Magic в Vikunja, по-русски) ──────────────
const WD_WORDS = ['понедельник', 'вторник', 'сред', 'четверг', 'пятниц', 'суббот', 'воскресень'];
/** Единственный по имени: сперва точное совпадение, потом начало. Двое — значит, никто. */
function findIn(list, name, keys) {
  const n = norm(name);
  const exact = list.filter((x) => keys(x).some((k) => norm(k) === n));
  if (exact.length === 1) return exact[0];
  const pre = list.filter((x) => keys(x).some((k) => norm(k).startsWith(n)));
  return pre.length === 1 ? pre[0] : null;
}
const liveProjects = () => [...S.projects.values()].filter((x) => !x.archived_at);
const livePeople = () => [...S.people.values()].filter((x) => !x.archived_at);
const projectKeys = (x) => [x.name, ...(x.aliases || [])];
const personKeys = (x) => [x.name, x.short || '', ...(x.aliases || [])].filter(Boolean);

function parseQuick(text, defaults) {
  const out = { title: text, set: { ...defaults }, tags: [] };
  if (/^".*"$/.test(text.trim())) { out.title = text.trim().slice(1, -1); return out; } // в кавычках — без разбора
  let s = ' ' + text + ' ';
  const take = (re, fn) => { s = s.replace(re, (...m) => (fn(...m) === false ? m[0] : ' ')); };
  take(/\s\+(?:"([^"]+)"|(\S+))/g, (m, q, w) => {
    const p = findIn(liveProjects(), q || w, projectKeys);
    if (!p) return false;
    out.set.project_id = p.id; out.tags.push('проект: ' + p.name);
    return true;
  });
  take(/\s@(?:"([^"]+)"|(\S+))/g, (m, q, w) => {
    const p = findIn(livePeople(), q || w, personKeys);
    if (!p) return false;
    out.set.person_id = p.id; out.tags.push('человек: ' + personName(p));
    return true;
  });
  take(/\s\*(\S+)/g, (m, w) => { (out.set.labels = out.set.labels || []).push(w); out.tags.push('метка: ' + w); });
  take(/\s!жду(?=\s)/gi, () => { out.set.ball = 'waiting'; out.tags.push('жду'); });
  take(/\s!повестка(?=\s)/gi, () => { out.set.ball = 'agenda'; out.tags.push('повестка'); });
  take(/\s!сейчас(?=\s)/gi, () => { out.set.focus_on = S.today; out.tags.push('сейчас'); });
  take(/\s!хочу(?=\s)/gi, () => { out.set.want = true; out.tags.push('хочу сам'); });
  take(/\s!(\d{1,3})\s?м(?:ин)?(?=\s)/gi, (m, n) => { out.set.estimate_min = +n; out.tags.push(n + ' мин'); });
  take(/\s!(оплачено|развитие)(?=\s)/gi, (m, w) => { out.set.money = norm(w) === 'оплачено' ? 'paid' : 'potential'; out.tags.push(norm(w)); });
  const due = (d, what) => { out.set.due_date = d; out.tags.push('срок: ' + what); };
  take(/\s(сегодня)(?=[\s,.])/gi, () => due(S.today, 'сегодня'));
  take(/\s(послезавтра)(?=[\s,.])/gi, () => due(D.add(S.today, 2), 'послезавтра'));
  take(/\s(завтра)(?=[\s,.])/gi, () => due(D.add(S.today, 1), 'завтра'));
  take(/\s(?:в|во)\s(понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье)(?=[\s,.])/gi, (m, w) => {
    const i = WD_WORDS.findIndex((stem) => norm(w).startsWith(stem));
    due(D.next(i), WD_SHORT[i] + ' ' + D.ddmm(D.next(i)));
  });
  take(/\sчерез\s(\d+|неделю|месяц)(?:\s(дн[еяь]й?|дня|недел[юи]|месяц[аев]*))?(?=[\s,.])/gi, (m, n, unit) => {
    let days;
    if (norm(n) === 'неделю') days = 7;
    else if (norm(n) === 'месяц') days = 30;
    else days = +n * (/недел/.test(unit || '') ? 7 : /месяц/.test(unit || '') ? 30 : 1);
    due(D.add(S.today, days), D.ddmm(D.add(S.today, days)));
  });
  take(/\sна\sследующей\sнеделе(?=[\s,.])/gi, () => due(D.next(0), 'пн ' + D.ddmm(D.next(0))));
  take(/\s(\d{1,2})\.(\d{1,2})(?:\.(\d{2,4}))?(?=[\s,])/g, (m, d, mo, y) => {
    if (+mo > 12 || +mo < 1 || +d > 31 || +d < 1) return false;
    const year = y ? (y.length === 2 ? 2000 + +y : +y) : +S.today.slice(0, 4);
    let iso = `${year}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    if (!y && iso < S.today) iso = `${year + 1}` + iso.slice(4);
    due(iso, D.ddmm(iso));
    return true;
  });
  take(/\s(\d{1,2})\s(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)(?=[\s,.])/gi, (m, d, mon) => {
    const mo = MONTHS.indexOf(norm(mon)) + 1;
    let iso = `${S.today.slice(0, 4)}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    if (iso < S.today) iso = `${+S.today.slice(0, 4) + 1}` + iso.slice(4);
    due(iso, D.ddmm(iso));
  });
  out.title = s.replace(/\s+/g, ' ').trim();
  return out;
}

// ── Подсказки при вводе: +проект, @человек, *метка, !команда ─────────────
const COMMANDS = [
  ['жду', 'мяч у человека: жду от него'], ['повестка', 'поднять при встрече или звонке'],
  ['сейчас', 'в фокус на сегодня'], ['хочу', 'делаю, потому что сам хочу'],
  ['5м', 'оценка: 5 минут'], ['15м', 'оценка: 15 минут'], ['30м', 'оценка: полчаса'], ['60м', 'оценка: час'],
];
const AC_HEAD = { '+': 'Проекты', '@': 'Люди', '*': 'Метки', '!': 'Команды' };

/** Слово под кареткой, если оно начинается со знака подсказки: {kind, q, start}. */
function acToken(text, caret) {
  const m = text.slice(0, caret).match(/(^|\s)([+@*!])(?:"([^"]*)|([^\s"]*))$/);
  return m ? { kind: m[2], q: m[3] ?? m[4] ?? '', start: m.index + m[1].length } : null;
}

/** Как вставить имя, чтобы разбор потом узнал именно его (тёзкам — полное имя). */
function acValue(kind, x) {
  let name = x;
  if (kind === '@') {
    const all = livePeople();
    name = [x.short, x.name, ...(x.aliases || [])].filter(Boolean).find((k) => findIn(all, k, personKeys) === x) || x.name;
  }
  return /\s/.test(name) ? `"${name}"` : name;
}

function acItems(kind, q) {
  const n = norm(q);
  const rank = (keys) => { // 0 — с начала имени, 1 — с начала слова, 2 — внутри, -1 — мимо
    let best = -1;
    for (const k of keys) {
      const v = norm(k);
      if (!v) continue;
      const r = v.startsWith(n) ? 0 : v.split(/[\s\-–«»"().,]+/).some((w) => w.startsWith(n)) ? 1 : v.includes(n) ? 2 : -1;
      if (r >= 0 && (best < 0 || r < best)) best = r;
    }
    return best;
  };
  let list = [];
  if (kind === '+') list = liveProjects().map((p) => ({ label: p.name, insert: acValue('+', p.name), keys: projectKeys(p),
    hint: [(p.aliases || []).join(', '), p.sphere === 'home' ? 'дом' : ''].filter(Boolean).join(' · '), fav: S.favs.includes(p.id) }));
  if (kind === '@') list = livePeople().map((p) => ({ label: personName(p), insert: acValue('@', p), keys: personKeys(p),
    hint: [p.short && p.short !== p.name ? p.name : '', ...(p.aliases || [])].filter(Boolean).join(', ') }));
  if (kind === '*') list = S.labels.map((l) => ({ label: l, insert: acValue('*', l), keys: [l] }));
  if (kind === '!') list = COMMANDS.map(([c, h]) => ({ label: '!' + c, insert: c, keys: [c], hint: h }));
  const out = list.map((x, i) => ({ ...x, i, r: n ? rank(x.keys) : 0 })).filter((x) => x.r >= 0);
  out.sort((a, b) => a.r - b.r || (kind === '!' ? a.i - b.i : (b.fav | 0) - (a.fav | 0) || a.label.localeCompare(b.label, 'ru')));
  return out.slice(0, 8);
}

// ── Значок Claude: лучистая звёздочка ───────────────────────────────────
function claudeIcon(size = 18) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  for (const [k, v] of Object.entries({ viewBox: '0 0 24 24', width: size, height: size, fill: 'none', stroke: 'currentColor',
    'stroke-width': '2.1', 'stroke-linecap': 'round', class: 'claude-ico' })) svg.setAttribute(k, v);
  let d = '';
  for (let i = 0; i < 12; i++) {
    const a = i * Math.PI / 6 + 0.12, r2 = i % 2 ? 7.2 : 10;
    d += `M${(12 + 2.4 * Math.cos(a)).toFixed(2)} ${(12 + 2.4 * Math.sin(a)).toFixed(2)}`
       + `L${(12 + r2 * Math.cos(a)).toFixed(2)} ${(12 + r2 * Math.sin(a)).toFixed(2)}`;
  }
  const p = document.createElementNS(ns, 'path');
  p.setAttribute('d', d);
  svg.append(p);
  return svg;
}

// ── Быстрый ввод: «+» — одно дело с разметкой, Claude — разбор текста на дела ──
// toClaude: Enter отдаёт Claude, а не заводит дело («Новое»: там пишут «первое прими», и это не дело).
function quickAdd(defaults, { toClaude = false, placeholder = null } = {}) {
  const busy = !!S.parse;
  const input = el('textarea', { id: 'quick', rows: 1, autocomplete: 'off', disabled: busy,
    placeholder: placeholder || 'Новое дело — Enter. Claude (звёздочка или микрофон): «все просроченные — на завтра», надиктовка' });
  if (busy) input.value = S.parse.text;
  else if (S.quickDraft) { input.value = S.quickDraft; S.quickDraft = null; }
  const preview = el('div', { class: 'preview' });
  const help = el('div', { class: 'help hidden' },
    toClaude ? 'Enter, звёздочка или микрофон — Claude: примет, отклонит или поправит предложения на экране · «+» — одно дело · Shift+Enter — новая строка · '
      : 'Enter — одно дело · звёздочка, Ctrl+Enter или микрофон — Claude: поправит дела на экране или заведёт новые · Shift+Enter — новая строка · ',
    '+проект  @человек  *метка  !жду  !повестка  !сейчас  !хочу  !15м · сегодня, завтра, в пятницу, через 3 дня, 12.10 · «в кавычках» — без разбора');
  const status = busy ? el('div', { class: 'claude-status' }, 'Claude думает… Можно уходить на другие страницы — изменения появятся сами.')
    : rec && S.listenQuick ? el('div', { class: 'claude-status listening' }, 'Слушаю… замолчишь — отдам Claude. Нажми микрофон ещё раз, чтобы закончить сразу.') : null;
  const ac = el('div', { class: 'ac hidden' });
  let acState = null; // {kind, q, start, items, at}

  // Растёт по тексту; пустое — в одну строку (подсказка-плейсхолдер высоту не задаёт).
  const grow = () => {
    input.style.height = '';
    input.style.overflowY = '';
    if (input.value.includes('\n') || input.scrollHeight > input.clientHeight + 2) input.style.height = Math.min(input.scrollHeight, 320) + 'px';
    if (input.scrollHeight > 320) input.style.overflowY = 'auto';
  };
  const closeAc = () => { acState = null; ac.classList.add('hidden'); ac.replaceChildren(); };
  const drawAc = () => {
    const { kind, items, at } = acState;
    ac.replaceChildren(el('div', { class: 'ac-head' }, AC_HEAD[kind]),
      ...(items.length ? items.map((x, i) => el('button', { type: 'button', class: i === at ? 'on' : null,
        onmousedown: (e) => { e.preventDefault(); pickAc(i); } },
        el('span', { class: 'ac-label' }, x.label), x.hint ? el('span', { class: 'ac-hint' }, x.hint) : null))
        : [el('div', { class: 'ac-empty' }, kind === '*' ? 'Такой метки нет — будет новая' : 'Не нашлось')]));
    ac.classList.remove('hidden');
    ac.querySelector('button.on')?.scrollIntoView({ block: 'nearest' });
  };
  const updateAc = () => {
    const t = document.activeElement === input && input.selectionStart === input.selectionEnd
      ? acToken(input.value, input.selectionStart) : null;
    if (!t) { closeAc(); return; }
    acState = { ...t, items: acItems(t.kind, t.q), at: 0 };
    drawAc();
  };
  const update = () => {
    const p = parseQuick(input.value, defaults);
    preview.replaceChildren(...p.tags.map((t) => el('span', {}, t)));
    help.classList.toggle('hidden', !input.value);
    grow();
    updateAc();
  };
  const pickAc = (i) => {
    const x = acState?.items[i];
    if (!x) return;
    const v = input.value;
    let end = input.selectionStart;
    while (end < v.length && !/\s/.test(v[end])) end++;
    const ins = acState.kind + x.insert + ' ';
    const pos = acState.start + ins.length;
    input.value = v.slice(0, acState.start) + ins + v.slice(end).replace(/^ /, '');
    input.setSelectionRange(pos, pos);
    closeAc();
    update();
  };
  const addOne = async () => {
    if (!input.value.trim()) { input.focus(); return; }
    const p = parseQuick(input.value.replace(/\s*\n\s*/g, ' '), defaults);
    if (!p.title) { toast('Нужно название'); return; }
    try {
      const r = await op1({ op: 'task.create', task: { ...p.set, title: p.title, source: 'web' } });
      input.value = '';
      render();
      document.getElementById('quick')?.focus();
      toast('Записал: #' + r.task.num + ' ' + r.task.title);
    } catch (err) { fail(err); }
  };

  input.addEventListener('input', update);
  input.addEventListener('click', updateAc);
  input.addEventListener('blur', () => setTimeout(closeAc, 150));
  input.addEventListener('keydown', (e) => {
    if (acState) {
      const n = acState.items.length;
      if (e.key === 'ArrowDown' && n) { e.preventDefault(); acState.at = (acState.at + 1) % n; drawAc(); return; }
      if (e.key === 'ArrowUp' && n) { e.preventDefault(); acState.at = (acState.at - 1 + n) % n; drawAc(); return; }
      if ((e.key === 'Enter' || e.key === 'Tab') && n && !e.shiftKey && !e.ctrlKey && !e.metaKey) { e.preventDefault(); pickAc(acState.at); return; }
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeAc(); return; }
    }
    if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) setTimeout(updateAc);
    if (e.key === 'Escape') { input.value = ''; update(); input.blur(); e.stopPropagation(); return; }
    if (e.key !== 'Enter' || e.shiftKey) return;
    e.preventDefault();
    // Ctrl+Enter — Claude; несколько строк — тоже ему: одно дело из абзаца не выйдет.
    if (toClaude || e.ctrlKey || e.metaKey || input.value.trim().includes('\n')) claudeParse(input, defaults);
    else addOne();
  });

  const plusBtn = el('button', { type: 'button', class: 'q-btn plus', title: 'Записать одно дело' + (toClaude ? '' : ' (Enter)'), disabled: busy, onclick: addOne }, '+');
  const claudeBtn = el('button', { type: 'button', class: 'q-btn claude-btn' + (busy ? ' busy' : ''), disabled: busy,
    title: !S.me.claude ? 'Claude на сервере ещё не настроен'
      : toClaude ? 'Claude: принять, отклонить или поправить предложения на экране (Enter)'
        : 'Claude: поправить дела на экране или разобрать текст на новые дела (Ctrl+Enter)',
    onclick: () => claudeParse(input, defaults) }, claudeIcon());
  // Микрофон: сказанное дописывается в поле и, когда человек замолчал, уходит Claude.
  const hearing = !!(rec && S.listenQuick);
  const micBtn = el('button', { type: 'button', class: 'q-btn mic' + (hearing ? ' on' : ''), disabled: busy,
    title: hearing ? 'Хватит слушать — отдать Claude' : 'Сказать Claude голосом: что поправить в делах на экране или что завести',
    onclick: () => {
      if (hearing) { stopListening(); return; }
      const base = input.value.trim();
      S.listenQuick = true;
      S.quickDraft = base || null;
      const ok = listen((txt) => {
        const q = document.getElementById('quick');
        S.quickDraft = (base ? base + ' ' : '') + txt; // перерисовка вернёт его в поле
        if (q) { q.value = S.quickDraft; q.dispatchEvent(new Event('input')); }
      }, (txt) => {
        S.listenQuick = false;
        const q = document.getElementById('quick');
        if (q && txt && q.value.trim() && voiceAuto()) { S.quickDraft = null; claudeParse(q, defaults); } else render();
      });
      if (!ok) S.listenQuick = false;
      render();
    } }, icon('mic', 17));
  if (input.value) setTimeout(grow);
  return el('div', { class: 'quick' },
    el('div', { class: 'quick-wrap' }, el('div', { class: 'quick-box' + (busy ? ' busy' : '') + (hearing ? ' hearing' : '') }, plusBtn, input, micBtn, claudeBtn), ac),
    status, preview, help);
}

// ── Голос: распознавание браузера (Chrome — по-русски, сразу), своего сервера не нужно ──
const Speech = window.SpeechRecognition || window.webkitSpeechRecognition;
let rec = null; // одна запись на страницу
const LISTEN_START = 7000; // столько ждём, пока человек начнёт говорить
function stopListening() { if (rec) { try { rec.stop(); } catch (e) { /* уже стоит */ } } }
/** Слушать: onText — текст по ходу речи, onDone — итог, когда человек замолчал или нажал ещё раз. */
function listen(onText, onDone) {
  if (!Speech) { toast('Этот браузер не распознаёт речь — напиши текстом (в Chrome микрофон работает)'); return false; }
  stopListening();
  const r = new Speech();
  r.lang = 'ru-RU';
  r.interimResults = true;
  r.continuous = true;
  let final = '', last = '', idle;
  const wait = (ms) => { clearTimeout(idle); idle = setTimeout(() => { try { r.stop(); } catch (e) { /* стоит */ } }, ms); };
  r.onresult = (e) => {
    let interim = '';
    for (let i = e.resultIndex; i < e.results.length; i++) {
      if (e.results[i].isFinal) final += e.results[i][0].transcript; else interim += e.results[i][0].transcript;
    }
    last = (final + interim).replace(/\s+/g, ' ').trim();
    onText(last);
    wait(voicePause());
  };
  r.onerror = (e) => {
    if (e.error === 'not-allowed' || e.error === 'service-not-allowed') toast('Браузер не дал микрофон — разреши его для этой страницы');
    else if (e.error !== 'no-speech' && e.error !== 'aborted') toast('Распознавание: ' + e.error);
  };
  // Итог — до перерисовки: она пересоздаёт поля, и надиктованное в несфокусированном поле пропало бы.
  r.onend = () => { clearTimeout(idle); if (rec === r) rec = null; onDone(last); };
  rec = r;
  r.start();
  wait(LISTEN_START);
  return true;
}

// ── Задания Claude на сервере: POST отдаёт номер, ответ спрашиваем по номеру ──
async function runJob(path, body) {
  const { job } = await api(path, body);
  for (let i = 0; ; i++) {
    await new Promise((r) => setTimeout(r, i < 15 ? 800 : 2500));
    const d = await api(path + '/' + job);
    if (d.status !== 'run') return d;
    if (i > 150) throw new Error('Claude думает дольше пяти минут — всё появится само, когда он закончит');
  }
}

/** Что видит человек на этой странице — Claude видит то же: название, дела и в «Новом» — предложения (П1, П2…). */
function pageScope(defaults = {}) {
  const r = route();
  const p = r.kind === 'project' ? project(r.id) : null, h = r.kind === 'person' ? person(r.id) : null;
  const one = r.kind === 'new' && r.batch ? pendingSugs().find((s) => s.batch_ref === r.batch) : null;
  const title = p ? `${KIND[p.kind] || 'Проект'}: ${p.name}` : h ? `Человек: ${h.name}` : r.kind === 'search' ? `Поиск: ${r.q}`
    : r.kind === 'new' && r.batch ? `Новое: одна пачка — ${one?.batch_title || r.batch}`
      : (VIEWS[r.kind] || CRM_VIEWS[r.kind] || { title: 'Дела' }).title;
  const scope = { title, task_ids: [...new Set(S.order)].slice(0, 300) };
  if (r.kind === 'new') scope.suggestion_ids = S.sugOrder.slice(0, 150);
  for (const k of ['project_id', 'person_id']) if (defaults[k]) scope[k] = defaults[k];
  return scope;
}

// ── Claude: команда или надиктовка из поля наверху — правка дел на экране или новые дела ──
async function claudeParse(input, defaults) {
  const text = input.value.trim();
  if (S.parse) return;
  if (!text) { toast('Скажи или напиши Claude: новые дела или что поправить в делах на экране'); input.focus(); return; }
  const scope = pageScope(defaults);
  S.parse = { text };
  render();
  try {
    const d = await runJob('/api/ask', { text, scope });
    S.parse = null;
    for (const t of d.tasks || []) S.tasks.set(t.id, t);
    await sync().catch(() => {});
    render();
    if (d.route === 'new') claudeResult(d); else askResult(d);
  } catch (e) {
    S.quickDraft = S.parse && S.parse.text;
    S.parse = null;
    render();
    fail(e);
  }
}

// ── Микрофон у дела: сказал — Claude поправил именно это дело ────────────
function openAsk(t) {
  S.askTask = t.id;
  S.askText = '';
  render();
  const go = () => document.getElementById('ask-' + t.id);
  go()?.focus();
  listen((txt) => { S.askText = txt; const i = go(); if (i) i.value = txt; },
    (txt) => { if (S.askTask === t.id && txt && voiceAuto()) askTask(t.id, txt); else render(); });
  render();
  go()?.focus();
}
async function askTask(id, text) {
  text = (text || '').trim();
  if (!text || S.asking) return;
  stopListening();
  S.asking = { id, text };
  render();
  try {
    const d = await runJob('/api/ask', { text, scope: { title: 'Одно дело', task_ids: [id], focus: id } });
    S.asking = null;
    S.askTask = null;
    S.askText = '';
    for (const t of d.tasks || []) S.tasks.set(t.id, t);
    await sync().catch(() => {});
    render();
    askResult(d);
  } catch (e) {
    S.asking = null;
    S.askText = text;
    render();
    fail(e);
  }
}
function askBox(t) {
  const busy = S.asking && S.asking.id === t.id;
  const stop = (e) => e.stopPropagation();
  const input = el('input', { class: 'ask-input', id: 'ask-' + t.id, value: busy ? S.asking.text : S.askText || '', disabled: busy,
    placeholder: rec ? 'Говори: «на пятницу, это Наташе, первым делом»…' : 'Что сделать с делом: «на пятницу, это Наташе»' });
  input.addEventListener('click', stop);
  input.addEventListener('input', () => { S.askText = input.value; });
  input.addEventListener('keydown', (e) => {
    e.stopPropagation();
    if (e.key === 'Enter') { e.preventDefault(); askTask(t.id, input.value); }
    if (e.key === 'Escape') { stopListening(); S.askTask = null; render(); }
  });
  return el('div', { class: 'ask-inline' + (busy ? ' busy' : ''), onclick: stop },
    input,
    busy ? el('span', { class: 'ask-status' }, claudeIcon(15), 'правит…') : [
      el('button', { class: 'q-btn mic' + (rec ? ' on' : ''), type: 'button', title: rec ? 'Хватит слушать' : 'Сказать голосом',
        onclick: () => { if (rec) stopListening(); else openAsk(t); } }, icon('mic', 16)),
      el('button', { class: 'q-btn claude-btn', type: 'button', title: 'Отдать Claude (Enter)', onclick: () => askTask(t.id, input.value) }, claudeIcon(16)),
      el('button', { class: 'icon-btn', type: 'button', title: 'Закрыть (Esc)', onclick: () => { stopListening(); S.askTask = null; render(); } }, icon('x', 13))]);
}

/** Что поменялось — словами: «срок 07.10, в «Сейчас», жду Наташа». */
function describe(c) {
  const a = c.after || {}, out = [];
  if ('title' in a) out.push(`название «${a.title}»`);
  if ('due_date' in a) out.push(a.due_date ? 'срок ' + D.ddmm(a.due_date) + (a.due_time ? ' ' + a.due_time : '') : 'без срока');
  else if ('due_time' in a) out.push(a.due_time ? 'время ' + a.due_time : 'без времени');
  if ('focus_on' in a) out.push(a.focus_on ? 'в «Сейчас»' : 'из «Сейчас»');
  if ('project_id' in a) out.push(a.project_id ? 'проект ' + (project(a.project_id)?.name || '?') : 'во «Входящие»');
  if (a.deal_id) out.push('сделка ' + (S.deals.get(a.deal_id)?.name || '?'));
  if ('ball' in a || 'person_id' in a) {
    const who = 'person_id' in a ? personName(person(a.person_id)) : '';
    out.push(('ball' in a ? BALL[a.ball] : 'человек') + (who ? ' ' + who : ''));
  }
  if ('notes' in a) out.push('дописал заметку');
  if ('estimate_min' in a) out.push(a.estimate_min ? a.estimate_min + ' мин' : 'без оценки');
  if ('labels' in a) out.push(a.labels.length ? 'метки ' + a.labels.join(', ') : 'без меток');
  if (c.status) out.push({ done: 'сделано', cancelled: 'отменено', open: 'в работу' }[c.status[1]]);
  return out.join(', ');
}

const DECIDED = { create: 'заведено', close: 'закрыто', update: 'уточнено', assign: 'взято себе' };

/** Итог правки: что сделал Claude, его слова, «Вернуть всё» одним движением.
 *  В «Новом» ещё и решения по предложениям: принятое возвращается (заведённое — отменой, поправленное —
 *  как было), отклонённое — нет: оно остаётся отклонённым вместе с причиной. */
function askResult(d) {
  document.querySelectorAll('.claude-result').forEach((x) => x.remove());
  const changed = d.changed || [], made = d.tasks || [], decided = d.decided || [];
  const took = decided.filter((x) => x.decision === 'accept' && x.id);
  const dropped = decided.filter((x) => x.decision === 'reject');
  const box = el('div', { class: 'claude-result', role: 'status' });
  const close = () => box.remove();
  const undo = async () => {
    const back = [];
    for (const c of [...changed, ...took.filter((x) => !x.created)]) {
      if (Object.keys(c.before || {}).length) back.push({ op: 'task.set', id: c.id, set: c.before });
      if (c.status) back.push({ op: { open: 'task.reopen', done: 'task.done', cancelled: 'task.cancel' }[c.status[0]], id: c.id });
    }
    for (const t of [...made, ...took.filter((x) => x.created)]) back.push({ op: 'task.cancel', id: t.id });
    try { if (back.length) await ops(back); close(); render(); toast('Вернул как было'); } catch (e) { fail(e); }
  };
  const nTasks = changed.length + made.length, n = nTasks + decided.length, back = nTasks + took.length;
  const what = [decided.length ? plural(decided.length, 'предложение', 'предложения', 'предложений') : null,
    nTasks ? plural(nTasks, 'дело', 'дела', 'дел') : null].filter(Boolean).join(', ');
  // Предложение словами — как в «Новом», пока оно есть у веба; иначе — как его видел Claude.
  const sugTitle = (x) => (S.sugs.get(x.sid) ? sugText(S.sugs.get(x.sid)).title : x.what);
  box.append(...[
    el('div', { class: 'cr-head' }, claudeIcon(16), el('span', {}, n ? `Claude: ${what}` : 'Claude ничего не менял'),
      el('button', { class: 'icon-btn', title: 'Закрыть', onclick: close }, icon('x', 14))),
    d.reply ? el('div', { class: 'cr-reply' }, d.reply) : null,
    decided.map((x) => (x.decision === 'accept'
      ? el('button', { class: 'cr-task', onclick: () => x.id && openCard(x.id) },
        el('div', {}, `П${x.n} ${DECIDED[x.kind] || 'принято'}` + (x.id ? `: #${x.num} ${x.title}` : '')),
        describe(x) ? el('div', { class: 'cr-meta' }, describe(x)) : null)
      : el('div', { class: 'cr-task' }, el('div', {}, `П${x.n} отклонено: ${sugTitle(x)}`),
        x.reason ? el('div', { class: 'cr-meta' }, x.reason) : null))),
    changed.map((c) => el('button', { class: 'cr-task', onclick: () => openCard(c.id) },
      el('div', {}, `#${c.num} ${c.after?.title || c.title}`), el('div', { class: 'cr-meta' }, describe(c)))),
    made.length ? el('div', { class: 'cr-sub' }, 'Новые:') : null,
    made.map((t) => el('button', { class: 'cr-task', onclick: () => openCard(t.id) }, el('div', {}, '#' + t.num + ' ' + t.title))),
    d.errors && d.errors.length ? el('div', { class: 'cr-err' }, 'Не вышло: ' + d.errors.join('; ')) : null,
    el('div', { class: 'cr-acts' },
      back ? el('button', { onclick: undo, title: dropped.length ? 'Отклонённое остаётся отклонённым — вернуть его нельзя' : '' },
        dropped.length ? 'Вернуть принятое' : 'Вернуть всё') : null,
      el('button', { onclick: close }, 'Хорошо')),
  ].flat().filter(Boolean));
  document.body.append(box);
  if (!n && !(d.errors || []).length) setTimeout(close, 8000);
}

/** Что сделал Claude: дела (открываются карточкой), заметки, отмена одним движением. */
function claudeResult(d) {
  document.querySelectorAll('.claude-result').forEach((x) => x.remove());
  const n = d.tasks.length, m = d.notes.length;
  const box = el('div', { class: 'claude-result', role: 'status' });
  const close = () => box.remove();
  const meta = (t) => [project(t.project_id)?.name,
    t.person_id ? ((t.ball !== 'mine' ? BALL[t.ball] + ' ' : '') + personName(person(t.person_id))) : null,
    t.due_date ? dueLabel(t).text : null, t.estimate_min ? t.estimate_min + ' мин' : null].filter(Boolean).join(' · ');
  const said = [n ? plural(n, 'дело', 'дела', 'дел') : null, m ? plural(m, 'заметка', 'заметки', 'заметок') : null].filter(Boolean).join(' и ');
  box.append(...[
    el('div', { class: 'cr-head' }, claudeIcon(16), el('span', {}, said ? 'Claude записал ' + said : 'Claude не нашёл тут дел'),
      el('button', { class: 'icon-btn', title: 'Закрыть', onclick: close }, icon('x', 14))),
    d.tasks.map((t) => el('button', { class: 'cr-task', onclick: () => openCard(t.id) },
      el('div', {}, '#' + t.num + ' ' + t.title), meta(t) ? el('div', { class: 'cr-meta' }, meta(t)) : null)),
    m ? el('div', { class: 'cr-sub' }, 'В хронологию:') : null,
    d.notes.map((x) => el('div', { class: 'cr-note' }, x.summary,
      project(x.project_id) ? el('span', { class: 'cr-meta' }, ' · ' + project(x.project_id).name) : null)),
    d.errors && d.errors.length ? el('div', { class: 'cr-err' }, 'Не записались: ' + d.errors.join('; ')) : null,
    el('div', { class: 'cr-acts' },
      n ? el('button', { onclick: async () => {
        try {
          await ops(d.tasks.map((t) => ({ op: 'task.cancel', id: t.id })));
          close(); render(); toast('Отменил ' + plural(n, 'дело', 'дела', 'дел') + (m ? '; заметки остались в хронологии' : ''));
        } catch (e) { fail(e); }
      } }, 'Отменить дела') : null,
      el('button', { onclick: close }, 'Хорошо'))].flat().filter(Boolean));
  document.body.append(box);
}

// ── Проект, человек, поиск, «Новое», «Неделя» ───────────────────────────
function renderProject(id) {
  const p = project(id);
  if (!p) return [head('Проект'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Нет такого проекта или он не виден.'))];
  const deals = [...S.deals.values()].filter((d) => d.project_id === id).sort((a, b) => (a.stage === 'archive') - (b.stage === 'archive') || a.name.localeCompare(b.name, 'ru'));
  const [picker, g] = groupPicker('project', 'date', GROUPS.filter(([v]) => v !== 'project'));
  let items = all().filter((t) => t.project_id === id && (S.showDone || isOpen(t)));
  if (S.dealFilter) items = items.filter((t) => t.deal_id === S.dealFilter);
  const open = items.filter(isOpen);
  const fav = S.favs.includes(id);
  const star = el('button', { class: 'star' + (fav ? ' on' : ''), title: fav ? 'Убрать из избранного' : 'В избранное',
    onclick: () => { S.favs = fav ? S.favs.filter((x) => x !== id) : [...S.favs, id]; LS.set('favs', S.favs); render(); } }, fav ? '★' : '☆');
  const sub = [KIND[p.kind] || p.kind, plural(open.length, 'открытое', 'открытых', 'открытых')];
  const late = open.filter(isLate).length;
  if (late) sub.push(el('span', { class: 'late' }, 'просрочено: ' + late));
  if (p.aliases && p.aliases.length) sub.push('ещё зовут: ' + p.aliases.join(', '));
  const dealBox = deals.length ? el('div', { class: 'deals' }, deals.map((d) => el('div', {
    class: 'deal' + (S.dealFilter === d.id ? ' on' : ''), title: 'Показать только дела этой сделки',
    onclick: () => { S.dealFilter = S.dealFilter === d.id ? null : d.id; render(); },
  }, el('div', {}, d.name), el('div', { class: 'stage' }, STAGE[d.stage] || d.stage, d.fee_kop ? ' · fee ' + Math.round(d.fee_kop / 100).toLocaleString('ru') + ' ₽' : ''),
  d.next_step ? el('div', { class: 'next' }, d.next_step.length > 110 ? d.next_step.slice(0, 110) + '…' : d.next_step) : null))) : null;
  if (p.kind === 'client' && crmOn()) {
    const c = clientBlock(p);
    sub.push(...c.info);
    return [head(p.name, sub, star),
      el('div', { class: 'body' }, c.dealBox, c.peopleBox, c.tabs,
        c.timeline || [el('div', { class: 'toolbar' }, picker, doneToggle()),
          quickAdd({ project_id: id, ...(S.dealFilter ? { deal_id: S.dealFilter } : {}) }),
          items.length ? grouped(items, g) : el('div', { class: 'empty' }, 'Дел нет. Следующий шаг — в строке выше.')])];
  }
  return [head(p.name, sub, star),
    el('div', { class: 'body' }, dealBox, el('div', { class: 'toolbar' }, picker, doneToggle()),
      quickAdd({ project_id: id, ...(S.dealFilter ? { deal_id: S.dealFilter } : {}) }),
      items.length ? grouped(items, g) : el('div', { class: 'empty' }, 'Дел нет. Следующий шаг — в строке выше.'))];
}

function renderPerson(id) {
  const p = person(id);
  if (!p) return [head('Человек'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Нет такого человека или он не виден.'))];
  const open = all().filter((t) => isOpen(t));
  const secs = [
    ['Повестка с ним', open.filter((t) => t.ball === 'agenda' && t.person_id === id)],
    ['Жду от него', open.filter((t) => t.ball === 'waiting' && t.person_id === id)],
    ['Его просьбы ко мне', open.filter((t) => t.requested_by === id)],
    ['Моё о нём', open.filter((t) => t.ball === 'mine' && t.person_id === id)],
  ];
  const info = [];
  const row = (k, v) => { if (v) info.push(el('span', {}, k), el('span', {}, v)); };
  row('Роль', p.role);
  const org = p.org_id ? [...S.projects.values()].find((x) => x.org_id === p.org_id) : null;
  if (org) info.push(el('span', {}, 'Клиент'), el('span', {}, el('a', { class: 'link', href: '#/p/' + org.id }, org.name)));
  row('Телефон', (p.phones || []).join(', '));
  row('Почта', (p.emails || []).join(', '));
  row('Telegram', p.telegram_username ? '@' + p.telegram_username : null);
  row('День рождения', p.birth_day ? `${p.birth_day} ${MONTHS[p.birth_month - 1]}` + (p.birth_year ? ' ' + p.birth_year : '') : null);
  row('Как часто', { month: 'раз в месяц', quarter: 'раз в 2–3 месяца', year: 'раз в год', none: 'не видимся' }[p.cadence]);
  row('Ищет', p.seeks); row('Предлагает', p.offers); row('Характер', p.traits); row('Откуда', p.source); row('Заметка', p.note);
  return [head(p.name, p.short && p.short !== p.name ? ['в задачах — ' + p.short] : null),
    el('div', { class: 'body' }, info.length ? el('div', { class: 'person-info' }, info) : null,
      quickAdd({ person_id: id }),
      secs.map(([t, items]) => (items.length ? groupBox(t, items.sort(sortTasks)) : null)),
      secs.every(([, i]) => !i.length) ? el('div', { class: 'empty' }, 'Открытых дел с ним нет.') : null,
      crmOn() && !p.user_id ? personCrmBlock(p) : null)];
}

function renderSearch(q) {
  const words = norm(q).split(/\s+/).filter(Boolean);
  const items = words.length ? all().filter((t) => {
    const hay = norm(t.title + ' ' + (t.notes || '') + ' ' + (project(t.project_id)?.name || '') + ' ' + personName(person(t.person_id)));
    return words.every((w) => hay.includes(w)) && (S.showDone || isOpen(t));
  }) : [];
  return [head('Поиск: ' + q, [plural(items.length, 'дело', 'дела', 'дел')]),
    el('div', { class: 'body' }, el('div', { class: 'toolbar' }, doneToggle()), quickAdd({}), items.length ? grouped(items, 'project') : el('div', { class: 'empty' }, 'Ничего не нашлось.'))];
}

function renderWeek() {
  const m = openMine();
  const now = Date.now();
  const stale = m.filter((t) => (t.due_date && D.diff(S.today, t.due_date) > 14) || (now - Date.parse(t.updated_at)) > 21 * 86400e3);
  const waitStale = m.filter((t) => t.ball === 'waiting' && t.waiting_since && D.diff(S.today, t.waiting_since) > 7 && (!t.nudge_on || t.nudge_on < S.today));
  const busy = new Set(all().filter((t) => isOpen(t) && t.ball === 'mine' && t.project_id).map((t) => t.project_id));
  const noStep = [...S.projects.values()].filter((p) => !p.archived_at && p.owner_id === S.me.user && ['paid', 'potential'].includes(p.money_default) && !busy.has(p.id));
  return [head('Неделя', ['раз в неделю: что протухло, кто молчит, где нет следующего шага']),
    el('div', { class: 'body' }, quickAdd({}),
      stale.length ? groupBox('Протухшее — закрыть, перенести или отпустить', stale.sort(sortTasks), { late: true }) : null,
      waitStale.length ? groupBox('Жду без движения больше недели', waitStale.sort(sortTasks)) : null,
      noStep.length ? el('div', { class: 'group' }, el('h2', {}, 'Проекты в работе без моего следующего шага', el('span', { class: 'n' }, noStep.length)),
        noStep.map((p) => navItem('#/p/' + p.id, dot(p.id), p.name, null, false))) : null,
      !stale.length && !waitStale.length && !noStep.length ? el('div', { class: 'empty' }, 'Чисто. Неделя разобрана.') : null)];
}

/** Что предлагает автоматика — словами: завести, закрыть или поправить дело. */
function sugText(s) {
  const p = s.payload || {};
  if (s.kind === 'create') {
    return { title: p.title || s.quote || '',
      hint: [p.project_name, p.person_name, p.ball !== 'mine' ? BALL[p.ball] : null, p.due_date && 'срок ' + D.ddmm(p.due_date)].filter(Boolean).join(' · ') };
  }
  const t = S.tasks.get(s.task_id);
  const ref = t ? `#${t.num} ${t.title}` : 'дело не видно';
  if (s.kind === 'close') return { title: 'Закрыть: ' + ref, hint: t?.project_name || '' };
  if (s.kind === 'assign') return { title: 'Взять себе: ' + ref, hint: t?.project_name || '' };
  // Уточнение: что поменяется в деле; подробности (note) при принятии лягут комментарием.
  const what = [p.title ? `название: «${p.title}»` : null,
    p.due_date ? 'срок ' + (t?.due_date ? D.ddmm(t.due_date) + ' → ' : '') + D.ddmm(p.due_date) : null,
    p.ball ? 'мяч: ' + BALL[p.ball] + (p.person_name ? ' ' + p.person_name : '') : (p.person_name ? 'человек: ' + p.person_name : null)]
    .filter(Boolean).join(' · ');
  return { title: 'Уточнить: ' + ref, hint: what, add: p.note || null };
}
// Пачки — по дате встречи, свежие сверху; без даты — по времени появления.
const sugAt = (s) => (s.payload && s.payload.meeting_at) || s.created_at.slice(0, 10);

// Пачка — одна встреча или одно окно дайджеста; значок — откуда она.
const SRC = { meeting: ['mic', '#e57bd1'], telegram: ['send', '#38bdf8'], userbot: ['send', '#38bdf8'], mcp: ['chat', '#d97757'] };

/** «Новое» пачками; batch — одна пачка по ссылке из уведомления Telegram. */
function renderNew(batch) {
  const all_ = pendingSugs().sort((a, b) => a.created_at.localeCompare(b.created_at));
  const list = batch ? all_.filter((s) => s.batch_ref === batch) : all_;
  const batches = new Map();
  for (const s of list) {
    const key = s.batch_ref || 'one:' + s.id;
    if (!batches.has(key)) batches.set(key, { title: s.batch_title, ref: s.source_ref, src: s.source, at: '', items: [] });
    const b = batches.get(key);
    b.items.push(s);
    if (sugAt(s) > b.at) b.at = sugAt(s);
  }
  const box = el('div', { class: 'body' });
  if (batch) box.append(el('div', { class: 'toolbar' }, el('a', { class: 'chip-btn', href: '#/new' }, `Всё «Новое»: ${all_.length}`)));
  // Строка Claude: «первое прими, срок пятница; второе не надо» — П-номера ниже те же, что видит он.
  box.append(quickAdd({}, list.length ? { toClaude: true, placeholder: 'Claude: «первое прими, срок пятница; второе не надо — делает Наташа»' } : {}));
  if (batch && !list.length) box.append(el('div', { class: 'empty' }, 'Эта пачка уже разобрана.'));
  else if (!list.length) box.append(el('div', { class: 'empty' }, 'Новых предложений нет.'));
  for (const b of [...batches.values()].sort((x, y) => y.at.localeCompare(x.at))) {
    const ids = b.items.map((s) => s.id);
    const [ic, col] = SRC[b.src] || ['inbox-in', VIEWS.new.color];
    box.append(el('div', { class: 'group' },
      el('h2', {}, tint(icon(ic, 14), col), b.title || 'Без пачки', el('span', { class: 'n' }, b.items.length),
        b.ref && /^https:\/\//.test(b.ref) ? el('a', { class: 'src', href: b.ref, target: '_blank', rel: 'noopener noreferrer' }, 'встреча') : null,
        ids.length > 1 ? el('span', { class: 'act' },
          el('button', { class: 'chip-btn', onclick: () => decide(ids, 'accept') }, 'Принять все'), ' ',
          el('button', { class: 'chip-btn', onclick: () => decide(ids, 'reject') }, 'Отклонить все')) : null),
      b.items.map((s) => {
        const { title, hint, add } = sugText(s);
        S.sugOrder.push(s.id);
        return el('div', { class: 'sug' + (s.kind !== 'create' ? ' ' + s.kind : '') },
          el('div', { class: 'main' }, el('div', {}, el('span', { class: 'sug-n', title: 'Номер для Claude: «П' + S.sugOrder.length + ' прими»' }, 'П' + S.sugOrder.length), title),
            hint ? el('div', { class: 'hint' }, hint) : null,
            add ? el('div', { class: 'add' }, add) : null,
            s.quote && s.quote !== title ? el('div', { class: 'hint' }, '«' + s.quote.slice(0, 200) + '»') : null),
          el('div', { class: 'acts' },
            el('button', { class: 'btn small ok', onclick: () => decide([s.id], 'accept') }, 'Принять'),
            s.kind === 'create' ? el('button', { class: 'btn small', onclick: () => { S.draft = s; S.cardId = null; render(); } }, 'Поправить') : null,
            el('button', { class: 'btn small bad', onclick: () => { const r = prompt(s.kind === 'create' ? 'Почему не дело? (можно пусто)' : 'Почему нет? (можно пусто)'); if (r !== null) decide([s.id], 'reject', r || null); } }, 'Отклонить')));
      })));
  }
  // Очевидное автоматика закрывает сама — тут видно, что и почему, и можно вернуть.
  const auto = batch ? autoClosed(365).filter((s) => s.batch_ref === batch) : autoClosed(7);
  if (auto.length) {
    box.append(el('div', { class: 'group' },
      el('h2', {}, tint(icon('check', 14), 'var(--ok)'), 'Закрыто само', el('span', { class: 'n' }, auto.length),
        el('span', { class: 'gsub' }, 'за неделю: очевидное из встреч и Telegram')),
      auto.map((s) => {
        const t = S.tasks.get(s.task_id);
        if (t) S.order.push(t.id); // закрытое само — тоже дело на экране: «верни акт» Claude поймёт
        const reopen = async () => {
          try { await op1({ op: 'task.reopen', id: t.id }); toast('Вернул в работу: ' + t.title); render(); } catch (e) { fail(e); }
        };
        return el('div', { class: 'sug auto' },
          el('div', { class: 'main' }, el('div', {}, t ? `#${t.num} ${t.title}` : 'дело не видно'),
            el('div', { class: 'hint' }, [s.batch_title, D.ddmm(s.decided_at.slice(0, 10))].filter(Boolean).join(' · ')),
            s.quote ? el('div', { class: 'hint' }, '«' + s.quote.slice(0, 200) + '»') : null),
          el('div', { class: 'acts' }, t && t.status === 'done' ? el('button', { class: 'btn small', onclick: reopen }, 'Вернуть')
            : el('span', { class: 'faint' }, t && t.status === 'open' ? 'вернул в работу' : '')));
      })));
  }
  return [head('Новое', ['что предложила автоматика: встречи, Telegram — дела появятся, когда примешь; очевидные закрытия проходят сами']), box];
}

// ── Настройки: Claude (у человека на сервере), голос (на этом устройстве), траты (владельцу) ──
const voiceAuto = () => LS.get('voiceAuto', true);
const voicePause = () => LS.get('voicePause', 2500);
const MODEL_INFO = {
  sonnet: ['Sonnet 5.5', 'дешевле: 3–5 с, около 0,4 цента за команду'],
  opus: ['Opus 5.5', 'умнее в запутанных командах: 4–6 с, около 0,7 цента за команду'],
};
const EFFORT_INFO = {
  low: ['Быстро', 'обычные правки: сроки, люди, проекты'],
  medium: ['Вдумчиво', 'много дел сразу или сложная команда'],
  high: ['Глубоко', 'редко нужно: заметно дольше и дороже'],
};
const usd = (x) => '$' + (x || 0).toFixed(x >= 10 ? 0 : 2).replace('.', ',');

function renderSettings() {
  const v = crmGet('/api/settings', 10000);
  if (!v) return [head('Настройки'), el('div', { class: 'body' }, loading())];
  const s = { ...v.default, ...(v.settings || {}) };
  const save = async (patch, what) => {
    try {
      await op1({ op: 'user.settings', settings: patch });
      S.me.settings = { ...(S.me.settings || {}), ...patch };
      crmDirty(); toast('Сохранил: ' + what); render();
    } catch (e) { fail(e); }
  };
  const choices = (key, info, cur, local) => el('div', { class: 'choices' }, Object.entries(info).map(([val, [title, hint]]) =>
    el('button', { class: 'choice' + (String(cur) === String(val) ? ' on' : ''), onclick: () => {
      if (String(cur) === String(val)) return;
      if (local) { LS.set(key, local(val)); toast('Сохранил: ' + title); render(); } else save({ [key]: val }, title);
    } }, el('b', {}, title), el('span', {}, hint))));
  const auto = el('input', { type: 'checkbox' });
  auto.checked = voiceAuto();
  auto.addEventListener('change', () => { LS.set('voiceAuto', auto.checked); render(); });
  const cost = v.cost;
  const kinds = { ask: 'правка словами', parse: 'разбор надиктовки' };
  return [head('Настройки', ['Claude в Делах, голос и траты']),
    el('div', { class: 'body settings' },
      el('div', { class: 'group' }, el('h2', {}, tint(claudeIcon(14), 'var(--claude)'), 'Claude правит дела словами'),
        el('div', { class: 'hint-line' }, 'Микрофон у дела, звёздочка и микрофон в поле наверху. Новые дела из надиктовки всегда разбирает Opus 5.5.'),
        v.claude ? null : el('div', { class: 'cr-err' }, 'Claude на сервере не настроен — нет ключа.'),
        el('div', { class: 'set-label' }, 'Модель'), choices('claude_model', MODEL_INFO, s.claude_model),
        el('div', { class: 'set-label' }, 'Глубина'), choices('claude_effort', EFFORT_INFO, s.claude_effort)),
      el('div', { class: 'group' }, el('h2', {}, tint(icon('mic', 14), 'var(--bad)'), 'Голос', el('span', { class: 'gsub' }, 'на этом устройстве')),
        el('label', { class: 'set-check' }, auto, 'Отдавать Claude сам, когда замолчал', el('span', { class: 'faint' }, ' — иначе текст ждёт Enter или звёздочку')),
        el('div', { class: 'set-label' }, 'Пауза, после которой команда сказана'),
        choices('voicePause', { 1500: ['1,5 с', 'говорю коротко'], 2500: ['2,5 с', 'обычно'], 4000: ['4 с', 'думаю на ходу'] }, voicePause(), Number),
        el('div', { class: 'hint-line' }, 'Речь распознаёт браузер (Chrome — и на телефоне). На компьютере можно диктовать и Wispr Flow прямо в поле.')),
      el('div', { class: 'group' }, el('h2', {}, tint(icon('flame', 14), 'var(--now)'), 'Путь: очки, серия, уровень'),
        el('div', { class: 'hint-line' }, 'Очки — только за доведённое: закрыл, отменил ненужное, разобрал «Новое». Карточка пути — вверху боковой панели, подробно — «Статистика».'),
        choices('game_theme', { torah: ['Тора', '42 стоянки странствий, серия — манна; суббота не рвёт'], greek: ['Греция', 'подвиги Геракла и путь Одиссея, серия — огонь Прометея'] }, s.game_theme || 'torah')),
      cost ? el('div', { class: 'group' }, el('h2', {}, tint(icon('coin', 14), '#45c07a'), 'Траты Claude в Делах'),
        el('div', { class: 'tiles' },
          el('div', { class: 'tile' }, el('div', { class: 'tl' }, 'Сегодня'), el('div', { class: 'tv' }, usd(cost.today))),
          el('div', { class: 'tile' }, el('div', { class: 'tl' }, 'За 30 дней'), el('div', { class: 'tv' }, usd(cost.month))),
          el('div', { class: 'tile' }, el('div', { class: 'tl' }, 'Всего с 05.10'), el('div', { class: 'tv' }, usd(cost.total)))),
        el('div', { class: 'hint-line' }, [...Object.entries(cost.by || {}).map(([k, x]) => `${kinds[k] || k} ${usd(x)}`),
          ...Object.entries(cost.models || {}).map(([k, x]) => `${k.replace('claude-', '').replace(/-(\d)-(\d)$/, ' $1.$2')} ${usd(x)}`)].join(' · ') || 'Пока ничего.')) : null)];
}

async function decide(ids, decision, reason, set) {
  try {
    await ops(ids.map((id) => ({ op: 'suggestion.decide', id, decision, reason, set })));
    toast((decision === 'accept' ? 'Принято: ' : 'Отклонено: ') + ids.length);
    S.draft = null;
    render();
  } catch (e) { fail(e); render(); }
}

// ── Карточка дела (справа; на телефоне — лист снизу) ─────────────────────
function openCard(id) { S.cardId = id; S.draft = null; S.dealId = null; render(); loadCardExtras(id); }
const extras = new Map(); // комментарии и журнал — по требованию
async function loadCardExtras(id) {
  const t = S.tasks.get(id);
  if (!t) return;
  try {
    const c = await api('/api/task/' + t.num);
    extras.set(id, c);
    if (S.cardId === id) render();
  } catch (e) { /* без комментариев карточка тоже работает */ }
}

function renderCard() {
  const close = () => { S.cardId = null; S.draft = null; render(); };
  const sug = S.draft;
  const t = sug ? null : S.tasks.get(S.cardId);
  if (!t && !sug) return el('section', { class: 'card-pane' });
  if (sug && !sug._src) {
    sug._src = { ...(sug.payload || {}), ball: (sug.payload || {}).ball || 'mine' };
    const n = norm(sug._src.project_name);
    const hit = n && [...S.projects.values()].find((p) => norm(p.name) === n || (p.aliases || []).some((a) => norm(a) === n));
    if (hit) sug._src.project_id = hit.id;
  }
  const src = t || sug._src;
  // Поле сохраняется само при изменении (как в Vikunja); у предложения — по кнопке «Принять».
  const save = (field, value) => { if (t) setFields([t.id], { [field]: value }); else src[field] = value; };
  const sel = (field, options, value) => {
    const s = el('select', {}, options.map(([v, label]) => el('option', { value: v, selected: String(value ?? '') === String(v) }, label)));
    s.addEventListener('change', () => save(field, s.value || null));
    return s;
  };
  const live = [...S.projects.values()].filter((p) => !p.archived_at || p.id === src.project_id).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  const projSel = el('select', {}, [['', 'Входящие'], ...live.map((p) => [p.id, p.name])].map(([v, l]) => el('option', { value: v, selected: (src.project_id || '') === v }, l)));
  projSel.addEventListener('change', () => { if (t) setFields([t.id], { project_id: projSel.value || null, deal_id: null }); else { src.project_id = projSel.value || null; src.deal_id = null; render(); } });
  const deals = [...S.deals.values()].filter((d) => d.project_id && d.project_id === src.project_id);
  const people = [...S.people.values()].filter((p) => !p.archived_at).sort((a, b) => personName(a).localeCompare(personName(b), 'ru'));
  const date = (field) => {
    const i = el('input', { type: 'date', value: src[field] || '' });
    i.addEventListener('change', () => save(field, i.value || null));
    return i;
  };
  const num = el('input', { type: 'number', min: 1, value: src.estimate_min || '' });
  num.addEventListener('change', () => save('estimate_min', num.value ? +num.value : null));
  const labels = el('input', { value: (src.labels || []).join(', '), placeholder: 'звонок, …' });
  labels.addEventListener('change', () => save('labels', labels.value.split(',').map((x) => x.trim()).filter(Boolean)));
  const title = el('textarea', { class: 'title-edit', rows: 2 }, src.title || '');
  title.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); title.blur(); } });
  title.addEventListener('change', () => title.value.trim() && save('title', title.value.trim()));
  const notes = el('textarea', { class: 'notes', placeholder: 'Заметки' }, src.notes || '');
  notes.addEventListener('change', () => save('notes', notes.value.trim() || null));
  const quickDates = el('div', { class: 'quick-dates' },
    [['Сегодня', S.today], ['Завтра', D.add(S.today, 1)], ['Пн', D.next(0)], ['+неделя', D.add(S.today, 7)], ['Нет', null]].map(([l, d]) =>
      el('button', { class: 'chip-btn' + ((src.due_date || null) === d ? ' on' : ''), onclick: () => { save('due_date', d); if (!t) render(); } }, l)));
  const chk = (field, label, on, value) => {
    const c = el('input', { type: 'checkbox' });
    c.checked = !!on;
    c.addEventListener('change', () => save(field, c.checked ? value : (field === 'want' ? false : null)));
    return el('label', {}, c, label);
  };

  const props = el('div', { class: 'props' },
    el('span', {}, 'Проект'), projSel,
    deals.length ? el('span', {}, 'Сделка') : null, deals.length ? sel('deal_id', [['', '—'], ...deals.map((d) => [d.id, d.name])], src.deal_id) : null,
    el('span', {}, 'Мяч'), sel('ball', Object.entries(BALL), src.ball),
    el('span', {}, 'Человек'), sel('person_id', [['', '—'], ...people.map((p) => [p.id, p.short && p.short !== p.name ? `${p.short} — ${p.name}` : p.name])], src.person_id),
    el('span', {}, 'Срок'), date('due_date'), el('span', {}), quickDates,
    el('span', {}, 'Напомнить ему'), date('nudge_on'),
    el('span', {}, 'Минут'), num,
    el('span', {}, 'Метки'), labels);

  const cp = project(src.project_id);
  const crumb = cp ? el('a', { class: 'crumb', href: '#/p/' + cp.id }, dot(cp.id), cp.name)
    : el('span', { class: 'crumb' }, tint(icon('tray', 13), VIEWS.inbox.color), 'Входящие');
  const card = el('div', { class: 'card' },
    el('div', { class: 'top' }, sug ? el('span', { class: 'crumb' }, tint(icon('inbox-in', 13), VIEWS.new.color), 'Предложение') : crumb,
      t ? el('span', { class: 'num' }, '#' + t.num + (t.status !== 'open' ? ' · ' + (t.status === 'done' ? 'сделано' : 'отменено') : '')) : null,
      el('button', { class: 'icon-btn', title: 'Закрыть (Esc)', onclick: close }, icon('x', 16))),
    title, props,
    el('div', { class: 'checks' }, chk('focus_on', 'Сейчас — на сегодня', isNow(src), S.today), chk('want', 'Хочу сам', src.want, true)),
    notes);
  const btns = el('div', { class: 'btns' });
  if (t) {
    btns.append(isOpen(t)
      ? el('button', { class: 'btn main', onclick: () => toggleDone([t]) }, icon('check', 15), 'Сделано')
      : el('button', { class: 'btn', onclick: () => toggleDone([t]) }, 'Вернуть в работу'));
    if (isOpen(t)) btns.append(el('button', { class: 'btn bad', onclick: async () => { if (confirm('Отменить дело? Оно останется в журнале.')) { try { await op1({ op: 'task.cancel', id: t.id }); render(); } catch (e) { fail(e); } } } }, 'Отменить дело'));
    const who = person(t.person_id); // проект — ссылкой наверху карточки
    if (who) btns.append(el('a', { class: 'btn', href: '#/h/' + who.id }, avatar(who), personName(who) + ' →'));
  } else {
    btns.append(el('button', {
      class: 'btn main',
      onclick: () => {
        const set = {};
        for (const k of ['notes', 'project_id', 'deal_id', 'ball', 'person_id', 'due_date', 'nudge_on', 'estimate_min', 'money', 'labels', 'focus_on', 'want']) {
          if (src[k] !== undefined && src[k] !== null && src[k] !== '') set[k] = src[k];
        }
        set.title = title.value.trim() || src.title;
        if (notes.value.trim()) set.notes = notes.value.trim();
        decide([sug.id], 'accept', null, set);
      },
    }, 'Принять'), el('button', { class: 'btn', onclick: close }, 'Закрыть'));
  }
  card.append(btns);
  if (t) {
    const x = extras.get(t.id);
    const add = el('input', { placeholder: 'Комментарий…' });
    const send = async () => {
      if (!add.value.trim()) return;
      try { await op1({ op: 'comment.add', comment: { task_id: t.id, text: add.value.trim() } }); add.value = ''; loadCardExtras(t.id); } catch (e) { fail(e); }
    };
    add.addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });
    card.append(el('h3', {}, 'Комментарии'),
      el('div', {}, (x ? x.comments : []).map((c) => el('div', { class: 'comment' }, el('div', { class: 'who' }, `${c.author_id} · ${c.created_at.slice(0, 16).replace('T', ' ')}`), c.text))),
      el('div', { class: 'add-comment' }, add, el('button', { class: 'btn small', onclick: send }, 'Добавить')));
    if (x) {
      card.append(el('details', {}, el('summary', {}, 'Журнал правок'), x.history.map((h) =>
        el('div', { class: 'hist' }, `${h.at.slice(0, 16).replace('T', ' ')} · ${h.actor} (${h.via}) · ${h.op === 'update' ? Object.keys(h.after || {}).join(', ') : h.op}`))));
    }
  }
  return el('section', { class: 'card-pane', 'data-key': 'task:' + (S.cardId || 'draft') }, card);
}

// ── CRM: воронка, клиенты, связи, деньги, карточка сделки ───────────────
// Сделки, люди, проекты и оплаты уже в памяти (синк); то, что считает сервер
// (последний контакт, следующее дело, время из Засечки, хронология, журнал), —
// видами /api/view/<имя> по требованию, с коротким кэшем.
const OUTCOME = { won: 'выиграли', lost: 'проиграли', paused: 'заморожено' };
const FEE_KIND = { fixed: 'фикс', retainer: 'ретейнер', success: 'success fee', hourly: 'почасово', mixed: 'смешанная' };
const PAY_KIND = { advance: 'аванс', stage: 'этап', final: 'финал', success: 'success fee', retainer: 'ретейнер', extra: 'допработы' };
const DEAL_TYPES = ['M&A', 'Банковский advisory', 'Управленка', 'Косткаттинг', 'Финансирование', 'Сопровождение'];
const IKIND = { call: 'звонок', meeting: 'встреча', zoom: 'Zoom', telegram: 'Telegram', email: 'почта', whatsapp: 'WhatsApp', note: 'заметка', other: 'другое' };
const CADENCE = { month: 'раз в месяц', quarter: 'раз в квартал', year: 'раз в год', none: 'не видимся' };
const OPEN_STAGES = ['lead', 'proposal', 'mandate', 'active', 'closing'];
const CRM_VIEWS = {
  crm: { title: 'Воронка', icon: 'funnel', color: '#f39a4a' },
  clients: { title: 'Клиенты', icon: 'building', color: '#2fc5c0' },
  ties: { title: 'Связи', icon: 'link', color: '#e57bd1' },
  money: { title: 'Деньги', icon: 'coin', color: '#45c07a', money: true },
};
Object.assign(ICONS, {
  funnel: 'M3 5h18l-7 8v6l-4 2v-8z',
  building: 'M4 21V5l8-3 8 3v16M9 21v-4h6v4M8 8h2M14 8h2M8 12h2M14 12h2',
  link: 'M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1',
  coin: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9 9h4.5a2 2 0 0 1 0 4H9v4M8 15h6',
  clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2',
  plus: 'M12 5v14M5 12h14',
});
const narrow = () => window.matchMedia('(max-width: 900px)').matches; // телефон: пустые колонки воронки не показываем
const crmOn = () => S.me && (S.me.role === 'owner' || S.me.clients !== 'own');
const rub = (kop) => (kop ? Math.round(kop / 100).toLocaleString('ru') + ' ₽' : '');
const rubShort = (kop) => {
  if (!kop) return '0';
  const r = kop / 100;
  return r >= 1e6 ? (r / 1e6).toFixed(r >= 1e7 ? 0 : 1).replace('.', ',') + ' млн' : Math.round(r / 1000) + ' тыс';
};
const hrs = (m) => (!m ? '' : m >= 60 ? `${Math.floor(m / 60)} ч ${String(m % 60).padStart(2, '0')} мин` : m + ' мин');
const ago = (iso) => {
  if (!iso) return null;
  const n = D.diff(S.today, iso.slice(0, 10));
  return n <= 0 ? 'сегодня' : n === 1 ? 'вчера' : n < 30 ? n + ' дн. назад' : D.ddmm(iso.slice(0, 10));
};
const stageWord = (d) => (d.stage === 'archive' && d.outcome ? OUTCOME[d.outcome] : STAGE[d.stage] || d.stage);
const pName = (id) => personName(person(id)) || '?';
/** Команда ЗФ — пользователи и те, кто уже ведёт сделки или в их командах. */
function teamPeople() {
  const ids = new Set([...S.people.values()].filter((p) => p.user_id).map((p) => p.id));
  for (const d of S.deals.values()) { if (d.lead_person_id) ids.add(d.lead_person_id); (d.team_ids || []).forEach((x) => ids.add(x)); }
  return [...ids].map(person).filter(Boolean).sort((a, b) => personName(a).localeCompare(personName(b), 'ru'));
}

// Кэш видов сервера: ключ — путь. Пустой ответ — «грузится», дальше рисуем ещё раз.
const crmCache = new Map();
function crmGet(path, maxAge = 30000) {
  const c = crmCache.get(path);
  if (c && c.data && Date.now() - c.at < maxAge) return c.data;
  if (!c || !c.loading) {
    crmCache.set(path, { ...(c || {}), loading: true });
    api(path).then((d) => { crmCache.set(path, { data: d, at: Date.now() }); render(); })
      .catch((e) => { crmCache.set(path, { data: c && c.data, at: Date.now(), error: e.message }); render(); });
  }
  return c ? c.data : null;
}
const crmDirty = () => { for (const c of crmCache.values()) c.at = 0; };
const loading = () => el('div', { class: 'empty loading' }, 'Загружаю…');

async function dealOp(op) {
  try { const r = await op1(op); crmDirty(); render(); return r; } catch (e) { fail(e); render(); return null; }
}
const setDeal = (id, set) => dealOp({ op: 'deal.set', id, set });

function openDeal(id) { S.dealId = id; S.cardId = null; S.draft = null; render(); }
async function newDeal(projectId) {
  const name = prompt('Название сделки («Клиент: тема»)', (project(projectId)?.name || '') + ': ');
  if (!name || !name.trim()) return;
  const r = await dealOp({ op: 'deal.create', data: { project_id: projectId, name: name.trim(), stage: 'lead' } });
  if (r && r.row) openDeal(r.row.id);
}

// ── Воронка ─────────────────────────────────────────────────────────────
function dealTile(d) {
  const meta = [];
  if (d.fee_kop) meta.push(el('span', { class: 'money' }, rubShort(d.fee_kop) + (d.p_eff != null && ['lead', 'proposal'].includes(d.stage) ? ` · ${d.p_eff}%` : '')));
  if (d.lead_person_id) meta.push(el('span', {}, pName(d.lead_person_id)));
  if (d.minutes_30) meta.push(el('span', { title: 'время из Засечки за 30 дней' }, hrs(d.minutes_30)));
  const next = d.next_task
    ? el('div', { class: 'next' }, `#${d.next_task.num} ${d.next_task.title}` + (d.next_task.due_date ? ' · ' + D.ddmm(d.next_task.due_date) : ''))
    : (d.stage !== 'archive' ? el('div', { class: 'next none' }, 'нет следующего дела') : null);
  const quiet = d.stage !== 'archive' && d.quiet_days > 30 ? el('span', { class: 'quiet' }, `тишина ${d.quiet_days} дн.`) : null;
  return el('div', { class: 'dtile' + (S.dealId === d.id ? ' on' : '') + (d.stale ? ' stale' : ''), onclick: () => openDeal(d.id) },
    el('div', { class: 'dname' }, d.name),
    el('div', { class: 'dclient' }, d.project_name, d.deal_type ? ' · ' + d.deal_type : ''),
    meta.length || quiet ? el('div', { class: 'dmeta' }, meta, quiet) : null, next,
    d.stage === 'archive' ? el('div', { class: 'dmeta' }, el('span', { class: 'out-' + (d.outcome || 'none') }, stageWord(d)),
      d.closed_on ? el('span', {}, D.ddmm(d.closed_on)) : null, d.lost_reason ? el('span', {}, d.lost_reason) : null) : null);
}

function renderPipeline() {
  const v = crmGet('/api/view/pipeline');
  if (!v) return [head('Воронка'), el('div', { class: 'body' }, loading())];
  const mine = S.me.person_id || [...S.people.values()].find((p) => p.user_id === S.me.user)?.id;
  let deals = v.deals;
  if (S.crmMine && mine) deals = deals.filter((d) => d.lead_person_id === mine || (d.team_ids || []).includes(mine));
  const t = v.totals;
  const sub = [plural(t.live, 'живая сделка', 'живые сделки', 'живых сделок')];
  if (v.money) sub.push('воронка взвешенно ' + rubShort(t.pipeline_kop), 'подписано, получить ' + rubShort(t.to_get_kop));
  if (t.stale) sub.push(el('span', { class: 'late' }, `застыли или без следующего дела: ${t.stale}`));
  const tb = el('div', { class: 'toolbar' },
    el('button', { class: 'chip-btn' + (S.crmList ? '' : ' on'), onclick: () => { S.crmList = false; LS.set('crmList', false); render(); } }, 'Доска'),
    el('button', { class: 'chip-btn' + (S.crmList ? ' on' : ''), onclick: () => { S.crmList = true; LS.set('crmList', true); render(); } }, 'Списком'),
    mine ? el('button', { class: 'chip-btn' + (S.crmMine ? ' on' : ''), onclick: () => { S.crmMine = !S.crmMine; render(); } }, 'Только мои') : null,
    el('button', { class: 'chip-btn' + (S.crmStale ? ' on' : ''), onclick: () => { S.crmStale = !S.crmStale; render(); } }, 'Застывшие'));
  if (S.crmStale) deals = deals.filter((d) => d.stale);
  const live = deals.filter((d) => d.stage !== 'archive');
  const closed = deals.filter((d) => d.stage === 'archive').sort((a, b) => (b.closed_on || '').localeCompare(a.closed_on || ''));
  const col = (s) => {
    const items = live.filter((d) => d.stage === s).sort((a, b) => (b.fee_kop || 0) - (a.fee_kop || 0));
    const st = v.stages.find((x) => x.stage === s) || {};
    return el('div', { class: 'col' },
      el('div', { class: 'col-h' }, el('b', {}, STAGE[s]), el('span', { class: 'n' }, items.length),
        v.money && st.fee_kop ? el('span', { class: 'sum' }, rubShort(st.fee_kop)) : null),
      items.map(dealTile));
  };
  const body = S.crmList
    ? OPEN_STAGES.map((s) => { const items = live.filter((d) => d.stage === s); return items.length ? el('div', { class: 'group' }, el('h2', {}, STAGE[s], el('span', { class: 'n' }, items.length)), el('div', { class: 'dlist' }, items.map(dealTile))) : null; })
    : el('div', { class: 'board' }, OPEN_STAGES.filter((s) => !narrow() || live.some((d) => d.stage === s)).map(col));
  return [head('Воронка', sub),
    el('div', { class: 'body wide' }, tb, body,
      closed.length ? el('div', { class: 'group' }, el('h2', {}, 'Закрыты за 90 дней', el('span', { class: 'n' }, closed.length)),
        el('div', { class: 'dlist' }, closed.map(dealTile))) : null,
      !live.length && !closed.length ? el('div', { class: 'empty' }, 'Сделок нет. Новая — со страницы клиента, кнопкой «+ Сделка».') : null)];
}

// ── Клиенты ─────────────────────────────────────────────────────────────
function renderClients() {
  const v = crmGet('/api/view/clients');
  if (!v) return [head('Клиенты'), el('div', { class: 'body' }, loading())];
  const q = norm(S.clientQ || '');
  let rows = v.clients.filter((c) => (S.clientArch ? true : !c.archived_at));
  if (q) rows = rows.filter((c) => norm(c.name + ' ' + (c.aliases || []).join(' ') + ' ' + (c.org || '')).includes(q));
  const search = el('input', { class: 'inline-search', type: 'search', placeholder: 'Найти клиента', value: S.clientQ || '' });
  search.addEventListener('input', () => { S.clientQ = search.value; const pos = search.selectionStart; render(); const s2 = document.querySelector('.inline-search'); if (s2) { s2.focus(); s2.setSelectionRange(pos, pos); } });
  const tb = el('div', { class: 'toolbar' }, search,
    el('button', { class: 'chip-btn' + (S.clientArch ? ' on' : ''), onclick: () => { S.clientArch = !S.clientArch; render(); } }, 'И архив'),
    el('button', { class: 'chip-btn', onclick: () => newClient() }, '+ Клиент'));
  const row = (c) => {
    const bits = [];
    if (c.stages && c.stages.length) bits.push(el('span', {}, c.stages.map((s) => STAGE[s]).join(', ')));
    else bits.push(el('span', { class: 'faint' }, c.all_deals ? 'сделки в архиве' : 'без сделок'));
    bits.push(el('span', { class: c.last_touch && D.diff(S.today, c.last_touch.slice(0, 10)) > 45 ? 'late' : '' }, c.last_touch ? 'контакт ' + ago(c.last_touch) : 'контактов нет'));
    if (v.money && c.paid_year_kop) bits.push(el('span', { class: 'money' }, 'за год ' + rubShort(c.paid_year_kop)));
    if (v.money && c.invoiced_kop) bits.push(el('span', { class: 'late' }, 'ждём ' + rubShort(c.invoiced_kop)));
    if (c.minutes_90) bits.push(el('span', { title: 'Засечка, 90 дней' }, hrs(c.minutes_90)));
    return el('div', { class: 'crow', onclick: () => go('#/p/' + c.id) },
      el('div', { class: 'main' },
        el('div', { class: 'title' }, c.name, c.org && norm(c.org) !== norm(c.name) ? el('span', { class: 'faint' }, ' · ' + c.org) : null, c.archived_at ? el('span', { class: 'faint' }, ' · архив') : null),
        el('div', { class: 'chips' }, bits),
        c.next_task ? el('div', { class: 'next' }, `#${c.next_task.num} ${c.next_task.title}` + (c.next_task.due_date ? ' · ' + D.ddmm(c.next_task.due_date) : ''))
          : (c.live_deals ? el('div', { class: 'next none' }, 'нет следующего дела') : null)));
  };
  const out = [tb, rows.length ? el('div', { class: 'group' }, rows.map(row)) : el('div', { class: 'empty' }, 'Не нашлось.')];
  if (v.unmatched && v.unmatched.length && !q) out.push(renderUnmatched(v.unmatched));
  return [head('Клиенты', [plural(rows.filter((c) => !c.archived_at).length, 'клиент', 'клиента', 'клиентов')]), el('div', { class: 'body' }, out)];
}

async function newClient(name) {
  name = (name || prompt('Клиент (как зовёте в делах)') || '').trim();
  if (!name) return null;
  try {
    const org = (await op1({ op: 'org.create', data: { name, kind: 'client' } })).row;
    const p = (await op1({ op: 'project.create', data: { name, sphere: 'work', kind: 'client', org_id: org.id, money_default: 'potential' } })).row;
    crmDirty();
    toast('Клиент заведён: ' + name);
    go('#/p/' + p.id);
    return p;
  } catch (e) { fail(e); return null; }
}

/** Клиенты Засечки, которых нет в справочнике: завести, привязать алиасом, скрыть. */
function renderUnmatched(list) {
  const hide = async (name) => {
    const cur = (S.me.settings && S.me.settings.time_ignore) || [];
    try { await op1({ op: 'user.settings', settings: { time_ignore: [...cur, name] } }); S.me.settings = { ...(S.me.settings || {}), time_ignore: [...cur, name] }; crmDirty(); render(); } catch (e) { fail(e); }
  };
  const alias = (anchor, name) => setTimeout(() => {
    const projs = liveProjects().filter((p) => p.kind === 'client').sort((a, b) => a.name.localeCompare(b.name, 'ru'));
    const ppl = livePeople().sort((a, b) => personName(a).localeCompare(personName(b), 'ru'));
    const sel = el('select', {}, el('option', { value: '' }, 'выбрать…'),
      el('optgroup', { label: 'Клиенты' }, projs.map((p) => el('option', { value: 'p:' + p.id }, p.name))),
      el('optgroup', { label: 'Люди' }, ppl.map((p) => el('option', { value: 'h:' + p.id }, p.name))));
    sel.addEventListener('change', async () => {
      if (!sel.value) return;
      closePop();
      const [k, id] = sel.value.split(':');
      const x = k === 'p' ? project(id) : person(id);
      try {
        await op1({ op: k === 'p' ? 'project.set' : 'person.set', id, set: { aliases: [...(x.aliases || []), name] } });
        crmDirty(); toast(`«${name}» теперь ${k === 'p' ? 'клиент' : 'человек'} ${x.name} — часы встали на место`); render();
      } catch (e) { fail(e); }
    });
    popAt(anchor, [el('div', { class: 'pop-h' }, `«${name}» — это:`), sel]);
  }, 0);
  return el('div', { class: 'group' },
    el('h2', {}, 'Засечка знает, справочник нет', el('span', { class: 'n' }, list.length)),
    el('div', { class: 'hint-line' }, 'Клиенты из ленты времени, которых нет в справочнике. Заведи клиентом или привяжи к существующему — прошлые часы сами встанут на место.'),
    list.map((u) => el('div', { class: 'crow static' },
      el('div', { class: 'main' }, el('div', { class: 'title' }, u.client),
        el('div', { class: 'chips' }, el('span', {}, hrs(u.minutes)), el('span', {}, plural(u.entries, 'запись', 'записи', 'записей')), el('span', {}, 'последняя ' + D.ddmm(u.last_day)))),
      el('div', { class: 'acts-inline' },
        el('button', { class: 'btn small', onclick: () => newClient(u.client) }, 'Новый клиент'),
        el('button', { class: 'btn small', onclick: (e) => alias(e.currentTarget, u.client) }, 'Это…'),
        el('button', { class: 'btn small', onclick: () => hide(u.client) }, 'Скрыть')))));
}

// ── Связи ───────────────────────────────────────────────────────────────
function renderTies() {
  const v = crmGet('/api/view/ties');
  if (!v) return [head('Связи'), el('div', { class: 'body' }, loading())];
  const due = v.people.filter((p) => p.due), rest = v.people.filter((p) => !p.due);
  const bday = v.people.filter((p) => p.birthday_in != null && p.birthday_in <= 14).sort((a, b) => a.birthday_in - b.birthday_in);
  const row = (p) => el('div', { class: 'crow', onclick: () => go('#/h/' + p.id) },
    el('div', { class: 'main' },
      el('div', { class: 'title' }, p.name, p.org ? el('span', { class: 'faint' }, ' · ' + p.org) : null),
      el('div', { class: 'chips' },
        p.cadence && CADENCE[p.cadence] ? el('span', {}, CADENCE[p.cadence]) : null, p.hub ? el('span', { class: 'badge potential' }, 'хаб') : null,
        el('span', { class: p.due ? 'late' : '' }, p.since_days != null ? 'контакт ' + (p.since_days === 0 ? 'сегодня' : p.since_days + ' дн. назад') : 'контактов не записано'),
        p.brought ? el('span', {}, 'привёл сделок: ' + p.brought) : null,
        p.agenda ? el('span', { class: 'ball-agenda' }, 'повестка: ' + p.agenda) : null,
        p.birthday_in != null && p.birthday_in <= 14 ? el('span', { class: 'today' }, p.birthday_in ? `день рождения через ${p.birthday_in} дн.` : 'день рождения сегодня') : null)),
    el('div', { class: 'acts-inline' },
      el('button', { class: 'btn small', title: 'Записать, что поговорили', onclick: (e) => { e.stopPropagation(); quickNote({ person_ids: [p.id] }, p.name); } }, 'Поговорили')));
  return [head('Связи', ['кому пора напомнить о себе: по теплоте из карточки человека (раз в месяц, квартал, год) и по последнему контакту в хронологии']),
    el('div', { class: 'body' },
      bday.length ? el('div', { class: 'group' }, el('h2', {}, 'Дни рождения в ближайшие две недели', el('span', { class: 'n' }, bday.length)), bday.map(row)) : null,
      el('div', { class: 'group' }, el('h2', { class: due.length ? 'late' : '' }, 'Пора напомнить о себе', el('span', { class: 'n' }, due.length)), due.length ? due.map(row) : el('div', { class: 'empty' }, 'Со всеми на связи.')),
      rest.length ? el('div', { class: 'group' }, el('h2', {}, 'На связи', el('span', { class: 'n' }, rest.length)), rest.map(row)) : null,
      el('div', { class: 'hint-line' }, 'Теплота и «хаб» ставятся на странице человека. Сюда попадают и те, кто приводил сделки.'))];
}

async function quickNote(base, who) {
  const text = prompt(`Что было${who ? ' с ' + who : ''}? (коротко — уйдёт в хронологию)`);
  if (!text || !text.trim()) return;
  try {
    await op1({ op: 'interaction.add', data: { at: new Date().toISOString(), kind: base.kind || 'note', summary: text.trim(), source: 'web', ...base } });
    crmDirty(); toast('Записано в хронологию'); render();
  } catch (e) { fail(e); }
}

// ── Деньги ──────────────────────────────────────────────────────────────
function payRow(p, withDeal) {
  const st = p.paid_on ? el('span', { class: 'ok' }, 'получено ' + D.ddmm(p.paid_on))
    : p.invoiced_on ? el('span', { class: (p.due_on || p.invoiced_on) < S.today ? 'late' : 'today' }, 'счёт ' + D.ddmm(p.invoiced_on) + (p.due_on ? ', срок ' + D.ddmm(p.due_on) : ''))
      : el('span', {}, p.due_on ? 'ждём ' + D.ddmm(p.due_on) : 'без даты');
  const acts = [];
  if (!p.paid_on && !p.cancelled_at) {
    if (!p.invoiced_on) acts.push(el('button', { class: 'btn small', onclick: (e) => { e.stopPropagation(); payOp(p.id, { invoiced_on: S.today }, 'Счёт выставлен'); } }, 'Счёт выставлен'));
    acts.push(el('button', { class: 'btn small ok', onclick: (e) => { e.stopPropagation(); payOp(p.id, { paid_on: S.today }, 'Оплата получена'); } }, 'Оплачено'));
  }
  return el('div', { class: 'prow' + (p.cancelled_at ? ' cancelled' : ''), onclick: withDeal ? () => openDeal(p.deal_id) : null },
    el('div', { class: 'main' },
      el('div', {}, el('b', {}, rub(p.amount_kop)), ' ', PAY_KIND[p.kind] || p.kind, p.title ? ' · ' + p.title : '',
        withDeal ? el('span', { class: 'faint' }, ' · ' + p.project_name + ' / ' + p.deal_name) : null),
      el('div', { class: 'chips' }, st, p.cancelled_at ? el('span', {}, 'отменено') : null, p.note ? el('span', {}, p.note) : null)),
    acts.length ? el('div', { class: 'acts-inline' }, acts) : null);
}
async function payOp(id, set, msg) {
  try { await op1({ op: 'payment.set', id, set }); crmDirty(); toast(msg); render(); } catch (e) { fail(e); }
}

function renderMoney() {
  if (!S.me.money) return [head('Деньги'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Деньги фирмы тебе не открыты.'))];
  const v = crmGet('/api/view/money');
  if (!v) return [head('Деньги'), el('div', { class: 'body' }, loading())];
  const t = v.totals;
  const tile = (label, kop, cls) => el('div', { class: 'tile ' + (cls || '') }, el('div', { class: 'tl' }, label), el('div', { class: 'tv' }, kop ? rub(kop) : '—'));
  const sec = (title, list, cls) => (list.length ? el('div', { class: 'group' }, el('h2', { class: cls || '' }, title, el('span', { class: 'n' }, list.length),
    el('span', { class: 'sum' }, rub(list.reduce((s, p) => s + p.amount_kop, 0)))), list.map((p) => payRow(p, true))) : null);
  return [head('Деньги', ['деньги фирмы по сделкам: что получено, что должны, чего ждём']),
    el('div', { class: 'body' },
      el('div', { class: 'tiles' },
        tile('Получено за месяц', t.paid_month, 'ok'), tile('За квартал', t.paid_quarter), tile('За год', t.paid_year),
        tile('Должны (счета)', t.receivable, t.receivable ? 'warn' : ''), tile('Ждём за 30 дней', t.expected_30),
        tile('Подписано, получить', t.to_get), tile('Воронка взвешенно', t.pipeline)),
      sec('Просрочено — счёт выставлен, срок прошёл', v.overdue, 'late'),
      sec('Счёт выставлен', v.invoiced),
      sec('Ждём в ближайший месяц', v.expected),
      sec('Позже или без даты', v.later),
      v.unplanned.length ? el('div', { class: 'group' }, el('h2', {}, 'Подписано, а оплаты расписаны не на всю сумму', el('span', { class: 'n' }, v.unplanned.length)),
        el('div', { class: 'dlist' }, v.unplanned.map(dealTile))) : null,
      sec('Пришло за 4 месяца', v.paid),
      !v.overdue.length && !v.invoiced.length && !v.expected.length && !v.later.length && !v.paid.length
        ? el('div', { class: 'empty' }, 'Оплат пока нет. Их заводят в карточке сделки: аванс, этапы, ретейнер, success fee.') : null)];
}

// ── Хронология ──────────────────────────────────────────────────────────
/** Записи хронологии и (владельцу) куски Засечки вперемешку, свежие сверху. */
function timelineBox(items, entries, base, opts = {}) {
  const kind = el('select', {}, Object.entries(IKIND).map(([k, l]) => el('option', { value: k, selected: k === (opts.kind || 'call') }, l)));
  const when = el('input', { type: 'date', value: S.today });
  const text = el('textarea', { rows: 2, placeholder: 'Что было: звонок, встреча, переписка — коротко, с сутью и договорённостями' });
  const add = async () => {
    if (!text.value.trim()) return;
    const at = when.value === S.today ? new Date().toISOString() : when.value + 'T12:00:00+03:00';
    try {
      await op1({ op: 'interaction.add', data: { at, kind: kind.value, summary: text.value.trim(), source: 'web', ...base } });
      text.value = ''; crmDirty(); toast('Записано в хронологию'); render();
    } catch (e) { fail(e); }
  };
  text.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) add(); });
  const rows = [
    ...(items || []).map((i) => ({ at: i.at, i })),
    ...(entries || []).map((e) => ({ at: e.day + 'T' + (e.start_local || '00:00').slice(-5) + ':00', e })),
  ].sort((a, b) => b.at.localeCompare(a.at)).slice(0, opts.limit || 150);
  const line = (r) => {
    if (r.e) {
      const e = r.e;
      return el('div', { class: 'tl-row work' }, el('div', { class: 'tl-when' }, D.ddmm(e.day)),
        el('div', { class: 'tl-what' }, el('span', { class: 'tl-kind' }, icon('clock', 12), ' ' + hrs(e.minutes)), ' ', e.title || 'работа',
          e.task_num ? el('span', { class: 'faint' }, ' · #' + e.task_num) : null));
    }
    const i = r.i;
    const who = (i.person_ids || []).map((x) => personName(person(x))).filter(Boolean);
    return el('div', { class: 'tl-row' }, el('div', { class: 'tl-when' }, D.ddmm(i.at.slice(0, 10))),
      el('div', { class: 'tl-what' },
        el('span', { class: 'tl-kind' }, IKIND[i.kind] || i.kind), ' ', i.summary,
        i.next_step ? el('div', { class: 'tl-next' }, '→ ' + i.next_step) : null,
        el('div', { class: 'tl-meta' }, [opts.showDeal && i.deal_name, who.length && who.join(', '), i.duration_min && i.duration_min + ' мин',
          i.source === 'meeting' && 'из встречи', i.source === 'notion' && 'Notion'].filter(Boolean).join(' · '),
          i.source_ref && /^https:\/\//.test(i.source_ref) ? el('a', { class: 'src', href: i.source_ref, target: '_blank', rel: 'noopener noreferrer' }, ' открыть') : null)),
      el('button', { class: 'icon-btn tl-del', title: 'Убрать из хронологии', onclick: async () => {
        if (!confirm('Убрать запись из хронологии? Она останется в журнале.')) return;
        await dealOp({ op: 'interaction.delete', id: i.id });
      } }, icon('x', 12)));
  };
  return el('div', { class: 'timeline' },
    el('div', { class: 'tl-add' }, el('div', { class: 'tl-add-row' }, kind, when), text,
      el('div', { class: 'tl-add-row' }, el('span', { class: 'faint' }, 'Ctrl+Enter'), el('button', { class: 'btn small', onclick: add }, 'Записать'))),
    rows.length ? rows.map(line) : el('div', { class: 'faint pad' }, 'Пока пусто.'));
}

// ── Клиент на странице проекта ──────────────────────────────────────────
function clientBlock(p) {
  const v = crmGet('/api/view/client?project_id=' + p.id);
  const deals = [...S.deals.values()].filter((d) => d.project_id === p.id)
    .sort((a, b) => (a.stage === 'archive') - (b.stage === 'archive') || OPEN_STAGES.indexOf(a.stage) - OPEN_STAGES.indexOf(b.stage));
  const full = v ? new Map(v.deals.map((d) => [d.id, d])) : new Map();
  const dealBox = el('div', { class: 'dlist' }, deals.map((d) => dealTile({ ...d, project_name: p.name, ...(full.get(d.id) || {}) })),
    el('button', { class: 'dtile add', onclick: () => newDeal(p.id) }, icon('plus', 14), ' Сделка'));
  const info = [];
  if (v && v.months && v.months.length) {
    const total = v.months.reduce((s, m) => s + m.minutes, 0);
    info.push(el('span', { title: 'время из Засечки' }, icon('clock', 12), ' ' + hrs(total) + ' всего · ' + v.months.slice(0, 3).map((m) => `${MONTHS[+m.month.slice(5) - 1].slice(0, 3)} ${hrs(m.minutes)}`).join(', ')));
  }
  const peopleBox = v && v.people.length ? el('div', { class: 'people-line' }, 'Люди: ', v.people.map((x, k) => [k ? ', ' : '', el('a', { class: 'link', href: '#/h/' + x.id }, x.name + (x.role ? ` (${x.role})` : ''))])) : null;
  const tabs = el('div', { class: 'seg tabs' }, [['tasks', 'Дела'], ['timeline', 'Хронология']].map(([k, l]) =>
    el('button', { class: (S.clientTab || 'tasks') === k ? 'on' : '', onclick: () => { S.clientTab = k; render(); } }, l)));
  return { dealBox, info, peopleBox, tabs, timeline: (S.clientTab === 'timeline')
    ? (v ? timelineBox(v.timeline, v.entries, { project_id: p.id }, { showDeal: true }) : loading()) : null };
}

// ── Карточка сделки ─────────────────────────────────────────────────────
function renderDealCard() {
  const close = () => { S.dealId = null; render(); };
  const d0 = S.deals.get(S.dealId);
  const v = crmGet('/api/view/deal?deal_id=' + S.dealId, 15000);
  if (!d0) return el('section', { class: 'card-pane' }, el('div', { class: 'card' }, el('div', { class: 'top' }, el('span', { class: 'num' }, 'Сделка'),
    el('button', { class: 'icon-btn', onclick: close }, icon('x', 16))), el('div', { class: 'empty' }, 'Сделка не видна.')));
  const d = { ...d0, ...(v ? v.deal : {}) };
  const p = project(d.project_id);
  const money = S.me.money;
  const save = (field, value) => setDeal(d.id, { [field]: value });
  const sel = (field, options, value, empty = '—') => {
    const s = el('select', {}, [['', empty], ...options].map(([k, l]) => el('option', { value: k, selected: String(value ?? '') === String(k) }, l)));
    s.addEventListener('change', () => save(field, s.value || null));
    return s;
  };
  const inp = (field, type, value, conv) => {
    const i = el('input', { type, value: value ?? '' });
    i.addEventListener('change', () => save(field, i.value === '' ? null : conv ? conv(i.value) : i.value));
    return i;
  };
  const rubIn = (field) => inp(field, 'number', d[field] != null ? Math.round(d[field] / 100) : '', (x) => Math.round(+x * 100));
  const peopleSel = (field, list) => {
    const ids = d[field] || [];
    const box = el('div', { class: 'multi' }, ids.map((x) => el('span', { class: 'pill' }, pName(x),
      el('button', { title: 'Убрать', onclick: () => save(field, ids.filter((y) => y !== x)) }, '×'))));
    const add = el('select', {}, el('option', { value: '' }, '+ добавить'), list.filter((x) => !ids.includes(x.id)).map((x) => el('option', { value: x.id }, x.short && x.short !== x.name ? `${x.short} — ${x.name}` : x.name)));
    add.addEventListener('change', () => add.value && save(field, [...ids, add.value]));
    box.append(add);
    return box;
  };
  const team = teamPeople();
  const ppl = livePeople().sort((a, b) => personName(a).localeCompare(personName(b), 'ru'));
  const clientPeople = ppl.filter((x) => !team.includes(x));

  const title = el('textarea', { class: 'title-edit', rows: 2 }, d.name);
  title.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); title.blur(); } });
  title.addEventListener('change', () => title.value.trim() && save('name', title.value.trim()));

  const stages = el('div', { class: 'stages' }, OPEN_STAGES.map((s) => el('button', { class: d.stage === s ? 'on' : '', onclick: () => save('stage', s) }, STAGE[s])));
  const closeDeal = (anchor) => setTimeout(() => popAt(anchor, [
    ...Object.entries(OUTCOME).map(([k, l]) => el('button', { onclick: () => {
      closePop();
      const why = k === 'won' ? '' : prompt(k === 'lost' ? 'Почему проиграли? (учит воронку, можно пусто)' : 'Почему заморозили? (можно пусто)');
      if (why === null) return;
      setDeal(d.id, { outcome: k, lost_reason: why || null });
    } }, l))]), 0);
  const stageRow = d.stage === 'archive'
    ? el('div', { class: 'stage-closed' }, el('span', { class: 'out-' + (d.outcome || 'none') }, d.outcome ? OUTCOME[d.outcome] : 'архив'),
      d.closed_on ? ' · ' + D.ddmm(d.closed_on) : '', d.lost_reason ? ' · ' + d.lost_reason : '',
      el('button', { class: 'btn small', onclick: () => save('stage', 'active') }, 'Вернуть в работу'))
    : el('div', { class: 'stage-row' }, stages, el('button', { class: 'btn small', onclick: (e) => closeDeal(e.currentTarget) }, 'Закрыть…'));

  const props = el('div', { class: 'props' },
    el('span', {}, 'Клиент'), p ? el('a', { class: 'link', href: '#/p/' + p.id }, p.name) : el('span', {}, '—'),
    el('span', {}, 'Тип'), sel('deal_type', DEAL_TYPES.map((x) => [x, x]).concat(d.deal_type && !DEAL_TYPES.includes(d.deal_type) ? [[d.deal_type, d.deal_type]] : []), d.deal_type),
    el('span', {}, 'Ведёт'), sel('lead_person_id', team.map((x) => [x.id, personName(x)]), d.lead_person_id),
    el('span', {}, 'Команда'), peopleSel('team_ids', team),
    el('span', {}, 'Люди клиента'), peopleSel('person_ids', clientPeople),
    el('span', {}, 'Привёл'), sel('source_person_id', ppl.map((x) => [x.id, x.name]), d.source_person_id),
    el('span', {}, 'Вероятность, %'), inp('probability', 'number', d.probability, (x) => Math.max(0, Math.min(100, Math.round(+x)))),
    el('span', {}, 'Решение ждём'), inp('expected_on', 'date', d.expected_on),
    el('span', {}, 'Дедлайн'), inp('deadline', 'date', d.deadline));

  const card = el('div', { class: 'card deal-card' },
    el('div', { class: 'top' }, el('span', { class: 'num' }, 'Сделка · ' + stageWord(d)), el('button', { class: 'icon-btn', title: 'Закрыть (Esc)', onclick: close }, icon('x', 16))),
    title, stageRow, props);

  if (money) {
    const pays = v ? v.payments : [...S.pays.values()].filter((x) => x.deal_id === d.id);
    const live = pays.filter((x) => !x.cancelled_at);
    const sum = (f) => live.filter(f).reduce((s, x) => s + x.amount_kop, 0);
    const paid = sum((x) => x.paid_on), inv = sum((x) => !x.paid_on && x.invoiced_on), plan = sum((x) => !x.paid_on && !x.invoiced_on);
    const kind = el('select', {}, Object.entries(PAY_KIND).map(([k, l]) => el('option', { value: k }, l)));
    const amount = el('input', { type: 'number', placeholder: 'сумма, ₽' });
    const due = el('input', { type: 'date' });
    const addPay = async () => {
      if (!(+amount.value > 0)) { toast('Сумма?'); return; }
      try { await op1({ op: 'payment.create', data: { deal_id: d.id, kind: kind.value, amount_kop: Math.round(+amount.value * 100), due_on: due.value || null } }); crmDirty(); render(); } catch (e) { fail(e); }
    };
    card.append(el('h3', {}, 'Деньги'),
      el('div', { class: 'props' },
        el('span', {}, 'Модель'), sel('fee_kind', Object.entries(FEE_KIND), d.fee_kind),
        el('span', {}, 'Гонорар, ₽'), rubIn('fee_kop'),
        el('span', {}, 'Ретейнер, ₽/мес'), rubIn('retainer_kop'),
        el('span', {}, 'Успех, %'), inp('success_pct', 'number', d.success_pct, (x) => +x)),
      el('div', { class: 'money-sum' }, [paid && 'получено ' + rub(paid), inv && 'счета ' + rub(inv), plan && 'план ' + rub(plan),
        d.fee_kop && ['mandate', 'active', 'closing'].includes(d.stage) ? 'осталось ' + rub(Math.max(d.fee_kop - paid, 0)) : null,
        d.fee_kop && paid + inv + plan < d.fee_kop && d.stage !== 'archive' ? 'не расписано ' + rub(d.fee_kop - paid - inv - plan) : null].filter(Boolean).join(' · ') || 'оплат пока нет'),
      el('div', { class: 'pays' }, pays.map((x) => payRow(x, false))),
      el('div', { class: 'pay-add' }, kind, amount, due, el('button', { class: 'btn small', onclick: addPay }, '+ Оплата')));
  }

  // Следующий шаг — открытое дело сделки.
  const open = (v ? v.open : all().filter((t) => t.deal_id === d.id && isOpen(t)));
  const next = el('input', { placeholder: 'Следующее дело по сделке… (Enter)' });
  next.addEventListener('keydown', async (e) => {
    if (e.key !== 'Enter' || !next.value.trim()) return;
    const parsed = parseQuick(next.value.trim(), {});
    try { await op1({ op: 'task.create', task: { title: parsed.title, ...parsed.set, project_id: d.project_id, deal_id: d.id, source: 'web' } }); next.value = ''; crmDirty(); render(); } catch (err) { fail(err); }
  });
  card.append(el('h3', {}, 'Следующий шаг'),
    el('div', {}, open.length ? open.map((t) => el('div', { class: 'mini-task', onclick: () => openCard(t.id) },
      el('button', { class: 'tick', title: 'Сделано', onclick: (e) => { e.stopPropagation(); toggleDone([S.tasks.get(t.id) || t]).then(() => { crmDirty(); render(); }); } }),
      el('span', {}, '#' + t.num + ' ' + t.title), t.due_date ? el('span', { class: t.due_date < S.today ? 'late' : 'faint' }, ' · ' + D.ddmm(t.due_date)) : null))
      : (d.stage !== 'archive' ? el('div', { class: 'next none' }, 'Нет следующего дела — сделка без шага застынет.') : null)),
    el('div', { class: 'add-comment' }, next));
  if (v && v.done.length) card.append(el('details', {}, el('summary', {}, `Сделано по сделке (${v.done.length})`),
    v.done.map((t) => el('div', { class: 'hist' }, `#${t.num} ${t.title}` + (t.completed_at ? ' · ' + D.ddmm(t.completed_at.slice(0, 10)) : '')))));

  card.append(el('h3', {}, 'Хронология'), v ? timelineBox(v.timeline, v.entries, { project_id: d.project_id, deal_id: d.id, person_ids: d.person_ids || [] }) : loading());
  if (v && v.minutes_all) card.append(el('div', { class: 'hint-line' }, icon('clock', 12), ' Время из Засечки по делам сделки: ' + hrs(v.minutes_all)));

  const legacy = [d.my_view != null || S.me.role === 'owner' ? ['Как я вижу (только мне)', 'my_view', true] : null, ['Идеи', 'ideas', true],
    d.next_step ? ['Следующий шаг (Notion)', 'next_step', false] : null, d.ball ? ['Мяч (Notion)', 'ball', false] : null,
    d.log ? ['Хронология (Notion)', 'log', false] : null].filter(Boolean);
  card.append(el('details', { open: d.my_view || d.ideas ? true : null }, el('summary', {}, 'Заметки и тексты из Notion'),
    legacy.map(([label, f, edit]) => {
      const ta = el('textarea', { class: 'notes', readonly: edit ? null : true }, d[f] || '');
      if (edit) ta.addEventListener('change', () => save(f, ta.value.trim() || null));
      return el('div', { class: 'legacy' }, el('div', { class: 'faint' }, label), ta);
    })));
  if (v && v.history.length) {
    card.append(el('details', {}, el('summary', {}, 'Журнал сделки'), v.history.map((h) => {
      const a = h.after || {};
      const what = h.op === 'insert' ? 'заведена' : a.stage ? 'стадия: ' + (STAGE[a.stage] || a.stage) + (a.outcome ? ' — ' + OUTCOME[a.outcome] : '') : Object.keys(a).join(', ');
      return el('div', { class: 'hist' }, `${h.at.slice(0, 16).replace('T', ' ')} · ${h.actor} · ${what}`);
    })));
  }
  return el('section', { class: 'card-pane', 'data-key': 'deal:' + S.dealId }, card);
}

// ── Человек в CRM (на его странице) ─────────────────────────────────────
function personCrmBlock(p) {
  const v = crmGet('/api/view/dossier?person_id=' + p.id);
  const cad = el('select', {}, [['', '—'], ...Object.entries(CADENCE)].map(([k, l]) => el('option', { value: k, selected: (p.cadence || '') === k }, l)));
  cad.addEventListener('change', async () => { try { await op1({ op: 'person.set', id: p.id, set: { cadence: cad.value || null } }); crmDirty(); render(); } catch (e) { fail(e); } });
  const hub = el('input', { type: 'checkbox' });
  hub.checked = !!p.hub;
  hub.addEventListener('change', async () => { try { await op1({ op: 'person.set', id: p.id, set: { hub: hub.checked } }); crmDirty(); } catch (e) { fail(e); } });
  return el('div', { class: 'crm-person' },
    el('div', { class: 'toolbar' }, el('label', {}, 'Теплота', cad), el('label', {}, hub, 'хаб — через него идут темы')),
    v && v.deals.length ? el('div', { class: 'group' }, el('h2', {}, 'Сделки', el('span', { class: 'n' }, v.deals.length)), el('div', { class: 'dlist' }, v.deals.map(dealTile))) : null,
    el('div', { class: 'group' }, el('h2', {}, 'Хронология'), v ? timelineBox(v.timeline, null, { person_ids: [p.id] }, { showDeal: true }) : loading()));
}

// ── Путь: очки, серия, уровень (считает сервер, вид stats; имена — тут) ─
// Тора — 42 стоянки странствий (Бемидбар 33, глава Масэй): путь, а не люди — уровни не ранжируют
// праотцов. Серия — манна: падает каждый день, в Шаббат — нет, поэтому суббота серию не рвёт.
// Греция — 12 подвигов Геракла и путь Одиссея домой; серия — огонь Прометея. Выбор — в «Настройках».
const THEMES = {
  torah: {
    title: 'Тора · 42 стоянки', step: 'стоянка', streak: 'манна', end: 'Ярден перейдён — новый круг',
    levels: [
      ['Раамсес', 'вышли «рукой высокой» — начало пути'], ['Суккот', 'облака славы укрыли народ'],
      ['Эйтам', 'на краю пустыни'], ['Пи-Ахирот', 'у моря; дальше — сквозь море'],
      ['Мара', 'горькую воду сделали сладкой'], ['Эйлим', 'двенадцать источников и семьдесят пальм'],
      ['Ям-Суф', 'снова у моря'], ['Пустыня Син', 'с неба пошла манна'], ['Дофка', ''], ['Алуш', ''],
      ['Рефидим', 'вода из скалы; война с Амалеком'], ['Синай', 'дарование Торы'],
      ['Киврот-Атаава', '«гробы прихоти»: захотели мяса'], ['Хацерот', 'Мирьям ждали семь дней'],
      ['Ритма', 'по Раши — память о злословии разведчиков'], ['Римон-Перец', ''], ['Ливна', ''], ['Риса', ''],
      ['Кеэлата', ''], ['Гора Шефер', ''], ['Харада', ''], ['Макэлот', ''], ['Тахат', ''], ['Тарах', ''],
      ['Митка', ''], ['Хашмона', ''], ['Мосерот', ''], ['Бней-Яакан', ''], ['Хор-Агидгад', ''], ['Йотвата', ''],
      ['Аврона', ''], ['Эцйон-Гевер', ''], ['Кадеш', 'пустыня Цин: умерла Мирьям, вода из скалы'],
      ['Гора Ор', 'умер Аарон'], ['Цальмона', ''], ['Пунон', ''], ['Овот', 'после медного змея'],
      ['Ийей-Аварим', ''], ['Дивон-Гад', ''], ['Алмон-Дивлатайма', ''], ['Горы Аварим', ''],
      ['Равнины Моава', 'у Иордана напротив Йерихо: здесь Мойше пересказал Тору'],
    ],
  },
  greek: {
    title: 'Греция · подвиги и путь Одиссея', step: 'шаг', streak: 'огонь Прометея', end: 'Итака — новый круг',
    levels: [
      ['Немейский лев', 'шкура, которую не берёт стрела'], ['Лернейская гидра', 'отрубил одну — выросли две'],
      ['Керинейская лань', 'год погони'], ['Эриманфский вепрь', 'загнать в снег'],
      ['Авгиевы конюшни', 'разгрести завал за день'], ['Стимфалийские птицы', 'спугнуть и сбить'],
      ['Критский бык', ''], ['Кони Диомеда', ''], ['Пояс Ипполиты', ''], ['Коровы Гериона', 'на край света'],
      ['Яблоки Гесперид', 'подержал небо за Атланта'], ['Кербер', 'вернулся из Аида'],
      ['Троя пала', 'теперь — домой'], ['Киконы', ''], ['Лотофаги', 'кто ел лотос — забывал дорогу домой'],
      ['Полифем', '«Меня зовут Никто»'], ['Эол', 'мешок ветров развязали в шаге от дома'], ['Лестригоны', ''],
      ['Цирцея', ''], ['Аид', ''], ['Сирены', 'привязан к мачте — не свернул'], ['Сцилла и Харибда', 'выбрать меньшее из двух'],
      ['Остров Гелиоса', ''], ['Калипсо', 'семь лет'], ['Феаки', ''], ['Итака', 'дома'],
    ],
  },
};
Object.assign(ICONS, {
  chart: 'M4 20V11M10 20V5M16 20v-6M3 20h18',
  flame: 'M12 3c1 3 4 4.5 4 8.5a4 4 0 0 1-8 0c0-2 1-3 2-4 0 1.5 1 2.5 2 2.5 0-3-1-5 0-7z',
});
const LAP = 26000; // очков на весь путь: ~полтора года ровной работы
const theme = () => THEMES[(S.me.settings || {}).game_theme] || THEMES.torah;
/** Уровень по сумме очков: пороги растут квадратом, после последнего — новый круг. */
function levelOf(total) {
  const th = theme(), N = th.levels.length, K = LAP / (N * N);
  const lap = Math.floor(total / LAP) + 1, inLap = total % LAP;
  let i = Math.min(N - 1, Math.floor(Math.sqrt(inLap / K)));
  const at = Math.round(K * i * i), next = Math.round(K * (i + 1) * (i + 1));
  return { i, n: N, lap, name: th.levels[i][0], note: th.levels[i][1], at, next,
    nextName: i < N - 1 ? th.levels[i + 1][0] : th.end, left: next - inLap, share: (inLap - at) / (next - at) };
}
const pointsFor = (t) => (isNow(t) || (t.due_date && t.due_date <= S.today) ? 15 : 10);

/** Карточка пути в боковой панели: стоянка, полоска до следующей, серия. Новая стоянка — тост. */
function gameCard() {
  const v = crmGet('/api/view/stats', 60000);
  if (!v) return null;
  const lv = levelOf(v.points.total), th = theme(), st = v.streak;
  const key = `${(S.me.settings || {}).game_theme || 'torah'}:${lv.lap}:${lv.i}`, was = LS.get('gameLevel', null);
  if (was && was !== key && was.split(':')[0] === key.split(':')[0] && v.points.total > LS.get('gamePoints', 0)) {
    setTimeout(() => toast(`Новая ${th.step}: ${lv.name}${lv.note ? ' — ' + lv.note : ''}`), 600);
  }
  LS.set('gameLevel', key);
  LS.set('gamePoints', v.points.total);
  const bar = el('div', { class: 'gc-bar' }, el('span', {}));
  bar.firstChild.style.width = Math.round(lv.share * 100) + '%';
  return el('button', { class: 'game-card' + (route().kind === 'stats' ? ' on' : ''), onclick: () => go('#/stats'), title: 'Статистика и путь' },
    el('div', { class: 'gc-top' }, el('b', {}, lv.name), el('span', {}, `${lv.i + 1}/${lv.n}`)),
    bar,
    el('div', { class: 'gc-sub' + (st.current && !st.today_done ? ' warn' : '') },
      icon('flame', 12), `${th.streak}: ${plural(st.current, 'день', 'дня', 'дней')}`,
      st.current && !st.today_done ? ' · сегодня ещё нет' : st.today_done ? ' · сегодня есть' : ''));
}

/** Столбики по дням: очки, субботы — светлее; подпись — по наведению. */
function dayChart(days) {
  const ns = 'http://www.w3.org/2000/svg', W = 600, H = 120, bw = W / days.length;
  const max = Math.max(15, ...days.map((d) => d.points));
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H + 16}`);
  svg.setAttribute('class', 'day-chart');
  days.forEach((d, k) => {
    const wd = D.wd(d.day), h = d.points ? Math.max(3, (d.points / max) * H) : 0;
    if (wd === 5) {
      const bg = document.createElementNS(ns, 'rect');
      for (const [a, b] of Object.entries({ x: k * bw, y: 0, width: bw, height: H, class: 'sat' })) bg.setAttribute(a, b);
      svg.append(bg);
    }
    const r = document.createElementNS(ns, 'rect');
    for (const [a, b] of Object.entries({ x: k * bw + 2, y: H - h, width: bw - 4, height: h, rx: 2, class: d.day === S.today ? 'bar today' : 'bar' })) r.setAttribute(a, b);
    const tip = document.createElementNS(ns, 'title');
    tip.textContent = `${D.ddmm(d.day)}, ${WD_SHORT[wd]}: ${d.points} очк. · закрыто ${d.done}` + (d.cancelled ? `, отменено ${d.cancelled}` : '') + (d.triage ? `, «Новое» ${d.triage}` : '');
    r.append(tip);
    svg.append(r);
    if (k % 5 === 4 || d.day === S.today) {
      const t = document.createElementNS(ns, 'text');
      for (const [a, b] of Object.entries({ x: k * bw + bw / 2, y: H + 13, 'text-anchor': 'middle', class: 'lbl' })) t.setAttribute(a, b);
      t.textContent = D.ddmm(d.day).slice(0, 5);
      svg.append(t);
    }
  });
  return svg;
}

function renderStats() {
  const v = crmGet('/api/view/stats', 15000);
  if (!v) return [head('Статистика'), el('div', { class: 'body' }, loading())];
  const lv = levelOf(v.points.total), th = theme(), st = v.streak, p = v.points;
  const delta = p.prev_week ? Math.round((p.week - p.prev_week) / p.prev_week * 100) : null;
  const bar = el('div', { class: 'lv-bar' }, el('span', {}));
  bar.firstChild.style.width = Math.round(lv.share * 100) + '%';
  const path = el('div', { class: 'lv-path' }, th.levels.map(([name, note], k) =>
    el('span', { class: 'pt' + (k < lv.i ? ' done' : k === lv.i ? ' here' : ''), title: `${k + 1}. ${name}${note ? ' — ' + note : ''}` })));
  const tile = (label, value, sub, cls) => el('div', { class: 'tile ' + (cls || '') }, el('div', { class: 'tl' }, label), el('div', { class: 'tv' }, value), sub ? el('div', { class: 'ts' }, sub) : null);
  const m = v.month;
  return [head('Статистика', [th.title, v.since ? 'считаю с ' + D.ddmm(v.since) : 'считаю с первого закрытого дела']),
    el('div', { class: 'body stats' },
      el('div', { class: 'lv-card' },
        el('div', { class: 'lv-step' }, `${th.step} ${lv.i + 1} из ${lv.n}` + (lv.lap > 1 ? ` · круг ${lv.lap}` : '')),
        el('div', { class: 'lv-name' }, lv.name),
        lv.note ? el('div', { class: 'lv-note' }, lv.note) : null,
        bar,
        el('div', { class: 'lv-next' }, `до «${lv.nextName}» — ${plural(lv.left, 'очко', 'очка', 'очков')} · всего ${p.total}`),
        path),
      el('div', { class: 'tiles' },
        tile('Сегодня', p.today + ' очк.', st.today_done ? 'есть закрытое' : 'пока ничего не закрыто', st.today_done ? 'ok' : ''),
        tile('За 7 дней', p.week + ' очк.', delta == null ? 'неделей раньше — 0' : (delta >= 0 ? '+' : '') + delta + '% к прошлой неделе', delta > 0 ? 'ok' : ''),
        tile(th.streak[0].toUpperCase() + th.streak.slice(1), plural(st.current, 'день', 'дня', 'дней'), 'лучшая — ' + plural(st.best, 'день', 'дня', 'дней'), st.current && !st.today_done ? 'warn' : ''),
        tile('Закрыто за 30 дней', String(m.done), m.with_due ? `в срок — ${Math.round(m.on_time / m.with_due * 100)}% (${m.on_time} из ${m.with_due})` : null),
        tile('Сейчас открыто', String(v.open.open), `просрочено ${v.open.overdue} · в «Сейчас» ${v.open.now_}`, v.open.overdue ? 'warn' : '')),
      el('div', { class: 'group' }, el('h2', {}, tint(icon('chart', 14), '#7c9cff'), 'Очки по дням', el('span', { class: 'gsub' }, '30 дней, субботы светлее')),
        dayChart(v.days)),
      v.by_project.length ? el('div', { class: 'group' }, el('h2', {}, 'Что закрывал за 30 дней'),
        v.by_project.map((x) => {
          const b = el('span', { class: 'hb' });
          b.style.width = Math.round(x.done / v.by_project[0].done * 100) + '%';
          return el('div', { class: 'hrow' }, el('span', { class: 'hn' }, x.name), el('span', { class: 'hbar' }, b), el('span', { class: 'hv' }, x.done));
        }),
        el('div', { class: 'hint-line' }, 'Откуда были дела: ' + v.by_source.map((x) => `${x.name} ${x.done}`).join(' · '))) : null,
      el('div', { class: 'hint-line rules' }, `Очки: закрыл — ${v.rules.done}, обещанное (в «Сейчас» или срок пришёл) — ещё ${v.rules.promised}, отменил ненужное — ${v.rules.cancel}, разобрал «Новое» — ${v.rules.triage}. Заводить дела — бесплатно. ${th.streak[0].toUpperCase() + th.streak.slice(1)} — дни подряд с закрытым делом; суббота не рвёт.`))];
}

// ── Клавиши ─────────────────────────────────────────────────────────────
document.addEventListener('keydown', (e) => {
  const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName);
  if (e.key === 'Escape') {
    if (document.querySelector('.pop')) { closePop(); return; }
    if (rec || S.askTask) { stopListening(); S.askTask = null; S.listenQuick = false; render(); return; }
    if (S.cardId || S.draft || S.dealId) { S.cardId = null; S.draft = null; S.dealId = null; render(); return; }
    if (S.sel.size) { S.sel.clear(); render(); }
    return;
  }
  if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
  if (e.key === '/') { e.preventDefault(); document.querySelector('.side-search')?.focus(); }
  if (e.key === 'n' || e.key === 'т') { e.preventDefault(); document.getElementById('quick')?.focus(); }
  const jump = { m: 'morning', u: 'upcoming', i: 'inbox', w: 'waiting' }[e.key];
  if (jump) go('#/' + jump);
});

boot();
