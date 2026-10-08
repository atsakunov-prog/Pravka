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
  orgs: new Map(), pays: new Map(), // CRM: организации, оплаты
  crmList: LS.get('crmList', false), crmMine: false, crmStale: false, clientQ: '', clientArch: false, clientTab: 'tasks',
  sphere: LS.get('sphere', ''), groups: LS.get('groups', {}), favs: LS.get('favs', []), closed: LS.get('closed', {}),
  showDone: false, upNoDate: false, upMineOnly: false, dealFilter: null,
  sel: new Set(), order: [], lastPick: null, cardId: null, draft: null, sideOpen: false,
  parse: null, quickText: '', // идёт разбор Claude; текст строки Claude — живёт, пока его не отдали (перерисовка его не стирает)
  askTask: null, askText: '', asking: null, // микрофон у дела: какое открыто, текст, идёт правка
  autoOpen: false, clientOpen: new Set(LS.get('clientOpen', [])), peopleQ: '', // «Сделано само» развёрнуто, клиенты со сделками, люди
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

// ── Напоминания в Telegram (06.10.2026; слово в слово DelaRemind на телефоне) ───
// Часы — московские при любом поясе браузера: в Москве нет перевода часов, сдвиг всегда +3.
const MSK_MS = 3 * 3600e3;
const msk = (ms) => { const s = new Date(ms + MSK_MS).toISOString(); return { day: s.slice(0, 10), hm: s.slice(11, 16) }; };
const mskIso = (day, hm) => `${day}T${hm}:00+03:00`;
const remindOn = () => ((S.me && S.me.features) || []).includes('remind');
const myPlaces = () => ((S.me && S.me.settings && S.me.settings.places) || []).filter((x) => String(x || '').trim());
/** «11:00», «завтра 09:00», «чт 09:00» (до недели вперёд), иначе «12.10 09:00». */
function remindWords(iso) {
  const { day, hm } = msk(Date.parse(iso));
  const n = D.diff(day, S.today);
  if (n === 0) return hm;
  if (n === 1) return 'завтра ' + hm;
  if (n >= 2 && n <= 6) return WD_SHORT[D.wd(day)] + ' ' + hm;
  return day.slice(8, 10) + '.' + day.slice(5, 7) + ' ' + hm;
}
/** Подпись строки: «⏰ 11:00», «⏰ дом»; отправленное и закрытое — без подписи. */
function remindChip(t) {
  if (t.reminded_at || t.status !== 'open') return '';
  if (t.remind_at) return '⏰ ' + remindWords(t.remind_at);
  return t.remind_place ? '⏰ ' + t.remind_place : '';
}
function remindState(t) {
  if (t.reminded_at) return 'отправлено в Telegram ' + remindWords(t.reminded_at);
  if (t.remind_at) return (t.remind_place ? `приехал «${t.remind_place}» — ` : '') + 'напомню в Telegram ' + remindWords(t.remind_at);
  if (t.remind_place) return 'напомню в Telegram, когда приедешь: ' + t.remind_place;
  return '';
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

// «Утра» и «Входящих» больше нет (владелец, 06.10.2026: «утро непонятно, что это такое… входящие —
// чем они отличаются от нового»). «Сейчас» — отдельной строкой, до пяти дел на сегодня; просроченное —
// наверху «Предстоящего»; «пора напомнить» — наверху «Жду»; дела без проекта и поставленные другими —
// в «Новом», рядом с наговорками и предложениями автоматики.
const NOW_MAX = 5;
const nowTasks = () => all().filter((t) => isOpen(t) && isMine(t) && isNow(t));
const nudgeDue = (t) => t.ball === 'waiting' && ((t.nudge_on && t.nudge_on <= S.today) || (t.due_date && t.due_date <= S.today));
const VIEWS = {
  // У каждого вида свой цвет значка — как у умных списков Things: глаз находит пункт раньше, чем читает.
  now: { title: 'Сейчас', icon: 'bolt', color: 'var(--tint)', count: () => nowTasks().length },
  new: { title: 'Новое', icon: 'inbox-in', color: 'var(--tint)', count: () => newCount() },
  upcoming: { title: 'Предстоящее', icon: 'cal', color: 'var(--tint)', count: () => openMine().filter((t) => t.due_date && t.due_date <= D.add(S.today, 7)).length,
    hot: () => openMine().some(isLate) },
  waiting: { title: 'Жду', icon: 'hourglass', color: 'var(--tint)', count: () => openMine().filter((t) => t.ball === 'waiting').length,
    hot: () => openMine().some(nudgeDue) },
  week: { title: 'Неделя', icon: 'broom', color: 'var(--tint)' },
  all: { title: 'Все дела', icon: 'list', color: 'var(--tint)' },
};
const NO_PROJECT = { color: 'var(--meta)' }; // дела без проекта (бывшие «Входящие») — разложить в «Неделе»
const pendingSugs = () => [...S.sugs.values()].filter((s) => s.status === 'pending' && s.for_user === S.me.user);
/** Закрытия и уточнения, которые сервер принял сам (store._auto: владелец 05.10 и 08.10.2026 — «спокойно
 *  закрывай и спокойно уточняй»), — за days дней, свежие сверху. Узнаются по причине решения: принятое руками
 *  предложение с payload.auto сюда не попадает. «Понятно» (seen_at) убирает их совсем: 06.10.2026 #268 висел
 *  неделю и «никуда не уходил». */
const AUTO_REASONS = ['закрыто само', 'уточнено само'];
const autoDone = (days) => [...S.sugs.values()].filter((s) => (s.kind === 'close' || s.kind === 'update') && s.status === 'accepted'
  && AUTO_REASONS.includes(s.reason) && !s.seen_at && s.for_user === S.me.user && s.decided_at && Date.now() - Date.parse(s.decided_at) < days * 86400e3)
  .sort((a, b) => b.decided_at.localeCompare(a.decided_at));
const noProject = () => openMine().filter((t) => !t.project_id);
// Число у «Нового» — только вопросы: поставленное и сделанное само ответа не ждут (владелец 08.10.2026).
const newCount = () => pendingSugs().length;

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
  // «Новое» (08.10.2026): встреча — двое, руками — карандаш, вопрос — кружок с «?».
  meet: 'M9 11a3 3 0 1 0 0-6a3 3 0 1 0 0 6zM3 20c.5-3.5 3-5.5 6-5.5s5.5 2 6 5.5M16 11a2.5 2.5 0 1 0 0-5M17.5 14.5c2 .5 3.3 2.3 3.5 5.5',
  pen: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
  help: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17h.01',
  // Правка 4.0 (Material Symbols Rounded, вес 300 — как в макетах): одно действие — один значок везде.
  back: 'M14.5 6l-6 6 6 6',
  chev: 'M9.5 6l6 6-6 6',
  down: 'M7 10l5 5 5-5',
  menu: 'M4 7h16M4 12h16M4 17h16',
  donut: 'M12 3.5a8.5 8.5 0 1 0 8.5 8.5H12zM12 3.5a8.5 8.5 0 0 1 8.5 8.5',
  up: 'M12 19V5.5M6 11l6-6 6 6',
  stop: 'M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z',
  tick: 'M20 6L9 17l-5-5',
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
/** Длительность как в Правке: «45 м», «1 ч», «1 ч 40 м» — никогда «мин» (DESIGN §3.9). */
const dur = (m) => (!m ? '' : m >= 60 ? `${Math.floor(m / 60)} ч` + (m % 60 ? ` ${m % 60} м` : '') : m + ' м');

/** Монета режима: круг в key с бликом и знаком. Дела — галочка плавающей кнопки (ic_mode_delo_btn);
 *  у страниц внутри — значок страницы или инициалы человека. */
function coin(what, cls) {
  const c = el('span', { class: 'coin' + (cls ? ' ' + cls : ''), 'aria-hidden': 'true' });
  if (ICONS[what]) {
    const svg = icon(what, 18);
    svg.setAttribute('stroke-width', what === 'tick' ? '2.4' : '2');
    c.append(svg);
  } else c.append(what);
  return c;
}
const initials = (name) => (name || '?').split(/\s+/).map((w) => (w.match(/[\p{L}\p{N}]/u) || [''])[0]).filter(Boolean).slice(0, 2).join('').toUpperCase() || '?';

// ── Цвета: у проекта и человека свой оттенок — по id, чтобы не менялся от переименования ──
// Цвет = источник (Правка 4.0): внутри Дел только синий режима — «свой оттенок» стал ступенью его шкалы, а не радугой.
const HUES = ['#D4E6F5', '#9FC2DE', '#6FA3CC', '#4A82AE', '#B9D3E9', '#8DB6D8', '#7F9AB2'];
const hueOf = (id) => { let h = 0; for (const c of String(id)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return HUES[h % HUES.length]; };
/** Цвет — переменной --c (CSSOM, CSP не мешает). */
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
  if (kind === 'd' && id) return { kind: 'deal', id }; // сделка — страницей, как клиент и человек (06.10.2026)
  if (kind === 'search') return { kind: 'search', q: decodeURIComponent(id || '') };
  if (kind === 'new' && id) return { kind: 'new', batch: decodeURIComponent(id) }; // ссылка из Telegram — одна пачка
  if (kind === 'task' && /^\d+$/.test(id || '')) return { kind: 'now', task: +id }; // кнопка «Открыть» под напоминанием
  if (kind === 'settings') return { kind: 'settings' };
  if (kind === 'stats') return { kind: 'stats' };
  if (kind === 'inbox') return { kind: 'new' }; // старые закладки: «Входящие» теперь в «Новом»
  if (CRM_VIEWS[kind]) return { kind };
  return { kind: VIEWS[kind] ? kind : 'now' };
}
const go = (hash) => { location.hash = hash; };
window.addEventListener('hashchange', () => { S.sel.clear(); S.dealFilter = null; S.sideOpen = false; S.toTop = true; render(); });

// ── Вход ────────────────────────────────────────────────────────────────
function renderLogin(msg) {
  $app.replaceChildren(el('div', { class: 'login' }, coin('tick', 'big'), el('h1', {}, 'Дела'),
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
  const refresh = () => sync().then(softRender).catch(() => {});
  setInterval(() => { if (!document.hidden) refresh(); }, 30000);
  window.addEventListener('focus', refresh);
}

// ── Каркас ──────────────────────────────────────────────────────────────
function render() {
  if (!S.me) return;
  const r = route();
  if (r.task) { // #task/61: карточка этого дела поверх «Сейчас», адрес — обычный
    const t = all().find((x) => x.num === r.task);
    history.replaceState(null, '', '#/now');
    if (t) { openCard(t.id); return; }
  }
  const ae = document.activeElement;
  const q0 = ae && (['quick', 'side-search', 'main-search'].includes(ae.id) || ae.classList?.contains('ask-input')) ? ae : null;
  const focused = q0 ? { id: q0.id, value: q0.value, a: q0.selectionStart, b: q0.selectionEnd } : null;
  const shell = el('div', { class: 'shell' + (S.cardId || S.draft ? ' with-card' : '') + (S.sideOpen ? ' side-open' : '') + (S.sel.size ? ' selecting' : '') });
  shell.append(renderSide(r), listColumn(r));
  if (S.cardId || S.draft) shell.append(renderCard());
  const scroll = S.toTop ? 0 : document.querySelector('main.list > .scroll')?.scrollTop || 0; // новая страница — с начала
  S.toTop = false;
  const old = document.querySelector('section.card-pane');
  const cardScroll = old ? [old.dataset.key, old.scrollTop] : null;
  const sideScroll = document.querySelector('aside.side')?.scrollTop || 0;
  const pillScroll = document.querySelector('.pills')?.scrollLeft;
  $app.replaceChildren(shell);
  shell.querySelector('aside.side').scrollTop = sideScroll;
  const pane = shell.querySelector('section.card-pane');
  if (pane && cardScroll && pane.dataset.key === cardScroll[0]) pane.scrollTop = cardScroll[1];
  if (S.sideOpen) $app.append(el('div', { class: 'scrim', onclick: () => { S.sideOpen = false; render(); } }));
  shell.querySelector('main.list > .scroll').scrollTop = scroll;
  // Вкладки листаются вбок: выбранная — в поле зрения, иначе — где была.
  const pills = shell.querySelector('.pills'), on = pills?.querySelector('.on');
  if (pills && on && (on.offsetLeft < pills.scrollLeft || on.offsetLeft + on.offsetWidth > pills.scrollLeft + pills.clientWidth)) pills.scrollLeft = on.offsetLeft - 12;
  else if (pills && pillScroll != null) pills.scrollLeft = pillScroll;
  if (focused) {
    const q = document.getElementById(focused.id);
    if (q && !q.disabled) {
      q.value = focused.value; q.focus(); q.setSelectionRange(focused.a, focused.b);
      if (focused.id === 'quick') q.dispatchEvent(new Event('input'));
    }
  }
  renderBulk();
}

/** Колонка списка как экран режима в Правке 4.0: шапка стоит, между ней и строкой «сказать» прокручивается
 *  содержимое. Страницы по-прежнему кладут строку «Скажи, что сделать» первой в тело — здесь она уезжает
 *  вниз, под большой палец (DESIGN §1: «пилюли-строки наверху больше нет»). */
const NO_SAY = ['settings', 'stats'];
function listColumn(r) {
  const parts = [renderMain(r)].flat(Infinity).filter(Boolean);
  const top = parts[0]?.classList?.contains('list-head') ? parts.shift() : null;
  const scroll = el('div', { class: 'scroll' }, parts);
  plate(scroll);
  let say = scroll.querySelector('.quick.say');
  if (!say && !NO_SAY.includes(r.kind)) say = quickAdd({});
  return el('main', { class: 'list' }, top, scroll, el('div', { class: 'dock' }, say));
}

/** Раздел = надстрочник над стеклянной плашкой: всё, что в группе под заголовком, — в одну плашку со
 *  строками через волосяную линию. Плитки и доска — сами стекло, их не оборачиваем. */
const OWN_GLASS = '.dlist, .tiles, .board, .choices, .day-chart, .lv-card, .deals, .pchips, .person-info, .toolbar, .empty';
function plate(root) {
  for (const g of root.querySelectorAll('.group')) {
    const kids = [...g.children].filter((x) => x.tagName !== 'H2');
    if (!kids.length || kids.every((x) => x.matches(OWN_GLASS) || x.classList.contains('rows'))) continue;
    const rows = el('div', { class: 'rows' });
    kids[0].before(rows);
    rows.append(...kids);
  }
}

// ── Перерисовка не по действию человека ─────────────────────────────────
// Синк раз в 30 с и пришедшие данные видов перерисовывали страницу целиком — и открытый выпадающий
// список, меню или начатый ввод пропадали (владелец, 06.10.2026: «открываю выпадающий список, страница
// двигается — он пропадает»). Пока человек в списке, меню или пишет — данные копятся, страница ждёт;
// отпустил — перерисовка.
function uiBusy() {
  const ae = document.activeElement;
  if (rec || S.askTask || document.querySelector('.pop')) return true;
  if (!ae || ae === document.body) return false;
  if (ae.tagName === 'SELECT') return true;
  // Поиск перерисовка переживает (фокус и текст возвращаются), остальной начатый ввод — нет.
  return /^(INPUT|TEXTAREA)$/.test(ae.tagName) && !!ae.value && !['side-search', 'main-search'].includes(ae.id);
}
function softRender() {
  if (uiBusy()) { S.dirty = true; return; }
  S.dirty = false;
  render();
}
function afterBusy() { setTimeout(() => { if (S.dirty && !uiBusy()) { S.dirty = false; render(); } }, 60); }
document.addEventListener('focusout', afterBusy);
document.addEventListener('change', (e) => { if (e.target.tagName === 'SELECT') afterBusy(); });

function navItem(hash, ico, label, count, on, extra, hot) {
  return el('button', { class: 'nav-item' + (on ? ' on' : ''), onclick: () => go(hash) },
    el('span', { class: 'ico' }, ico), el('span', { class: 'label' }, label), extra || null,
    count ? el('span', { class: 'n' + (hot ? ' hot' : ''), title: hot ? 'есть просроченные' : null }, count) : null);
}

function renderSide(r) {
  const search = el('input', { id: 'side-search', class: 'side-search', type: 'search', placeholder: 'Поиск  /', value: r.kind === 'search' ? r.q : '' });
  search.addEventListener('input', () => liveSearch(search.value));
  search.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { S.sideOpen = false; liveSearch(search.value); } // на телефоне — убрать шторку, результаты под ней
    if (e.key === 'Escape') { search.value = ''; search.blur(); }
  });
  // Виды («Сейчас», «Новое»…) и сфера — в шапке списка вкладками и «Все сферы ⌄», как в Правке;
  // полка — только разделы: CRM, избранное, клиенты, проекты, люди, архив.

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
  const plainItem = (p) => navItem('#/p/' + p.id, dot(p.id), p.name, openBy.get(p.id), r.kind === 'project' && r.id === p.id, null, lateBy.has(p.id));
  // Клиент — с раскрывающимися проектами (сделками) прямо в меню (владелец, 06.10.2026: «проекты
  // группируются под клиента… в клиентах раскрываемый список у каждого клиента по проектам»).
  const projItem = (p) => {
    if (p.kind !== 'client') return plainItem(p);
    const deals = clientDeals(p.id).filter((d) => d.stage !== 'archive');
    if (!deals.length) return el('div', { class: 'side-client' }, el('span', { class: 'side-caret' }), plainItem(p));
    const here = r.kind === 'deal' && deals.some((d) => d.id === r.id);
    const open = S.clientOpen.has(p.id) || here;
    const caret = el('button', { class: 'side-caret', title: open ? 'Свернуть проекты' : `Проекты клиента: ${deals.length}`,
      onclick: (e) => { e.stopPropagation(); toggleClientOpen(p.id); } }, open ? '▾' : '▸');
    return el('div', { class: 'side-client' + (open ? ' open' : '') }, caret, plainItem(p),
      open ? el('div', { class: 'side-deals' }, deals.map((d) => {
        const ts = all().filter((t) => t.deal_id === d.id && isOpen(t));
        return navItem('#/d/' + d.id, el('span', { class: 'st-dot st-' + d.stage, title: STAGE[d.stage] }), dealShort(d, p), ts.length || null,
          r.kind === 'deal' && r.id === d.id, null, ts.some(isLate));
      })) : null);
  };
  const group = (key, title, items, act) => {
    if (!items.length) return null;
    return el('div', { class: 'side-group' + (S.closed[key] ? ' closed' : '') },
      el('div', { class: 'gh-row' },
        el('button', { class: 'gh', onclick: () => { S.closed[key] = !S.closed[key]; LS.set('closed', S.closed); render(); } },
          el('span', { class: 'caret' }, '▾'), title, el('span', { class: 'n' }, items.length)),
        act && !S.closed[key] ? act : null),
      el('div', { class: 'items' }, items));
  };
  const favs = live.filter((p) => S.favs.includes(p.id));
  const crmNav = crmOn() ? Object.entries(CRM_VIEWS).filter(([, v]) => !v.money || S.me.money)
    .map(([k, v]) => navItem('#/' + k, tint(icon(v.icon), v.color), v.title, null, r.kind === k)) : [];
  const groups = [group('crm', 'CRM', crmNav), group('fav', 'Избранное', favs.map(projItem))];
  for (const [kind, title] of Object.entries(KIND)) {
    const list = live.filter((p) => p.kind === kind && !S.favs.includes(p.id));
    groups.push(group('k-' + kind, title, list.map(projItem), kind === 'client' ? openAllBtn(list) : null));
  }
  // Люди: с кем больше всего открытых дел. С CRM — отдельная страница «Люди» по компаниям.
  if (!crmOn()) {
    const byPerson = new Map();
    for (const t of all()) if (isOpen(t) && t.person_id) byPerson.set(t.person_id, (byPerson.get(t.person_id) || 0) + 1);
    const people = [...byPerson.entries()].sort((a, b) => b[1] - a[1]).map(([id, n]) => [person(id), n]).filter(([p]) => p);
    groups.push(group('people', 'Люди', people.map(([p, n]) => navItem('#/h/' + p.id, avatar(p), personName(p), n, r.kind === 'person' && r.id === p.id))));
  }
  const archived = [...S.projects.values()].filter((p) => p.archived_at).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  if (S.closed.arch === undefined) S.closed.arch = true;
  groups.push(group('arch', 'Архив', archived.map(projItem)));

  const me = person(S.me.person_id) || { id: S.me.user, name: S.me.name };
  return el('aside', { class: 'side' },
    el('div', { class: 'brand' }, coin('tick'), el('b', {}, 'Дела'),
      el('span', { class: 'who' }, S.me.name), avatar(me)),
    gameCard(),
    search, groups,
    el('div', { class: 'side-foot' },
      el('button', { class: 'settings-btn' + (r.kind === 'settings' ? ' on' : ''), onclick: () => go('#/settings') }, icon('gear', 15), 'Настройки'),
      el('button', { onclick: async () => { await api('/auth/logout', {}); location.reload(); } }, 'Выйти')),
    el('div', { class: 'side-keys' }, el('span', { class: 'kbd' }, 'n'), ' сказать Claude · ', el('span', { class: 'kbd' }, '/'), ' поиск'));
}

/** «Раскрыть все / свернуть все» — проекты всех клиентов списка разом (владелец, 06.10.2026). */
function openAllBtn(clients) {
  const ids = clients.filter((p) => clientDeals(p.id).some((d) => d.stage !== 'archive')).map((p) => p.id);
  if (!ids.length) return null;
  const allOpen = ids.every((id) => S.clientOpen.has(id));
  return el('button', { class: 'gh-act', title: allOpen ? 'Свернуть проекты всех клиентов' : 'Показать проекты всех клиентов',
    onclick: () => { ids.forEach((id) => (allOpen ? S.clientOpen.delete(id) : S.clientOpen.add(id))); LS.set('clientOpen', [...S.clientOpen]); render(); } },
  allOpen ? 'свернуть все' : 'раскрыть все');
}

/** Поиск сразу, по мере набора: адрес — #/search/<текст>; первая буква — новая запись истории
 *  (назад — туда, откуда пришёл), дальше та же. Ищет всё: клиентов, проекты, людей, дела. */
function liveSearch(v) {
  const h = '#/search/' + encodeURIComponent(v.trim());
  if (route().kind === 'search') history.replaceState(null, '', h);
  else { history.pushState(null, '', h); S.toTop = true; S.sel.clear(); S.dealFilter = null; }
  render();
}

// ── Основная колонка ────────────────────────────────────────────────────
// ── Шапка режима (Правка 4.0, ModeHeader): монета, название Literata, второй тон; строка состояния;
// вкладки-пилюли. На видах («Сейчас», «Новое»…) название — «Дела», второй тон — выбор сферы; на страницах
// внутри (клиент, человек, сделка, CRM) — «‹», значок страницы на монете и её имя.
const KIND_ONE = { client: 'Клиент', internal: 'Внутреннее', personal: 'Личное' };
const SPHERE = { work: 'Работа', home: 'Дом', '': 'Все сферы' };
function headCoin(r) {
  if (VIEWS[r.kind]) return coin('tick');
  if (CRM_VIEWS[r.kind]) return coin(CRM_VIEWS[r.kind].icon);
  if (r.kind === 'project') return coin(project(r.id)?.kind === 'client' ? 'building' : 'folder');
  if (r.kind === 'person' && person(r.id)) return coin(initials(person(r.id).name));
  if (r.kind === 'deal') return coin('funnel');
  return coin({ search: 'search', settings: 'gear', stats: 'chart' }[r.kind] || 'tick');
}
function headSub(r) {
  if (VIEWS[r.kind]) {
    return el('button', { class: 'h-sub', title: 'Сфера: работа, дом или всё', onclick: (e) => {
      const at = e.currentTarget;
      setTimeout(() => popAt(at, Object.entries(SPHERE).map(([v, t]) => el('button', { onclick: () => { closePop(); S.sphere = v; LS.set('sphere', v); render(); } },
        t, S.sphere === v ? el('span', { class: 'k' }, icon('check', 14)) : null))), 0);
    } }, SPHERE[S.sphere] || SPHERE[''], icon('down', 14));
  }
  const p = r.kind === 'project' ? project(r.id) : null;
  const dl = r.kind === 'deal' ? S.deals.get(r.id) : null;
  if (dl && project(dl.project_id)) return el('a', { class: 'h-sub', href: '#/p/' + dl.project_id }, 'Сделка · ' + project(dl.project_id).name);
  const word = p ? KIND_ONE[p.kind] || 'Проект' : r.kind === 'person' ? 'Человек' : CRM_VIEWS[r.kind] ? 'CRM' : 'Дела';
  return el('span', { class: 'h-sub' }, word);
}
/** Назад — туда, откуда пришёл; пришёл по ссылке — на «Сейчас». */
const back = () => (history.length > 1 ? history.back() : go('#/now'));

function tabsRow(r) {
  const pills = Object.entries(VIEWS).map(([k, v]) => {
    const n = v.count ? v.count() : 0;
    return el('a', { class: 'pill-tab' + (r.kind === k ? ' on' : '') + (k === 'now' && n >= NOW_MAX ? ' full' : '') + (v.hot && v.hot() ? ' hot' : ''), href: '#/' + k },
      v.title, k === 'now' ? el('b', {}, `${n} из ${NOW_MAX}`) : n ? el('b', {}, n) : null);
  });
  return el('div', { class: 'tabs-row' },
    el('button', { class: 'ib burger', title: 'Разделы: CRM, клиенты, проекты, люди', onclick: () => { S.sideOpen = true; render(); } }, icon('menu', 22)),
    el('nav', { class: 'pills' }, pills));
}

function head(title, sub, extra) {
  const r = route();
  const view = !!VIEWS[r.kind];
  const tools = el('span', { class: 'tools' },
    el('button', { class: 'ib', title: 'Поиск (/)', onclick: () => {
      const s = document.querySelector('.side-search');
      if (s && s.offsetParent) s.focus(); else liveSearch('');
    } }, icon('search', 22)),
    el('button', { class: 'ib', title: 'Сказать или записать дело (n)', onclick: () => document.getElementById('quick')?.focus() }, icon('plus', 22)));
  return el('div', { class: 'list-head' },
    el('div', { class: 'row1' },
      view ? null : el('button', { class: 'ib h-back', title: 'Назад', onclick: back }, icon('back', 22)),
      headCoin(r),
      el('div', { class: 'h-titles' }, el('h1', { title: view ? null : title }, view ? 'Дела' : title), headSub(r)),
      extra || null,
      el('a', { class: 'ib', href: '#/stats', title: 'Статистика и путь' }, icon('donut', 22)),
      el('a', { class: 'ib', href: '#/settings', title: 'Настройки' }, icon('gear', 22))),
    el('div', { class: 'sub' }, [].concat(sub || []).filter(Boolean).map((x) => (x.nodeType ? x : el('span', {}, x))), tools),
    tabsRow(r));
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
  if (r.kind === 'deal') return renderDeal(r.id);
  if (r.kind === 'search') return renderSearch(r.q);
  if (r.kind === 'new') return renderNew(r.batch);
  if (r.kind === 'settings') return renderSettings();
  if (r.kind === 'stats') return renderStats();
  if (r.kind === 'week') return renderWeek();
  if (r.kind === 'crm') return renderPipeline();
  if (r.kind === 'clients') return renderClients();
  if (r.kind === 'people') return renderPeople();
  if (r.kind === 'ties') return renderTies();
  if (r.kind === 'money') return renderMoney();
  if (r.kind === 'now') return renderNow();
  if (r.kind === 'upcoming') {
    let items = openMine();
    if (S.upMineOnly) items = items.filter((t) => t.ball === 'mine');
    if (!S.upNoDate) items = items.filter((t) => t.due_date);
    const late = items.filter(isLate).length;
    const tb = el('div', { class: 'toolbar' },
      el('button', { class: 'chip-btn' + (S.upMineOnly ? ' on' : ''), onclick: () => { S.upMineOnly = !S.upMineOnly; render(); } }, 'Только мяч у меня'),
      el('button', { class: 'chip-btn' + (S.upNoDate ? ' on' : ''), onclick: () => { S.upNoDate = !S.upNoDate; render(); } }, 'И без даты'));
    // Просроченное — первой группой (ключ «0late» в grouped): «в предстоящих сверху — просроченные».
    return [head('Предстоящее', [plural(items.length, 'дело', 'дела', 'дел'), late ? el('span', { class: 'late' }, 'просрочено: ' + late) : null]),
      el('div', { class: 'body' }, quickAdd({}, { placeholder: 'Скажи, что сделать с этими делами: «КП Альфе — этого уже нет, убирай», «все просроченные — на пятницу»' }),
        tb, items.length ? grouped(items, 'date') : el('div', { class: 'empty' }, 'Впереди пусто.'))];
  }
  if (r.kind === 'waiting') {
    const items = openMine().filter((t) => t.ball === 'waiting');
    const due = items.filter(nudgeDue).sort(sortTasks);
    const rest = items.filter((t) => !nudgeDue(t));
    return [head('Жду', ['что должны другие — по людям, с давностью', due.length ? el('span', { class: 'late' }, 'пора напомнить: ' + due.length) : null]),
      el('div', { class: 'body' }, quickAdd({}, { placeholder: 'Скажи, что сделать: «Ивану напомнил, жду до пятницы», «модель пришла — закрой»' }),
        due.length ? groupBox('Пора напомнить', due, { lead: tint(icon('hourglass', 14), 'var(--waiting)'), late: true }) : null,
        rest.length ? grouped(rest, 'person') : null,
        !items.length ? el('div', { class: 'empty' }, 'Ни от кого ничего не ждём.') : null)];
  }
  if (r.kind === 'all') {
    const [picker, g] = groupPicker('all', 'project', GROUPS);
    const items = all().filter((t) => isMine(t) && inSphere(t) && (S.showDone || isOpen(t)));
    const tb = el('div', { class: 'toolbar' }, picker, doneToggle());
    return [head(VIEWS.all.title, [plural(items.filter(isOpen).length, 'открытое', 'открытых', 'открытых')]),
      el('div', { class: 'body' }, quickAdd({}), tb, items.length ? grouped(items, g) : el('div', { class: 'empty' }, 'Пусто.'))];
  }
  return [head('Дела'), el('div', { class: 'body' })];
}

/** «Сейчас»: до пяти дел, которые обязан сделать сегодня, — сначала они, потом всё остальное.
 *  Они же — наверху Засечки на телефоне (ZasechkaTasks.shortlist: «сейчас» — сразу после идущего). */
function renderNow() {
  const m = openMine();
  const now = nowTasks().sort(sortTasks);
  const left = NOW_MAX - now.length;
  const pick = m.filter((t) => !isNow(t) && t.ball === 'mine' && t.due_date && t.due_date <= S.today);
  const next = m.filter((t) => !isNow(t) && t.ball === 'mine' && t.due_date === D.add(S.today, 1));
  const n = newCount();
  const est = (list) => dur(list.reduce((s, t) => s + (t.estimate_min || 0), 0));
  return [head('Сейчас', [D.long(S.today)]),
    el('div', { class: 'body' },
      quickAdd({}, { placeholder: 'Скажи, что сделать: «сверку Наташе — первым делом», «звонок Ивану убери из сейчас»' }),
      nowTiles(),
      now.length ? groupBox('Сделать сегодня', now, { sum: est(now) })
        : el('div', { class: 'empty' }, `В «Сейчас» пусто — молния у дела ставит его сюда, до ${NOW_MAX}`),
      pick.length ? groupBox(left > 0 ? `Выбрать на сегодня: просрочено и на сегодня` : 'Ещё на сегодня и просрочено', pick.sort(sortTasks),
        { late: pick.some(isLate) }) : null,
      next.length && left > 0 ? groupBox('Завтра — если останется время', next.sort(sortTasks), { sum: est(next), cls: 'later' }) : null,
      el('a', { class: 'new-link', href: '#/new' }, icon('meet', 22), el('span', { class: 't' }, 'Новое из встреч и чатов'),
        n ? el('span', { class: 'badge-new' }, n) : null, icon('chev', 22)))];
}

/** Плитки над «Сейчас» — как в Делах на телефоне (DelaTab.DelaStats): Сегодня «0 из 5 · 2 ч 25 м»,
 *  Неделя «14 из 23 · осталось 9», Просрочено «1 · на 2 дн», Жду «6 · от 4 чел.». Сутки сделанного — московские. */
function nowTiles() {
  const mine = openMine(), now = nowTasks();
  const doneAll = all().filter((t) => t.status === 'done' && isMine(t) && t.completed_at);
  const doneDay = (t) => MSK_DAY(t.completed_at).day;
  const doneToday = doneAll.filter((t) => doneDay(t) === S.today && t.focus_on === S.today).length;
  const monday = D.add(S.today, -D.wd(S.today)), sunday = D.add(monday, 6);
  const weekOpen = mine.filter((t) => t.due_date && t.due_date <= sunday).length;
  const weekDone = doneAll.filter((t) => doneDay(t) >= monday && doneDay(t) <= sunday).length;
  const late = mine.filter(isLate);
  const lateDays = Math.max(0, ...late.map((t) => D.diff(S.today, t.due_date)));
  const waiting = mine.filter((t) => t.ball === 'waiting');
  const people = new Set(waiting.map((t) => t.person_id).filter(Boolean)).size;
  const tile = (href, label, value, delta, cls) => el('a', { class: 'st' + (cls ? ' ' + cls : ''), href },
    el('small', {}, label), el('b', {}, value), el('i', {}, delta || ' '));
  return el('div', { class: 'stats-row' },
    tile('#/now', 'Сегодня', `${doneToday} из ${now.length + doneToday}`, dur(now.reduce((s, t) => s + (t.estimate_min || 0), 0))),
    tile('#/upcoming', 'Неделя', `${weekDone} из ${weekOpen + weekDone}`, weekOpen ? `осталось ${weekOpen}` : ''),
    tile('#/upcoming', 'Просрочено', String(late.length), lateDays ? `на ${lateDays} дн` : '', late.length ? 'worse' : ''),
    tile('#/waiting', 'Жду', String(waiting.length), people ? `от ${people} чел.` : ''));
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
      put(p ? '1' + p.name : '0', p ? p.name : 'Без проекта', t, { href: p ? '#/p/' + p.id : '#/week', lead: () => (p ? dot(p.id) : tint(icon('tray', 14), NO_PROJECT.color)) });
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
    opts.sub ? el('span', { class: 'gsub' }, opts.sub) : null,
    opts.sum && !opts.act ? el('span', { class: 'sum' }, opts.sum) : null,
    opts.act ? el('span', { class: 'act' }, opts.act) : opts.late && items.length > 1 ? el('span', { class: 'act' },
      el('button', { class: 'chip-btn', onclick: (e) => reschedulePop(e.currentTarget, items.map((t) => t.id)) }, 'Перенести все')) : null) : null;
  return el('div', { class: 'group' + (opts.cls ? ' ' + opts.cls : '') }, h, opts.before || null, items.map((t) => taskRow(t, by, opts.chips && opts.chips(t))));
}

