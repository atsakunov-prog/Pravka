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
  sphere: LS.get('sphere', ''), groups: LS.get('groups', {}), favs: LS.get('favs', []), closed: LS.get('closed', {}),
  showDone: false, upNoDate: false, upMineOnly: false, dealFilter: null,
  sel: new Set(), order: [], lastPick: null, cardId: null, draft: null, sideOpen: false,
  parse: null, quickDraft: null, // идёт разбор Claude; текст, вернувшийся после неудачи
};
const BALL = { mine: 'моё', waiting: 'жду', agenda: 'повестка' };
const MONEY = { paid: 'оплачено', potential: 'развитие', none: 'без денег' };
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
  if (!r.ok || d.ok === false) throw new Error(d.error || ('ошибка ' + r.status));
  return d;
}
const uuid = () => (crypto.randomUUID ? crypto.randomUUID()
  : 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => (Math.random() * 16 | 0).toString(16)));

/** Пачка операций; ответные дела сразу ложатся в память, остальное доедет синком. */
async function ops(items) {
  const d = await api('/api/ops', { ops: items.map((o) => ({ op_id: uuid(), ...o })) });
  for (const r of d.results) if (r.ok && r.task) S.tasks.set(r.task.id, r.task);
  const bad = d.results.filter((r) => !r.ok);
  await sync();
  if (bad.length) throw new Error(bad[0].error + (bad.length > 1 ? ` (и ещё ${bad.length - 1})` : ''));
  return d.results;
}
const op1 = async (item) => (await ops([item]))[0];