/** «Сейчас» — не больше пяти: шестое не встаёт, пока не убрал одно (владелец, 06.10.2026). */
function nowRoom(ids) {
  const have = new Set(nowTasks().map((t) => t.id));
  const add = ids.filter((id) => !have.has(id)).length;
  if (have.size + add <= NOW_MAX) return true;
  toast(`В «Сейчас» уже ${have.size} из ${NOW_MAX}` + (add > 1 ? ` — влезет ещё ${Math.max(0, NOW_MAX - have.size)}` : ' — сначала убери одно'));
  return false;
}
function toggleNow(t) {
  if (isNow(t)) { setFields([t.id], { focus_on: null }); return; }
  if (nowRoom([t.id])) setFields([t.id], { focus_on: S.today });
}

// Строка дела — как TaskRow в Делах на телефоне (Правка 4.0): слева молния «Сейчас», кольцо-галочка (зона
// касания 44), «Кто: действие» и вторая строка meta через «·» — срок, проект, мяч, минуты, номер; справа
// микрофон «поправить словами». Просроченное — жирным «просрочено 2 дн» и толстым кольцом, без красного.
// extra — подписи от места, где строка стоит. opt.compact — короткая строка «Нового» (владелец 08.10.2026:
// «ужасно всё засоряет»): только срок и проект, а справа вместо номера — значок, откуда дело (opt.mark).
function taskRow(t, by, extra, opt = {}) {
  S.order.push(t.id);
  const p = project(t.project_id);
  const who = person(t.person_id);
  const due = dueLabel(t);
  const now = isNow(t);
  const stop = (e) => e.stopPropagation();
  const m = (cls, ...kids) => el('span', { class: 'm' + (cls ? ' ' + cls : '') }, ...kids);
  const chips = [];
  if (due && due.cls === 'late') chips.push(m('late', `просрочено ${D.diff(S.today, t.due_date)} дн`), m('', D.ddmm(t.due_date)));
  else if (due && by !== 'date') chips.push(m(due.cls, due.text));
  const rem = remindChip(t);
  if (rem) chips.push(m('remind', rem));
  if (opt.compact) {
    if (p) chips.push(el('a', { class: 'm link', href: '#/p/' + p.id, onclick: stop }, p.name));
  } else if (by !== 'project' && !['project', 'deal'].includes(route().kind)) {
    // Без проекта — не тупик, а вопрос: щелчок — выбрать проект.
    chips.push(p ? el('a', { class: 'm link', href: '#/p/' + p.id, onclick: stop }, p.name)
      : el('button', { class: 'm noproj', title: 'Выбрать проект', onclick: (e) => { stop(e); projectPop(e.currentTarget, [t.id]); } }, 'без проекта'));
  }
  const dl = !opt.compact && t.deal_id ? S.deals.get(t.deal_id) : null;
  if (dl && by !== 'deal' && route().kind !== 'deal') chips.push(el('a', { class: 'm link', href: '#/d/' + dl.id, onclick: stop }, dl.name));
  // В короткой строке мяч и человек — в самом названии («Кто: действие»), минуты и метки — в карточке.
  if (!opt.compact && t.ball !== 'mine' && by !== 'ball') {
    chips.push(m('ball-' + t.ball, BALL[t.ball] + (who ? ' ' + personName(who) : '')
      + (t.ball === 'waiting' && t.waiting_since ? ' ' + D.diff(S.today, t.waiting_since) + ' дн' : '')));
  } else if (!opt.compact && who && by !== 'person') {
    chips.push(el('a', { class: 'm link', href: '#/h/' + who.id, onclick: stop }, '@' + personName(who)));
  }
  if (t.estimate_min && !opt.compact) chips.push(m('', dur(t.estimate_min)));
  for (const l of opt.compact ? [] : t.labels || []) chips.push(el('span', { class: 'tag' }, l));
  if (extra) chips.push(...[].concat(extra).filter(Boolean));
  if (!opt.mark) chips.push(m('', '#' + t.num));
  const pick = el('input', { type: 'checkbox', class: 'pick', title: 'Выбрать (Shift — диапазон)' });
  pick.checked = S.sel.has(t.id);
  pick.addEventListener('click', (e) => { e.stopPropagation(); togglePick(t.id, e.shiftKey); });
  return el('div', {
    class: 'task ball-' + t.ball + (opt.compact ? ' compact' : '') + (isOpen(t) ? '' : ' done') + (now ? ' now' : '') + (isLate(t) ? ' late' : '')
      + (S.cardId === t.id ? ' open-now' : '') + (S.sel.has(t.id) ? ' sel' : ''),
    'data-id': t.id,
    onclick: () => openCard(t.id),
  },
  pick,
  el('div', { class: 'lead' },
    el('button', { class: 'now-btn' + (now ? ' on' : ''), title: now ? 'Убрать из «Сейчас»' : `В «Сейчас» — сделать сегодня (до ${NOW_MAX} дел)`,
      onclick: (e) => { e.stopPropagation(); toggleNow(t); } }, icon('bolt', 18))),
  el('button', { class: 'tick' + (isOpen(t) ? '' : ' done'), title: isOpen(t) ? 'Сделано' : 'Вернуть', onclick: (e) => { e.stopPropagation(); toggleDone([t]); } }),
  el('div', { class: 'main' }, el('div', { class: 'title' }, t.title), chips.length ? el('div', { class: 'chips' }, chips) : null,
    S.askTask === t.id && S.cardId !== t.id ? askBox(t) : null),
  opt.compact ? null : el('div', { class: 'acts-r' },
    el('button', { class: 'mic-btn' + (S.askTask === t.id ? ' on' : ''), title: 'Поправить словами: сказать Claude, что сделать с этим делом',
      onclick: (e) => { e.stopPropagation(); if (S.askTask === t.id && rec) stopListening(); else openAsk(t); } }, icon('mic', 22))),
  opt.mark || null);
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

function closePop() {
  const had = document.querySelector('.pop');
  document.querySelectorAll('.pop').forEach((p) => p.remove());
  if (had) afterBusy();
}
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
    popAt(anchor, [el('button', { onclick: () => { closePop(); setFields(ids, { project_id: null, deal_id: null }, 'Без проекта'); } }, 'Без проекта'),
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
    el('button', { onclick: () => nowRoom(ids) && setFields(ids, { focus_on: S.today }, 'В «Сейчас»') }, 'В «Сейчас»'),
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

// ── Строка «Скажи, что сделать» — на каждой странице, внизу (строка «сказать» Правки 4.0) ────────
// Владелец, 06.10.2026: «везде должен быть сверху красивый текстбокс… скажи, что сделать с этим делом,
// с этим клиентом — я наговариваю, и он внутри всё правит». Claude видит дела на экране (в карточке
// клиента, сделки, человека — и саму карточку, это Opus), правит их, заводит новые, в «Новом» решает
// предложения. 08.10.2026: «знак Клода уберём… слева микрофончик, справа send»: микрофон — нажал, говоришь,
// нажал ещё раз — ушло Claude; написал — стрелка или Enter. В тот же день — вид Правки 4.0: строка стоит
// внизу экрана под большим пальцем и не уезжает при прокрутке (владелец: «внизу, ага»); listColumn
// переносит её туда. «+» больше нет: одно дело как написано, с разметкой, — Alt+Enter.
// label — о чём строка («с клиентом «Альфа»»), placeholder — пример команды для этой страницы.
// С 08.10.2026 (Правка 4.0) это строка «сказать» внизу экрана: подсказка короткая («Саша, говори дела»),
// примеры команд — во всплывающей подсказке поля; клавиша справа — голос, а с текстом — «отдать Claude».
function quickAdd(defaults, { placeholder = null, label = null } = {}) {
  const busy = !!S.parse;
  const input = el('textarea', { id: 'quick', rows: 1, autocomplete: 'off', disabled: busy,
    'aria-label': 'Скажи, что сделать ' + (label || 'с этими делами'),
    placeholder: label ? 'Скажи, что сделать ' + label : `${S.me.name || 'Саша'}, говори дела`,
    title: placeholder || 'Скажи или напиши: «все просроченные — на пятницу», «Ивану позвонить завтра», надиктовка целиком' });
  if (busy) input.value = S.parse.text;
  else input.value = S.quickText || '';
  const preview = el('div', { class: 'preview' });
  const help = el('div', { class: 'help hidden' },
    'Enter или стрелка — Claude · Shift+Enter — новая строка · Alt+Enter — одно дело как написано: ',
    '+проект  @человек  *метка  !жду  !повестка  !сейчас  !хочу  !15м · сегодня, завтра, в пятницу, 12.10');
  const status = busy ? el('div', { class: 'claude-status' }, 'Claude думает… Можно уходить на другие страницы — изменения появятся сами.')
    : rec && S.listenQuick ? el('div', { class: 'claude-status listening' }, 'Слушаю — говори сколько нужно. Нажми микрофон ещё раз — отдам Claude.') : null;
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
    S.quickText = input.value;
    const p = parseQuick(input.value, defaults);
    // Как Alt+Enter поймёт разметку — только если она есть: Claude читает текст сам.
    preview.replaceChildren(...(p.tags.length ? [el('span', { class: 'pv-h' }, 'Alt+Enter запишет:'), ...p.tags.map((t) => el('span', {}, t))] : []));
    help.classList.toggle('hidden', !input.value);
    box.classList.toggle('has-text', !!input.value.trim());
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
    if (p.set.focus_on && !nowRoom(['new'])) return;
    try {
      const r = await op1({ op: 'task.create', task: { ...p.set, title: p.title, source: 'web' } });
      input.value = S.quickText = '';
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
    if (e.key === 'Escape') { input.value = S.quickText = ''; update(); input.blur(); e.stopPropagation(); return; }
    if (e.key !== 'Enter' || e.shiftKey) return;
    e.preventDefault();
    if (e.altKey) addOne(); // Alt+Enter — одно дело, как «+»
    else claudeParse(input, defaults);
  });

  // Отправить — клавиша справа, под большим пальцем; пока Claude думает — заливка по строке, без крутилки.
  const sendBtn = el('button', { type: 'button', class: 'q-btn send-btn' + (busy ? ' busy' : ''), disabled: busy,
    title: !S.me.claude ? 'Claude на сервере ещё не настроен' : busy ? 'Claude думает…' : 'Отдать Claude (Enter)',
    'aria-label': 'Отдать Claude', onclick: () => claudeParse(input, defaults) }, icon('up', 24));
  // Микрофон: нажал — слушаю (сказанное дописывается в поле), нажал ещё раз — ушло Claude. Браузер сам
  // закрывает распознавание в паузе (Chrome на телефоне — почти сразу) — listen открывает его снова: 08.10.2026
  // так команда уходила посреди фразы, а второе нажатие начинало новую запись в пустом поле — «всё стирается».
  const hearing = !!(rec && S.listenQuick);
  const micBtn = el('button', { type: 'button', class: 'q-btn mic' + (hearing ? ' on' : ''), disabled: busy,
    title: hearing ? 'Готово — отдать Claude' : 'Сказать голосом: нажми, говори, нажми ещё раз',
    'aria-label': hearing ? 'Закончить и отдать Claude' : 'Сказать голосом', 'aria-pressed': hearing ? 'true' : 'false',
    onclick: () => {
      if (hearing) { stopListening(); return; }
      const base = input.value.trim();
      S.listenQuick = true;
      const ok = listen((txt) => {
        const q = document.getElementById('quick');
        S.quickText = (base ? base + ' ' : '') + txt; // перерисовка вернёт его в поле
        if (q) { q.value = S.quickText; q.dispatchEvent(new Event('input')); }
      }, () => {
        S.listenQuick = false;
        const q = document.getElementById('quick');
        const said = (q ? q.value : S.quickText).trim();
        if (said && said !== base && voiceAuto()) claudeSay(said, defaults);
        else render();
      });
      if (!ok) S.listenQuick = false;
      render();
    } }, icon(hearing ? 'stop' : 'mic', 22));
  if (input.value) setTimeout(grow);
  const card = pageScope(defaults).card;
  // В карточке клиента, сделки, человека над строкой — о чём она и что Claude видит карточку целиком.
  const say = card ? el('label', { class: 'say-h', for: 'quick' }, el('span', {}, 'Скажи, что сделать ' + (label || 'с этими делами')),
    el('span', { class: 'say-model', title: 'В карточке Claude видит её целиком: сделки, людей, хронологию — и правит их' }, 'Opus · видит карточку')) : null;
  const box = el('div', { class: 'quick-box' + (busy ? ' busy' : '') + (hearing ? ' hearing' : '') + (input.value.trim() ? ' has-text' : '') }, micBtn, input, sendBtn);
  return el('div', { class: 'quick say' + (card ? ' card-scope' : '') },
    say,
    el('div', { class: 'quick-wrap' }, box, ac),
    status, preview, help);
}

// ── Голос: распознавание браузера (Chrome — по-русски, сразу), своего сервера не нужно ──
const Speech = window.SpeechRecognition || window.webkitSpeechRecognition;
let rec = null; // одна запись на страницу
const HOLD_QUIET = 45000; // забыл нажать второй раз — через столько тишины отдаём сами
function stopListening() { if (rec) { rec.byUser = true; try { rec.stop(); } catch (e) { /* уже стоит */ } } }
/** Слушать: onText — текст по ходу речи, onDone — итог, когда человек нажал микрофон ещё раз.
 *  Владелец 08.10.2026: «нажимаю на микрофон, говорю, ещё раз нажимаю» — итог по паузе (было до того)
 *  обрывал команду на полуслове. Браузер сам закрывает распознавание в паузе (Chrome на телефоне — почти
 *  сразу) — открываем его снова и дописываем к сказанному. */
function listen(onText, onDone) {
  if (!Speech) { toast('Этот браузер не распознаёт речь — напиши текстом (в Chrome микрофон работает)'); return false; }
  stopListening();
  let said = '', last = '', idle, broken = false, quietAt = Date.now();
  const start = () => {
    const r = new Speech();
    r.lang = 'ru-RU';
    r.interimResults = true;
    r.continuous = true;
    let final = '';
    const wait = (ms) => { clearTimeout(idle); idle = setTimeout(() => { r.byUser = true; try { r.stop(); } catch (e) { /* стоит */ } }, ms); };
    r.onresult = (e) => {
      let interim = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) final += ' ' + e.results[i][0].transcript; else interim += ' ' + e.results[i][0].transcript;
      }
      last = (said + ' ' + final + interim).replace(/\s+/g, ' ').trim();
      onText(last);
      quietAt = Date.now();
      wait(HOLD_QUIET);
    };
    r.onerror = (e) => {
      if (e.error === 'no-speech' || e.error === 'aborted') return; // тишина — не поломка: слушаем дальше
      broken = true; // микрофон не дали, сети нет — снова открывать бесполезно
      toast(e.error === 'not-allowed' || e.error === 'service-not-allowed' ? 'Браузер не дал микрофон — разреши его для этой страницы' : 'Распознавание: ' + e.error);
    };
    // Итог — до перерисовки: она пересоздаёт поля, и надиктованное в несфокусированном поле пропало бы.
    r.onend = () => {
      clearTimeout(idle);
      if (rec !== r) return;
      if (!r.byUser && !broken && Date.now() - quietAt < HOLD_QUIET) { said = last; start(); return; } // закрыл браузер, не человек
      rec = null;
      onDone(last);
    };
    rec = r;
    try { r.start(); } catch (e) { rec = null; onDone(last); return; }
    wait(HOLD_QUIET);
  };
  start();
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

/** Что видит человек на этой странице — Claude видит то же (владелец 08.10.2026: «пусть смотрит на то, что
 *  сейчас на экране»): дела страницы сверху вниз — какие из них в окне прямо сейчас, какие выбраны
 *  галочками, какое открыто в карточке, — и в «Новом» предложения (П1, П2…). Дела берутся с самой
 *  страницы в миг отправки: что нарисовано, то и видно; S.order — запас, если строк в DOM нет. */
function pageScope(defaults = {}) {
  const r = route();
  const p = r.kind === 'project' ? project(r.id) : null, h = r.kind === 'person' ? person(r.id) : null;
  const dl = r.kind === 'deal' ? S.deals.get(r.id) : null;
  const one = r.kind === 'new' && r.batch ? pendingSugs().find((s) => s.batch_ref === r.batch) : null;
  const title = p ? `${KIND[p.kind] || 'Проект'}: ${p.name}` : h ? `Человек: ${h.name}` : dl ? `Сделка: ${dl.name}`
    : r.kind === 'search' ? `Поиск: ${r.q}`
      : r.kind === 'new' && r.batch ? `Новое: одна пачка — ${one?.batch_title || r.batch}`
        : (VIEWS[r.kind] || CRM_VIEWS[r.kind] || { title: 'Дела' }).title;
  const rows = [...document.querySelectorAll('main.list .task[data-id]')];
  const uniq = (xs) => [...new Set(xs)];
  // В окне — между низом липкой шапки и низом окна; строка, от которой видна лишь кромка, не в счёт:
  // её названия не видно.
  const top = document.querySelector('main.list .list-head')?.getBoundingClientRect().bottom || 0;
  const bottom = Math.min(window.innerHeight, document.querySelector('main.list')?.getBoundingClientRect().bottom || window.innerHeight,
    document.querySelector('main.list .dock')?.getBoundingClientRect().top || Infinity); // строка внизу экрана — тоже не окно
  const inView = rows.filter((x) => {
    const b = x.getBoundingClientRect();
    return b.height > 0 && Math.min(b.bottom, bottom) - Math.max(b.top, top) >= b.height * 0.6;
  });
  const scope = { title, task_ids: uniq(rows.length ? rows.map((x) => x.dataset.id) : S.order).slice(0, 300),
    visible_ids: uniq(inView.map((x) => x.dataset.id)).slice(0, 300) };
  if (S.sel.size) scope.selected_ids = [...S.sel].slice(0, 300);
  if (S.cardId && S.tasks.has(S.cardId)) scope.open = S.cardId;
  if (r.kind === 'new') scope.suggestion_ids = S.sugOrder.slice(0, 150);
  for (const k of ['project_id', 'person_id', 'deal_id']) if (defaults[k]) scope[k] = defaults[k];
  // Карточка: Claude (Opus) видит её целиком и правит хронологию, людей и сделки, не только дела.
  if (crmOn() && p && p.kind === 'client') Object.assign(scope, { card: 'client', project_id: p.id });
  if (crmOn() && h) Object.assign(scope, { card: 'person', person_id: h.id });
  if (crmOn() && dl) Object.assign(scope, { card: 'deal', deal_id: dl.id, project_id: dl.project_id });
  return scope;
}

// ── Claude: команда или надиктовка из строки «сказать» — правка дел на экране или новые дела ──
async function claudeParse(input, defaults) {
  const text = input.value.trim();
  if (S.parse) return;
  if (!text) { toast('Скажи или напиши Claude: новые дела или что поправить в делах на экране'); input.focus(); return; }
  return claudeSay(text, defaults);
}
async function claudeSay(text, defaults) {
  if (S.parse) return;
  const scope = pageScope(defaults); // экран — до перерисовки: что в окне, видно сейчас
  S.parse = { text };
  render();
  try {
    const d = await runJob('/api/ask', { text, scope });
    S.parse = null;
    crmDirty(); // карточку (хронологию, людей, сделки) Claude мог поправить на сервере
    for (const t of d.tasks || []) S.tasks.set(t.id, t);
    // Claude ничего не сделал (не понял, переспросил) — команда возвращается в поле: дополнить и отправить
    // ещё раз, а не говорить заново (08.10.2026: «нажимаю на микрофон, и всё стирается»).
    const did = ['changed', 'tasks', 'decided', 'crm', 'notes'].some((k) => (d[k] || []).length);
    S.quickText = did ? '' : text; // пока Claude думал, поле было занято командой — чужого текста там нет
    await sync().catch(() => {});
    render();
    if (d.route === 'new') claudeResult(d); else askResult(d);
  } catch (e) {
    S.quickText = S.parse ? S.parse.text : S.quickText;
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
  // Как у строки «говори дела»: нажал — говоришь, нажал ещё раз — ушло Claude.
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
    // Ничего не сделал — поле у дела остаётся с командой: дополнить и отправить ещё раз.
    const did = ['changed', 'tasks', 'decided', 'crm'].some((k) => (d[k] || []).length);
    S.askTask = did ? null : id;
    S.askText = did ? '' : text;
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
  // Как строка «говори дела»: микрофон слева, стрелка справа; пока Claude правит — заливка по строке.
  return el('div', { class: 'ask-inline' + (busy ? ' busy' : ''), onclick: stop },
    busy ? null : el('button', { class: 'q-btn mic' + (rec ? ' on' : ''), type: 'button', title: rec ? 'Готово — отдать Claude' : 'Сказать голосом: нажми, говори, нажми ещё раз',
      onclick: () => { if (rec) stopListening(); else openAsk(t); } }, icon('mic', 16)),
    input,
    busy ? el('span', { class: 'ask-status' }, 'правит…') : [
      el('button', { class: 'q-btn send-btn', type: 'button', title: 'Отдать Claude (Enter)', 'aria-label': 'Отдать Claude', onclick: () => askTask(t.id, input.value) }, icon('up', 16)),
      el('button', { class: 'icon-btn', type: 'button', title: 'Закрыть (Esc)', onclick: () => { stopListening(); S.askTask = null; render(); } }, icon('x', 13))]);
}

/** Что поменялось — словами: «срок 07.10, в «Сейчас», жду Наташа». */
function describe(c) {
  const a = c.after || {}, out = [];
  if ('title' in a) out.push(`название «${a.title}»`);
  if ('due_date' in a) out.push(a.due_date ? 'срок ' + D.ddmm(a.due_date) + (a.due_time ? ' ' + a.due_time : '') : 'без срока');
  else if ('due_time' in a) out.push(a.due_time ? 'время ' + a.due_time : 'без времени');
  if ('focus_on' in a) out.push(a.focus_on ? 'в «Сейчас»' : 'из «Сейчас»');
  if ('project_id' in a) out.push(a.project_id ? 'проект ' + (project(a.project_id)?.name || '?') : 'без проекта');
  if (a.deal_id) out.push('сделка ' + (S.deals.get(a.deal_id)?.name || '?'));
  if ('ball' in a || 'person_id' in a) {
    const who = 'person_id' in a ? personName(person(a.person_id)) : '';
    out.push(('ball' in a ? BALL[a.ball] : 'человек') + (who ? ' ' + who : ''));
  }
  if ('notes' in a) out.push('дописал заметку');
  if ('estimate_min' in a) out.push(a.estimate_min ? a.estimate_min + ' мин' : 'без оценки');
  if ('labels' in a) out.push(a.labels.length ? 'метки ' + a.labels.join(', ') : 'без меток');
  if (a.remind_at) out.push('напомню в Telegram ' + remindWords(a.remind_at));
  else if (a.remind_place) out.push('напомню, когда приедешь: ' + a.remind_place);
  else if ('remind_at' in a || 'remind_place' in a) out.push('без напоминания');
  if (c.status) out.push({ done: 'сделано', cancelled: 'отменено', open: 'в работу' }[c.status[1]]);
  return out.join(', ');
}

const DECIDED = { create: 'заведено', close: 'закрыто', update: 'уточнено', assign: 'взято себе' };

/** Итог правки: что сделал Claude, его слова, «Вернуть всё» одним движением.
 *  В «Новом» ещё и решения по предложениям: принятое возвращается (заведённое — отменой, поправленное —
 *  как было), отклонённое — нет: оно остаётся отклонённым вместе с причиной. */
function askResult(d) {
  document.querySelectorAll('.claude-result').forEach((x) => x.remove());
  const changed = d.changed || [], made = d.tasks || [], decided = d.decided || [], crm = d.crm || [];
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
    for (const c of crm) back.push(...(c.undo || [])); // карточка: хронология, люди, сделки — как было
    try { if (back.length) await ops(back); close(); render(); toast('Вернул как было'); } catch (e) { fail(e); }
  };
  const nTasks = changed.length + made.length, n = nTasks + decided.length + crm.length;
  const back = nTasks + took.length + crm.filter((c) => (c.undo || []).length).length;
  const what = [decided.length ? plural(decided.length, 'предложение', 'предложения', 'предложений') : null,
    nTasks ? plural(nTasks, 'дело', 'дела', 'дел') : null, crm.length ? 'карточка: ' + crm.length : null].filter(Boolean).join(', ');
  // Предложение словами — как в «Новом», пока оно есть у веба; иначе — как его видел Claude.
  const sugTitle = (x) => (S.sugs.get(x.sid) ? sugText(S.sugs.get(x.sid)).title : x.what);
  box.append(...[
    el('div', { class: 'cr-head' }, el('span', {}, n ? `Claude: ${what}` : 'Claude ничего не поменял — команда снова в поле'),
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
    crm.length ? el('div', { class: 'cr-sub' }, 'В карточке:') : null,
    crm.map((c) => el('div', { class: 'cr-note' }, c.what)),
    d.errors && d.errors.length ? el('div', { class: 'cr-err' }, 'Не вышло: ' + d.errors.join('; ')) : null,
    el('div', { class: 'cr-acts' },
      back ? el('button', { onclick: undo, title: dropped.length ? 'Отклонённое остаётся отклонённым — вернуть его нельзя' : '' },
        dropped.length ? 'Вернуть принятое' : 'Вернуть всё') : null,
      el('button', { onclick: close }, 'Хорошо')),
  ].flat().filter(Boolean));
  document.body.append(box);
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
    el('div', { class: 'cr-head' }, el('span', {}, said ? 'Claude записал ' + said : 'Claude не нашёл тут дел'),
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
      el('div', { class: 'body' },
        quickAdd({ project_id: id }, { label: `с клиентом «${p.name}»`,
          placeholder: '«Иван — их CFO, отправили ему сегодня», «КП уже нет — закрой сделку», «созвонились, ждут модель к пятнице»' }),
        c.dealBox, c.peopleBox, c.tabs,
        c.timeline || [el('div', { class: 'toolbar' }, picker, doneToggle()),
          items.length ? grouped(items, g) : el('div', { class: 'empty' }, 'Дел нет. Следующий шаг — скажи в строке выше.')])];
  }
  return [head(p.name, sub, star),
    el('div', { class: 'body' },
      quickAdd({ project_id: id, ...(S.dealFilter ? { deal_id: S.dealFilter } : {}) }, { label: `с проектом «${p.name}»` }),
      dealBox, el('div', { class: 'toolbar' }, picker, doneToggle()),
      items.length ? grouped(items, g) : el('div', { class: 'empty' }, 'Дел нет. Следующий шаг — скажи в строке выше.'))];
}

// ── Люди плашками: круглые, с крестиком; щелчок — откуда он и должность ──
/** Организации для «откуда он»: клиенты (по имени проекта) и остальные компании. */
function orgChoices() {
  const byOrg = new Map();
  for (const p of S.projects.values()) if (p.kind === 'client' && p.org_id && !p.archived_at) byOrg.set(p.org_id, p.name);
  const clients = [...byOrg.entries()].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  const other = [...S.orgs.values()].filter((o) => !o.archived_at && !byOrg.has(o.id)).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  return { clients, other };
}
/** Как называть организацию человека: клиентом, если она чья-то, иначе своим именем. */
function orgLabel(orgId) {
  if (!orgId) return null;
  const c = [...S.projects.values()].find((p) => p.kind === 'client' && p.org_id === orgId);
  return c ? { name: c.name, href: '#/p/' + c.id } : S.orgs.get(orgId) ? { name: S.orgs.get(orgId).name, href: null } : null;
}
async function personSet(x, set, msg) {
  const before = Object.fromEntries(Object.keys(set).map((k) => [k, x[k] ?? null]));
  try { await op1({ op: 'person.set', id: x.id, set }); render(); toast(msg, () => op1({ op: 'person.set', id: x.id, set: before })); } catch (e) { fail(e); }
}
function personPop(anchor, x) {
  setTimeout(() => {
    const { clients, other } = orgChoices();
    const sel = el('select', {}, el('option', { value: '' }, '— ни откуда —'),
      el('optgroup', { label: 'Клиенты' }, clients.map((o) => el('option', { value: o.id, selected: x.org_id === o.id }, o.name))),
      other.length ? el('optgroup', { label: 'Другие компании' }, other.map((o) => el('option', { value: o.id, selected: x.org_id === o.id }, o.name))) : null);
    sel.addEventListener('change', () => { closePop(); personSet(x, { org_id: sel.value || null }, `${personName(x)}: ` + (sel.value ? 'откуда — ' + orgLabel(sel.value)?.name : 'без компании')); });
    const role = el('input', { value: x.role || '', placeholder: 'должность: CFO, партнёр…' });
    role.addEventListener('keydown', (e) => { if (e.key === 'Enter') { closePop(); personSet(x, { role: role.value.trim() || null }, `${personName(x)}: ` + (role.value.trim() || 'без должности')); } });
    popAt(anchor, [el('div', { class: 'pop-h' }, x.name), el('div', { class: 'pop-l' }, 'Откуда он'), sel,
      el('div', { class: 'pop-l' }, 'Должность (Enter)'), role,
      el('div', { class: 'sep' }), el('button', { onclick: () => { closePop(); go('#/h/' + x.id); } }, 'Открыть карточку', el('span', { class: 'k' }, '→'))]);
  }, 0);
}
/** Плашки людей: list — люди, remove(x) — убрать отсюда, add(anchor) — «+ человек». */
function peopleChips(title, list, { remove, add, empty } = {}) {
  return el('div', { class: 'pchips' }, el('span', { class: 'pc-label' }, title),
    list.length ? list.map((x) => el('span', { class: 'pchip' },
      el('button', { class: 'pc-main', title: 'Откуда он, должность', onclick: (e) => personPop(e.currentTarget, x) },
        avatar(x), el('span', { class: 'pc-name' }, personName(x)), x.role ? el('span', { class: 'pc-role' }, x.role) : null),
      remove ? el('button', { class: 'pc-x', title: 'Убрать отсюда', onclick: () => remove(x) }, icon('x', 11)) : null))
      : el('span', { class: 'faint' }, empty || 'никого'),
    add ? el('button', { class: 'pchip add', onclick: (e) => add(e.currentTarget) }, icon('plus', 12), 'человек') : null);
}
/** «+ человек»: выбрать из справочника или завести нового — pick(id) или create(name). */
function addPersonPop(anchor, exclude, pick, create) {
  setTimeout(() => {
    const sel = el('select', {}, el('option', { value: '' }, 'выбрать…'),
      livePeople().filter((x) => !exclude.includes(x.id)).sort((a, b) => personName(a).localeCompare(personName(b), 'ru'))
        .map((x) => el('option', { value: x.id }, x.short && x.short !== x.name ? `${x.short} — ${x.name}` : x.name)));
    sel.addEventListener('change', () => { if (sel.value) { closePop(); pick(sel.value); } });
    const name = el('input', { placeholder: 'или новый: имя фамилия, Enter' });
    name.addEventListener('keydown', (e) => { if (e.key === 'Enter' && name.value.trim()) { closePop(); create(name.value.trim()); } });
    popAt(anchor, [el('div', { class: 'pop-h' }, 'Кто ещё'), sel, name]);
  }, 0);
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
  // Откуда он — плашкой: щелчок меняет компанию и должность.
  const ol = orgLabel(p.org_id);
  const whereBtn = el('button', { class: 'pchip where', title: 'Откуда он, должность', onclick: (e) => personPop(e.currentTarget, p) },
    icon('building', 12), ol ? ol.name : 'откуда — не знаю', p.role ? el('span', { class: 'pc-role' }, p.role) : null);
  info.push(el('span', {}, 'Откуда'), el('span', {}, whereBtn, ol && ol.href ? el('a', { class: 'link faint', href: ol.href }, ' карточка клиента →') : null));
  row('Телефон', (p.phones || []).join(', '));
  row('Почта', (p.emails || []).join(', '));
  row('Telegram', p.telegram_username ? '@' + p.telegram_username : null);
  row('День рождения', p.birth_day ? `${p.birth_day} ${MONTHS[p.birth_month - 1]}` + (p.birth_year ? ' ' + p.birth_year : '') : null);
  row('Как часто', { month: 'раз в месяц', quarter: 'раз в 2–3 месяца', year: 'раз в год', none: 'не видимся' }[p.cadence]);
  row('Ищет', p.seeks); row('Предлагает', p.offers); row('Характер', p.traits); row('Откуда', p.source); row('Заметка', p.note);
  return [head(p.name, p.short && p.short !== p.name ? ['в задачах — ' + p.short] : null),
    el('div', { class: 'body' },
      quickAdd({ person_id: id }, { label: `с человеком «${p.name}»`,
        placeholder: '«он теперь CFO в Бете», «созвонились, пришлёт модель в пятницу», «жду от него договор»' }),
      info.length ? el('div', { class: 'person-info' }, info) : null,
      secs.map(([t, items]) => (items.length ? groupBox(t, items.sort(sortTasks)) : null)),
      secs.every(([, i]) => !i.length) ? el('div', { class: 'empty' }, 'Открытых дел с ним нет.') : null,
      crmOn() && !p.user_id ? personCrmBlock(p) : null)];
}

/** Общий поиск — сразу, по мере набора: клиенты и проекты справочника, проекты клиентов (сделки),
 *  люди, дела. Всё уже в памяти — считается тут, без запросов. Каждое слово запроса должно найтись. */
const SEARCH_SHOWN = 8;
function renderSearch(q) {
  const words = norm(q).split(/\s+/).filter(Boolean);
  const hit = (...parts) => words.length > 0 && words.every((w) => norm(parts.flat().filter(Boolean).join(' ')).includes(w));
  const projs = [...S.projects.values()].filter((p) => hit(p.name, p.aliases || [], p.note))
    .sort((a, b) => !!a.archived_at - !!b.archived_at || a.name.localeCompare(b.name, 'ru'));
  const deals = [...S.deals.values()].filter((d) => hit(d.name, project(d.project_id)?.name, d.deal_type))
    .sort((a, b) => (a.stage === 'archive') - (b.stage === 'archive') || a.name.localeCompare(b.name, 'ru'));
  const ppl = livePeople().filter((x) => !x.merged_into && hit(x.name, x.short, x.aliases || [], x.role, orgLabel(x.org_id)?.name))
    .sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  const tasks = all().filter((t) => (S.showDone || isOpen(t)) && hit(t.title, t.notes, project(t.project_id)?.name,
    personName(person(t.person_id)), t.deal_id && S.deals.get(t.deal_id)?.name, t.labels || []));
  const openBy = (pid) => all().filter((t) => t.project_id === pid && isOpen(t)).length;
  const part = (title, list, row) => (list.length ? el('div', { class: 'group' }, el('h2', {}, title, el('span', { class: 'n' }, list.length)),
    list.slice(0, SEARCH_SHOWN).map(row),
    list.length > SEARCH_SHOWN ? el('div', { class: 'faint pad' }, `и ещё ${list.length - SEARCH_SHOWN} — уточни запрос`) : null) : null);
  const box = el('input', { id: 'main-search', class: 'main-search inline-search', type: 'search', placeholder: 'Что найти: клиент, проект, человек, дело', value: q });
  box.addEventListener('input', () => liveSearch(box.value));
  const total = projs.length + deals.length + ppl.length + tasks.length;
  return [head(q ? 'Поиск: ' + q : 'Поиск', words.length ? [plural(total, 'находка', 'находки', 'находок'),
    deals.length ? plural(deals.length, 'проект', 'проекта', 'проектов') : null, ppl.length ? plural(ppl.length, 'человек', 'человека', 'человек') : null,
    plural(tasks.length, 'дело', 'дела', 'дел')] : ['набирай в поле «Поиск» слева — результаты появляются сразу']),
  el('div', { class: 'body' }, box,
    part('Клиенты и разделы', projs, (p) => el('div', { class: 'crow', onclick: () => go('#/p/' + p.id) }, dot(p.id),
      el('div', { class: 'main' }, el('div', { class: 'title' }, p.name, p.archived_at ? el('span', { class: 'faint' }, ' · архив') : null),
        el('div', { class: 'chips' }, el('span', {}, KIND[p.kind] || p.kind), openBy(p.id) ? el('span', {}, plural(openBy(p.id), 'открытое дело', 'открытых дела', 'открытых дел')) : null)))),
    part('Проекты клиентов', deals, (d) => el('div', { class: 'crow', onclick: () => openDeal(d.id) }, el('span', { class: 'st-dot st-' + d.stage }),
      el('div', { class: 'main' }, el('div', { class: 'title' }, d.name),
        el('div', { class: 'chips' }, el('span', {}, stageWord(d)), project(d.project_id) ? el('span', {}, project(d.project_id).name) : null)))),
    part('Люди', ppl, (x) => el('div', { class: 'crow person-row', onclick: () => go('#/h/' + x.id) }, avatar(x),
      el('div', { class: 'main' }, el('div', { class: 'title' }, x.name, x.short && x.short !== x.name ? el('span', { class: 'faint' }, ' · ' + x.short) : null),
        el('div', { class: 'chips' }, x.role ? el('span', {}, x.role) : null, orgLabel(x.org_id) ? el('span', {}, orgLabel(x.org_id).name) : null)))),
    words.length ? el('div', { class: 'group' }, el('h2', {}, 'Дела', el('span', { class: 'n' }, tasks.length), el('span', { class: 'act' }, doneToggle()))) : null,
    words.length && tasks.length ? quickAdd({}, { placeholder: 'Скажи, что сделать с найденными делами: «все — на пятницу», «эти два закрой»' }) : null,
    tasks.length ? grouped(tasks, 'project') : null,
    words.length && !total ? el('div', { class: 'empty' }, 'Ничего не нашлось. Ищу по началам и кусочкам слов: «альф», «фонд», «иван».') : null)];
}

function renderWeek() {
  const m = openMine();
  const now = Date.now();
  const stale = m.filter((t) => (t.due_date && D.diff(S.today, t.due_date) > 14) || (now - Date.parse(t.updated_at)) > 21 * 86400e3);
  const waitStale = m.filter((t) => t.ball === 'waiting' && t.waiting_since && D.diff(S.today, t.waiting_since) > 7 && (!t.nudge_on || t.nudge_on < S.today));
  const busy = new Set(all().filter((t) => isOpen(t) && t.ball === 'mine' && t.project_id).map((t) => t.project_id));
  const noStep = [...S.projects.values()].filter((p) => !p.archived_at && p.owner_id === S.me.user && ['paid', 'potential'].includes(p.money_default) && !busy.has(p.id));
  // Без проекта — не вопрос «Нового» (владелец 08.10.2026: «без проекта… ужасно всё засоряет»), а уборка раз в неделю.
  const loose = noProject();
  return [head('Неделя', ['раз в неделю: что протухло, кто молчит, где нет следующего шага, что без проекта']),
    el('div', { class: 'body' }, quickAdd({}),
      stale.length ? groupBox('Протухшее — закрыть, перенести или отпустить', stale.sort(sortTasks), { late: true }) : null,
      waitStale.length ? groupBox('Жду без движения больше недели', waitStale.sort(sortTasks)) : null,
      noStep.length ? el('div', { class: 'group' }, el('h2', {}, 'Проекты в работе без моего следующего шага', el('span', { class: 'n' }, noStep.length)),
        noStep.map((p) => navItem('#/p/' + p.id, dot(p.id), p.name, null, false))) : null,
      loose.length ? groupBox('Без проекта — куда их?', loose.sort(sortTasks), { lead: tint(icon('tray', 14), NO_PROJECT.color),
        sub: 'щелчок по «без проекта» — выбрать, или скажи Claude внизу',
        act: loose.length > 1 ? el('button', { class: 'chip-btn', onclick: (e) => projectPop(e.currentTarget, loose.map((t) => t.id)) }, 'Все в проект…') : null }) : null,
      !stale.length && !waitStale.length && !noStep.length && !loose.length ? el('div', { class: 'empty' }, 'Чисто. Неделя разобрана.') : null)];
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
/** Что поменяло уточнение, принятое само: было → стало (result.was — как было, result — как стало). */
function autoWords(s) {
  const r = s.result || {}, was = r.was || {}, p = s.payload || {};
  return [
    'title' in was ? `название: «${r.title}»` : null,
    'due_date' in was ? 'срок ' + (was.due_date ? D.ddmm(was.due_date) + ' → ' : '') + (r.due_date ? D.ddmm(r.due_date) : 'без срока') : null,
    'ball' in was || 'person_id' in was ? 'мяч: ' + (BALL[r.ball] || r.ball) + (p.person_name ? ' ' + p.person_name : '') : null,
  ].filter(Boolean).join(' · ');
}
// Пачки — по дате встречи, свежие сверху; без даты — по времени появления.
const sugAt = (s) => (s.payload && s.payload.meeting_at) || s.created_at.slice(0, 10);

// ── «Новое»: подскажи — поставил — само (владелец 08.10.2026) ────────────
// «Зачем мне это в новом, если это понятное?.. Это ужасно всё засоряет» (о своей же наговорке). Поэтому сверху —
// только то, где без человека не обойтись («Подскажи»), ниже — короткий список поставленного за три дня,
// свежее сверху, со значком источника справа; что именно сказано и откуда — в карточке дела.
const FEED_DAYS = 3;
const FEED_MAX = 40;
const MSK_DAY = (iso) => msk(Date.parse(iso));
function whenWords(iso) {
  const { day, hm } = MSK_DAY(iso);
  const n = D.diff(S.today, day);
  return (n === 0 ? 'сегодня' : n === 1 ? 'вчера' : D.ddmm(day)) + ' ' + hm;
}
const SUG_FROM = { meeting: 'встреча', telegram: 'Telegram', userbot: 'Telegram', mcp: 'Claude' };
// Откуда пришло — значок справа: наговорка — микрофон, встреча — двое, Telegram — самолётик, Claude — облачко.
const ORIGIN = {
  voice: ['mic', 'var(--plan)', 'наговорка'], meeting: ['meet', 'var(--plan)', 'встреча'], telegram: ['send', 'var(--plan)', 'Telegram'],
  userbot: ['send', 'var(--plan)', 'Telegram'], bot: ['send', 'var(--plan)', 'ответ в Telegram'], mcp: ['chat', 'var(--plan)', 'Claude'],
  web: ['pen', 'var(--meta)', 'в вебе'], manual: ['pen', 'var(--meta)', 'руками'], import: ['list', 'var(--meta)', 'перенос'],
};
const origin = (src) => ORIGIN[src] || ORIGIN.manual;
/** Предложение, из которого вышло дело: пачка встречи или окна Telegram. */
function sugByTask() {
  const m = new Map();
  for (const s of S.sugs.values()) if (s.kind === 'create' && s.result_task_id) m.set(s.result_task_id, s);
  return m;
}
/** Значок справа в строке «Поставил»: откуда, когда и номер — подсказкой; поставил другой — его кружок. */
function originMark(t, s) {
  if (t.created_by && t.created_by !== t.owner_id) {
    const who = [...S.people.values()].find((p) => p.user_id === t.created_by);
    return el('span', { class: 'src-mark', title: `поставил(а) ${who ? personName(who) : t.created_by} · ${whenWords(t.created_at)} · #${t.num}` },
      who ? avatar(who, 'tiny') : tint(icon('person', 14), 'var(--plan)'));
  }
  const [ic, col, word] = origin(t.source);
  return el('span', { class: 'src-mark', title: [word + (s && s.batch_title ? ': ' + s.batch_title : ''), whenWords(t.created_at), '#' + t.num].join(' · ') },
    tint(icon(ic, 14), col));
}
/** Значок источника у предложения: встреча — ссылкой на неё, цитата — подсказкой. */
function sugMark(s) {
  const [ic, col, word] = origin(s.source);
  const title = [s.batch_title || word, s.quote ? '«' + s.quote.slice(0, 200) + '»' : null].filter(Boolean).join('\n');
  return /^https:\/\//.test(s.source_ref || '')
    ? el('a', { class: 'src-mark', href: s.source_ref, target: '_blank', rel: 'noopener noreferrer', title, onclick: (e) => e.stopPropagation() }, tint(icon(ic, 14), col))
    : el('span', { class: 'src-mark', title }, tint(icon(ic, 14), col));
}
/** Откуда дело — в карточке: наговорка дословно или встреча и Telegram с цитатой (origin у /api/task). */
function originLine(t) {
  const o = extras.get(t.id)?.origin;
  if (!o) return null;
  const [ic, col, word] = origin(o.kind === 'dictation' ? 'voice' : o.source);
  const top = o.kind === 'dictation' ? 'Наговорка · ' + whenWords(o.at) : [o.batch_title || word, o.auto ? 'поставлено само' : null].filter(Boolean).join(' · ');
  const text = o.kind === 'dictation' ? o.text : o.quote;
  return el('div', { class: 'origin' }, tint(icon(ic, 13), col),
    el('div', {}, el('span', { class: 'o-h' }, top),
      o.url ? el('a', { class: 'link', href: o.url, target: '_blank', rel: 'noopener noreferrer' }, ' · встреча →') : null,
      text ? el('div', { class: 'o-t' }, text) : null));
}

/** Похожее открытое дело для предложения «завести»: то же из наговорки, пришедшее ещё раз из встречи
 *  или Telegram. Совпадение — по началам слов названия, хотя бы 60 % меньшего. */
const stems = (s) => norm(s).replace(/[^a-zа-я0-9]+/g, ' ').split(' ').filter((w) => w.length > 3).map((w) => w.slice(0, 5));
function twinOf(s) {
  if (s.kind !== 'create') return null;
  const a = new Set(stems((s.payload || {}).title || s.quote));
  if (a.size < 2) return null;
  let best = null, score = 0;
  for (const t of all()) {
    if (!isOpen(t) || !isMine(t)) continue;
    const b = new Set(stems(t.title));
    if (b.size < 2) continue;
    let k = 0;
    for (const w of a) if (b.has(w)) k++;
    const sc = k / Math.min(a.size, b.size);
    if (sc > score) { score = sc; best = t; }
  }
  return score >= 0.6 ? best : null;
}
/** «Это оно»: предложение срастается с делом — его слова ложатся комментарием, само предложение отклонено с причиной. */
async function mergeInto(s, t) {
  const p = s.payload || {};
  const said = [p.title, s.quote && s.quote !== p.title ? '«' + s.quote + '»' : null, p.notes].filter(Boolean).join(' — ');
  try {
    await ops([{ op: 'comment.add', comment: { task_id: t.id, text: `${s.batch_title || SUG_FROM[s.source] || 'Автоматика'}: ${said}` } },
      { op: 'suggestion.decide', id: s.id, decision: 'reject', reason: `уже есть #${t.num}` }]);
    toast(`Срослось с #${t.num}: слова — комментарием к делу`);
    render();
  } catch (e) { fail(e); render(); }
}

/** Почему предложение ждёт человека: вопрос самой автоматики или что не узналось по имени. */
const byName = (list, name) => {
  const n = norm(name);
  return n && list.find((x) => [x.name, x.short, ...(x.aliases || [])].some((a) => a && norm(a) === n));
};
function askWhy(s) {
  const p = s.payload || {};
  if (p.ask) return p.ask;
  if (p.project_name && !byName([...S.projects.values()], p.project_name)) return `Не знаю проект «${p.project_name}» — куда?`;
  if (p.person_name && !byName([...S.people.values()], p.person_name)) return `Кто это — «${p.person_name}»?`;
  return null;
}
const ASK_YES = { create: 'Поставить', close: 'Закрыть', update: 'Уточнить', assign: 'Взять' };
function askItem(s) {
  const { title, hint, add } = sugText(s);
  S.sugOrder.push(s.id);
  const twin = twinOf(s);
  const q = askWhy(s);
  // Карточка предложения как в Делах · Новое (screens/07): сверху источник — кремовый значок и пачка.
  return el('div', { class: 'sug' + (s.kind !== 'create' ? ' ' + s.kind : '') },
    el('div', { class: 'src-line' }, sugMark(s), el('span', {}, s.batch_title || origin(s.source)[2])),
    el('div', { class: 'main' }, el('div', {}, el('span', { class: 'sug-n', title: 'Номер для Claude: «П' + S.sugOrder.length + ' прими»' }, 'П' + S.sugOrder.length), title),
      q ? el('div', { class: 'q' }, q) : null,
      hint ? el('div', { class: 'hint' }, hint) : null,
      add ? el('div', { class: 'add' }, add) : null,
      s.quote && s.quote !== title ? el('div', { class: 'hint quote' }, '«' + s.quote.slice(0, 160) + (s.quote.length > 160 ? '…' : '') + '»') : null,
      twin ? el('div', { class: 'twin' }, icon('link', 12), 'похоже, это уже есть: ',
        el('a', { class: 'link', href: '#', onclick: (e) => { e.preventDefault(); openCard(twin.id); } }, `#${twin.num} ${twin.title}`),
        el('button', { class: 'chip-btn', title: 'Не заводить второе: слова — комментарием к делу', onclick: () => mergeInto(s, twin) }, 'Это оно')) : null),
    el('div', { class: 'acts' },
      el('button', { class: 'btn small ok', onclick: () => decide([s.id], 'accept') }, ASK_YES[s.kind] || 'Да'),
      s.kind === 'create' ? el('button', { class: 'btn small', onclick: () => { S.draft = s; S.cardId = null; render(); } }, 'Поправить') : null,
      el('button', { class: 'btn small bad', onclick: () => { const r = prompt(s.kind === 'create' ? 'Почему не дело? (можно пусто)' : 'Почему нет? (можно пусто)'); if (r !== null) decide([s.id], 'reject', r || null); } }, 'Не надо')));
}

/** «Поставил»: новые дела, свежие сверху, по дням; сделанные не показываем — они уже не новость. */
function feedBlock(list, from) {
  const days = new Map();
  for (const t of list.slice(0, FEED_MAX)) {
    const d = MSK_DAY(t.created_at).day;
    if (!days.has(d)) days.set(d, []);
    days.get(d).push(t);
  }
  const dayName = (d) => { const n = D.diff(S.today, d); return n === 0 ? 'Сегодня' : n === 1 ? 'Вчера' : D.long(d); };
  return el('div', { class: 'group feed' },
    el('h2', {}, tint(icon('inbox-in', 14), VIEWS.new.color), 'Поставил', el('span', { class: 'n' }, list.length),
      el('span', { class: 'gsub' }, 'откуда — значок справа, что сказано — в карточке')),
    [...days].map(([d, items]) => [el('div', { class: 'feed-day' }, dayName(d)),
      items.map((t) => taskRow(t, null, null, { compact: true, mark: originMark(t, from.get(t.id)) }))]),
    list.length > FEED_MAX ? el('a', { class: 'faint pad link', href: '#/all' }, `и ещё ${list.length - FEED_MAX} — во «Всех делах»`) : null);
}

/** Закрыть и уточнить автоматика может сама — свёрнуто одной строкой; «Понятно» убирает, «Вернуть» — как было. */
function autoBlock(auto, batch) {
  if (!auto.length) return null;
  const open = !!batch || S.autoOpen;
  const seen = async (ids) => { try { await op1({ op: 'suggestion.seen', ids }); render(); } catch (e) { fail(e); } };
  const nc = auto.filter((s) => s.kind === 'close').length;
  const what = [nc ? `закрыл ${nc}` : null, auto.length - nc ? `уточнил ${auto.length - nc}` : null].filter(Boolean).join(', ');
  return el('div', { class: 'group auto-done' + (open ? '' : ' folded') },
    el('h2', {}, tint(icon('check', 14), 'var(--ok)'), 'Сделано само', el('span', { class: 'n' }, auto.length),
      el('span', { class: 'gsub' }, what + ' по встречам и Telegram'),
      el('span', { class: 'act' },
        open && auto.length > 1 ? el('button', { class: 'chip-btn', title: 'Видел, согласен — убрать отсюда', onclick: () => seen(auto.map((s) => s.id)) }, 'Понятно, все') : null, ' ',
        batch ? null : el('button', { class: 'chip-btn', onclick: () => { S.autoOpen = !S.autoOpen; render(); } }, open ? 'Свернуть' : 'Показать'))),
    open ? auto.map((s) => autoItem(s, seen)) : null);
}
function autoItem(s, seen) {
  const t = S.tasks.get(s.task_id);
  if (t) S.order.push(t.id); // сделанное само — тоже дело на экране: «верни акт» Claude поймёт
  const r = s.result || {};
  const was = r.was || {};
  const back = t && (s.kind === 'close' ? t.status === 'done' : Object.keys(was).length || r.comment_id);
  const undo = async () => {
    const list = s.kind === 'close' ? [{ op: 'task.reopen', id: t.id }]
      : Object.keys(was).length ? [{ op: 'task.set', id: t.id, set: was }] : [];
    if (r.comment_id) list.push({ op: 'comment.delete', id: r.comment_id });
    list.push({ op: 'suggestion.seen', ids: [s.id] });
    try { await ops(list); toast((s.kind === 'close' ? 'Вернул в работу: ' : 'Вернул как было: ') + (was.title || t.title)); render(); } catch (e) { fail(e); }
  };
  const words = s.kind === 'update' ? autoWords(s) : '';
  return el('div', { class: 'sug auto' },
    el('div', { class: 'main' }, el('div', {}, (s.kind === 'close' ? 'Закрыто: ' : 'Уточнено: ') + (t ? `#${t.num} ${t.title}` : 'дело не видно')),
      words ? el('div', { class: 'hint' }, words) : null),
    el('div', { class: 'acts' },
      el('button', { class: 'btn small ok', title: 'Согласен — убрать отсюда', onclick: () => seen([s.id]) }, 'Понятно'),
      back ? el('button', { class: 'btn small', title: s.kind === 'close' ? 'Открыть дело снова' : 'Вернуть дело как было до уточнения', onclick: undo }, 'Вернуть') : null),
    sugMark(s));
}

/** «Новое» (владелец 08.10.2026: «делится на "не уверен — подскажи" и "уверен — поставил"»):
 *  «Подскажи» — только где без человека не поставить (вопрос автоматики, не узнан проект или человек,
 *  старая встреча); «Поставил» — новые дела за три дня одной строкой со значком источника справа;
 *  «Сделано само» — свёрнуто. Наговорки целиком, «без проекта», минуты и «что сказала автоматика» сюда
 *  больше не идут: что сказано — в карточке дела, без проекта — в «Неделе». batch — пачка из Telegram. */
function renderNew(batch) {
  const asks = pendingSugs().filter((s) => !batch || s.batch_ref === batch)
    .sort((a, b) => sugAt(b).localeCompare(sugAt(a)) || b.created_at.localeCompare(a.created_at));
  const from = sugByTask();
  const since = Date.now() - FEED_DAYS * 86400e3;
  const feed = (batch ? all().filter((t) => isOpen(t) && from.get(t.id)?.batch_ref === batch)
    : openMine().filter((t) => Date.parse(t.created_at) > since)).sort((a, b) => b.created_at.localeCompare(a.created_at));
  const auto = batch ? autoDone(365).filter((s) => s.batch_ref === batch) : autoDone(7);
  const box = el('div', { class: 'body' });
  if (batch) box.append(el('div', { class: 'toolbar' }, el('a', { class: 'chip-btn', href: '#/new' }, 'Всё «Новое»')));
  // Строка Claude: «первое поставь, срок пятница; второе не надо» — П-номера ниже те же, что видит он.
  box.append(quickAdd({}, { label: 'с новым', placeholder: asks.length
    ? '«П1 поставь, срок пятница; П2 не надо — делает Ольга»'
    : '«последние три — в Альфу», «полку — на субботу»' }));
  if (asks.length) {
    const ids = asks.map((s) => s.id);
    box.append(el('div', { class: 'group asks' },
      el('h2', {}, tint(icon('help', 14), 'var(--warn)'), 'Подскажи', el('span', { class: 'n' }, asks.length),
        el('span', { class: 'gsub' }, 'без тебя не поставлю'),
        ids.length > 1 ? el('span', { class: 'act' },
          el('button', { class: 'chip-btn', onclick: () => decide(ids, 'accept') }, 'Поставить все'), ' ',
          el('button', { class: 'chip-btn', onclick: () => decide(ids, 'reject') }, 'Не надо все')) : null),
      asks.map(askItem)));
  }
  if (feed.length) box.append(feedBlock(feed, from));
  const done = autoBlock(auto, batch);
  if (done) box.append(done);
  if (!asks.length && !feed.length && !auto.length) {
    box.append(el('div', { class: 'empty' }, batch ? 'Эта пачка уже разобрана.' : 'Пусто. Новое появится, когда наговоришь или придёт со встречи и из Telegram.'));
  }
  return [head('Новое', [asks.length ? `подскажи: ${asks.length}` : 'вопросов нет',
    feed.length ? (batch ? `поставил: ${feed.length}` : `поставил за ${FEED_DAYS} дня: ${feed.length}`) : null]), box];
}

// ── Настройки: Claude (у человека на сервере), голос (на этом устройстве), траты (владельцу) ──
const voiceAuto = () => LS.get('voiceAuto', true);
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
      el('div', { class: 'group' }, el('h2', {}, tint(icon('chat', 14), 'var(--claude)'), 'Claude правит дела словами'),
        el('div', { class: 'hint-line' }, 'Строка «говори дела» внизу каждой страницы и микрофон у дела. Claude видит, что на экране и в окне, что выбрано галочками и открыто в карточке, — и все остальные открытые дела коротко. Модель ниже — для списков; в карточке клиента, сделки и человека и для новых дел из надиктовки всегда Opus 5.5.'),
        v.claude ? null : el('div', { class: 'cr-err' }, 'Claude на сервере не настроен — нет ключа.'),
        el('div', { class: 'set-label' }, 'Модель'), choices('claude_model', MODEL_INFO, s.claude_model),
        el('div', { class: 'set-label' }, 'Глубина'), choices('claude_effort', EFFORT_INFO, s.claude_effort)),
      el('div', { class: 'group' }, el('h2', {}, tint(icon('mic', 14), 'var(--bad)'), 'Голос', el('span', { class: 'gsub' }, 'на этом устройстве')),
        el('div', { class: 'hint-line' }, 'Нажми микрофон — говори сколько нужно, хоть с паузами — нажми ещё раз.'),
        el('label', { class: 'set-check' }, auto, 'Второе нажатие сразу отдаёт Claude', el('span', { class: 'faint' }, ' — иначе текст ждёт в поле стрелку справа или Enter')),
        el('div', { class: 'hint-line' }, 'Речь распознаёт браузер (Chrome — и на телефоне). На компьютере можно диктовать и Wispr Flow прямо в поле.')),
      el('div', { class: 'group' }, el('h2', {}, tint(icon('flame', 14), 'var(--now)'), 'Путь: очки, серия, уровень'),
        el('div', { class: 'hint-line' }, 'Очки — только за доведённое: закрыл, отменил ненужное, разобрал «Новое». Карточка пути — вверху боковой панели, подробно — «Статистика».'),
        choices('game_theme', { torah: ['Тора', '42 стоянки странствий, серия — манна; суббота не рвёт'], greek: ['Греция', 'подвиги Геракла и путь Одиссея, серия — огонь Прометея'] }, s.game_theme || 'torah')),
      cost ? el('div', { class: 'group' }, el('h2', {}, tint(icon('coin', 14), 'var(--tint)'), 'Траты Claude в Делах'),
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

// «Напомнить в Telegram»: время или место — одно из двух. Шлёт бот Ковчега; новое время или место
// взводит напоминание заново, даже отправленное. Места — те, что прислал телефон (автопилот Засечки).
function remindBlock(t) {
  const set = (at, place) => setFields([t.id], { remind_at: at, remind_place: place });
  const now = Date.now();
  const hour = msk(now + 3600e3);
  const quick = [['через час', mskIso(hour.day, hour.hm)]];
  if (msk(now).hm < '18:30') quick.push(['вечером 19:00', mskIso(S.today, '19:00')]);
  quick.push(['завтра 09:00', mskIso(D.add(S.today, 1), '09:00')]);
  const same = (iso) => t.remind_at && Date.parse(t.remind_at) === Date.parse(iso);
  const chips = quick.map(([label, iso]) => el('button', { class: 'chip-btn' + (same(iso) ? ' on' : ''), onclick: () => set(iso, null) }, label));
  for (const p of myPlaces()) {
    const on = !t.remind_at && norm(t.remind_place) === norm(p);
    chips.push(el('button', { class: 'chip-btn' + (on ? ' on' : ''), onclick: () => set(null, p) }, 'приеду: ' + p));
  }
  if (t.remind_at || t.remind_place) chips.push(el('button', { class: 'chip-btn', onclick: () => set(null, null) }, 'убрать'));
  const cur = t.remind_at ? msk(Date.parse(t.remind_at)) : null;
  const own = el('input', { type: 'datetime-local', title: 'Своё время (по Москве)', value: cur ? `${cur.day}T${cur.hm}` : '' });
  own.addEventListener('change', () => { if (own.value) set(mskIso(own.value.slice(0, 10), own.value.slice(11, 16)), null); });
  const said = remindState(t);
  return [el('span', { class: 'top-label' }, 'Напомнить в Telegram'),
    el('div', { class: 'remind-box' }, said ? el('div', { class: 'remind-state' }, said) : null,
      el('div', { class: 'quick-dates' }, chips), own)];
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
  const projSel = el('select', {}, [['', 'Без проекта'], ...live.map((p) => [p.id, p.name])].map(([v, l]) => el('option', { value: v, selected: (src.project_id || '') === v }, l)));
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
  const title = el('textarea', { class: 'title-edit', rows: 1 }, src.title || '');
  const fit = () => { title.style.height = ''; title.style.height = title.scrollHeight + 'px'; }; // название — сколько строк надо
  title.addEventListener('input', fit);
  setTimeout(fit);
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
    c.addEventListener('change', () => {
      if (field === 'focus_on' && c.checked && t && !nowRoom([t.id])) { c.checked = false; return; }
      save(field, c.checked ? value : (field === 'want' ? false : null));
    });
    return el('label', {}, c, label);
  };

  const props = el('div', { class: 'props' },
    el('span', {}, 'Проект'), projSel,
    deals.length ? el('span', {}, 'Сделка') : null, deals.length ? sel('deal_id', [['', '—'], ...deals.map((d) => [d.id, d.name])], src.deal_id) : null,
    el('span', {}, 'Мяч'), sel('ball', Object.entries(BALL), src.ball),
    el('span', {}, 'Человек'), sel('person_id', [['', '—'], ...people.map((p) => [p.id, p.short && p.short !== p.name ? `${p.short} — ${p.name}` : p.name])], src.person_id),
    el('span', {}, 'Срок'), date('due_date'), el('span', {}), quickDates,
    el('span', {}, 'Напомнить ему'), date('nudge_on'),
    t && isOpen(t) && remindOn() ? remindBlock(t) : null,
    el('span', {}, 'Минут'), num,
    el('span', {}, 'Метки'), labels);

  // Карточка как на развороте Правки (screens/08): номер плашкой и проект, название Literata, ряд действий
  // («Поправить» словами, «Сейчас») и галочка-клавиша справа, свойства — стеклянной плашкой со строками.
  const cp = project(src.project_id);
  const crumb = cp ? el('a', { class: 'crumb', href: '#/p/' + cp.id }, cp.name) : el('span', { class: 'crumb' }, 'Без проекта');
  const acts = t ? el('div', { class: 'card-acts' },
    isOpen(t) ? [
      el('button', { class: 'btn' + (S.askTask === t.id ? ' on' : ''), title: 'Сказать Claude, что сделать с делом', onclick: () => (S.askTask === t.id && rec ? stopListening() : openAsk(t)) },
        icon('mic', 18), 'Поправить'),
      el('button', { class: 'btn' + (isNow(t) ? ' on' : ''), title: isNow(t) ? 'Убрать из «Сейчас»' : `В «Сейчас» — сделать сегодня (до ${NOW_MAX} дел)`, onclick: () => toggleNow(t) },
        icon('bolt', 17), 'Сейчас'),
      el('button', { class: 'key done-key', title: 'Сделано', onclick: () => toggleDone([t]) }, icon('tick', 24)),
    ] : el('button', { class: 'btn', onclick: () => toggleDone([t]) }, 'Вернуть в работу')) : null;
  const card = el('div', { class: 'card' },
    el('div', { class: 'top' },
      t ? el('span', { class: 'num' }, '#' + t.num) : null,
      sug ? el('span', { class: 'crumb' }, 'Предложение') : crumb,
      t && t.status !== 'open' ? el('span', { class: 'faint' }, t.status === 'done' ? 'сделано' : 'отменено') : null,
      el('button', { class: 'icon-btn', title: 'Закрыть (Esc)', onclick: close }, icon('x', 18))),
    title, acts, t && S.askTask === t.id ? askBox(t) : null, t ? originLine(t) : null, props,
    el('div', { class: 'checks' }, t ? null : chk('focus_on', 'Сейчас — на сегодня', isNow(src), S.today), chk('want', 'Хочу сам', src.want, true)),
    notes);
  const btns = el('div', { class: 'btns' });
  let personLink = null;
  if (t) {
    if (isOpen(t)) btns.append(el('button', { class: 'btn bad', onclick: async () => { if (confirm('Отменить дело? Оно останется в журнале.')) { try { await op1({ op: 'task.cancel', id: t.id }); render(); } catch (e) { fail(e); } } } }, 'Отменить дело'));
    const who = person(t.person_id); // проект — ссылкой наверху карточки
    if (who) {
      const org = orgLabel(who.org_id);
      personLink = el('a', { class: 'glass person-link', href: '#/h/' + who.id }, avatar(who),
        el('span', { class: 't' }, who.name, org ? el('span', { class: 'faint' }, ' · ' + org.name) : null, who.role ? el('span', { class: 's' }, who.role) : null),
        icon('chev', 20));
    }
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
  card.append(btns, personLink || '');
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
  crm: { title: 'Воронка', icon: 'funnel', color: 'var(--tint)' },
  clients: { title: 'Клиенты', icon: 'building', color: 'var(--tint)' },
  people: { title: 'Люди', icon: 'person', color: 'var(--tint)' },
  ties: { title: 'Связи', icon: 'link', color: 'var(--tint)' },
  money: { title: 'Деньги', icon: 'coin', color: 'var(--tint)', money: true },
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
    api(path).then((d) => { crmCache.set(path, { data: d, at: Date.now() }); softRender(); })
      .catch((e) => { crmCache.set(path, { data: c && c.data, at: Date.now(), error: e.message }); softRender(); });
  }
  return c ? c.data : null;
}
const crmDirty = () => { for (const c of crmCache.values()) c.at = 0; };
const loading = () => el('div', { class: 'empty loading' }, 'Загружаю…');

async function dealOp(op) {
  try { const r = await op1(op); crmDirty(); render(); return r; } catch (e) { fail(e); render(); return null; }
}
const setDeal = (id, set) => dealOp({ op: 'deal.set', id, set });

// Сделка — страницей (#/d/<id>), как клиент и человек: «не просто сайдбар» (владелец, 06.10.2026).
function openDeal(id) { S.cardId = null; S.draft = null; go('#/d/' + id); }
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
  return el('div', { class: 'dtile' + (route().id === d.id ? ' on' : '') + (d.stale ? ' stale' : ''), onclick: () => openDeal(d.id) },
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
// Проект клиента — его сделка в CRM («ПТИЦ: хедж юаня» у клиента «ПТИЦ»): владелец зовёт их проектами.
const clientDeals = (id) => [...S.deals.values()].filter((d) => d.project_id === id)
  .sort((a, b) => (a.stage === 'archive') - (b.stage === 'archive') || OPEN_STAGES.indexOf(a.stage) - OPEN_STAGES.indexOf(b.stage) || a.name.localeCompare(b.name, 'ru'));
function toggleClientOpen(id) {
  if (S.clientOpen.has(id)) S.clientOpen.delete(id); else S.clientOpen.add(id);
  LS.set('clientOpen', [...S.clientOpen]);
  render();
}
/** «Клиент: тема» под своим клиентом — просто «тема»: имя клиента и так видно строкой выше. */
function dealShort(d, p) {
  const m = d.name.match(/^([^:]{1,60}):\s*(.+)$/);
  if (!m || !p) return d.name;
  const head = norm(m[1]);
  return [p.name, ...(p.aliases || [])].some((n) => { const x = norm(n); return x && (head.startsWith(x.slice(0, 4)) || x.startsWith(head.slice(0, 4))); }) ? m[2] : d.name;
}
function renderClients() {
  const v = crmGet('/api/view/clients');
  if (!v) return [head('Клиенты'), el('div', { class: 'body' }, loading())];
  const q = norm(S.clientQ || '');
  let rows = v.clients.filter((c) => (S.clientArch ? true : !c.archived_at));
  if (q) rows = rows.filter((c) => norm(c.name + ' ' + (c.aliases || []).join(' ') + ' ' + (c.org || '')).includes(q));
  const search = el('input', { class: 'inline-search', type: 'search', placeholder: 'Найти клиента', value: S.clientQ || '' });
  search.addEventListener('input', () => { S.clientQ = search.value; const pos = search.selectionStart; render(); const s2 = document.querySelector('.inline-search'); if (s2) { s2.focus(); s2.setSelectionRange(pos, pos); } });
  // Проекты клиента (сделки CRM) раскрываются под строкой клиента — так же, как в меню слева.
  const dealsOf = clientDeals, toggle = toggleClientOpen;
  const allOpen = rows.length && rows.every((c) => S.clientOpen.has(c.id));
  const tb = el('div', { class: 'toolbar' }, search,
    el('button', { class: 'chip-btn' + (allOpen ? ' on' : ''), onclick: () => {
      rows.forEach((c) => (allOpen ? S.clientOpen.delete(c.id) : S.clientOpen.add(c.id))); LS.set('clientOpen', [...S.clientOpen]); render();
    } }, allOpen ? 'Свернуть проекты' : 'Раскрыть проекты'),
    el('button', { class: 'chip-btn' + (S.clientArch ? ' on' : ''), onclick: () => { S.clientArch = !S.clientArch; render(); } }, 'И архив'),
    el('button', { class: 'chip-btn', onclick: () => newClient() }, '+ Клиент'));
  const dealLine = (d) => {
    const next = all().filter((t) => t.deal_id === d.id && isOpen(t)).sort(sortTasks)[0];
    return el('div', { class: 'cdeal' + (d.stage === 'archive' ? ' arch' : ''), onclick: (e) => { e.stopPropagation(); openDeal(d.id); } },
      el('span', { class: 'cd-stage st-' + d.stage }, stageWord(d)),
      el('span', { class: 'cd-name' }, dealShort(d, project(d.project_id))),
      next ? el('span', { class: 'cd-next' + (isLate(next) ? ' late' : '') }, `#${next.num} ${next.title}` + (next.due_date ? ' · ' + D.ddmm(next.due_date) : ''))
        : d.stage !== 'archive' ? el('span', { class: 'cd-next none' }, 'нет следующего дела') : null);
  };
  const row = (c) => {
    const bits = [];
    if (c.stages && c.stages.length) bits.push(el('span', {}, c.stages.map((s) => STAGE[s]).join(', ')));
    else bits.push(el('span', { class: 'faint' }, c.all_deals ? 'проекты в архиве' : 'без проектов'));
    bits.push(el('span', { class: c.last_touch && D.diff(S.today, c.last_touch.slice(0, 10)) > 45 ? 'late' : '' }, c.last_touch ? 'контакт ' + ago(c.last_touch) : 'контактов нет'));
    if (v.money && c.paid_year_kop) bits.push(el('span', { class: 'money' }, 'за год ' + rubShort(c.paid_year_kop)));
    if (v.money && c.invoiced_kop) bits.push(el('span', { class: 'late' }, 'ждём ' + rubShort(c.invoiced_kop)));
    if (c.minutes_90) bits.push(el('span', { title: 'Засечка, 90 дней' }, hrs(c.minutes_90)));
    const deals = dealsOf(c.id);
    const shownDeals = deals.filter((d) => d.stage !== 'archive' || S.clientArch);
    const open = S.clientOpen.has(c.id);
    return el('div', { class: 'cblock' + (open ? ' open' : '') },
      el('div', { class: 'crow', onclick: () => go('#/p/' + c.id) },
        el('button', { class: 'caret-btn', title: open ? 'Свернуть проекты' : 'Показать проекты', disabled: !deals.length,
          onclick: (e) => { e.stopPropagation(); toggle(c.id); } }, deals.length ? (open ? '▾' : '▸') : ''),
        el('div', { class: 'main' },
          el('div', { class: 'title' }, c.name, c.org && norm(c.org) !== norm(c.name) ? el('span', { class: 'faint' }, ' · ' + c.org) : null,
            c.archived_at ? el('span', { class: 'faint' }, ' · архив') : null,
            deals.length ? el('span', { class: 'faint' }, ' · ' + plural(deals.length, 'проект', 'проекта', 'проектов')) : null),
          el('div', { class: 'chips' }, bits),
          !open && c.next_task ? el('div', { class: 'next' }, `#${c.next_task.num} ${c.next_task.title}` + (c.next_task.due_date ? ' · ' + D.ddmm(c.next_task.due_date) : ''))
            : (!open && c.live_deals ? el('div', { class: 'next none' }, 'нет следующего дела') : null))),
      open ? el('div', { class: 'cdeals' }, shownDeals.map(dealLine),
        deals.length > shownDeals.length ? el('div', { class: 'faint cd-more' }, `и в архиве: ${deals.length - shownDeals.length}`) : null,
        el('button', { class: 'cdeal add', onclick: () => newDeal(c.id) }, icon('plus', 12), ' проект')) : null);
  };
  const out = [tb, rows.length ? el('div', { class: 'group' }, rows.map(row)) : el('div', { class: 'empty' }, 'Не нашлось.')];
  if (v.unmatched && v.unmatched.length && !q) out.push(renderUnmatched(v.unmatched));
  return [head('Клиенты', [plural(rows.filter((c) => !c.archived_at).length, 'клиент', 'клиента', 'клиентов')]), el('div', { class: 'body' }, out)];
}

// ── Люди по компаниям ───────────────────────────────────────────────────
// Владелец, 06.10.2026: «в людях обязательно нужна группировка по компаниям». Компания — клиент (его
// страница по щелчку) или другая организация; команда — сверху, без компании — внизу. Плашка «откуда»
// у строки меняет компанию и должность, не заходя в карточку.
function renderPeople() {
  const q = norm(S.peopleQ || '');
  const hay = (x) => norm([x.name, x.short, ...(x.aliases || []), x.role, orgLabel(x.org_id)?.name].filter(Boolean).join(' '));
  const list = livePeople().filter((x) => !x.merged_into && (!q || hay(x).includes(q)));
  const openBy = new Map(), waitBy = new Map();
  for (const t of all()) {
    if (!isOpen(t) || !t.person_id) continue;
    openBy.set(t.person_id, (openBy.get(t.person_id) || 0) + 1);
    if (t.ball === 'waiting') waitBy.set(t.person_id, (waitBy.get(t.person_id) || 0) + 1);
  }
  const groups = new Map();
  for (const x of list) {
    const ol = orgLabel(x.org_id);
    const key = x.user_id ? '0' : ol ? '1' + norm(ol.name) : '9';
    if (!groups.has(key)) groups.set(key, { title: x.user_id ? 'Команда' : ol ? ol.name : 'Без компании', href: x.user_id ? null : ol && ol.href, items: [] });
    groups.get(key).items.push(x);
  }
  const search = el('input', { class: 'inline-search people-search', type: 'search', placeholder: 'Найти человека или компанию', value: S.peopleQ || '' });
  search.addEventListener('input', () => {
    S.peopleQ = search.value; const pos = search.selectionStart; render();
    const s2 = document.querySelector('.people-search'); if (s2) { s2.focus(); s2.setSelectionRange(pos, pos); }
  });
  const row = (x) => el('div', { class: 'crow person-row', onclick: () => go('#/h/' + x.id) },
    avatar(x),
    el('div', { class: 'main' },
      el('div', { class: 'title' }, x.name, x.short && x.short !== x.name ? el('span', { class: 'faint' }, ' · ' + x.short) : null),
      el('div', { class: 'chips' }, x.role ? el('span', {}, x.role) : null,
        openBy.get(x.id) ? el('span', {}, plural(openBy.get(x.id), 'дело', 'дела', 'дел')) : null,
        waitBy.get(x.id) ? el('span', { class: 'ball-waiting' }, 'жду: ' + waitBy.get(x.id)) : null)),
    el('button', { class: 'chip-btn', title: 'Откуда он, должность', onclick: (e) => { e.stopPropagation(); personPop(e.currentTarget, x); } }, 'откуда…'));
  const boxes = [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0], 'ru')).map(([, g]) =>
    el('div', { class: 'group' }, el('h2', {}, g.href ? el('a', { class: 'link', href: g.href }, g.title) : g.title,
      el('span', { class: 'n' }, g.items.length)), g.items.sort((a, b) => a.name.localeCompare(b.name, 'ru')).map(row)));
  return [head('Люди', [plural(list.length, 'человек', 'человека', 'человек'), 'по компаниям: команда, клиенты, другие организации']),
    el('div', { class: 'body' },
      quickAdd({}, { label: 'с людьми', placeholder: '«Иван теперь CFO в Бете», «Ольга ушла из Альфы», «заведи Петра Сидорова из Гаммы, юрист»' }),
      el('div', { class: 'toolbar' }, search),
      boxes.length ? boxes : el('div', { class: 'empty' }, 'Не нашлось.'))];
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
  // Люди клиента — плашками: крестик убирает отсюда, щелчок — откуда он и должность (владелец, 06.10.2026).
  const people = v ? v.people.map((x) => person(x.id) || x) : [];
  const peopleBox = v ? peopleChips('Люди', people, {
    empty: 'пока никого',
    remove: (x) => leaveClient(x, p, v),
    add: (anchor) => addPersonPop(anchor, people.map((x) => x.id), (id) => joinClient(person(id), p), (name) => joinClient(null, p, name)),
  }) : null;
  const tabs = el('div', { class: 'seg tabs' }, [['tasks', 'Дела'], ['timeline', 'Хронология']].map(([k, l]) =>
    el('button', { class: (S.clientTab || 'tasks') === k ? 'on' : '', onclick: () => { S.clientTab = k; render(); } }, l)));
  return { dealBox, info, peopleBox, tabs, timeline: (S.clientTab === 'timeline')
    ? (v ? timelineBox(v.timeline, v.entries, { project_id: p.id }, { showDeal: true }) : loading()) : null };
}

/** Организация клиента; у старого клиента без неё — заводим (люди привязываются к организации). */
async function clientOrg(p) {
  if (p.org_id) return p.org_id;
  const org = (await op1({ op: 'org.create', data: { name: p.name, kind: 'client' } })).row;
  await op1({ op: 'project.set', id: p.id, set: { org_id: org.id } });
  return org.id;
}
/** Человек — к клиенту: его организация; нового — заводим сразу с ней. */
async function joinClient(x, p, name) {
  try {
    const org = await clientOrg(p);
    if (x) {
      const was = x.org_id || null;
      await op1({ op: 'person.set', id: x.id, set: { org_id: org } });
      toast(`${personName(x)} — теперь из «${p.name}»`, () => op1({ op: 'person.set', id: x.id, set: { org_id: was } }));
    } else {
      await op1({ op: 'person.create', data: { name, org_id: org } });
      toast(`Завёл: ${name} из «${p.name}»`);
    }
    render();
  } catch (e) { fail(e); }
}
/** Убрать человека из клиента: из его организации и из людей его сделок. Строки не удаляются — «Вернуть». */
async function leaveClient(x, p, v) {
  if (!confirm(`Убрать ${x.name} из «${p.name}»? Карточка человека останется.`)) return;
  const go_ = [], back = [];
  if (x.org_id && x.org_id === p.org_id) {
    go_.push({ op: 'person.set', id: x.id, set: { org_id: null } });
    back.push({ op: 'person.set', id: x.id, set: { org_id: x.org_id } });
  }
  for (const d of (v && v.deals) || []) {
    const ids = d.person_ids || [];
    if (!ids.includes(x.id)) continue;
    go_.push({ op: 'deal.set', id: d.id, set: { person_ids: ids.filter((i) => i !== x.id) } });
    back.push({ op: 'deal.set', id: d.id, set: { person_ids: ids } });
  }
  if (!go_.length) { toast(`${x.name} связан с «${p.name}» иначе — поправь в его карточке`); return; }
  try { await ops(go_); render(); toast(`${personName(x)} больше не в «${p.name}»`, () => ops(back)); } catch (e) { fail(e); }
}

// ── Сделка — страницей ──────────────────────────────────────────────────
// Владелец, 06.10.2026: «если я нажимаю на проект, он должен быть не просто сайдбаром, а таким же,
// как клиент и человек: видны дела, что за проект, и можно его двигать дальше». Сделка у клиента —
// это его проект. Наверху — строка Claude (Opus видит сделку целиком), стадии — кнопками.
function renderDeal(id) {
  const d0 = S.deals.get(id);
  const v = crmGet('/api/view/deal?deal_id=' + id, 15000);
  if (!d0) return [head('Сделка'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Сделка не видна.'))];
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
  const team = teamPeople();
  const ppl = livePeople().sort((a, b) => personName(a).localeCompare(personName(b), 'ru'));
  // Команда и люди клиента — те же плашки, что у клиента.
  const chipsOf = (field, title) => {
    const ids = d[field] || [];
    return peopleChips(title, ids.map(person).filter(Boolean), {
      remove: (x) => save(field, ids.filter((y) => y !== x.id)),
      add: (anchor) => addPersonPop(anchor, ids, (pid) => save(field, [...ids, pid]), async (name) => {
        const org = field === 'person_ids' && p ? await clientOrg(p).catch(() => null) : null;
        const r = await dealOp({ op: 'person.create', data: { name, ...(org ? { org_id: org } : {}) } });
        if (r && r.row) save(field, [...ids, r.row.id]);
      }),
    });
  };

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

  const title = el('input', { class: 'deal-name', value: d.name, title: 'Название сделки' });
  title.addEventListener('keydown', (e) => { if (e.key === 'Enter') title.blur(); });
  title.addEventListener('change', () => title.value.trim() && save('name', title.value.trim()));
  const props = el('div', { class: 'props deal-props' },
    el('span', {}, 'Название'), title,
    el('span', {}, 'Тип'), sel('deal_type', DEAL_TYPES.map((x) => [x, x]).concat(d.deal_type && !DEAL_TYPES.includes(d.deal_type) ? [[d.deal_type, d.deal_type]] : []), d.deal_type),
    el('span', {}, 'Ведёт'), sel('lead_person_id', team.map((x) => [x.id, personName(x)]), d.lead_person_id),
    el('span', {}, 'Привёл'), sel('source_person_id', ppl.map((x) => [x.id, x.name]), d.source_person_id),
    el('span', {}, 'Вероятность, %'), inp('probability', 'number', d.probability, (x) => Math.max(0, Math.min(100, Math.round(+x)))),
    el('span', {}, 'Решение ждём'), inp('expected_on', 'date', d.expected_on),
    el('span', {}, 'Дедлайн'), inp('deadline', 'date', d.deadline));

  const sub = [p ? el('a', { class: 'link', href: '#/p/' + p.id }, dot(p.id), ' ', p.name) : null, stageWord(d),
    d.fee_kop && money ? rubShort(d.fee_kop) : null, v && v.minutes_all ? el('span', { title: 'время из Засечки по делам сделки' }, icon('clock', 12), ' ' + hrs(v.minutes_all)) : null];
  const body = el('div', { class: 'body' },
    quickAdd({ project_id: d.project_id, deal_id: d.id }, { label: `со сделкой «${d.name}»`,
      placeholder: '«подписали мандат», «этого уже нет — закрой, проиграли», «Ольга теперь в команде клиента», «созвон: ждут модель к пятнице»' }),
    stageRow,
    el('div', { class: 'deal-people' }, chipsOf('person_ids', 'Люди клиента'), chipsOf('team_ids', 'Команда')),
    el('details', { class: 'deal-more', open: S.dealMore ? true : null, ontoggle: (e) => { S.dealMore = e.currentTarget.open; } },
      el('summary', {}, 'Что за сделка: тип, кто ведёт, вероятность, сроки'), props));

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
    body.append(el('details', { class: 'deal-more' }, el('summary', {}, 'Деньги: ' + ([paid && 'получено ' + rubShort(paid), inv && 'счета ' + rubShort(inv),
      plan && 'план ' + rubShort(plan)].filter(Boolean).join(' · ') || 'оплат пока нет')),
      el('div', { class: 'props' },
        el('span', {}, 'Модель'), sel('fee_kind', Object.entries(FEE_KIND), d.fee_kind),
        el('span', {}, 'Гонорар, ₽'), rubIn('fee_kop'),
        el('span', {}, 'Ретейнер, ₽/мес'), rubIn('retainer_kop'),
        el('span', {}, 'Успех, %'), inp('success_pct', 'number', d.success_pct, (x) => +x)),
      el('div', { class: 'money-sum' }, [paid && 'получено ' + rub(paid), inv && 'счета ' + rub(inv), plan && 'план ' + rub(plan),
        d.fee_kop && ['mandate', 'active', 'closing'].includes(d.stage) ? 'осталось ' + rub(Math.max(d.fee_kop - paid, 0)) : null,
        d.fee_kop && paid + inv + plan < d.fee_kop && d.stage !== 'archive' ? 'не расписано ' + rub(d.fee_kop - paid - inv - plan) : null].filter(Boolean).join(' · ') || 'оплат пока нет'),
      el('div', { class: 'pays' }, pays.map((x) => payRow(x, false))),
      el('div', { class: 'pay-add' }, kind, amount, due, el('button', { class: 'btn small', onclick: addPay }, '+ Оплата'))));
  }

  const tabs = el('div', { class: 'seg tabs' }, [['tasks', 'Дела'], ['timeline', 'Хронология']].map(([k, l]) =>
    el('button', { class: (S.clientTab || 'tasks') === k ? 'on' : '', onclick: () => { S.clientTab = k; render(); } }, l)));
  body.append(tabs);
  if (S.clientTab === 'timeline') {
    body.append(v ? timelineBox(v.timeline, v.entries, { project_id: d.project_id, deal_id: d.id, person_ids: d.person_ids || [] }) : loading());
  } else {
    // Следующий шаг — открытое дело сделки; без него сделка застынет.
    const items = all().filter((t) => t.deal_id === d.id && (S.showDone || isOpen(t)));
    const open = items.filter(isOpen);
    body.append(el('div', {}, el('div', { class: 'toolbar' }, doneToggle()),
      open.length || d.stage === 'archive' ? null : el('div', { class: 'next none pad' }, 'Нет следующего дела — сделка без шага застынет. Скажи его в строке наверху.'),
      items.length ? grouped(items, 'date') : null));
  }
  const legacy = [d.my_view != null || S.me.role === 'owner' ? ['Как я вижу (только мне)', 'my_view', true] : null, ['Идеи', 'ideas', true],
    d.next_step ? ['Следующий шаг (Notion)', 'next_step', false] : null, d.ball ? ['Мяч (Notion)', 'ball', false] : null,
    d.log ? ['Хронология (Notion)', 'log', false] : null].filter(Boolean);
  body.append(el('details', { open: d.my_view || d.ideas ? true : null }, el('summary', {}, 'Заметки и тексты из Notion'),
    legacy.map(([label, f, edit]) => {
      const ta = el('textarea', { class: 'notes', readonly: edit ? null : true }, d[f] || '');
      if (edit) ta.addEventListener('change', () => save(f, ta.value.trim() || null));
      return el('div', { class: 'legacy' }, el('div', { class: 'faint' }, label), ta);
    })));
  if (v && v.history.length) {
    body.append(el('details', {}, el('summary', {}, 'Журнал сделки'), v.history.map((h) => {
      const a = h.after || {};
      const what = h.op === 'insert' ? 'заведена' : a.stage ? 'стадия: ' + (STAGE[a.stage] || a.stage) + (a.outcome ? ' — ' + OUTCOME[a.outcome] : '') : Object.keys(a).join(', ');
      return el('div', { class: 'hist' }, `${h.at.slice(0, 16).replace('T', ' ')} · ${h.actor} · ${what}`);
    })));
  }
  return [head(d.name, sub), body];
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
      el('div', { class: 'group' }, el('h2', {}, tint(icon('chart', 14), 'var(--tint)'), 'Очки по дням', el('span', { class: 'gsub' }, '30 дней, субботы светлее')),
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
    if (S.cardId || S.draft) { S.cardId = null; S.draft = null; render(); return; }
    if (S.sel.size) { S.sel.clear(); render(); }
    return;
  }
  if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
  if (e.key === '/') { e.preventDefault(); document.querySelector('.side-search')?.focus(); }
  if (e.key === 'n' || e.key === 'т') { e.preventDefault(); document.getElementById('quick')?.focus(); }
  const jump = { m: 'now', u: 'upcoming', i: 'new', w: 'waiting' }[e.key];
  if (jump) go('#/' + jump);
});

boot();