async function sync(full) {
  const d = await api('/api/sync?since=' + (full ? 0 : S.seq));
  if (d.full) { S.tasks.clear(); S.projects.clear(); S.people.clear(); S.deals.clear(); S.sugs.clear(); }
  for (const t of d.tasks) S.tasks.set(t.id, t);
  for (const p of d.projects) S.projects.set(p.id, p);
  for (const p of d.people) S.people.set(p.id, p);
  for (const x of d.deals) S.deals.set(x.id, x);
  for (const s of d.suggestions) S.sugs.set(s.id, s);
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
const moneyOf = (t) => t.money || (project(t.project_id) ? project(t.project_id).money_default : 'none') || 'none';
const isLate = (t) => isOpen(t) && t.due_date && t.due_date < S.today;
const isNow = (t) => t.focus_on === S.today;
const personName = (p) => (p ? p.short || p.name : '');
function sortTasks(a, b) {
  return (a.due_date || '9999').localeCompare(b.due_date || '9999')
    || (a.due_time || '99').localeCompare(b.due_time || '99')
    || (moneyOf(b) === 'paid') - (moneyOf(a) === 'paid')
    || a.num - b.num;
}
const all = () => [...S.tasks.values()];
const openMine = () => all().filter((t) => isOpen(t) && isMine(t) && inSphere(t));

const VIEWS = {
  morning: {
    title: 'Утро', icon: 'sun',
    sections() {
      const m = openMine();
      const from = new Date(Date.now() - 3 * 86400e3).toISOString();
      return [
        ['Сейчас', m.filter(isNow)],
        ['На сегодня и просроченное', m.filter((t) => t.ball === 'mine' && t.due_date && t.due_date <= S.today && !isNow(t))],
        ['Пора напомнить', m.filter((t) => t.ball === 'waiting' && ((t.nudge_on && t.nudge_on <= S.today) || (t.due_date && t.due_date <= S.today)))],
        ['Оплачено, без даты', m.filter((t) => t.ball === 'mine' && moneyOf(t) === 'paid' && !t.due_date && !isNow(t))],
        ['Поставили другие', m.filter((t) => t.created_by !== t.owner_id && t.created_at > from)],
      ];
    },
    count: () => openMine().filter((t) => (t.ball === 'mine' && t.due_date && t.due_date <= S.today) || isNow(t)).length,
  },
  upcoming: { title: 'Предстоящее', icon: 'cal', count: () => openMine().filter((t) => t.due_date && t.due_date > S.today && t.due_date <= D.add(S.today, 7)).length },
  new: { title: 'Новое', icon: 'inbox-in', count: () => pendingSugs().length },
  waiting: { title: 'Жду', icon: 'hourglass', count: () => openMine().filter((t) => t.ball === 'waiting').length },
  week: { title: 'Неделя', icon: 'broom' },
  inbox: { title: 'Входящие', icon: 'tray', count: () => openMine().filter((t) => !t.project_id).length },
  all: { title: 'Все дела', icon: 'list' },
};
const pendingSugs = () => [...S.sugs.values()].filter((s) => s.status === 'pending' && s.for_user === S.me.user);

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

// ── Маршрут ─────────────────────────────────────────────────────────────
function route() {
  const h = location.hash.replace(/^#\/?/, '');
  const [kind, id] = h.split('/');
  if (kind === 'p' && id) return { kind: 'project', id };
  if (kind === 'h' && id) return { kind: 'person', id };
  if (kind === 'search') return { kind: 'search', q: decodeURIComponent(id || '') };
  return { kind: VIEWS[kind] ? kind : 'morning' };
}
const go = (hash) => { location.hash = hash; };
window.addEventListener('hashchange', () => { S.sel.clear(); S.dealFilter = null; S.sideOpen = false; render(); });

// ── Вход ────────────────────────────────────────────────────────────────
function renderLogin(msg) {
  $app.replaceChildren(el('div', { class: 'login' }, el('h1', {}, 'Дела'),
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
  const typing = () => document.activeElement?.id === 'quick' && document.activeElement.value;
  const refresh = () => sync().then(() => { if (!typing()) render(); }).catch(() => {});
  setInterval(() => { if (!document.hidden) refresh(); }, 30000);
  window.addEventListener('focus', refresh);
}

// ── Каркас ──────────────────────────────────────────────────────────────
function render() {
  if (!S.me) return;
  const r = route();
  const q0 = document.activeElement && document.activeElement.id === 'quick' ? document.activeElement : null;
  const focused = q0 ? { value: q0.value, a: q0.selectionStart, b: q0.selectionEnd } : null;
  const shell = el('div', { class: 'shell' + (S.cardId || S.draft ? ' with-card' : '') + (S.sideOpen ? ' side-open' : '') + (S.sel.size ? ' selecting' : '') });
  shell.append(renderSide(r), el('main', { class: 'list' }, renderMain(r)));
  if (S.cardId || S.draft) shell.append(renderCard());
  const scroll = document.querySelector('main.list')?.scrollTop || 0;
  $app.replaceChildren(shell);
  if (S.sideOpen) $app.append(el('div', { class: 'scrim', onclick: () => { S.sideOpen = false; render(); } }));
  shell.querySelector('main.list').scrollTop = scroll;
  if (focused) {
    const q = document.getElementById('quick');
    if (q && !q.disabled) { q.value = focused.value; q.focus(); q.setSelectionRange(focused.a, focused.b); q.dispatchEvent(new Event('input')); }
  }
  renderBulk();
}

function navItem(hash, ico, label, count, on, extra) {
  return el('button', { class: 'nav-item' + (on ? ' on' : ''), onclick: () => go(hash) },
    el('span', { class: 'ico' }, ico), el('span', { class: 'label' }, label), extra || null,
    count ? el('span', { class: 'n' }, count) : null);
}

function renderSide(r) {
  const search = el('input', { class: 'side-search', type: 'search', placeholder: 'Поиск  /', value: r.kind === 'search' ? r.q : '' });
  search.addEventListener('keydown', (e) => { if (e.key === 'Enter') go('#/search/' + encodeURIComponent(search.value.trim())); });
  const sphere = el('div', { class: 'seg' }, [['work', 'Работа'], ['home', 'Дом'], ['', 'Всё']].map(([v, t]) =>
    el('button', { class: S.sphere === v ? 'on' : '', onclick: () => { S.sphere = v; LS.set('sphere', v); render(); } }, t)));
  const nav = Object.entries(VIEWS).map(([k, v]) => {
    const n = v.count ? v.count() : 0;
    const badge = k === 'new' && n ? el('span', { class: 'badge-new' }, n) : null;
    return navItem('#/' + k, icon(v.icon), v.title, k === 'new' ? null : n, r.kind === k, badge);
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
  const projItem = (p) => navItem('#/p/' + p.id, icon('folder', 14), p.name, openBy.get(p.id), r.kind === 'project' && r.id === p.id,
    lateBy.has(p.id) ? el('span', { class: 'dot', title: 'есть просроченные' }) : null);
  const group = (key, title, items) => {
    if (!items.length) return null;
    return el('div', { class: 'side-group' + (S.closed[key] ? ' closed' : '') },
      el('button', { class: 'gh', onclick: () => { S.closed[key] = !S.closed[key]; LS.set('closed', S.closed); render(); } },
        el('span', { class: 'caret' }, '▾'), title, el('span', { class: 'n' }, items.length)),
      el('div', { class: 'items' }, items));
  };
  const favs = live.filter((p) => S.favs.includes(p.id));
  const groups = [group('fav', 'Избранное', favs.map(projItem))];
  for (const [kind, title] of Object.entries(KIND)) {
    groups.push(group('k-' + kind, title, live.filter((p) => p.kind === kind && !S.favs.includes(p.id)).map(projItem)));
  }
  // Люди: с кем больше всего открытых дел.
  const byPerson = new Map();
  for (const t of all()) if (isOpen(t) && t.person_id) byPerson.set(t.person_id, (byPerson.get(t.person_id) || 0) + 1);
  const people = [...byPerson.entries()].sort((a, b) => b[1] - a[1]).map(([id, n]) => [person(id), n]).filter(([p]) => p);
  groups.push(group('people', 'Люди', people.map(([p, n]) => navItem('#/h/' + p.id, icon('person', 14), personName(p), n, r.kind === 'person' && r.id === p.id))));
  const archived = [...S.projects.values()].filter((p) => p.archived_at).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  if (S.closed.arch === undefined) S.closed.arch = true;
  groups.push(group('arch', 'Архив', archived.map(projItem)));

  return el('aside', { class: 'side' },
    el('div', { class: 'brand' }, el('b', {}, 'Дела'), el('span', { class: 'who' }, S.me.name)),
    search, sphere, nav, groups,
    el('div', { class: 'side-foot' }, el('span', {}, el('span', { class: 'kbd' }, 'n'), ' новое · ', el('span', { class: 'kbd' }, '/'), ' поиск'),
      el('button', { onclick: async () => { await api('/auth/logout', {}); location.reload(); } }, 'Выйти')));
}

// ── Основная колонка ────────────────────────────────────────────────────
function head(title, sub, extra) {
  return el('div', { class: 'list-head' },
    el('div', { class: 'row1' },
      el('button', { class: 'burger', onclick: () => { S.sideOpen = true; render(); } }, '☰'),
      el('h1', {}, title), extra || null),
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
  if (r.kind === 'project') return renderProject(r.id);
  if (r.kind === 'person') return renderPerson(r.id);
  if (r.kind === 'search') return renderSearch(r.q);
  if (r.kind === 'new') return renderNew();
  if (r.kind === 'week') return renderWeek();
  const v = VIEWS[r.kind];
  if (r.kind === 'morning') {
    const secs = v.sections();
    const body = el('div', { class: 'body' }, quickAdd({}),
      secs.map(([t, items]) => (items.length ? groupBox(t, items.sort(sortTasks), { late: false }) : null)));
    if (!secs.some(([, i]) => i.length)) body.append(el('div', { class: 'empty' }, 'На сегодня пусто. Загляни в «Предстоящее».'));
    const n = pendingSugs().length;
    return [head('Утро', [D.long(S.today), n ? el('a', { class: 'link', href: '#/new' }, `в «Новом» ждут решения: ${n}`) : null]), body];
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
      el('div', { class: 'body' }, items.length ? grouped(items, 'person') : el('div', { class: 'empty' }, 'Ни от кого ничего не ждём.'))];
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
  for (const t of items) {
    if (by === 'date') {
      if (!isOpen(t)) put('9done', 'Сделано', t);
      else if (!t.due_date) put('8none', 'Без даты', t);
      else {
        const n = D.diff(t.due_date, S.today);
        if (n < 0) put('0late', 'Просрочено', t, { late: true });
        else if (n === 0) put('1today', 'Сегодня · ' + D.long(S.today), t);
        else if (n === 1) put('2tomorrow', 'Завтра · ' + D.long(t.due_date), t);
        else if (n < 7) put('3' + t.due_date, D.long(t.due_date), t);
        else if (n < 14) put('5next', 'Следующая неделя', t);
        else put('6later', 'Позже', t);
      }
    } else if (by === 'project') {
      const p = project(t.project_id);
      put(p ? '1' + p.name : '0', p ? p.name : 'Входящие', t, { href: p ? '#/p/' + p.id : '#/inbox' });
    } else if (by === 'person') {
      const p = person(t.person_id);
      put(p ? '1' + personName(p) : '2', p ? personName(p) : 'Без человека', t, { href: p ? '#/h/' + p.id : null });
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
  const h = title ? el('h2', { class: opts.late ? 'late' : '' },
    opts.href ? el('a', { class: 'link', href: opts.href }, title) : title, el('span', { class: 'n' }, items.length),
    opts.late && items.length > 1 ? el('span', { class: 'act' },
      el('button', { class: 'chip-btn', onclick: (e) => reschedulePop(e.currentTarget, items.map((t) => t.id)) }, 'Перенести все')) : null) : null;
  return el('div', { class: 'group' }, h, items.map((t) => taskRow(t, by)));
}

function taskRow(t, by) {
  S.order.push(t.id);
  const p = project(t.project_id);
  const who = person(t.person_id);
  const due = dueLabel(t);
  const chips = [];
  if (by !== 'project' && route().kind !== 'project') chips.push(p ? el('a', { class: 'link', href: '#/p/' + p.id, onclick: (e) => e.stopPropagation() }, p.name) : el('span', {}, 'Входящие'));
  const dl = t.deal_id ? S.deals.get(t.deal_id) : null;
  if (dl && by !== 'deal') chips.push(el('span', {}, dl.name));
  if (t.ball !== 'mine' && by !== 'ball') {
    chips.push(el('span', { class: 'ball-' + t.ball }, BALL[t.ball] + (who ? ' ' + personName(who) : '') + (t.ball === 'waiting' && t.waiting_since ? ' · ' + D.diff(S.today, t.waiting_since) + ' дн.' : '')));
  } else if (who && by !== 'person') {
    chips.push(el('a', { class: 'link', href: '#/h/' + who.id, onclick: (e) => e.stopPropagation() }, '@' + personName(who)));
  }
  if (due && (by !== 'date' || due.cls === 'late')) chips.push(el('span', { class: due.cls }, due.text));
  if (t.estimate_min) chips.push(el('span', {}, t.estimate_min + ' мин'));
  const m = moneyOf(t);
  if (m === 'paid' || m === 'potential') chips.push(el('span', { class: 'badge ' + m }, MONEY[m]));
  if (isNow(t)) chips.push(el('span', { class: 'badge now' }, 'сейчас'));
  for (const l of t.labels || []) chips.push(el('span', {}, '*' + l));
  const pick = el('input', { type: 'checkbox', class: 'pick', title: 'Выбрать (Shift — диапазон)' });
  pick.checked = S.sel.has(t.id);
  pick.addEventListener('click', (e) => { e.stopPropagation(); togglePick(t.id, e.shiftKey); });
  return el('div', {
    class: 'task' + (isOpen(t) ? '' : ' done') + (S.cardId === t.id ? ' open-now' : '') + (S.sel.has(t.id) ? ' sel' : ''),
    onclick: () => openCard(t.id),
  },
  pick,
  el('button', { class: 'tick' + (isOpen(t) ? '' : ' done'), title: isOpen(t) ? 'Сделано' : 'Вернуть', onclick: (e) => { e.stopPropagation(); toggleDone([t]); } }),
  el('div', { class: 'main' }, el('div', { class: 'title' }, el('span', { class: 'num' }, '#' + t.num), t.title), chips.length ? el('div', { class: 'chips' }, chips) : null),
  el('div', { class: 'acts' },
    el('button', { class: 'icon-btn', title: 'Перенести', onclick: (e) => { e.stopPropagation(); reschedulePop(e.currentTarget, [t.id]); } }, icon('date', 14)),
    el('button', { class: 'icon-btn', title: isNow(t) ? 'Убрать из «Сейчас»' : 'В «Сейчас»', onclick: (e) => { e.stopPropagation(); setFields([t.id], { focus_on: isNow(t) ? null : S.today }); } }, icon('bolt', 14))));
}

// ── Действия ────────────────────────────────────────────────────────────
async function toggleDone(list) {
  const open = list.filter(isOpen);
  const target = open.length ? open : list;
  const opName = open.length ? 'task.done' : 'task.reopen';
  try {
    await ops(target.map((t) => ({ op: opName, id: t.id })));
    S.sel.clear();
    render();
    toast(open.length ? (target.length > 1 ? `Сделано: ${target.length}` : 'Сделано: ' + target[0].title) : 'Вернул в работу',
      () => ops(target.map((t) => ({ op: open.length ? 'task.reopen' : 'task.done', id: t.id }))));
  } catch (e) { fail(e); render(); }
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
  ['оплачено', 'деньги: оплата согласована'], ['развитие', 'деньги: развитие бизнеса'],
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
function quickAdd(defaults) {
  const busy = !!S.parse;
  const input = el('textarea', { id: 'quick', rows: 1, autocomplete: 'off', disabled: busy,
    placeholder: 'Новое дело: «Иван: прислать модель +Альфа @Иван !жду в пятницу» или текст для Claude' });
  if (busy) input.value = S.parse.text;
  else if (S.quickDraft) { input.value = S.quickDraft; S.quickDraft = null; }
  const preview = el('div', { class: 'preview' });
  const help = el('div', { class: 'help hidden' },
    'Enter — одно дело · Ctrl+Enter или звёздочка — Claude разберёт текст на несколько дел · Shift+Enter — новая строка · ',
    '+проект  @человек  *метка  !жду  !повестка  !сейчас  !хочу  !15м  !оплачено · сегодня, завтра, в пятницу, через 3 дня, 12.10 · «в кавычках» — без разбора');
  const status = busy ? el('div', { class: 'claude-status' }, 'Claude разбирает… Можно уходить на другие страницы — дела появятся сами.') : null;
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
    if (e.ctrlKey || e.metaKey || input.value.trim().includes('\n')) claudeParse(input, defaults);
    else addOne();
  });

  const plusBtn = el('button', { type: 'button', class: 'q-btn plus', title: 'Записать одно дело (Enter)', disabled: busy, onclick: addOne }, '+');
  const claudeBtn = el('button', { type: 'button', class: 'q-btn claude-btn' + (busy ? ' busy' : ''), disabled: busy,
    title: S.me.claude ? 'Claude: разобрать текст на дела — люди, проекты, сроки, заметки (Ctrl+Enter)' : 'Claude на сервере ещё не настроен',
    onclick: () => claudeParse(input, defaults) }, claudeIcon());
  if (input.value) setTimeout(grow);
  return el('div', { class: 'quick' },
    el('div', { class: 'quick-wrap' }, el('div', { class: 'quick-box' + (busy ? ' busy' : '') }, plusBtn, input, claudeBtn), ac),
    status, preview, help);
}

// ── Разбор Claude: задание на сервере, ответ — дела и заметки ───────────
async function claudeParse(input, defaults) {
  const text = input.value.trim();
  if (S.parse) return;
  if (!text) { toast('Напиши или надиктуй, что разобрать: Claude разложит на дела'); input.focus(); return; }
  const body = { text };
  for (const k of ['project_id', 'person_id']) if (defaults[k]) body[k] = defaults[k];
  S.parse = { text };
  render();
  try {
    const { job } = await api('/api/parse', body);
    let d;
    for (let i = 0; ; i++) {
      await new Promise((r) => setTimeout(r, i < 15 ? 1000 : 2500));
      d = await api('/api/parse/' + job);
      if (d.status !== 'run') break;
      if (i > 150) throw new Error('Claude думает дольше пяти минут — дела появятся сами, когда он закончит');
    }
    S.parse = null;
    for (const t of d.tasks) S.tasks.set(t.id, t);
    await sync().catch(() => {});
    render();
    claudeResult(d);
  } catch (e) {
    S.quickDraft = S.parse && S.parse.text;
    S.parse = null;
    render();
    fail(e);
  }
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
  const sub = [KIND[p.kind] || p.kind, 'деньги по умолчанию: ' + MONEY[p.money_default], plural(open.length, 'открытое', 'открытых', 'открытых')];
  const late = open.filter(isLate).length;
  if (late) sub.push(el('span', { class: 'late' }, 'просрочено: ' + late));
  if (p.aliases && p.aliases.length) sub.push('ещё зовут: ' + p.aliases.join(', '));
  const dealBox = deals.length ? el('div', { class: 'deals' }, deals.map((d) => el('div', {
    class: 'deal' + (S.dealFilter === d.id ? ' on' : ''), title: 'Показать только дела этой сделки',
    onclick: () => { S.dealFilter = S.dealFilter === d.id ? null : d.id; render(); },
  }, el('div', {}, d.name), el('div', { class: 'stage' }, STAGE[d.stage] || d.stage, d.fee_kop ? ' · fee ' + Math.round(d.fee_kop / 100).toLocaleString('ru') + ' ₽' : ''),
  d.next_step ? el('div', { class: 'next' }, d.next_step.length > 110 ? d.next_step.slice(0, 110) + '…' : d.next_step) : null))) : null;
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
      secs.every(([, i]) => !i.length) ? el('div', { class: 'empty' }, 'Открытых дел с ним нет.') : null)];
}

function renderSearch(q) {
  const words = norm(q).split(/\s+/).filter(Boolean);
  const items = words.length ? all().filter((t) => {
    const hay = norm(t.title + ' ' + (t.notes || '') + ' ' + (project(t.project_id)?.name || '') + ' ' + personName(person(t.person_id)));
    return words.every((w) => hay.includes(w)) && (S.showDone || isOpen(t));
  }) : [];
  return [head('Поиск: ' + q, [plural(items.length, 'дело', 'дела', 'дел')]),
    el('div', { class: 'body' }, el('div', { class: 'toolbar' }, doneToggle()), items.length ? grouped(items, 'project') : el('div', { class: 'empty' }, 'Ничего не нашлось.'))];
}

function renderWeek() {
  const m = openMine();
  const now = Date.now();
  const stale = m.filter((t) => (t.due_date && D.diff(S.today, t.due_date) > 14) || (now - Date.parse(t.updated_at)) > 21 * 86400e3);
  const waitStale = m.filter((t) => t.ball === 'waiting' && t.waiting_since && D.diff(S.today, t.waiting_since) > 7 && (!t.nudge_on || t.nudge_on < S.today));
  const busy = new Set(all().filter((t) => isOpen(t) && t.ball === 'mine' && t.project_id).map((t) => t.project_id));
  const noStep = [...S.projects.values()].filter((p) => !p.archived_at && p.owner_id === S.me.user && ['paid', 'potential'].includes(p.money_default) && !busy.has(p.id));
  return [head('Неделя', ['раз в неделю: что протухло, кто молчит, где нет следующего шага']),
    el('div', { class: 'body' },
      stale.length ? groupBox('Протухшее — закрыть, перенести или отпустить', stale.sort(sortTasks), { late: true }) : null,
      waitStale.length ? groupBox('Жду без движения больше недели', waitStale.sort(sortTasks)) : null,
      noStep.length ? el('div', { class: 'group' }, el('h2', {}, 'Проекты с деньгами без моего следующего шага', el('span', { class: 'n' }, noStep.length)),
        noStep.map((p) => navItem('#/p/' + p.id, icon('folder', 14), p.name, MONEY[p.money_default], false))) : null,
      !stale.length && !waitStale.length && !noStep.length ? el('div', { class: 'empty' }, 'Чисто. Неделя разобрана.') : null)];
}

function renderNew() {
  const list = pendingSugs().sort((a, b) => (a.batch_ref || '').localeCompare(b.batch_ref || '') || a.created_at.localeCompare(b.created_at));
  const batches = new Map();
  for (const s of list) {
    const key = s.batch_ref || 'one:' + s.id;
    if (!batches.has(key)) batches.set(key, { title: s.batch_title, items: [] });
    batches.get(key).items.push(s);
  }
  const box = el('div', { class: 'body' });
  if (!list.length) box.append(el('div', { class: 'empty' }, 'Новых предложений нет.'));
  for (const b of batches.values()) {
    const ids = b.items.map((s) => s.id);
    box.append(el('div', { class: 'group' },
      el('h2', {}, b.title || 'Без пачки', el('span', { class: 'n' }, b.items.length), ids.length > 1 ? el('span', { class: 'act' },
        el('button', { class: 'chip-btn', onclick: () => decide(ids, 'accept') }, 'Принять все'), ' ',
        el('button', { class: 'chip-btn', onclick: () => decide(ids, 'reject') }, 'Отклонить все')) : null),
      b.items.map((s) => {
        const p = s.payload || {};
        const hint = [p.project_name, p.person_name, BALL[p.ball], p.due_date && 'срок ' + D.ddmm(p.due_date)].filter(Boolean).join(' · ');
        return el('div', { class: 'sug' },
          el('div', { class: 'main' }, el('div', {}, s.kind === 'create' ? p.title : `${s.kind}: ${p.title || ''}`), hint ? el('div', { class: 'hint' }, hint) : null,
            s.quote && s.quote !== p.title ? el('div', { class: 'hint' }, '«' + s.quote.slice(0, 160) + '»') : null),
          el('div', { class: 'acts' },
            el('button', { class: 'btn small ok', onclick: () => decide([s.id], 'accept') }, 'Принять'),
            el('button', { class: 'btn small', onclick: () => { S.draft = s; S.cardId = null; render(); } }, 'Поправить'),
            el('button', { class: 'btn small bad', onclick: () => { const r = prompt('Почему не дело? (можно пусто)'); if (r !== null) decide([s.id], 'reject', r || null); } }, 'Отклонить')));
      })));
  }
  return [head('Новое', ['что предложила автоматика: встречи, Telegram — дела появятся, когда примешь']), box];
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
function openCard(id) { S.cardId = id; S.draft = null; render(); loadCardExtras(id); }
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
    el('span', {}, 'Деньги'), sel('money', [['', 'как у проекта'], ['paid', 'оплачено'], ['potential', 'развитие'], ['none', 'без денег']], src.money),
    el('span', {}, 'Метки'), labels);

  const card = el('div', { class: 'card' },
    el('div', { class: 'top' }, el('span', { class: 'num' }, t ? '#' + t.num + (t.status !== 'open' ? ' · ' + (t.status === 'done' ? 'сделано' : 'отменено') : '') : 'Предложение'),
      el('button', { class: 'icon-btn', title: 'Закрыть (Esc)', onclick: close }, icon('x', 16))),
    title, props,
    el('div', { class: 'checks' }, chk('focus_on', 'Сейчас — на сегодня', isNow(src), S.today), chk('want', 'Хочу сам', src.want, true)),
    notes);
  const btns = el('div', { class: 'btns' });
  if (t) {
    btns.append(isOpen(t)
      ? el('button', { class: 'btn main', onclick: () => toggleDone([t]) }, 'Сделано')
      : el('button', { class: 'btn', onclick: () => toggleDone([t]) }, 'Вернуть в работу'));
    if (isOpen(t)) btns.append(el('button', { class: 'btn bad', onclick: async () => { if (confirm('Отменить дело? Оно останется в журнале.')) { try { await op1({ op: 'task.cancel', id: t.id }); render(); } catch (e) { fail(e); } } } }, 'Отменить дело'));
    const p = project(t.project_id), who = person(t.person_id);
    if (p) btns.append(el('a', { class: 'btn', href: '#/p/' + p.id }, p.name + ' →'));
    if (who) btns.append(el('a', { class: 'btn', href: '#/h/' + who.id }, personName(who) + ' →'));
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
      (x ? x.comments : []).map((c) => el('div', { class: 'comment' }, el('div', { class: 'who' }, `${c.author_id} · ${c.created_at.slice(0, 16).replace('T', ' ')}`), c.text)),
      el('div', { class: 'add-comment' }, add, el('button', { class: 'btn small', onclick: send }, 'Добавить')));
    if (x) {
      card.append(el('details', {}, el('summary', {}, 'Журнал правок'), x.history.map((h) =>
        el('div', { class: 'hist' }, `${h.at.slice(0, 16).replace('T', ' ')} · ${h.actor} (${h.via}) · ${h.op === 'update' ? Object.keys(h.after || {}).join(', ') : h.op}`))));
    }
  }
  return el('section', { class: 'card-pane' }, card);
}

// ── Клавиши ─────────────────────────────────────────────────────────────
document.addEventListener('keydown', (e) => {
  const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName);
  if (e.key === 'Escape') {
    if (document.querySelector('.pop')) { closePop(); return; }
    if (S.cardId || S.draft) { S.cardId = null; S.draft = null; render(); return; }
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
