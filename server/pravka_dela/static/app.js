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
  crmList: LS.get('crmList', false), crmMine: false, crmStale: false, clientQ: '', clientArch: false,
  sphere: LS.get('sphere', ''), levels: LS.get('levels', {}), fdraft: {}, pick: {}, favs: LS.get('favs', []), closed: LS.get('closed', {}),
  showDone: false, upNoDate: false, upMineOnly: false, dealFilter: null,
  sel: new Set(), order: [], lastPick: null, cardId: null, draft: null, sideOpen: false,
  sideHidden: LS.get('sideHidden', false), // боковина на широком спрятана язычком (13.10.2026) — помнит браузер
  cardHidden: LS.get('cardHidden', false), lastCard: null, // карточка справа спрятана язычком (14.10.2026); какая была открыта
  parse: null, quickText: '', // идёт разбор Claude; текст строки Claude — живёт, пока его не отдали (перерисовка его не стирает)
  cardSay: {}, kstati: false, whenText: {}, // строка Claude в карточке дела (по делу), «Кстати», строка «Когда» (13.10.2026)
  selfOpen: null, selfText: {}, // «Сам допишу» у уточнения в «Подскажи»: какое открыто и что написано
  autoOpen: false, clientOpen: new Set(LS.get('clientOpen', [])), peopleQ: '', // «Сделано само» развёрнуто, клиенты со сделками, люди
  krug: { dirs: [], principles: [], proj: new Map() }, kpick: {}, // Круг жизни: направления и принципы (Свод), проекты в Круге; выбор в «Подскажи»
  treeMode: LS.get('treeMode', 'tree'), treeDir: LS.get('treeDir', null), treeF: null, // Дерево жизни: вид, направление, фильтры списка
  treeLevel: LS.get('treeLevel', null), // до какого уровня раскрыто дерево (кнопки 1–4, 0 — «Всё»)
  allTree: LS.get('allTree', false), cardStep: false, // «Все дела» деревом; строка карточки в режиме «Для этого» (15.10.2026)
  renamed: new Map(), // id «Зачем» → старые названия (Свод zachem.renamed, 13.10.2026)
  merge: null, // «Это тот же, что…»: {from, to} — лист подтверждения слияния на странице человека (аудит №23)
};
// Три группы дел (владелец 10.10.2026): «есть дела, которые ждут меня — люди что-то спросили и ждут, они
// мега важные… второе — что я сам придумал… третье — проверить, что мне люди должны». Вечером назвал их:
// «первая — отбить, вторая — запустить, третья — мониторить… это должна быть базовая группировка везде».
// Порядок — приоритет. Группа — из owed и ball (store.group_of, dela_0007): «Запустить» — это и mine, и повестка.
const GRP = { owed: 'Отбить', self: 'Запустить', check: 'Мониторить' };
const GRP_SUB = { owed: 'люди ждут ответа или дела — первым делом', self: 'сам придумал — запустить', check: 'что должны мне — проверить, напомнить' };
// Значок группы в её заголовке (макет 13.10.2026): отбить — стрелка ответа, запустить — «играть», мониторить — глаз.
const GRP_ICON = { owed: 'reply', self: 'play', check: 'eye' };
const grpOf = (t) => (t.owed || t.ball === 'owed' ? 'owed' : t.ball === 'waiting' ? 'check' : 'self');
/** Группа словами — в «что поменялось» и подсказках «Нового». */
const grpWord = (a) => ({ owed: 'отбить', check: 'мониторить', self: a.ball === 'agenda' ? 'при встрече' : 'запустить' })[grpOf(a)];
/** Группа → поля дела. Повестка, ставшая «Запустить», остаётся повесткой: это тоже «Запустить». */
const grpSet = (t, g) => (g === 'owed' ? { ball: 'mine', owed: true } : g === 'check' ? { ball: 'waiting', owed: false }
  : { ball: t && t.ball === 'agenda' ? 'agenda' : 'mine', owed: false });
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
  // Круг жизни: направления проектов (их наследуют дела) и справочник из Свода — только свои (RLS).
  if (d.full) S.krug.proj.clear();
  for (const k of d.krug || []) S.krug.proj.set(k.id, { directions: k.directions || [], why_id: k.why_id || null });
  for (const r of d.svod || []) {
    const v = Array.isArray(r.value) ? r.value : [];
    if (r.key === 'krug.directions') S.krug.dirs = v.filter((x) => x && x.key).sort((a, b) => ((+a.pos || 0) % 12) - ((+b.pos || 0) % 12));
    if (r.key === 'krug.principles') S.krug.principles = v.map((x) => String(x).trim()).filter(Boolean);
    // Переименование «Зачем» (13.10.2026): старые названия — чтобы поиск по ним находил (и было что вернуть).
    if (r.key === 'zachem.renamed') {
      S.renamed = new Map();
      for (const x of v) if (x && x.id && x.was) S.renamed.set(x.id, [...(S.renamed.get(x.id) || []), x.was]);
    }
  }
  // Траты Claude (dela_0017): $ за 7 дней и непрочитанные советы — для значка на «Сегодня»; только владельцу.
  S.spend = d.spend || null;
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
const openMine = () => all().filter((t) => isOpen(t) && isMine(t) && inSphere(t) && !t.goal);

// ── Круг жизни (12.10.2026) и дерево жизни (13.10.2026) ─────────────────
// Владелец: «каждое моё дело должно преследовать какое-то направление… просто направление и Круг жизни». Бриф 7.2
// свёл это в одно дерево: девять направлений, под каждым — его цели (цель — состояние: «сын хорошо знает
// математику»; цель с ритмом — практика), подцели, проекты (клиент — целиком) и дела. Цель — дело с пометкой goal:
// её нет в списках дел, она живёт в дереве и в цепочке «Зачем». Главное направление дела — у корня цепочки «Зачем»
// (через проект, если «Зачем» у дела нет); свои направления, кроме главного, — метки. То же правило —
// tasks.krug_dirs на сервере. Справочник — Свод владельца (krug.directions), место делам без цели предлагает
// разборщик на сервере (krug_auto.py). Чужие дела направлений и целей не показывают.
const krugOn = () => S.krug.dirs.length > 0 && ((S.me && S.me.features) || []).includes('krug');
const treeOn = () => krugOn() && ((S.me && S.me.features) || []).includes('tree');
/** Дела — без целей: цель не делают, к ней идут (в списках дел целей нет). */
const deeds = () => all().filter((t) => !t.goal);
const dirBy = (k) => S.krug.dirs.find((d) => d.key === k);
const dirName = (k) => { const d = dirBy(k); return d ? d.short || d.name : k; };
/** Цепочка вверх (как tasks.krug_dirs): само дело, его «Зачем» и выше; у звена без «Зачем» — проект дела (строка
 *  Круга проекта: {kind: 'project'}) и цель, на которой проект висит. */
function upOf(t) {
  const out = [];
  for (let x = t, n = 0; x && n < 60; n++) {
    out.push(x);
    if (x.why_id) { const w = S.tasks.get(x.why_id); x = w && !out.includes(w) ? w : null; continue; }
    if (x.kind === 'project' || x.goal) break;
    const p = x.project_id && S.krug.proj.get(x.project_id);
    x = p ? { kind: 'project', id: x.project_id, directions: p.directions || [], why_id: p.why_id || null } : null;
  }
  return out;
}
/** Направления дела: [главное, метки…]. Главное — у самого верхнего звена цепочки, где направления есть. */
function dirsOf(t) {
  if (!t || !isMine(t)) return [];
  const up = upOf(t);
  let main = null;
  for (let i = up.length - 1; i >= 0 && !main; i--) if ((up[i].directions || []).length) main = up[i].directions[0];
  return main ? [main, ...(t.directions || []).filter((k) => k !== main)].slice(0, 3) : [];
}
/** Открытые шаги к цели: дела и подцели, у которых «Зачем» — это дело. */
const stepsOf = (id) => all().filter((x) => x.why_id === id && isOpen(x));
/** Всё, что ниже дела по «Зачем», вместе с ним: туда его же не повесишь (иначе круг). */
function belowOf(id) {
  const below = new Set([id]);
  for (let grew = true; grew;) {
    grew = false;
    for (const x of all()) if (x.why_id && below.has(x.why_id) && !below.has(x.id)) { below.add(x.id); grew = true; }
  }
  return below;
}
// ── Дела внутри дела (15.10.2026) ────────────────────────────────────────
// Владелец: «до этого надо из расшифровки встречи набросать драфт… под делом должно быть „до этого“ / „для этого“ /
// „поправить это дело“ — и в карточке, и в обычном списке»; вечером — «вложенность одного в другое и показывать тоже в
// дереве»; «это не отдельная сущность, а обычные дела, вложенные в дело». Вложенное — обычное дело, у которого «Зачем»
// (why_id) — другое дело, не цель: свои поля, карточка, свои вложенные. Порядок среди соседей — pos (dela_0030).
// Одно правило с сервером (store.step_of, step_due, _nest_rise) и телефоном (core/DelaSteps.kt). «Зачем» видит только
// хозяин дела — у команды дела внутри стоят плоско, обычными делами.
const nestOn = () => ((S.me && S.me.features) || []).includes('nest');
/** Дело, внутри которого это дело (не цель: у цели своё «Зачем»). */
const parentOf = (t) => { const p = t && t.why_id ? S.tasks.get(t.why_id) : null; return p && !p.goal ? p : null; };
/** Место среди соседей: pos; пусто — после занумерованных, по времени заведения (как krug.tree и телефон). */
const byPos = (a, b) => (a.pos == null) - (b.pos == null) || (a.pos || 0) - (b.pos || 0)
  || (a.created_at || '').localeCompare(b.created_at || '') || a.num - b.num;
/** Открытые дела внутри — по месту; первое — «следующее» («сначала: …» у родителя). */
const insideOf = (id) => all().filter((x) => x.why_id === id && isOpen(x) && !x.goal).sort(byPos);
/** Срок нового дела внутри, если его не назвали (store.step_due): родитель в «Сейчас», просрочен или на сегодня —
 *  сегодня; позже — тот же; без срока — без срока. */
const stepDue = (p) => (p.focus_on === S.today || (p.due_date && p.due_date <= S.today) ? S.today : p.due_date || null);
const shortTitle = (s, n = 40) => (s.length > n ? s.slice(0, n - 1) + '…' : s);
/** В одном плоском списке дело внутри — прямо перед своим родителем (вложенное вложенного — перед ним). Дела внутри
 *  одного дела, чьего родителя в списке нет, — подряд и по месту (pos), там, где стоит первое из них. */
function stepsFirst(items) {
  const ids = new Set(items.map((t) => t.id));
  const under = (t) => t.why_id && t.why_id !== t.id && ids.has(t.why_id);
  const kids = new Map(), lone = new Map();
  for (const t of items) {
    const at = under(t) ? kids : parentOf(t) ? lone : null;
    if (at) { if (!at.has(t.why_id)) at.set(t.why_id, []); at.get(t.why_id).push(t); }
  }
  if (!kids.size && ![...lone.values()].some((v) => v.length > 1)) return items;
  const out = [], placed = new Set();
  const place = (t) => { if (placed.has(t.id)) return; placed.add(t.id); for (const c of (kids.get(t.id) || []).sort(byPos)) place(c); out.push(t); };
  for (const t of items) {
    if (under(t)) continue;
    const sibs = lone.get(t.why_id);
    if (sibs && sibs.length > 1 && parentOf(t)) [...sibs].sort(byPos).forEach(place); else place(t);
  }
  for (const t of items) place(t); // круг «Зачем» (база его не пускает) — всё равно никого не теряем
  return out;
}
/** Верхний предок дела среди дел этого же списка (само дело — если его родителя в списке нет). По нему уровни
 *  группировки кладут дело внутри туда же, где родитель (15.10.2026): иначе «Отбить» и «Запустить» растащили бы их,
 *  и «прямо перед родителем» не вышло бы. */
function topIn(items) {
  const by = new Map(items.map((t) => [t.id, t]));
  return (t) => {
    let x = t;
    for (let n = 0; n < 60; n++) { const p = x.why_id && x.why_id !== x.id ? by.get(x.why_id) : null; if (!p || p === t) break; x = p; }
    return x;
  };
}
/** Открытые дела внутри дел списка (все уровни, кроме тех, что и так в нём): [дело, глубина]. */
function innerOf(list) {
  const have = new Set(list.map((t) => t.id)), out = [];
  const walk = (id, depth) => {
    for (const c of insideOf(id)) { if (have.has(c.id)) continue; have.add(c.id); out.push([c, depth]); walk(c.id, depth + 1); }
  };
  for (const t of list) walk(t.id, 1);
  return out;
}
/** «Деревом»: корни списка (их родителя в списке нет) и дети по месту. Только дела из самого списка. */
function nestForest(items) {
  const ids = new Set(items.map((t) => t.id));
  const kids = new Map();
  for (const t of items) if (t.why_id && t.why_id !== t.id && ids.has(t.why_id)) { if (!kids.has(t.why_id)) kids.set(t.why_id, []); kids.get(t.why_id).push(t); }
  for (const v of kids.values()) v.sort(byPos);
  const roots = items.filter((t) => !(t.why_id && t.why_id !== t.id && ids.has(t.why_id)));
  // Круг «Зачем» внутри списка (не бывает — правило базы): без корня его дела пропали бы — показываем их корнями.
  const shown = new Set();
  const walk = (t) => { if (shown.has(t.id)) return; shown.add(t.id); (kids.get(t.id) || []).forEach(walk); };
  roots.forEach(walk);
  return { roots: [...roots, ...items.filter((t) => !shown.has(t.id))], kids };
}
/** Путь цели словами: «Здоровье › Еда › Белок 140+». */
function goalPath(g) {
  const main = dirsOf(g)[0];
  return [main ? dirName(main) : null, ...upOf(g).reverse().filter((x) => x.kind !== 'project').map((x) => x.title)]
    .filter(Boolean).join(' › ');
}
/** Движение за неделю по направлениям (как krug.movement): закрытое дело — 1, закрытая цель — 3; только главное.
 *  Цель — только узел дерева: дело с делами внутри (15.10.2026) — обычное дело, 1 очко (раньше «было у кого шаги» — 3). */
function movement() {
  const since = D.add(S.today, -6);
  const m = {};
  for (const t of all()) {
    if (t.status !== 'done' || !isMine(t) || !t.completed_at || MSK_DAY(t.completed_at).day < since) continue;
    const k = dirsOf(t)[0];
    if (!k) continue;
    const x = m[k] || (m[k] = { done: 0, events: 0 });
    x.done++;
    x.events += t.goal ? 3 : 1;
  }
  for (const x of Object.values(m)) x.radius = Math.min(10, Math.round(3 * Math.sqrt(x.events) * 10) / 10);
  return m;
}
/** Шаг к цели (как krug.toward_goal): вверх по «Зачем» есть цель дерева. Дело внутри обычного дела, над которым целей
 *  нет (драфт для термшита клиенту, 15.10.2026), — не шаг к цели: «Моё» — то, что хочу я, а не то, чего ждёт клиент. */
function towardGoal(t) {
  for (let x = t, n = 0; x && x.why_id && n < 60; n++) {
    const w = S.tasks.get(x.why_id);
    if (!w || w.goal) return !!w;
    x = w;
  }
  return false;
}
/** «Моё» (бриф, §7; принцип «Делаю то, что хочу я»): до трёх открытых дел, которых никто не ждёт — «Хочу сам»
 *  и шаги к целям (вверх по «Зачем» есть цель, своих открытых дел внутри нет); первым закреплённое, потом шаги из
 *  направлений, где за неделю меньше всего движения. Не «отбить» и не «мониторить»: там ждут люди. Цели — не дела. С
 *  13.10.2026 — без того, что и так на виду: срок сегодня («Сегодня») или уже в «Сейчас». Правило — krug.mine_from и
 *  store.view_today. */
const MINE_MAX = 3;
function mineTasks() {
  if (!krugOn()) return [];
  const mv = movement();
  const withSteps = new Set(all().filter((x) => isOpen(x) && x.why_id).map((x) => x.why_id));
  const least = (t) => { const k = dirsOf(t)[0]; return k ? (mv[k] || {}).events || 0 : 0; };
  return deeds().filter((t) => isOpen(t) && isMine(t) && inSphere(t) && !t.owed && t.ball !== 'waiting'
    && !isNow(t) && t.due_date !== S.today && (t.want || (t.why_id && !withSteps.has(t.id) && towardGoal(t))))
    .sort((a, b) => (b.want ? 1 : 0) - (a.want ? 1 : 0) || least(a) - least(b)
      || (a.created_at || '').localeCompare(b.created_at || '') || a.num - b.num)
    .slice(0, MINE_MAX);
}
/** Принцип дня — по кругу, один в день (как krug.principle_of_day: порядковый номер дня по-питоновски). */
function principleToday() {
  const list = S.krug.principles;
  if (!list.length || !S.today) return null;
  return list[(Math.round(D.parse(S.today) / 86400e3) + 719163) % list.length];
}
const principleLine = () => { const p = principleToday(); return p ? el('div', { class: 'principle', title: 'Принцип дня' }, p) : null; };
/** Направления дела — переключателями: горят главное и метки (dirsOf). Главное по цели — само (владелец 13.10.2026:
 *  «выбрал Зачем — направление ставится по ветке цели»): у него пометка «по цели», и щелчком его не снять — меняется
 *  цель; остальные — свои, руками, как раньше. */
function dirChips(t, onSet) {
  const own = t.directions || [];
  const lit = dirsOf(t);
  // Откуда главное: самое верхнее звено над делом, у которого есть направления, — цель или проект.
  const src = lit[0] ? upOf(t).slice(1).reverse().find((x) => (x.directions || []).length) : null;
  const by = src ? (src.kind === 'project' ? 'по проекту' : 'по цели') : '';
  return el('div', { class: 'quick-dates dir-pick' }, S.krug.dirs.map((d) => {
    const auto = !!src && d.key === lit[0];
    return el('button', {
      class: 'chip-btn' + (lit.includes(d.key) ? ' on' : '') + (auto ? ' auto' : ''),
      title: auto ? `Главное — ${by}; сменить — поменяй «Зачем»` : d.formula || d.name,
      onclick: () => (auto ? toast(`Главное направление — ${by}. Сменить его — поменяй «Зачем».`)
        : onSet(own.includes(d.key) ? own.filter((k) => k !== d.key) : [...own, d.key])) },
    d.short || d.name, auto ? el('span', { class: 'chip-note' }, by) : null);
  }));
}
/** «Без направления» у дела — щелчок: цель из дерева или просто направление. */
function dirPop(anchor, t) { whyPop(anchor, t); }
/** Выбор цели: открытые цели с путём от направления и поиском; сверху — «Новая цель» по набранному, без набранного —
 *  направления (повесить прямо на направление, «без зачем») и «Убрать «Зачем»». */
function goalPop(anchor, { exclude = new Set(), current = null, pick, make, toDir, title = 'Зачем' }) {
  // Только цели дерева (15.10.2026): дело с делами внутри целью не стало — вкладывают «Вложить в…».
  const pool = all().filter((x) => isOpen(x) && isMine(x) && !exclude.has(x.id) && !x.no_steps && x.goal)
    .map((x) => ({ x, path: goalPath(x) })).sort((a, b) => a.path.localeCompare(b.path, 'ru'));
  const done = (fn) => (e) => { e.preventDefault(); closePop(); fn(); };
  setTimeout(() => {
    const q = el('input', { class: 'ps-q', placeholder: 'К какой цели ведёт? Найти или написать новую', autocomplete: 'off' });
    const box = el('div', { class: 'ps-list' });
    const draw = () => {
      const words = norm(q.value).split(/\s+/).filter(Boolean);
      const shown = pool.filter((g) => words.every((w) => norm(g.path).includes(w))).slice(0, 40);
      const exact = shown.some((g) => norm(g.x.title) === norm(q.value));
      box.replaceChildren(...[
        words.length && !exact && make ? el('button', { class: 'ps-item', onmousedown: done(() => make(q.value.trim())) },
          el('span', { class: 'ps-dot' }), el('span', { class: 'ps-t' }, el('span', { class: 'ps-n' }, 'Новая цель: «' + q.value.trim() + '»'))) : null,
        current && !words.length ? el('button', { class: 'ps-item', onmousedown: done(() => pick(null)) },
          el('span', { class: 'ps-dot' }), el('span', { class: 'ps-t' }, el('span', { class: 'ps-n' }, 'Убрать «Зачем»'))) : null,
        ...shown.map((g) => el('button', { class: 'ps-item' + (g.x.id === current ? ' on' : ''), onmousedown: done(() => pick(g.x.id)) },
          el('span', { class: 'ps-dot' + (g.x.goal ? ' goal' : '') }), el('span', { class: 'ps-t' }, el('span', { class: 'ps-n' }, g.x.title),
            el('span', { class: 'ps-s' }, [g.path.split(' › ').slice(0, -1).join(' › '), g.x.rhythm ? 'практика · ' + g.x.rhythm : null].filter(Boolean).join(' · '))))),
        !words.length && toDir ? el('div', { class: 'pop-h' }, 'Или прямо на направление') : null,
        ...(!words.length && toDir ? S.krug.dirs.map((d) => el('button', { class: 'ps-item', onmousedown: done(() => toDir(d.key)) },
          el('span', { class: 'ps-dot dir' }), el('span', { class: 'ps-t' }, el('span', { class: 'ps-n' }, d.name)))) : [])]
        .filter(Boolean));
    };
    q.addEventListener('input', draw);
    q.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && q.value.trim()) {
        e.preventDefault();
        const hit = pool.find((g) => norm(g.x.title) === norm(q.value));
        closePop();
        if (hit) pick(hit.x.id); else if (make) make(q.value.trim());
      }
      if (e.key === 'Escape') { e.stopPropagation(); closePop(); }
    });
    draw();
    const pop = popAt(anchor, [el('div', { class: 'pop-h' }, title), q, box]);
    pop.classList.add('ps-pop');
    q.focus();
  }, 0);
}
/** «Зачем» дела: цель (кроме самого дела и того, что ниже него), новая цель словами или направление. */
function whyPop(anchor, t) {
  goalPop(anchor, { exclude: belowOf(t.id), current: t.why_id,
    pick: (id) => setFields([t.id], { why_id: id }, id ? 'Зачем: ' + S.tasks.get(id).title : 'Без «Зачем»'),
    make: (title) => setFields([t.id], { why_title: title }, 'Новая цель: ' + title),
    toDir: (k) => setFields([t.id], { why_id: null, directions: [k] }, 'Направление: ' + dirName(k)) });
}

// Виды (владелец 10.10.2026: «не надо этих всех странных видов. Просто надо говорить: на сегодня вот такие дела»;
// 13.10.2026: «В делах давай разделим: Сейчас (то, что отметил) и Сегодня (с датой сегодня)», порядок на главной —
// Сейчас → Новое → Сегодня). С 10.10 по 13.10 «Сегодня» было одним котлом — срок сегодня или прошёл, «Сейчас»
// телефона, «отбить» без срока, «мониторить» с напоминанием, — и в нём тонуло то, что владелец сам взял на день.
// Правило одно с сервером (store.NOW_IF, TODAY_IF, LATE_IF) и телефоном (этап 4 «Правки 5.0»):
// - «Сейчас» — только отмеченное владельцем (focus_on = сегодня), не больше пяти; это главная страница;
// - «Сегодня» — свои открытые дела со сроком ровно сегодня;
// - просроченное само ни туда, ни туда не попадает: оно во «Всех делах», группой «Просрочено» сверху.
// «Новое» — вопросы автоматики; «Все дела» — всё открытое, с завода уровнями «Зачем › группы › время»; свои
// сочетания — фильтрами на полке. «Предстоящее», «Жду» и «Неделя» ушли (10.10): их дела — во «Всех делах».
const NOW_MAX = 5;
const nowTasks = () => all().filter((t) => isOpen(t) && isMine(t) && isNow(t) && !t.goal);
/** На сегодня — как store.TODAY_IF: своё открытое дело со сроком ровно сегодня. Не сделал — завтра оно не здесь,
 *  а во «Всех делах», в «Просрочено». */
const inToday = (t) => isOpen(t) && isMine(t) && !t.goal && t.due_date === S.today;
const todayTasks = () => all().filter((t) => inToday(t) && inSphere(t));
/** Просрочено — как store.LATE_IF: своё открытое, срок прошёл. Живёт во «Всех делах», сверху. */
const lateTasks = () => deeds().filter((t) => isLate(t) && isMine(t) && inSphere(t));
/** «Ждут от тебя без срока» и «жду больше недели» (аудит 15.10.2026, №5) — как store.OWED_NODUE_IF и WAITING_STALE_IF:
 *  «Отбить» без срока и «Мониторить», где человек молчит дольше WAITING_STALE_DAYS, а напоминание не назначено или
 *  прошло. Сами ни в «Сейчас», ни в «Сегодня» не встают (сроков не выдумываем) — числами в шапке «Сейчас». */
const WAITING_STALE_DAYS = 7;
const owedNoDue = () => deeds().filter((t) => isOpen(t) && isMine(t) && inSphere(t) && grpOf(t) === 'owed' && !t.due_date);
const waitingStale = () => deeds().filter((t) => isOpen(t) && isMine(t) && inSphere(t) && t.ball === 'waiting'
  && t.waiting_since && D.diff(S.today, t.waiting_since) > WAITING_STALE_DAYS && (!t.nudge_on || t.nudge_on < S.today));
/** Когда дело делать — для уровня «время» и сортировки: срок; «Сейчас» — сегодня (его взяли на сегодня);
 *  «мониторить» — к сроку или дню напоминания, что раньше. «Отбить» без срока с 13.10.2026 — без срока: само оно
 *  больше не «сегодня». Сделанное и без срока — null. */
function whenDay(t) {
  if (!isOpen(t)) return null;
  const days = [t.due_date, isNow(t) ? S.today : null, t.ball === 'waiting' ? t.nudge_on : null].filter(Boolean).sort();
  return days[0] || null;
}
/** По времени (владелец: «внутри уже сортировка по времени»): когда делать, час, потом кто дольше ждёт. */
const byTime = (a, b) => (whenDay(a) || '9999').localeCompare(whenDay(b) || '9999') || (a.due_time || '99').localeCompare(b.due_time || '99')
  || (a.created_at || '').localeCompare(b.created_at || '') || a.num - b.num;
// hot — число акцентом (люди ждут); late — цветом беды. У «Всех дел» число — только просроченное: оно живёт там.
const VIEWS = {
  now: { title: 'Сейчас', icon: 'bolt', count: () => nowTasks().length },
  today: { title: 'Сегодня', icon: 'sun', count: () => todayTasks().length, hot: () => todayTasks().some((t) => t.owed) },
  new: { title: 'Новое', icon: 'inbox', count: () => newCount() },
  all: { title: 'Все дела', icon: 'list', count: () => lateTasks().length, late: () => lateTasks().length > 0 },
  // Круг жизни (12.10.2026): дерево дел от направлений — «где-то должно быть дерево дел, прямо от направлений».
  krug: { title: 'Круг жизни', icon: 'target', show: () => krugOn() },
  // Дерево жизни (13.10.2026): направления › цели › проекты и дела — плюсиками, колонками или списком с фильтрами.
  tree: { title: 'Дерево жизни', icon: 'tree', show: () => treeOn() },
};
// ── Уровни группировки и фильтры (владелец 10.10.2026 вечером): «нажимаю „Все дела“ — первый уровень
// группировки проекты, второй — под ними по категориям, третий — по времени… по каждому проекту посмотреть,
// что я могу сделать сегодня… и раздел слева „Фильтры“: сделал первый, второй, третий уровень — это
// сохраняется как фильтр». Уровней до трёх; внутри последнего — по времени (byTime). Фильтр — что показать
// (scope) и уровни; хранится у человека на сервере (users.settings.filters), чтобы телефон видел те же.
const LEVELS = { project: 'Зачем', grp: 'группы', date: 'время', person: 'люди', deal: 'сделки', dir: 'направления' };
const LEVELS_DEF = { today: ['grp'], all: ['project', 'grp', 'date'], project: ['grp', 'date'] };
const SCOPES = { today: 'Сегодня', all: 'Все дела', owed: 'Отбить', self: 'Запустить', check: 'Мониторить' };
// С завода — пока человек не сохранил своих: сегодня по проектам (его же пример), и кому отбить и кого
// мониторить — по людям: перед звонком человеку видно всё, что ему должен и что жду от него.
const FILTERS_DEF = [
  { id: 'f-today-proj', name: 'Сегодня по проектам', scope: 'today', levels: ['project', 'grp'] },
  { id: 'f-owed-people', name: 'Отбить — по людям', scope: 'owed', levels: ['person'] },
  { id: 'f-check-people', name: 'Мониторить — по людям', scope: 'check', levels: ['person'] },
];
const filters = () => { const f = (S.me.settings || {}).filters; return Array.isArray(f) ? f : FILTERS_DEF; };
async function saveFilters(list) {
  await op1({ op: 'user.settings', settings: { filters: list } });
  S.me.settings = { ...(S.me.settings || {}), filters: list };
}
/** Что показывает фильтр: сегодня, всё открытое или одна группа. */
const scopeItems = (scope) => (scope === 'today' ? todayTasks()
  : deeds().filter((t) => isOpen(t) && isMine(t) && inSphere(t) && (scope === 'all' || grpOf(t) === scope)));
const levelsOf = (key) => { const v = S.levels[key]; return Array.isArray(v) ? v.filter((x) => LEVELS[x]) : LEVELS_DEF[key] || ['grp']; };
const levelsName = (levels) => (levels.length ? levels.map((l) => LEVELS[l]).join(' › ') : 'без групп');
const pendingSugs = () => [...S.sugs.values()].filter((s) => s.status === 'pending' && s.for_user === S.me.user);
/** Закрытия и уточнения, которые сервер принял сам (store._auto: владелец 05.10 и 08.10.2026 — «спокойно
 *  закрывай и спокойно уточняй»), — за days дней, свежие сверху. Узнаются по причине решения: принятое руками
 *  предложение с payload.auto сюда не попадает. «Понятно» (seen_at) убирает их совсем: 06.10.2026 #268 висел
 *  неделю и «никуда не уходил». */
// «человек заведён сам» (dela_0019, 14.10.2026) — новый человек из Telegram или телефонной книжки: строкой с «Вернуть».
const AUTO_REASONS = ['закрыто само', 'уточнено само', 'человек заведён сам'];
const autoDone = (days) => [...S.sugs.values()].filter((s) => (s.kind === 'close' || s.kind === 'update' || s.kind === 'person') && s.status === 'accepted'
  && AUTO_REASONS.includes(s.reason) && !s.seen_at && s.for_user === S.me.user && s.decided_at && Date.now() - Date.parse(s.decided_at) < days * 86400e3)
  .sort((a, b) => b.decided_at.localeCompare(a.decided_at));
// Число у «Нового» — только вопросы: поставленное и сделанное само ответа не ждут (владелец 08.10.2026).
const newCount = () => pendingSugs().length;

// ── Значки: общий набор Ковчега (13.10.2026, вид 2) ─────────────────────
// Одна линия 1,75, скруглённые концы, 24×24, цвет — от текста. Пути — те же, что в общем слое остальных сервисов
// (он на компе владельца, объект I): Дела его не получают — у них своя копия в публичном репозитории.
// Узор на фоне рисуется из этих же путей — поэтому они слово в слово как там.
const ICONS = {
  home: 'M3 10.5L12 3l9 7.5M5 9.5V20h5v-6h4v6h5V9.5',
  dela: 'M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM8.5 12.5l2.5 2.5 5-5.5',
  mic: 'M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3',
  wallet: 'M19 7V5a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a2 2 0 0 1-2-2V6M16.5 14h.01',
  kids: 'M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM4 10h16M8 3v4M16 3v4',
  calendar: 'M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM4 10h16M8 3v4M16 3v4',
  book: 'M12 6.5C10 5 7 4.5 3 5v13c4-.5 7 0 9 1.5 2-1.5 5-2 9-1.5V5c-4-.5-7 0-9 1.5zM12 6.5V19',
  film: 'M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8 4v16M16 4v16M4 9h4M4 15h4M16 9h4M16 15h4',
  candles: 'M6.5 11h3v10h-3zM14.5 11h3v10h-3zM8 8.5c-1.2-1-1.2-2.4 0-4 1.2 1.6 1.2 3 0 4zM16 8.5c-1.2-1-1.2-2.4 0-4 1.2 1.6 1.2 3 0 4zM4 21h16',
  shield: 'M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z',
  digest: 'M5 4h14a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9l-5 4V5a1 1 0 0 1 1-1zM8 8.5h8M8 12h5',
  archive: 'M3 4h18v4H3zM5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8M10 12h4',
  copies: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
  cloud: 'M7 18h10a4 4 0 0 0 .6-7.95A6 6 0 0 0 6.2 9.1 4.5 4.5 0 0 0 7 18z',
  globe: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM3 12h18M12 3c2.5 2.6 3.7 5.6 3.7 9s-1.2 6.4-3.7 9M12 3c-2.5 2.6-3.7 5.6-3.7 9s1.2 6.4 3.7 9',
  monitor: 'M4 4h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8 20h8M12 16v4',
  sun: 'M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon: 'M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z',
  logout: 'M14 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 8l-4 4 4 4M6 12h10',
  search: 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4',
  inbox: 'M4 13l2.5-7.5h11L20 13v6H4zM4 13h5l1.5 2.5h3L15 13h5',
  list: 'M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01',
  target: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 11.5v1',
  bookmark: 'M6 3h12v18l-6-4-6 4z',
  funnel: 'M4 5h16l-6 7.5V19l-4 2v-8.5z',
  briefcase: 'M4 8h16v11H4zM9 8V5h6v3M4 13h16',
  users: 'M9 4a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7zM2.5 20a6.5 6.5 0 0 1 13 0M15.5 4.2a3.5 3.5 0 0 1 0 6.6M18 14.2a6.5 6.5 0 0 1 3.5 5.8',
  link: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
  user: 'M12 3.5a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM4.5 20.5a7.5 7.5 0 0 1 15 0',
  reply: 'M9 14L4 9l5-5M4 9h10a6 6 0 0 1 6 6v5',
  play: 'M7 4.5l12 7.5-12 7.5z',
  eye: 'M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z',
  plus: 'M12 5v14M5 12h14',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  x: 'M6 6l12 12M18 6L6 18',
  edit: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
  trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3',
  refresh: 'M20 11a8 8 0 0 0-14.6-4.5L4 8M4 4v4h4M4 13a8 8 0 0 0 14.6 4.5L20 16M20 20v-4h-4',
  settings: 'M4 7h10M18 7h2M4 17h4M12 17h8M16 5v4M10 15v4',
  clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2',
  alert: 'M12 4l9 16H3zM12 10v4M12 17h.01',
  info: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v6M12 7.5h.01',
  chart: 'M4 20V4M4 20h16M8 16v-5M12 16V8M16 16v-3',
  send: 'M4 12l16-8-6 16-3-7z',
  folder: 'M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z',
  arrowLeft: 'M19 12H5M11 6l-6 6 6 6',
  chevron: 'M9 6l6 6-6 6',
  coin: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM10 7.5h3.2a2.3 2.3 0 0 1 0 4.6H10M10 7.5v9M8.5 14.3h4.5',
  // Траты Claude в долларах (14.10.2026, «Claude: траты»): у Ковчега только рубль (coin). Тот же путь — в GlyphsKv телефона.
  usd: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM14.6 9.2c-.4-1-1.4-1.7-2.6-1.7c-1.6 0-2.7.9-2.7 2.1c0 2.9 5.6 1.7 5.6 4.6c0 1.3-1.2 2.2-2.9 2.2c-1.3 0-2.4-.7-2.8-1.7M12 6v1.5M12 16.4V18',
  car: 'M4 16v-4.5l2.2-5a1.5 1.5 0 0 1 1.4-1h8.8a1.5 1.5 0 0 1 1.4 1l2.2 5V16M3 16h18v2.5H3zM6.5 18.5V20M17.5 18.5V20M4 11.5h16M7 14h.01M17 14h.01',
  chevronDown: 'M6 9l6 6 6-6',
  copy: 'M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1zM5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1',
  download: 'M12 4v11M7 10l5 5 5-5M5 20h14',
  upload: 'M12 20V9M7 14l5-5 5 5M5 4h14',
  flow: 'M4 8h15M15 4l4 4-4 4M20 16H5M9 12l-4 4 4 4',
  headphones: 'M4 15v-3a8 8 0 0 1 16 0v3M4 15h3v5H5a1 1 0 0 1-1-1zM20 15h-3v5h2a1 1 0 0 0 1-1z',
  magen: 'M12 3.5l7.4 12.75H4.6zM12 20.5L4.6 7.75h14.8z',
  minus: 'M5 12h14',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  phone: 'M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2',
  claude: 'M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6L5.6 18.4',   // звёздочка — ссылка на приложение Claude в меню Ковчега
  // «Спорт» и «Еда» на компе (13.10.2026) — для шапки сервисов (DELA_NAV): тот же набор, что в общем слое Ковчега.
  activity: 'M3 12h4l3-8 4 16 3-8h4',
  food: 'M12 5a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM3 3v5a1.5 1.5 0 0 0 1.5 1.5h0A1.5 1.5 0 0 0 6 8V3M4.5 9.5V21M20.5 3c-1.4 1-2 2.6-2 4.5V12h2V21',
  print: 'M7 9V4h10v5M7 17H5a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-2M7 14h10v6H7z',
  server: 'M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01',
  smile: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM8.5 14.5a4.5 4.5 0 0 0 7 0M9 9.5h.01M15 9.5h.01',
  // Свои у Дел — тем же штрихом: «отдать Claude», «стоп» записи, раскрыть, меню, вопрос, разговор, огонь серии,
  // дерево жизни, молния «Сейчас» (13.10.2026 — та же молния, что у телефона).
  up: 'M12 19V5.5M6 11l6-6 6 6',
  stop: 'M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z',
  down: 'M7 10l5 5 5-5',
  menu: 'M4 7h16M4 12h16M4 17h16',
  help: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17h.01',
  chat: 'M5 5h14v10H10l-5 4z',
  flame: 'M12 3c1 3 4 4.5 4 8.5a4 4 0 0 1-8 0c0-2 1-3 2-4 0 1.5 1 2.5 2 2.5 0-3-1-5 0-7z',
  tree: 'M10 3h4v4h-4zM12 7v4M6 11h12M6 11v3M18 11v3M4 14h4v4H4zM16 14h4v4h-4z',
  bolt: 'M13 3L5 13.5h6.5L10.5 21 19 10.5h-6.5z',
  // Замок «только тебе» (15.10.2026, аудит №8–10): приватный комментарий, запись хронологии, пометки о человеке.
  lock: 'M6 11h12v9H6zM8.5 11V8a3.5 3.5 0 0 1 7 0v3',
};
// Шестерёнка — восемь зубцов, считается один раз (как в общем слое): «солнце» рядом слишком на неё похоже.
ICONS.gear = (() => {
  let d = '';
  const rad = Math.PI / 180;
  for (let i = 0; i < 8; i++) {
    const a = i * 45;
    [[a - 15, 7], [a - 8, 9.6], [a + 8, 9.6], [a + 15, 7]].forEach(([deg, r], j) => {
      d += (i === 0 && j === 0 ? 'M' : 'L') + (12 + r * Math.cos(deg * rad)).toFixed(2) + ' ' + (12 + r * Math.sin(deg * rad)).toFixed(2);
    });
  }
  return d + 'zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z';
})();
// Старые имена из вида Правки 4.0 — на новые значки: зовущий код их не меняет.
const ICON_ALIAS = { 'inbox-in': 'inbox', tray: 'inbox', person: 'user', building: 'briefcase', case: 'briefcase', meet: 'users',
  pen: 'edit', donut: 'chart', back: 'arrowLeft', chev: 'chevron', tick: 'check', filter: 'bookmark', circle: 'target',
  cal: 'calendar', date: 'calendar' };
function icon(name, size = 16) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('class', 'kv-i');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.75');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS(ns, 'path');
  p.setAttribute('d', ICONS[name] || ICONS[ICON_ALIAS[name]] || ICONS.list);
  svg.append(p);
  return svg;
}

// ── Вид: тема, шрифт, размер текста, узор (общий вид Ковчега) ───────────
// Выбор человека — общий на все сервисы семьи: кука на родительский домен (его называет сервер в /api/me — в
// публичном коде доменов нет) и запасом localStorage (адрес напрямую через роутер, локальная копия). Значение по
// умолчанию не храним — кука пустая. Тема без выбора — как в системе. Всё встаёт до первой отрисовки: app.js с defer —
// до DOMContentLoaded. Списки и правила — как в общем слое остальных сервисов: выбрал в Доме — так же в Делах.
const HTML = document.documentElement;
const KV = {
  cookie(name) { const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)')); return m ? decodeURIComponent(m[1]) : ''; },
  /** Домен общей куки: только если страница и правда под ним (иначе браузер куку молча не поставит). */
  domain() {
    const d = (S.me && S.me.look && S.me.look.domain) || '', h = location.hostname;
    return d && (h === d || h.endsWith('.' + d)) ? d : '';
  },
  setCookie(name, value) {
    const d = KV.domain();
    document.cookie = name + '=' + encodeURIComponent(value) + '; path=/; max-age=' + (value ? 31536000 : 0) + '; SameSite=Lax'
      + (d ? '; domain=' + d : '') + (location.protocol === 'https:' ? '; Secure' : '');
  },
  pref(name) { let v = KV.cookie(name); if (!v) { try { v = localStorage.getItem(name) || ''; } catch (e) { v = ''; } } return v; },
  store(name, value) {
    KV.setCookie(name, value);
    try { if (value) localStorage.setItem(name, value); else localStorage.removeItem(name); } catch (e) { /* хватит куки */ }
  },
  chosen() { const t = KV.pref('kv-theme'); return t === 'light' || t === 'dark' ? t : ''; },
  dark: window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null,
  theme() { return HTML.getAttribute('data-theme') || (KV.dark && KV.dark.matches ? 'dark' : 'light'); },
  /** t: 'light' | 'dark' | '' — пусто значит «как в системе». */
  setTheme(t) {
    KV.store('kv-theme', t);
    if (t) HTML.setAttribute('data-theme', t); else HTML.removeAttribute('data-theme');
    paintLook();
  },
  setPattern(on) { KV.store('kv-pattern', on ? '' : 'off'); paintLook(); },
  /** Остальной выбор вида: KV.set('font' | 'size' | 'pcolor' | 'pdens' | 'theme', id). */
  set(what, v) {
    if (what === 'theme') { KV.setTheme(v); return; }
    const o = OPTS[what];
    if (!o) return;
    KV.store(o[0], v === o[2] ? '' : v);
    if (what === 'font') applyFont();
    if (what === 'size') applySize();
    paintLook();
  },
};
if (KV.chosen()) HTML.setAttribute('data-theme', KV.chosen());
// Внутри приложения Правки (14.10.2026): телефон открывает страницы сервисов у себя (WebView) под своей шапкой
// Ковчега — значок с меню, название, тема; веб Дел туда попадает ссылкой с другой страницы (плитка «Дома»). Своя
// шапка здесь была бы второй такой же строкой. WebView Правки дописывает в User-Agent метку PravkaApp — по ней
// <html data-kv-app>, и style.css прячет шапку (разделы Дел — боковиной, она остаётся). В браузере метки нет.
if (/(^|[\s;(])PravkaApp\b/.test(navigator.userAgent || '')) HTML.setAttribute('data-kv-app', 'pravka');

// Шрифты: [id, подпись, семейство Google Fonts, запасные]. Golos — свой файл (style.css); остальные — с Google Fonts
// (CSP Дел пускает только их стили и шрифты), и только выбранный.
const FONTS = [
  ['golos', 'Golos', 'Golos+Text:wght@400;500;600', 'sans'],
  ['onest', 'Onest', 'Onest:wght@400;500;600', 'sans'],
  ['manrope', 'Manrope', 'Manrope:wght@400;500;600', 'sans'],
  ['plex', 'IBM Plex', 'IBM+Plex+Sans:wght@400;500;600', 'sans'],
  ['nunito', 'Nunito', 'Nunito:wght@400;500;600', 'sans'],
  ['ptsans', 'PT Sans', 'PT+Sans:wght@400;700', 'sans'],
  ['literata', 'Literata', 'Literata:opsz,wght@7..72,400;7..72,500;7..72,600', 'serif'],
  ['ptserif', 'PT Serif', 'PT+Serif:wght@400;700', 'serif'],
  ['lora', 'Lora', 'Lora:wght@400;500;600', 'serif'],
];
// Размер текста — масштабом всей страницы (zoom у body): размеры в px, и только так растёт всё вместе.
const SIZES = [['s', 'Мельче', 0.92, 12], ['m', 'Обычный', 1, 14], ['l', 'Крупнее', 1.08, 16], ['xl', 'Ещё крупнее', 1.16, 18]];
// Цвет узора: [id, название, светлая, тёмная, прозрачность в светлой, в тёмной]. Цветной чуть плотнее серого, иначе на
// тёплом фоне его не видно вовсе.
const PCOLORS = [
  ['orange', 'Оранжевый', '#d9692c', '#f0a06a', 0.17, 0.16],
  ['gray', 'Серый', '#1c1b19', '#eceae5', 0.08, 0.07],
  ['blue', 'Синий', '#2747c9', '#93abf8', 0.13, 0.15],
  ['green', 'Зелёный', '#2f7d4f', '#7cc59a', 0.15, 0.15],
  ['lilac', 'Сиреневый', '#7a5cc7', '#b9a3f5', 0.15, 0.15],
  ['pink', 'Розовый', '#c2477a', '#e58cbc', 0.14, 0.15],
];
const DENS = [['rare', 'Реже', 160], ['mid', 'Обычно', 120], ['dense', 'Гуще', 96]]; // шаг сетки, плитка 960
const OPTS = { // что: [кука, список, значение по умолчанию]
  font: ['kv-font', FONTS, 'golos'], size: ['kv-size', SIZES, 'm'],
  pcolor: ['kv-pcolor', PCOLORS, 'orange'], pdens: ['kv-pdens', DENS, 'mid'],
};
function opt(what) {
  const [name, list, def] = OPTS[what], v = KV.pref(name) || def;
  return list.find((x) => x[0] === v) || list.find((x) => x[0] === def);
}
const fontStack = (f) => '"' + f[2].split(':')[0].replace(/\+/g, ' ') + '", '
  + (f[3] === 'serif' ? 'Georgia, "Times New Roman", serif' : '"Segoe UI Variable Text", "Segoe UI", system-ui, -apple-system, Roboto, sans-serif');
function loadCss(href, id) {
  if (document.getElementById(id) || !document.head) return;
  document.head.append(el('link', { id, rel: 'stylesheet', href }));
}
function applyFont() {
  const f = opt('font');
  if (f[0] === 'golos') { HTML.style.removeProperty('--font'); return; }
  loadCss('https://fonts.googleapis.com/css2?family=' + f[2] + '&display=swap', 'kv-font-' + f[0]);
  HTML.style.setProperty('--font', fontStack(f));
}
function applySize() {
  const z = opt('size')[2];
  if (z === 1) HTML.style.removeProperty('--kv-zoom'); else HTML.style.setProperty('--kv-zoom', String(z));
}
/** Масштаб «Размера текста» у элемента: экранные px на px раскладки. С zoom у body Chrome 128+ отдаёт
 *  getBoundingClientRect и clientX/Y в экранных px, а style.left/top и offset* — в раскладочных: координаты мыши и
 *  меню надо делить на него (как zk в Детях). Без zoom и в старых браузерах — 1. */
const zk = (node) => (node && node.offsetHeight ? node.getBoundingClientRect().height / node.offsetHeight : 1) || 1;

// Узор на фоне: значки Дел вразброс — под разными углами, разного размера, еле видные. Плитка 960×960 (SVG в data:,
// CSP пускает img-src data:) ложится на фон <html>; панели белые, узор живёт в полях. Раскладка — от имени сервиса и
// плотности (тот же генератор, что в общем слое): одинаковая при каждой загрузке и такая же, как у Дел там.
// В шестерёнке — выключить (kv-pattern=off), плотность и цвет: куки общие с остальными сервисами.
const PATTERN = ['dela', 'clock', 'briefcase', 'users', 'link', 'inbox', 'target', 'bookmark', 'check', 'list', 'reply', 'eye'];
const patternCache = {};
function seeded(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return () => { // mulberry32
    h = h + 0x6D2B79F5 | 0;
    let t = Math.imul(h ^ h >>> 15, 1 | h);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}
function patternUrl(theme, col, den) {
  const key = [theme, col[0], den[0]].join('|');
  if (patternCache[key]) return patternCache[key];
  const TILE = 960, cell = den[2], big = Math.round(cell * 0.42), rnd = seeded('dela' + den[0]), g = [];
  for (let y = 0; y < TILE; y += cell) {
    for (let x = 0; x < TILE; x += cell) {
      if (rnd() < 0.18) continue; // пустые клетки — чтобы не было сетки
      const size = 16 + Math.round(Math.pow(rnd(), 1.4) * (big - 16)), s = size / 24, room = cell - size - 16; // чаще мелкие
      const px = x + 8 + rnd() * room, py = y + 8 + rnd() * room, rot = Math.round(rnd() * 64 - 32);
      const name = PATTERN[Math.floor(rnd() * PATTERN.length)];
      g.push(`<g transform="translate(${px.toFixed(1)} ${py.toFixed(1)}) scale(${s.toFixed(3)}) rotate(${rot} 12 12)" stroke-width="${(1.5 / s).toFixed(2)}"><path d="${ICONS[name]}"/></g>`);
    }
  }
  const dk = theme === 'dark';
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${TILE}" height="${TILE}" fill="none" stroke="${dk ? col[3] : col[2]}"`
    + ` stroke-opacity="${dk ? col[5] : col[4]}" stroke-linecap="round" stroke-linejoin="round">${g.join('')}</svg>`;
  return (patternCache[key] = 'url("data:image/svg+xml,' + encodeURIComponent(svg) + '")');
}
// Вид из Свода (ui.look, Правка 5.0, этап 7): токены обеих тем поверх style.css — сервер отдаёт их всем в /api/me
// (look.tokens; без записи — заводской акцент v2). Кладём свойствами на <html> через CSSOM: атрибут style и свой
// <style> CSP Дел не пускает, а свойства, поставленные скриптом, — можно. Они сильнее тем из style.css, поэтому кладём
// токены ТЕКУЩЕЙ темы и перекладываем при каждой её смене (paintLook). До ответа сервера — последние известные из
// localStorage: иначе каждая загрузка мигала бы заводским синим.
const TOKEN_NAMES = new Set(['bg', 'side', 'panel', 'panel-2', 'line', 'line-soft', 'text', 'muted', 'faint', 'icon', 'check',
  'ok', 'warn', 'bad', 'info', 'claude', 'accent', 'accent-ink', 'accent-soft', 'bar-fill', 'sp0', 'sp1', 'sp2', 'sp3', 'sp4',
  'sp5', 'sp6', 'sp7']);
let tokensOn = [];
function lookTokens() { return (S.me && S.me.look && S.me.look.tokens) || LS.get('kvTokens', null) || {}; }
function applyTokens(theme) {
  for (const n of tokensOn) HTML.style.removeProperty('--' + n);
  tokensOn = [];
  const m = lookTokens()[theme] || {};
  for (const [n, v] of Object.entries(m)) {
    if (!TOKEN_NAMES.has(n) || !/^#[0-9a-f]{6}$/i.test(String(v))) continue;   // чужое — мимо, без единого исключения
    HTML.style.setProperty('--' + n, v);
    tokensOn.push(n);
  }
}
/** Ответ /api/me пришёл — запомнить токены на следующую загрузку и перекрасить. */
function rememberLook() {
  const t = S.me && S.me.look && S.me.look.tokens;
  if (t) LS.set('kvTokens', t);
  paintLook();
}
/** Переключатель темы, меню шестерёнки, узор, токены вида из Свода и цвет строки браузера — по текущему выбору. */
function paintLook() {
  const t = KV.theme(), cur = { theme: KV.chosen() };
  applyTokens(t);
  for (const w of Object.keys(OPTS)) cur[w] = opt(w)[0];
  for (const b of document.querySelectorAll('.kv-theme button[data-t]')) {
    b.classList.toggle('on', b.dataset.t === t);
    b.setAttribute('aria-pressed', b.dataset.t === t ? 'true' : 'false');
  }
  for (const b of document.querySelectorAll('.kv-menu [data-kv-set]')) {
    const on = cur[b.dataset.kvSet] === b.dataset.v;
    b.classList.toggle('on', on);
    b.setAttribute('aria-checked', on ? 'true' : 'false');
  }
  const patOn = KV.pref('kv-pattern') !== 'off';
  for (const c of document.querySelectorAll('.kv-menu input[data-kv-pref=pattern]')) c.checked = patOn;
  for (const p of document.querySelectorAll('.kv-pat')) p.classList.toggle('off', !patOn);
  if (!patOn) HTML.style.removeProperty('--kv-pattern');
  else HTML.style.setProperty('--kv-pattern', patternUrl(t, opt('pcolor'), opt('pdens')));
  const meta = document.querySelector('meta[name=theme-color]');
  if (meta) meta.setAttribute('content', getComputedStyle(HTML).backgroundColor);
}
if (KV.dark && KV.dark.addEventListener) KV.dark.addEventListener('change', paintLook);
applyFont();
applySize();
paintLook();

// ── Помощники ───────────────────────────────────────────────────────────
function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else if (k === 'value') e.value = v;
    // Стиль — через CSSOM: атрибут style политика страницы (CSP без unsafe-inline) молча отбрасывает.
    else if (k === 'style') for (const part of String(v).split(';')) { const i = part.indexOf(':'); if (i > 0) e.style.setProperty(part.slice(0, i).trim(), part.slice(i + 1).trim()); }
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

const initials = (name) => (name || '?').split(/\s+/).map((w) => (w.match(/[\p{L}\p{N}]/u) || [''])[0]).filter(Boolean).slice(0, 2).join('').toUpperCase() || '?';

// ── Цвета (общий вид Ковчега, 13.10.2026): один акцент на всё; у проекта и человека своего цвета больше нет —
// проект узнаётся значком (клиент — портфель, остальное — папка), человек — инициалами в квадрате. ──
/** Цвет — переменной --c (CSSOM, CSP не мешает): значок источника, «на заметку» у вопроса. */
const tint = (node, c) => { node.classList.add('tinted'); node.style.setProperty('--c', c); return node; };
const dot = (id) => { const svg = icon(project(id)?.kind === 'client' ? 'briefcase' : 'folder', 16); svg.classList.add('pdot'); return svg; };
function avatar(p, cls) {
  const name = (p && (p.name || p.short)) || '?';
  return el('span', { class: 'ava' + (cls ? ' ' + cls : ''), title: name, 'aria-hidden': 'true' }, initials(name));
}

// ── Маршрут ─────────────────────────────────────────────────────────────
function route() {
  const h = location.hash.replace(/^#\/?/, '');
  const [kind, id] = h.split('/');
  if (kind === 'p' && id) return { kind: 'project', id };
  if (kind === 'h' && id) return { kind: 'person', id };
  if (kind === 'd' && id) return { kind: 'deal', id }; // сделка — страницей, как клиент и человек (06.10.2026)
  if (kind === 'm' && id) return { kind: 'meeting', id }; // встреча — страницей (12.10.2026)
  if (kind === 'f' && id) return { kind: 'filter', id: decodeURIComponent(id) }; // сохранённый фильтр (10.10.2026)
  if (kind === 'search') return { kind: 'search', q: decodeURIComponent(id || '') };
  if (kind === 'new' && id) return { kind: 'new', batch: decodeURIComponent(id) }; // ссылка из Telegram — одна пачка
  if (kind === 'task' && /^\d+$/.test(id || '')) return { kind: 'now', task: +id }; // кнопка «Открыть» под напоминанием
  // Страницы-двойники телефона (twins.js, 14.10.2026): #/day, #/zasechka, #/pravka — только владельцу.
  const twin = twinsRoute(kind);
  if (twin) return twin;
  if (kind === 'phone') return { kind: 'phone', id: decodeURIComponent(id || '') }; // настройки телефона (14.10.2026, phone.js)
  if (kind === 'spend') return { kind: 'spend' }; // «Claude: траты» (14.10.2026, spend.js) — только владельцу
  // Дайджест Telegram (14.10.2026, digest.js): список и поиск, одна сводка — по ref (ссылка из хронологии CRM).
  if (kind === 'digest') return id ? { kind: 'digest', id: decodeURIComponent(id) } : { kind: 'digests' };
  if (kind === 'settings') return { kind: 'settings' };
  if (kind === 'stats') return { kind: 'stats' };
  if (kind === 'inbox') return { kind: 'new' }; // старые закладки: «Входящие» теперь в «Новом»
  if (kind === 'week') return { kind: 'all' }; // 10.10.2026: «Неделя», «Предстоящее», «Жду» — во «Всех делах»
  // «все встречи проекта / клиента →» из карточки (аудит №3): #/meetings/deal=<id>, #/meetings/client=<id>.
  if (kind === 'meetings' && id) {
    const m = /^(deal|client)=([\w-]+)$/.exec(decodeURIComponent(id));
    if (m) return { kind, [m[1]]: m[2] };
  }
  if (CRM_VIEWS[kind]) return { kind };
  // Главная — «Сейчас» (13.10.2026: Сейчас → Новое → Сегодня); незнакомый адрес — туда же.
  return { kind: VIEWS[kind] ? kind : 'now' };
}
const go = (hash) => { location.hash = hash; };
window.addEventListener('hashchange', () => { S.sel.clear(); S.dealFilter = null; S.pick.project = null; S.sideOpen = false; S.toTop = true; S.secOn = null; S.tlAll = null; render(); });

// ── Вход ────────────────────────────────────────────────────────────────
function renderLogin(msg) {
  S.top = null;
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
  rememberLook();
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
  if (r.kind === 'twin' && twinsRender(r)) return; // страница-двойник телефона — свой каркас (twins.js)
  if (r.task) { // #task/61: карточка этого дела поверх главной («Сейчас»), адрес — обычный
    const t = all().find((x) => x.num === r.task);
    history.replaceState(null, '', '#/now');
    if (t) { openCard(t.id); return; }
  }
  const ae = document.activeElement;
  const q0 = ae && ['quick', 'side-search', 'main-search'].includes(ae.id) ? ae : null;
  const focused = q0 ? { id: q0.id, value: q0.value, a: q0.selectionStart, b: q0.selectionEnd } : null;
  // Карточка спрятана язычком, а открыли другое дело (или новое) — она выезжает с ним: щелчок по делу и значит «покажи».
  const cardKey = S.cardId || S.draft || null;
  if (cardKey && cardKey !== S.lastCard && S.cardHidden) { S.cardHidden = false; LS.set('cardHidden', false); }
  S.lastCard = cardKey;
  const shell = el('div', { class: 'shell' + (cardKey ? ' with-card' : '') + (cardKey && S.cardHidden ? ' card-hidden' : '')
    + (S.sideOpen ? ' side-open' : '') + (S.sideHidden ? ' side-hidden' : '') + (S.sel.size ? ' selecting' : '') });
  shell.append(renderSide(r), listColumn(r), sideGrip());
  if (cardKey) shell.append(renderCard(), cardGrip());
  shell.style.setProperty('--card-w', cardPct() + '%');
  const sw = sideW();
  if (sw) { shell.style.setProperty('--side-w', sw + 'px'); shell.style.setProperty('--side-w-c', sw + 'px'); }
  const scroll = S.toTop ? 0 : document.querySelector('main.list > .scroll')?.scrollTop || 0; // новая страница — с начала
  S.toTop = false;
  const old = document.querySelector('section.card-pane');
  const cardScroll = old ? [old.dataset.key, old.scrollTop] : null;
  const sideScroll = document.querySelector('aside.side .side-scroll')?.scrollTop || 0;
  const pillScroll = document.querySelector('.pills')?.scrollLeft;
  // Шапка Ковчега строится один раз: перерисовка страницы не закрывает меню шестерёнки и не плодит его слушателей.
  if (!S.top) S.top = topBar();
  $app.replaceChildren(S.top, shell);
  paintLook();
  shell.querySelector('aside.side .side-scroll').scrollTop = sideScroll;
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

/** Язычок боковины (13.10.2026, владелец: «Делу нужна боковина, которая вылезает и скрывается… Нужна кнопка у края
 *  со стрелкой, которая её вытаскивает, и такая же кнопка, которая убирает») — как в приложении Правка: на широком
 *  прячет колонку разделов и отдаёт её место списку и карточке (выбор помнит браузер), на узком вытаскивает шторку
 *  поверх (тап мимо, Esc и выбор раздела — убирают). › — показать, ‹ — спрятать. Жеста от края нет: он спорит с
 *  «назад» браузера и телефона. */
const NARROW = window.matchMedia('(max-width: 900px)');
NARROW.addEventListener('change', () => { S.sideOpen = false; render(); });
function sideGrip() {
  const open = NARROW.matches ? S.sideOpen : !S.sideHidden;
  const btn = el('button', { class: 'dgrip', type: 'button', onclick: () => {
    if (Date.now() - (btn.dragged || 0) < 400) return; // щелчок сразу после протяжки — это была протяжка
    // Класс — на уже нарисованном каркасе, без перерисовки: тогда ширина колонки (и шторка) уезжает плавно, а не
    // рывком — render() строит каркас заново, и переходу CSS не от чего было бы идти. Следующая перерисовка
    // возьмёт то же состояние из S.
    const shell = btn.closest('.shell');
    let now;
    if (NARROW.matches) {
      now = S.sideOpen = !S.sideOpen;
      shell?.classList.toggle('side-open', now);
      if (now) $app.append(el('div', { class: 'scrim', onclick: () => { S.sideOpen = false; render(); } }));
      else $app.querySelector(':scope > .scrim')?.remove();
    } else {
      S.sideHidden = !S.sideHidden;
      LS.set('sideHidden', S.sideHidden);
      now = !S.sideHidden;
      shell?.classList.toggle('side-hidden', !now);
    }
    paint(now);
  } }, icon('chevron', 15));
  const paint = (on) => {
    const label = on ? 'Спрятать разделы' : 'Показать разделы';
    btn.classList.toggle('in', on);
    btn.title = label + (NARROW.matches ? '' : ' · потяни — шире или уже');
    btn.setAttribute('aria-label', label);
    btn.setAttribute('aria-expanded', on ? 'true' : 'false');
  };
  paint(open);
  gripDrag(btn, 'side', paint);
  return btn;
}

/** Ширина боковины, которую дотянули (px страницы); не тянули — 0: ширину даёт css (264, рядом с карточкой — 232). */
const SIDE_MIN = 180;
const sideW = () => { const w = +LS.get('sideW', 0) || 0; return w >= SIDE_MIN ? Math.round(w) : 0; };
/** Списку — не меньше 40 % ширины (как у приложения, core/DelaColumns.kt): боковине и карточке вместе — 60 %. */
const MID_SHARE = 0.4;

/** Язычок карточки справа (14.10.2026, владелец: «И то же самое с правой колонкой, где само дело… справа и слева
 *  должен иметь возможность эти колонки убирать. Механика одинаковая»): щелчок — спрятать карточку к правому краю
 *  или показать снова, протяжка — ширина (бывшая ручка на её краю). Щелчок по другому делу показывает её сам. */
function cardGrip() {
  const btn = el('button', { class: 'cgrip', type: 'button', onclick: () => {
    if (Date.now() - (btn.dragged || 0) < 400) return; // щелчок сразу после протяжки — это была протяжка
    S.cardHidden = !S.cardHidden;
    LS.set('cardHidden', S.cardHidden);
    btn.closest('.shell')?.classList.toggle('card-hidden', S.cardHidden);
    paint(!S.cardHidden);
  } }, icon('chevron', 15));
  const paint = (on) => {
    const label = on ? 'Спрятать карточку' : 'Показать карточку';
    btn.classList.toggle('in', on);
    btn.title = label + ' · потяни — шире или уже';
    btn.setAttribute('aria-label', label);
    btn.setAttribute('aria-expanded', on ? 'true' : 'false');
  };
  paint(!S.cardHidden);
  gripDrag(btn, 'card', paint);
  return btn;
}

/** Протяжка язычка мышью или пальцем — одна у обеих колонок (14.10.2026; владелец: «если я держу эту кнопку, она
 *  двигает ширину столбца»), счёт — как у приложения (core/DelaColumns.kt). Сдвиг больше 4 px — тянешь ширину, и
 *  щелчка после этого нет; отпустил у самого края (меньше половины наименьшей ширины) — колонка уходит в док, а её
 *  ширина остаётся прежней; тянул спрятанную — она выезжает. Сосед уступает место до своего минимума: карточка —
 *  на время (это делает сам css: --cw не шире, чем оставляют боковина и доля списка), боковина — насовсем (так и
 *  остаётся уже, какой её видели). Боковина помнит ширину в px, карточка — в процентах окна. Px мыши — экранные:
 *  при «Размере текста» (zoom) страница считает в своих, поэтому сдвиг пересчитывается долей ширины каркаса.
 *  Стрелки с клавиатуры — тот же шаг без мыши. На узком экране протяжки нет: там шторка и лист. */
function gripDrag(btn, edge, paint) {
  const isSide = edge === 'side';
  const hidden = () => (isSide ? S.sideHidden : S.cardHidden);
  const setHidden = (h, shell) => {
    if (isSide) { S.sideHidden = h; LS.set('sideHidden', h); shell.classList.toggle('side-hidden', h); }
    else { S.cardHidden = h; LS.set('cardHidden', h); shell.classList.toggle('card-hidden', h); }
    paint(!h);
  };
  const scale = (shell) => shell.offsetWidth / shell.getBoundingClientRect().width || 1;
  const widthOf = (shell, sel) => { const x = shell.querySelector(sel); return x ? x.getBoundingClientRect().width * scale(shell) : 0; };
  const cardOn = (shell) => shell.classList.contains('with-card') && !S.cardHidden;
  // Пределы: соседу — его минимум, списку — его доля (40 %).
  const limits = (shell, W) => {
    const room = W * (1 - MID_SHARE), cmin = W * CARD_MIN / 100;
    return isSide
      ? { min: SIDE_MIN, max: Math.max(SIDE_MIN, room - (cardOn(shell) ? cmin : 0)) }
      : { min: cmin, max: Math.min(W * CARD_MAX / 100, Math.max(cmin, room - (S.sideHidden ? 0 : SIDE_MIN))) };
  };
  const putSide = (shell, w) => { shell.style.setProperty('--side-w', w + 'px'); shell.style.setProperty('--side-w-c', w + 'px'); };
  const put = (shell, w, W) => (isSide ? putSide(shell, w) : shell.style.setProperty('--card-w', (w / W * 100) + '%'));
  const restoreSide = (shell) => {
    const sw = sideW();
    if (sw) putSide(shell, sw);
    else { shell.style.removeProperty('--side-w'); shell.style.removeProperty('--side-w-c'); }
  };
  const restore = (shell) => (isSide ? restoreSide(shell) : shell.style.setProperty('--card-w', cardPct() + '%'));
  // Карточку тянут шире, чем оставляет боковина, — боковина уже (до своего минимума); null — уступать не надо.
  const squeeze = (st, card) => {
    if (isSide || S.sideHidden) return null;
    const room = st.W * (1 - MID_SHARE);
    return st.side0 + Math.max(card, st.W * CARD_MIN / 100) > room ? Math.max(SIDE_MIN, room - Math.max(card, st.W * CARD_MIN / 100)) : null;
  };
  let st = null;
  btn.addEventListener('pointerdown', (e) => {
    if (NARROW.matches || e.button !== 0) return;
    const shell = btn.closest('.shell');
    if (!shell) return;
    const w0 = hidden() ? 0 : widthOf(shell, isSide ? 'aside.side' : 'section.card-pane');
    st = { shell, k: scale(shell), W: shell.offsetWidth, x: e.clientX, w0, w: w0, moved: false, was: hidden(),
      side0: S.sideHidden ? 0 : widthOf(shell, 'aside.side') };
    try { btn.setPointerCapture(e.pointerId); } catch (err) { /* без захвата тоже тянется, пока мышь над язычком */ }
  });
  btn.addEventListener('pointermove', (e) => {
    if (!st) return;
    const dx = (e.clientX - st.x) * st.k * (isSide ? 1 : -1);
    if (!st.moved) {
      if (Math.abs(dx) < 4) return;
      st.moved = true;
      st.shell.classList.add('resizing');
      if (st.was) { put(st.shell, 0, st.W); st.shell.classList.remove(isSide ? 'side-hidden' : 'card-hidden'); }
    }
    st.w = Math.min(limits(st.shell, st.W).max, Math.max(0, st.w0 + dx));
    put(st.shell, st.w, st.W);
    const sq = squeeze(st, st.w);
    if (sq != null) putSide(st.shell, sq); else if (!isSide) restoreSide(st.shell);
  });
  const end = () => {
    if (!st) return;
    const cur = st;
    st = null;
    if (!cur.moved) return;
    btn.dragged = Date.now(); // щелчок, который браузер пришлёт следом за отпусканием, — не в счёт
    const { shell, W, w, was } = cur;
    shell.classList.remove('resizing');
    const { min, max } = limits(shell, W);
    if (w < min / 2) {
      restore(shell);
      if (!isSide) restoreSide(shell);
      setHidden(true, shell);
      return;
    }
    const fin = Math.min(max, Math.max(min, w));
    put(shell, fin, W);
    if (isSide) LS.set('sideW', Math.round(fin));
    else {
      LS.set('cardPct', Math.round(fin / W * 1000) / 10);
      const sq = squeeze(cur, fin);
      if (sq != null) { putSide(shell, sq); LS.set('sideW', Math.round(sq)); }
    }
    if (was) setHidden(false, shell);
  };
  btn.addEventListener('pointerup', end);
  btn.addEventListener('pointercancel', () => {
    if (!st) return;
    const { shell, was } = st;
    st = null;
    shell.classList.remove('resizing');
    restore(shell);
    if (!isSide) restoreSide(shell);
    if (was) shell.classList.add(isSide ? 'side-hidden' : 'card-hidden');
  });
  // Клавиатура: стрелка от списка — колонка шире, к списку — уже (шаг 24 px).
  btn.addEventListener('keydown', (e) => {
    const sign = { ArrowLeft: -1, ArrowRight: 1 }[e.key];
    if (!sign || NARROW.matches || hidden()) return;
    e.preventDefault();
    const shell = btn.closest('.shell');
    if (!shell) return;
    const W = shell.offsetWidth;
    const w0 = widthOf(shell, isSide ? 'aside.side' : 'section.card-pane');
    const { min, max } = limits(shell, W);
    const fin = Math.min(max, Math.max(min, w0 + 24 * sign * (isSide ? 1 : -1)));
    put(shell, fin, W);
    if (isSide) LS.set('sideW', Math.round(fin));
    else {
      LS.set('cardPct', Math.round(fin / W * 1000) / 10);
      const sq = squeeze({ W, side0: S.sideHidden ? 0 : widthOf(shell, 'aside.side') }, fin);
      if (sq != null) { putSide(shell, sq); LS.set('sideW', Math.round(sq)); }
    }
  });
}

/** Колонка страницы (общий вид Ковчега, 13.10.2026): заголовок, строка «говори дела» и содержимое прокручиваются
 *  вместе, как на макете; липнут к верху только разделы карточки (пилюли «О клиенте · Дела · Хронология»).
 *  Строка Claude — одна на всё (владелец 09.10.2026): страница кладёт её в тело сама, где ей место (на «Сегодня» —
 *  под девизом); не положила — встаёт первой под заголовком. */
function listColumn(r) {
  const parts = [renderMain(r)].flat(Infinity).filter(Boolean);
  const scroll = el('div', { class: 'scroll' }, parts);
  plate(scroll);
  if (!scroll.querySelector('.quick.say')) {
    const say = quickAdd({}), body = scroll.querySelector('.body');
    const motto = body && body.firstElementChild?.classList.contains('principle') ? body.firstElementChild : null;
    if (motto) motto.after(say); else if (body) body.prepend(say); else scroll.append(say);
  }
  return el('main', { class: 'list' }, scroll);
}

/** Раздел — белая панель (макет «Сегодня»): заголовок с линией снизу, строки через тонкую линию. Плитки, доска и
 *  колонки — свои панели: группа из них одних — просто заголовок над ними. */
const OWN_GLASS = '.dlist, .tiles, .board, .choices, .lv-card, .deals, .pchips, .person-info, .toolbar, .empty, .subgroup';
function plate(root) {
  for (const g of root.querySelectorAll('.group')) {
    const kids = [...g.children].filter((x) => x.tagName !== 'H2');
    if (!kids.length) continue;
    if (kids.some((x) => x.classList.contains('rows') || x.classList.contains('subgroup'))) g.classList.add('panel');
    if (kids.every((x) => x.matches(OWN_GLASS) || x.classList.contains('rows'))) continue;
    const rows = el('div', { class: 'rows' });
    kids[0].before(rows);
    rows.append(...kids);
    g.classList.add('panel');
  }
}

// ── Перерисовка не по действию человека ─────────────────────────────────
// Синк раз в 30 с и пришедшие данные видов перерисовывали страницу целиком — и открытый выпадающий
// список, меню или начатый ввод пропадали (владелец, 06.10.2026: «открываю выпадающий список, страница
// двигается — он пропадает»). Пока человек в списке, меню или пишет — данные копятся, страница ждёт;
// отпустил — перерисовка.
function uiBusy() {
  const ae = document.activeElement;
  if (rec || document.querySelector('.pop') || document.querySelector('.kv-menu:not([hidden])')) return true;
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

/** Пункт боковины: значок и название. Чисел нет с 14.10.2026 (владелец: «Давай уберём эти количества дел везде,
 *  потому что они съедают пространство… плашки с номерами в клиентах не нужно»): длинное название переносится, а не
 *  режется «…». Просроченное видно строкой «просрочено N» в шапке «Сейчас» и «Сегодня». */
function navItem(hash, ico, label, on) {
  return el('button', { class: 'nav-item' + (on ? ' on' : ''), onclick: () => go(hash), 'aria-current': on ? 'page' : null },
    el('span', { class: 'ico' }, ico), el('span', { class: 'label' }, label));
}

/** Виды — на компьютере пунктами боковой колонки со значками (макет 13.10.2026), на телефоне — вкладками под
 *  заголовком; без чисел (14.10.2026), как вкладки приложения. */
function viewPills(r) {
  return Object.entries(VIEWS).filter(([, v]) => !v.show || v.show()).map(([k, v]) =>
    el('a', { class: 'pill-tab' + (r.kind === k ? ' on' : ''), href: '#/' + k, onclick: () => { S.sideOpen = false; } }, v.title));
}

// ── Шапка Ковчега: значок «Ковчег» с подписью «Дела», справа тема, шестерёнка, имя и выход ──
// С 13.10.2026 (вечер, Саша) ряда сервисов в шапке нет: значок по нажатию открывает меню всех сервисов по направлениям
// Круга жизни. С 14.10.2026 (Правка 5.0, этап 7) что в меню и в каком порядке — Свод ui.menu, одна правда для веба,
// телефона и всех страниц Ковчега: сервер отдаёт его владельцу в /api/me (look.menu; нет записи или она кривая —
// заводское), правит владелец здесь же — «Настройки → Вид и меню». В меню — КЛЮЧИ сервисов, а адрес каждого — отсюда:
// сами Дела, их CRM, страницы-двойники Дел (look.pages: «Сегодня», «Засечка», «Правка» — только те, что сервер умеет),
// Claude, соседние сервисы из настроек на компе (look.nav: ключ даёт сервер, у старого — по значку). Знакомый, но не
// поставленный в меню сервис — в «Ещё» (ничего не теряется), ключ без дороги не показывается. Восемь направлений —
// столбиками (4, на узкой шапке 2): направление с одним сервисом не оставляет дыры под собой; «Творчество» — строкой
// ниже, большие кнопки — сверху. Команде (сервисов нет) меню не нужно — значок остаётся ссылкой на «Сейчас».
// Запас на сервер без look.menu — ровно ui.menu_factory контракта svod.json (его сверяет test_dela_ui.py).
/* ui.menu_factory */ const MENU_FACTORY = {"v":1,"big":[{"key":"today","name":"Сегодня","icon":"sun","sub":"день целиком"},{"key":"zasechka","name":"Засечка","icon":"clock","sub":"что делаю сейчас"},{"key":"pravka","name":"Правка","icon":"edit","sub":"все диктовки"}],"directions":[{"name":"Здоровье","sub":"сильное тело, ясная голова","items":[{"key":"sport","name":"Спорт","icon":"activity"},{"key":"eda","name":"Еда","icon":"food"}]},{"name":"Ум","sub":"много знаю и узнаю","items":[{"key":"knigi","name":"Книги","icon":"book"}]},{"name":"Дело","sub":"растёт вглубь и вширь","items":[{"key":"dela","name":"Дела","icon":"dela"},{"key":"meetings","name":"Встречи","icon":"mic"}]},{"name":"Деньги","sub":"деньги — не ограничение","items":[{"key":"dengi","name":"Деньги","icon":"wallet"}]},{"name":"Круг","sub":"знаю многих","items":[{"key":"crm","name":"CRM","icon":"briefcase"}]},{"name":"Родительство","sub":"сыновья растут сильными","items":[{"key":"deti","name":"Дети","icon":"kids"},{"key":"shabbat","name":"Шаббат","icon":"candles"}]},{"name":"Пара","sub":"близкая живая пара","items":[{"key":"kino","name":"Кино","icon":"film"}]},{"name":"Быт","sub":"дома хорошо всем пятерым","items":[{"key":"dom","name":"Дом","icon":"home"},{"key":"vpn","name":"VPN","icon":"shield"}]}],"create":{"name":"Творчество","sub":"создаю новое","items":[{"key":"claude","name":"Claude","icon":"claude"}]}};
const KEY_BY_ICON = {"sun": "today", "clock": "zasechka", "edit": "pravka", "activity": "sport", "food": "eda", "book": "knigi", "dela": "dela", "mic": "meetings", "wallet": "dengi", "briefcase": "crm", "kids": "deti", "candles": "shabbat", "film": "kino", "home": "dom", "shield": "vpn", "claude": "claude"};
// Большая кнопка → страница-двойник веба Дел (её маршрут — #/<страница>; kovcheg_ui.PAGE_OF).
const PAGE_OF = { today: 'day', zasechka: 'zasechka', pravka: 'pravka' };
const knownIcon = (n) => !!(n && (ICONS[n] || ICON_ALIAS[n]));
// Шапка строится заново на каждый render(): закрывать меню щелчком мимо и Esc — одним слушателем на всё время.
const svcMenu = { box: null, close: null };
document.addEventListener('click', (e) => { if (svcMenu.box && svcMenu.close && !svcMenu.box.contains(e.target)) svcMenu.close(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && svcMenu.close) svcMenu.close(); });

/** Дорога каждого ключа меню — что этому вебу открыть по пункту. */
function svcRoads(look) {
  const nav = look.nav || [];
  const road = new Map([['dela', { name: 'Дела', icon: 'dela', url: '/' }]]);
  // CRM — раздел самих Дел (кому он открыт); Claude — внешняя ссылка, только владельцу (у него есть соседние сервисы).
  if (crmOn()) road.set('crm', { name: 'CRM', icon: 'briefcase', url: '#/crm' });
  if (nav.length) road.set('claude', { name: 'Claude', icon: 'claude', url: 'https://claude.ai/', out: true });
  for (const [k, p] of Object.entries(PAGE_OF)) {
    if ((look.pages || []).includes(p)) road.set(k, { name: '', icon: '', url: '#/' + p });
  }
  const loose = [];   // сервисы настройки без ключа (незнакомый значок) — в «Ещё»
  for (const n of nav) {
    if (n.url === '/') continue;   // сами Дела — уже есть
    const k = n.key || KEY_BY_ICON[n.icon] || '';
    if (k && !road.has(k)) road.set(k, { name: n.name, icon: n.icon, url: n.url });
    else if (!k) loose.push({ name: n.name, icon: n.icon, url: n.url });
  }
  return { road, loose };
}

function svcBrand(look) {
  const menu = look.menu && Array.isArray(look.menu.directions) ? look.menu : MENU_FACTORY;
  const { road, loose } = svcRoads(look);
  const used = new Set();
  // Пункт меню → ссылка: подпись и значок — из меню (значок, которого веб не знает, — свой у дороги).
  const pick = (items) => (Array.isArray(items) ? items : []).map((x) => {
    const k = typeof x === 'string' ? x : x && x.key, r = road.get(k);
    if (!r || used.has(k)) return null;
    used.add(k);
    const icon0 = x && knownIcon(x.icon) ? x.icon : r.icon || (MENU_FACTORY.big.find((b) => b.key === k) || {}).icon || 'list';
    return { ...r, key: k, name: (x && x.name) || r.name || k, icon: icon0, sub: (x && x.sub) || '' };
  }).filter(Boolean);
  const big = pick(menu.big);
  const specs = (menu.directions || []).map((d) => [d && d.name, d && d.sub, pick(d && d.items)]).filter((s) => s[2].length);
  const create = menu.create ? pick(menu.create.items) : [];
  const rest = [...road].filter(([k]) => !used.has(k)).map(([k, r]) => ({ ...r, key: k, name: r.name || k, icon: r.icon || 'list' }))
    .concat(loose);
  if (rest.length) specs.push(['Ещё', '', rest]);
  const count = big.length + create.length + specs.reduce((n, s) => n + s[2].length, 0);
  const img = () => el('img', { src: look.icon || '/static/icon.svg', alt: '' });
  if (count < 2) return el('a', { class: 'brand', href: '#/now' }, img(), el('b', {}, 'Дела'));
  const link = (x) => (x.url === '/'
    ? el('a', { class: 'on', href: '#/now', 'aria-current': 'page' }, icon(x.icon, 17), el('span', {}, x.name))
    : el('a', { href: x.url, target: x.out ? '_blank' : null, rel: x.out ? 'noopener' : null },
      icon(x.icon, 17), el('span', {}, x.name), x.sub ? el('small', {}, x.sub) : null));
  const group = (title, sub, items) => el('div', { class: 'kv-svc-g' },
    el('div', { class: 'kv-svc-h' }, title), sub ? el('div', { class: 'kv-svc-sub' }, sub) : null,
    el('div', { class: 'kv-svc-items' }, items.map(link)));
  // Две раскладки — на 4 и на 2 столбика; какую показать, решает ширина шапки (контейнерный запрос в css).
  const cols = (n) => el('div', { class: 'kv-svc-cols n' + n }, Array.from({ length: n }, (_, c) =>
    el('div', { class: 'kv-svc-col' }, specs.filter((g, k) => k % n === c).map(([t, s, items]) => group(t, s, items)))));
  const menuBox = el('div', { class: 'kv-svc-menu', id: 'kv-svc-menu', hidden: true },
    big.length ? el('div', { class: 'kv-svc-top' }, big.map(link)) : null, cols(4), cols(2),
    create.length ? el('div', { class: 'kv-svc-create' }, group(menu.create.name, menu.create.sub, []), el('div', { class: 'kv-svc-items' }, create.map(link))) : null,
    // Внизу меню — «Настройки телефона» (макет «Ковчег на телефоне», 14.10.2026): страница «Телефон» самих Дел.
    phoneOn() ? el('div', { class: 'kv-svc-items kv-svc-foot' }, el('a', { href: '#/phone' }, icon('phone', 17), el('span', {}, 'Настройки телефона'))) : null);
  const brand = el('a', { class: 'brand kv-svc-btn', href: '#/now', role: 'button', title: 'Все сервисы Ковчега',
    'aria-haspopup': 'true', 'aria-expanded': 'false', 'aria-controls': 'kv-svc-menu' }, img(), el('b', {}, 'Дела'));
  const show = (open) => {
    menuBox.hidden = !open;
    brand.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (!open) return;
    // Шапка строится один раз, а маршрут меняется: страница Дел, на которой мы сейчас (#/crm, #/day…), подсвечивается
    // при каждом открытии меню.
    const here = '#/' + location.hash.replace(/^#\/?/, '').split('/')[0];
    for (const a of menuBox.querySelectorAll('a[href^="#/"]:not([href="#/now"])')) a.classList.toggle('on', a.getAttribute('href') === here);
  };
  brand.addEventListener('click', (e) => { e.preventDefault(); show(menuBox.hidden); });
  menuBox.addEventListener('click', (e) => { if (e.target.closest('a')) show(false); });   // переход внутри Дел (#/crm)
  const box = el('nav', { class: 'kv-svc', 'aria-label': 'Сервисы Ковчега' }, brand, menuBox);
  svcMenu.box = box;
  svcMenu.close = () => show(false);
  return box;
}

function topBar() {
  const look = S.me.look || {};
  // Как у остальных страниц «только Саше»: кука kv-nav=owner — семейные страницы (Дети, Шаббат) покажут ему все сервисы.
  if ((look.nav || []).length && KV.cookie('kv-nav') !== 'owner') KV.setCookie('kv-nav', 'owner');
  const theme = el('div', { class: 'kv-theme', role: 'group', 'aria-label': 'Тема' },
    el('button', { type: 'button', 'data-t': 'light', title: 'Светлая', 'aria-label': 'Светлая тема', onclick: () => KV.setTheme('light') }, icon('sun', 16)),
    el('button', { type: 'button', 'data-t': 'dark', title: 'Тёмная', 'aria-label': 'Тёмная тема', onclick: () => KV.setTheme('dark') }, icon('moon', 16)));
  return el('header', { class: 'top' }, el('div', { class: 'top-in' },
    // «☰» в шапке больше нет (13.10.2026): разделы — боковиной с язычком у левого края (sideGrip).
    svcBrand(look),
    el('div', { class: 'top-end' }, theme, prefsMenu(),
      el('span', { class: 'who' }, S.me.name || S.me.user),
      el('button', { class: 'icon kv-out', title: 'Выйти', 'aria-label': 'Выйти',
        onclick: async () => { try { await api('/auth/logout', {}); } catch (e) { /* и так выйдем */ } location.reload(); } }, icon('logout', 18)))));
}

/** Шестерёнка рядом с темой — всё про вид, как в общем слое: тема (и «как в системе» — переключатель её не умеет),
 *  размер текста, шрифт (каждое имя своим начертанием), узор — включён ли, плотность, цвет кружками (так просил
 *  Саша). Стили образцов — через CSSOM: атрибут style CSP Дел не пускает. */
function prefsMenu() {
  const seg = (items) => el('div', { class: 'seg', role: 'radiogroup' }, items);
  const item = (what, v, kids, label, cls) => el('button', { type: 'button', role: 'radio', class: cls || null, 'data-kv-set': what, 'data-v': v,
    title: label || null, 'aria-label': label || null }, kids);
  const sizes = SIZES.map((z) => { const a = el('span', {}, 'А'); a.style.fontSize = z[3] + 'px'; return item('size', z[0], a, z[1]); });
  const fonts = FONTS.map((f) => { const b = item('font', f[0], f[1]); b.style.fontFamily = fontStack(f); return b; });
  const swatches = PCOLORS.map((c) => {
    const b = item('pcolor', c[0], null, c[1], 'kv-sw');
    b.style.setProperty('--sw-l', c[2]); b.style.setProperty('--sw-d', c[3]);
    return b;
  });
  const pat = el('input', { type: 'checkbox', 'data-kv-pref': 'pattern' });
  pat.addEventListener('change', () => KV.setPattern(pat.checked));
  const menu = el('div', { class: 'kv-menu', role: 'dialog', 'aria-label': 'Настройки вида', hidden: true },
    el('div', { class: 'kv-menu-h' }, 'Тема'),
    seg([item('theme', 'light', [icon('sun', 15), el('span', {}, 'Светлая')]), item('theme', 'dark', [icon('moon', 15), el('span', {}, 'Тёмная')]),
      item('theme', '', [icon('monitor', 15), el('span', {}, 'Авто')], 'Как в системе')]),
    el('div', { class: 'kv-menu-h' }, 'Размер текста'), seg(sizes),
    el('div', { class: 'kv-menu-h' }, 'Шрифт'), el('div', { class: 'kv-fonts', role: 'radiogroup' }, fonts),
    el('div', { class: 'kv-menu-sep' }), el('label', { class: 'check' }, pat, el('span', {}, 'Узор на фоне')),
    el('div', { class: 'kv-pat' }, el('div', { class: 'kv-menu-h' }, 'Плотность'), seg(DENS.map((d) => item('pdens', d[0], d[1]))),
      el('div', { class: 'kv-menu-h' }, 'Цвет узора'), el('div', { class: 'kv-swatches', role: 'radiogroup' }, swatches)));
  menu.addEventListener('click', (e) => { const b = e.target.closest('[data-kv-set]'); if (b) KV.set(b.dataset.kvSet, b.dataset.v); });
  const btn = el('button', { type: 'button', class: 'icon', 'aria-haspopup': 'dialog', 'aria-expanded': 'false', title: 'Настройки вида', 'aria-label': 'Настройки вида' }, icon('gear', 18));
  const box = el('div', { class: 'kv-prefs' }, btn, menu);
  const show = (open) => {
    menu.hidden = !open;
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (!open) return;
    // Образцы шрифтов — только буквы подписей, несколько килобайт, и только когда меню открыли.
    loadCss('https://fonts.googleapis.com/css2?' + FONTS.slice(1).map((f) => 'family=' + f[2].split(':')[0]).join('&')
      + '&text=' + encodeURIComponent(FONTS.map((f) => f[1]).join('')), 'kv-font-samples');
    paintLook();
  };
  btn.addEventListener('click', () => show(menu.hidden));
  document.addEventListener('click', (e) => { if (!menu.hidden && !box.contains(e.target)) show(false); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !menu.hidden) { show(false); btn.focus(); } });
  return box;
}

/** Когда в проекте что-то было: последнее дело заведено или закрыто (владелец 09.10.2026: «сортировать по тому,
 *  у кого последние задачи, а то вылезает клиент, где уже год как ничего»). */
function freshBy() {
  const at = new Map();
  for (const t of all()) {
    if (!t.project_id) continue;
    const x = [t.created_at, t.completed_at].filter(Boolean).sort().pop() || '';
    if (x > (at.get(t.project_id) || '')) at.set(t.project_id, x);
  }
  return at;
}

function renderSide(r) {
  const search = el('input', { id: 'side-search', class: 'side-search', type: 'search', placeholder: 'Поиск', 'aria-label': 'Поиск по делам, клиентам и людям (/)',
    value: r.kind === 'search' ? r.q : '' });
  search.addEventListener('input', () => liveSearch(search.value));
  search.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { S.sideOpen = false; liveSearch(search.value); } // на телефоне — убрать шторку, результаты под ней
    if (e.key === 'Escape') { search.value = ''; search.blur(); }
  });

  // Проекты: избранные сверху, дальше по видам. Клиенты — по свежести дел.
  const fresh = freshBy();
  const live = [...S.projects.values()].filter((p) => !p.archived_at && (!S.sphere || p.sphere === S.sphere))
    .sort((a, b) => (a.kind === 'client' && b.kind === 'client' ? (fresh.get(b.id) || '').localeCompare(fresh.get(a.id) || '') : 0)
      || a.name.localeCompare(b.name, 'ru'));
  // Проект — значком (клиент — портфель, остальное — папка).
  const plainItem = (p) => navItem('#/p/' + p.id, dot(p.id), p.name, r.kind === 'project' && r.id === p.id);
  // Клиент — с раскрывающимися проектами (сделками) прямо в меню (владелец, 06.10.2026: «проекты
  // группируются под клиента… в клиентах раскрываемый список у каждого клиента по проектам»).
  const projItem = (p) => {
    if (p.kind !== 'client') return plainItem(p);
    const deals = clientDeals(p.id).filter((d) => d.stage !== 'archive');
    if (!deals.length) return el('div', { class: 'side-client' }, el('span', { class: 'side-caret' }), plainItem(p));
    const here = r.kind === 'deal' && deals.some((d) => d.id === r.id);
    const open = S.clientOpen.has(p.id) || here;
    const caret = el('button', { class: 'side-caret' + (open ? ' open' : ''), title: open ? 'Свернуть «Зачем»' : `«Зачем» клиента: ${deals.length}`,
      'aria-expanded': open ? 'true' : 'false', onclick: (e) => { e.stopPropagation(); toggleClientOpen(p.id); } }, icon('chevron', 14));
    return el('div', { class: 'side-client' + (open ? ' open' : '') }, caret, plainItem(p),
      open ? el('div', { class: 'side-deals' }, deals.map((d) =>
        navItem('#/d/' + d.id, el('span', { class: 'st-dot st-' + d.stage, title: STAGE[d.stage] }), dealShort(d, p), r.kind === 'deal' && r.id === d.id))) : null);
  };
  // Раздел полки: заголовок мелким серым (макет: «Фильтры», «CRM»), щелчок сворачивает. Сколько внутри — не пишем
  // (14.10.2026, как в приложении: числа съедали место, «раскрыть все» уезжало за край).
  const group = (key, title, items, act) => {
    if (!items.length) return null;
    return el('div', { class: 'side-group' + (S.closed[key] ? ' closed' : '') },
      el('div', { class: 'gh-row' },
        el('button', { class: 'gh', 'aria-expanded': S.closed[key] ? 'false' : 'true', onclick: () => { S.closed[key] = !S.closed[key]; LS.set('closed', S.closed); render(); } },
          title, el('span', { class: 'caret' }, icon('down', 14))),
        act && !S.closed[key] ? act : null),
      el('div', { class: 'items' }, items));
  };
  const favs = live.filter((p) => S.favs.includes(p.id));
  const crmNav = crmOn() ? Object.entries(CRM_VIEWS).filter(([, v]) => (!v.money || S.me.money) && (!v.owner || S.me.role === 'owner'))
    .map(([k, v]) => navItem('#/' + k, icon(v.icon, 17), v.title, r.kind === k)) : [];
  const fnav = filters().map((f) => navItem('#/f/' + encodeURIComponent(f.id), icon('bookmark', 17), f.name, r.kind === 'filter' && r.id === f.id));
  // Виды — пунктами со значками (макет 13.10.2026: Сейчас — молния, как у телефона, Сегодня — солнце, Новое — лоток,
  // Все дела — список, Круг жизни — мишень); чисел нет с 14.10.2026.
  const views = Object.entries(VIEWS).filter(([, v]) => !v.show || v.show()).map(([k, v]) => navItem('#/' + k, icon(v.icon, 17), v.title, r.kind === k));
  const groups = [group('filters', 'Фильтры', fnav), group('crm', 'CRM', crmNav), group('fav', 'Избранное', favs.map(projItem))];
  for (const [kind, title] of Object.entries(KIND)) {
    const list = live.filter((p) => p.kind === kind && !S.favs.includes(p.id));
    groups.push(group('k-' + kind, title, list.map(projItem), kind === 'client' ? openAllBtn(list) : null));
  }
  // Люди: с кем больше всего открытых дел (порядок — по их числу, самого числа нет). С CRM — страница «Люди» по компаниям.
  if (!crmOn()) {
    const byPerson = new Map();
    for (const t of all()) if (isOpen(t) && t.person_id) byPerson.set(t.person_id, (byPerson.get(t.person_id) || 0) + 1);
    const people = [...byPerson.entries()].sort((a, b) => b[1] - a[1]).map(([id]) => person(id)).filter(Boolean);
    groups.push(group('people', 'Люди', people.map((p) => navItem('#/h/' + p.id, avatar(p), personName(p), r.kind === 'person' && r.id === p.id))));
  }
  const archived = [...S.projects.values()].filter((p) => p.archived_at).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  if (S.closed.arch === undefined) S.closed.arch = true;
  groups.push(group('arch', 'Архив', archived.map(projItem)));

  // Боковая колонка (макет 13.10.2026): сверху стоят поиск и виды, ниже прокручиваются фильтры, CRM, избранное,
  // клиенты, проекты, люди, архив; внизу — статистика, настройки и путь (стоянка, серия).
  const aside = el('aside', { class: 'side', 'aria-label': 'Разделы' },
    el('div', { class: 'side-top' },
      el('label', { class: 'side-find' }, icon('search', 16), search),
      el('nav', { class: 'side-views', 'aria-label': 'Виды' }, views)),
    el('div', { class: 'side-scroll' },
      groups,
      el('div', { class: 'side-foot' },
        navItem('#/stats', icon('chart', 17), 'Статистика', r.kind === 'stats'),
        phoneOn() ? navItem('#/phone', icon('phone', 17), 'Телефон', r.kind === 'phone') : null,
        typeof digestOn === 'function' && digestOn() ? navItem('#/digest', icon('digest', 17), 'Дайджест', r.kind === 'digests' || r.kind === 'digest') : null,
        navItem('#/settings', icon('settings', 17), 'Настройки', r.kind === 'settings')),
      gameCard(),
      el('div', { class: 'side-keys' }, el('span', { class: 'kbd' }, 'n'), ' сказать Claude · ', el('span', { class: 'kbd' }, '/'), ' поиск')));
  // На телефоне колонка — шторка, как в приложении Claude: кнопка в шапке открывает и закрывает, тап по затемнению
  // и свайп влево — закрывают (владелец 13.10.2026).
  let sx = null, sy = null;
  aside.addEventListener('touchstart', (e) => { sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
  aside.addEventListener('touchend', (e) => {
    if (sx == null || !S.sideOpen) return;
    const dx = e.changedTouches[0].clientX - sx, dy = e.changedTouches[0].clientY - sy;
    sx = null;
    if (dx < -60 && Math.abs(dy) < Math.abs(dx)) { S.sideOpen = false; render(); }
  }, { passive: true });
  return aside;
}

/** Ширина карточки дела справа (владелец 13.10.2026), в процентах окна, помнится в браузере; тянут её язычком
 *  (cardGrip, с 14.10.2026 — вместо ручки на краю). */
const CARD_MIN = 28, CARD_MAX = 65, CARD_DEF = 36;
const cardPct = () => Math.min(CARD_MAX, Math.max(CARD_MIN, +LS.get('cardPct', CARD_DEF) || CARD_DEF));

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
// ── Заголовок страницы (общий вид Ковчега, 13.10.2026): название 26/600 и пояснение в строку через « · » —
// «Сегодня · вторник, 13 октября · осталось 9 · закрыто 2». На страницах внутри (клиент, человек, сделка, встреча)
// перед названием — «назад» и что это за страница. Ниже — разделы карточки (липнут к верху); на телефоне —
// виды вкладками.
const KIND_ONE = { client: 'Клиент', internal: 'Внутреннее', personal: 'Личное' };
const SPHERE = { work: 'Работа', home: 'Дом', '': 'Все сферы' };
/** «Сферы» — обычный выпадающий список рядом с «Группировать» (макет): работа, дом или всё. */
function sphereSel() {
  const s = el('select', { 'aria-label': 'Сфера' }, [['', 'все'], ['work', 'работа'], ['home', 'дом']]
    .map(([v, t]) => el('option', { value: v, selected: (S.sphere || '') === v }, t)));
  s.addEventListener('change', () => { S.sphere = s.value; LS.set('sphere', s.value); render(); });
  return el('label', {}, 'Сферы', s);
}
/** Что за страница — над названием мелко: «Клиент», «Сделка · Альфа», «Встреча», «CRM». */
function headKind(r) {
  if (VIEWS[r.kind]) return null;
  const p = r.kind === 'project' ? project(r.id) : null;
  const dl = r.kind === 'deal' ? S.deals.get(r.id) : null;
  if (dl && project(dl.project_id)) return el('a', { class: 'h-kind', href: '#/p/' + dl.project_id }, 'Зачем клиента · ' + project(dl.project_id).name);
  if (r.kind === 'meeting') return el('a', { class: 'h-kind', href: '#/meetings' }, 'Встреча');
  const word = p ? KIND_ONE[p.kind] || 'Проект' : r.kind === 'person' ? 'Человек' : CRM_VIEWS[r.kind] ? 'CRM' : r.kind === 'filter' ? 'Фильтр' : null;
  return word ? el('span', { class: 'h-kind' }, word) : null;
}
/** Назад — туда, откуда пришёл; пришёл по ссылке — на главную («Сейчас»). */
const back = () => (history.length > 1 ? history.back() : go('#/now'));
const INNER = new Set(['project', 'person', 'deal', 'meeting', 'search', 'digest']);

/** Строка под заголовком. В карточке клиента и проекта — разделы карточки: всё стоит одно под другим, а вкладки
 *  листают к разделу (владелец 09.10.2026: «такие переключения внутри: статус, команда и дела, хронология, деньги,
 *  файлы»; хронология — «не переключалкой, а под делами»). На видах — вкладки видов только на телефоне. */
function tabsRow(r, nav) {
  const pills = nav ? nav.map(([k, label]) => el('button', { class: 'pill-tab' + (k === (S.secOn || nav[0][0]) ? ' on' : ''), 'data-sec': k,
    onclick: (e) => {
      // Нажатая вкладка горит, пока страница едет: внизу короткий раздел до верха не доедет.
      S.secOn = k; S.secLock = Date.now();
      for (const b of e.currentTarget.parentNode.children) b.classList.toggle('on', b.dataset.sec === k);
      document.getElementById('sec-' + k)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } }, label))
    : viewPills(r);
  return el('div', { class: 'tabs-row' + (nav ? ' sec-nav' : '') }, el('nav', { class: 'pills' }, pills));
}
/** Нижний край того, что липнет к верху колонки (вкладки разделов): под ним начинается видимое. */
function stuckBottom() {
  const box = document.querySelector('main.list > .scroll');
  const top = box ? box.getBoundingClientRect().top : 0;
  const tabs = document.querySelector('main.list .tabs-row');
  return tabs && tabs.offsetParent ? Math.max(top, tabs.getBoundingClientRect().bottom) : top;
}
/** Вкладка раздела загорается, когда он под ней: какой раздел последним ушёл вверх, тот и сейчас. */
document.addEventListener('scroll', (e) => {
  const box = e.target;
  if (!box.classList?.contains('scroll')) return;
  const nav = document.querySelector('.sec-nav .pills');
  if (!nav || Date.now() - (S.secLock || 0) < 900) return;
  const top = stuckBottom() + 12;
  let cur = nav.firstChild?.dataset.sec;
  for (const b of nav.children) {
    const sec = document.getElementById('sec-' + b.dataset.sec);
    if (sec && sec.getBoundingClientRect().top <= top) cur = b.dataset.sec;
  }
  S.secOn = cur;
  for (const b of nav.children) b.classList.toggle('on', b.dataset.sec === cur);
}, true);

function head(title, sub, extra, nav) {
  const r = route();
  const items = [].concat(sub || []).filter(Boolean);
  const subLine = items.length ? el('span', { class: 'h-sub' }, items.flatMap((x, i) => [i ? el('span', { class: 'sep', 'aria-hidden': 'true' }, ' · ') : null,
    x.nodeType ? x : el('span', {}, x)])) : null;
  // Вкладки — соседом заголовка, а не внутри него: липнуть к верху колонки они могут только в ней самой.
  return [el('div', { class: 'list-head' },
    headKind(r),
    el('div', { class: 'kv-head' },
      INNER.has(r.kind) ? el('button', { class: 'icon h-back', title: 'Назад', 'aria-label': 'Назад', onclick: back }, icon('arrowLeft', 20)) : null,
      el('h1', {}, title), subLine, extra ? el('span', { class: 'h-extra' }, extra) : null)),
  tabsRow(r, nav)];
}

/** Уровни группировки — до трёх списков «проекты › группы › время»; «—» убирает уровень и всё после него,
 *  один уровень дважды не встаёт. Отдаёт элементы для toolbar. omit — чего не предлагать (в проекте — проекты). */
function levelsBar(levels, onChange, omit = []) {
  const opts = Object.entries(LEVELS).filter(([k]) => !omit.includes(k) && (k !== 'dir' || krugOn()));
  const sel = (i) => {
    const cur = levels[i] || null;
    const pick = (v) => onChange([...new Set(v ? [...levels.slice(0, i), v, ...levels.slice(i + 1)] : levels.slice(0, i))].slice(0, 3));
    return choiceBtn(cur ? LEVELS[cur] : i ? '—' : 'без групп', (a) => choicePop(a, { title: `Уровень ${i + 1}`, current: cur,
      none: i ? '— (убрать уровень)' : 'Без групп', items: opts.map(([v, l]) => ({ v, title: l })), onPick: pick }), { empty: !cur });
  };
  const sels = [];
  for (let i = 0; i < Math.min(3, levels.length + 1); i++) sels.push(i ? el('span', { class: 'lv-sep' }, '›') : null, sel(i));
  return [el('label', {}, 'Группировать'), ...sels.filter(Boolean)];
}
const setLevels = (key) => (lv) => { S.levels[key] = lv; LS.set('levels', S.levels); render(); };

// ── «Только одно» по первому уровню (владелец 10.10.2026: «сделал люди по проектам — следующее, что хочется:
// выпадающий список со всеми людьми и с поисковой строкой: набрать „Наташ“ — выпадут все Наташи, выберу свою…
// по первому уровню группировки поиск, чтобы показывалось только одно»). pick = {lv, key, title}: ветка первого
// уровня; уровни поменялись — выбор молча не действует. На видах живёт до перезагрузки, в фильтре — сохраняется.
const ALL_OF = { project: 'Все «Зачем»', grp: 'Все группы', date: 'Всё время', person: 'Все люди', deal: 'Все сделки', dir: 'Все направления' };
const pickOn = (pick, levels) => !!pick && pick.lv === levels[0];
// Человек, проект, сделка — по id: переименуешь — сохранённый фильтр не потеряет свою «Наташу».
const isPicked = (t, lv, pick) => { const [k, , o] = levelKey(t, lv); return pick.id ? o.id === pick.id : k === pick.key; };
const picked = (items, levels, pick) => (pickOn(pick, levels) ? items.filter((t) => isPicked(t, levels[0], pick)) : items);
const pickOf = (b, lv) => ({ lv, key: b.key, title: b.title, ...(b.opts.id ? { id: b.opts.id } : {}) });

/** Ветки первого уровня с числом дел — для списка выбора. Группы и время — по порядку, остальное — где больше дел. */
function branches(items, lv) {
  const m = new Map();
  for (const t of items) {
    const [key, title, opts] = levelKey(t, lv);
    if (!m.has(key)) m.set(key, { key, title, opts, n: 0 });
    m.get(key).n++;
  }
  const list = [...m.values()];
  const seen = new Map();
  for (const b of list) seen.set(b.title, (seen.get(b.title) || 0) + 1);
  for (const b of list) if (seen.get(b.title) > 1 && b.opts.full) b.title = b.opts.full;
  return ['grp', 'date', 'dir'].includes(lv) ? list.sort((a, b) => a.key.localeCompare(b.key, 'ru'))
    : list.sort((a, b) => b.n - a.n || a.title.localeCompare(b.title, 'ru'));
}

/** Строка под группировкой: «Все люди ▾» или «Только: Наташа ✕». Щелчок — список с поиском. */
function pickBar(items, levels, pick, onPick) {
  const lv = levels[0];
  if (!lv) return null;
  const on = pickOn(pick, levels);
  return el('div', { class: 'pick-bar' },
    el('button', { class: 'chip-btn pick-btn' + (on ? ' on' : ''), title: 'Показать только одно', onclick: (e) => pickPop(e.currentTarget, branches(items, lv), lv, onPick) },
      icon('search', 14), on ? 'Только: ' + pick.title : ALL_OF[lv], icon('down', 14)),
    on ? el('button', { class: 'chip-btn', title: 'Показать все', onclick: () => onPick(null) }, icon('x', 14)) : null);
}

/** Список веток первого уровня с поиском, как «+ человек»: «наташ» — все Наташи; Enter — первая. */
function pickPop(anchor, list, lv, onPick) {
  setTimeout(() => {
    const q = el('input', { class: 'ps-q', placeholder: 'Найти…', autocomplete: 'off' });
    const box = el('div', { class: 'ps-list' });
    let shown = [], at = 0;
    const draw = () => {
      const words = norm(q.value).split(/\s+/).filter(Boolean);
      shown = list.filter((b) => {
        const ks = [b.title, ...(b.opts.find || [])].map(norm);
        return words.every((w) => ks.some((k) => k.includes(w)));
      });
      at = Math.min(at, Math.max(0, shown.length - 1));
      box.replaceChildren(...[
        !words.length ? el('button', { class: 'ps-item', onmousedown: (e) => { e.preventDefault(); closePop(); onPick(null); } },
          el('span', { class: 'ps-t' }, el('span', { class: 'ps-n' }, ALL_OF[lv]))) : null,
        ...shown.slice(0, 40).map((b, i) => el('button', { class: 'ps-item' + (i === at && words.length ? ' on' : ''),
          onmousedown: (e) => { e.preventDefault(); closePop(); onPick(pickOf(b, lv)); } },
        (typeof b.opts.lead === 'function' ? b.opts.lead() : null) || el('span', { class: 'ps-dot' }),
        el('span', { class: 'ps-t' }, el('span', { class: 'ps-n' }, b.title), b.opts.note ? el('span', { class: 'ps-s' }, b.opts.note) : null),
        el('span', { class: 'ps-c' }, b.n))),
        words.length && !shown.length ? el('div', { class: 'ps-empty' }, 'Не нашёл — здесь таких нет') : null].filter(Boolean));
    };
    q.addEventListener('input', () => { at = 0; draw(); });
    q.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown' && shown.length) { e.preventDefault(); at = (at + 1) % shown.length; draw(); }
      if (e.key === 'ArrowUp' && shown.length) { e.preventDefault(); at = (at - 1 + shown.length) % shown.length; draw(); }
      if (e.key === 'Enter' && shown[at]) { e.preventDefault(); closePop(); onPick(pickOf(shown[at], lv)); }
      if (e.key === 'Escape') { e.stopPropagation(); closePop(); }
    });
    draw();
    const pop = popAt(anchor, [el('div', { class: 'pop-h' }, 'Показать только'), q, box]);
    pop.classList.add('ps-pop');
    // Кнопка стоит слева — список открывается от её левого края, а не на полку.
    const z = zk(document.body); // экранные px якоря — в раскладочные (zoom у body), как в popAt
    pop.style.left = Math.max(8, Math.min(window.innerWidth / z - pop.offsetWidth - 8, anchor.getBoundingClientRect().left / z)) + 'px';
    q.focus();
  }, 0);
}
const setPick = (key) => (pk) => { S.pick[key] = pk; S.toTop = true; render(); };

/** Выбор из длинного списка — поповер со строкой поиска сверху, точно как «Показать только» (владелец 13.10.2026:
 *  «вот образец», вместо системных выпадающих списков): фокус сразу в поле, фильтр по мере ввода (каждое слово —
 *  в названии, подписи или других именах), стрелки и Enter, Esc — закрыть. items: [{v, title, note?, keys?, lead?}];
 *  current — выбранное (галочка); none — пункт «без …» наверху; create(text) — «Новый: …», если набрано то, чего нет. */
function choicePop(anchor, { title, items, current = null, none = null, onPick, create = null, placeholder = 'Найти…' }) {
  setTimeout(() => {
    const q = el('input', { class: 'ps-q', placeholder, autocomplete: 'off', 'aria-label': placeholder });
    const box = el('div', { class: 'ps-list', role: 'listbox' });
    let shown = [], at = -1;
    const choose = (it) => { closePop(); if (it.make != null) create(it.make); else onPick(it.v); };
    const draw = () => {
      const words = norm(q.value).split(/\s+/).filter(Boolean), typed = q.value.trim();
      const hit = items.filter((it) => words.every((w) => [it.title, it.note, ...(it.keys || [])].some((k) => k && norm(k).includes(w))));
      shown = [...(!words.length && none ? [{ v: null, title: none }] : []), ...hit.slice(0, 80)];
      if (typed && create && !hit.some((it) => norm(it.title) === norm(typed))) shown.push({ make: typed, title: `Новый: «${typed}»` });
      if (at < 0) at = Math.max(0, shown.findIndex((it) => it.make == null && it.v === current));
      at = Math.min(at, Math.max(0, shown.length - 1));
      box.replaceChildren(...shown.map((it, i) => el('button', { type: 'button', role: 'option', class: 'ps-item' + (i === at ? ' on' : '') + (it.make != null ? ' ps-new' : ''),
        'aria-selected': it.make == null && it.v === current ? 'true' : 'false', onmousedown: (e) => { e.preventDefault(); choose(it); } },
      it.make != null ? icon('plus', 16) : (typeof it.lead === 'function' ? it.lead() : it.lead) || el('span', { class: 'ps-dot' }),
      el('span', { class: 'ps-t' }, el('span', { class: 'ps-n' }, it.title), it.note ? el('span', { class: 'ps-s' }, it.note) : null),
      it.make == null && it.v === current ? el('span', { class: 'ps-c' }, icon('check', 16)) : null)),
      ...(words.length && !shown.length ? [el('div', { class: 'ps-empty' }, 'Не нашёл')] : []));
      box.querySelector('.ps-item.on')?.scrollIntoView({ block: 'nearest' });
    };
    q.addEventListener('input', () => { at = 0; draw(); });
    q.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown' && shown.length) { e.preventDefault(); at = (at + 1) % shown.length; draw(); }
      if (e.key === 'ArrowUp' && shown.length) { e.preventDefault(); at = (at - 1 + shown.length) % shown.length; draw(); }
      if (e.key === 'Enter' && shown[at]) { e.preventDefault(); choose(shown[at]); }
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closePop(); }
    });
    draw();
    const pop = popAt(anchor, [title ? el('div', { class: 'pop-h' }, title) : null, q, box]);
    pop.classList.add('ps-pop');
    // От левого края кнопки, как «Показать только»; экранные px якоря — в раскладочные (zoom у body).
    const z = zk(document.body);
    pop.style.left = Math.max(8, Math.min(window.innerWidth / z - pop.offsetWidth - 8, anchor.getBoundingClientRect().left / z)) + 'px';
    q.focus();
  }, 0);
}
/** Кнопка вместо длинного <select>: показывает выбранное, щелчок — choicePop со строкой поиска. */
function choiceBtn(text, open, { title = null, empty = false, cls = '' } = {}) {
  return el('button', { type: 'button', class: 'pick-sel' + (cls ? ' ' + cls : ''), title, 'aria-haspopup': 'listbox',
    onclick: (e) => open(e.currentTarget) }, el('span', { class: 'pick-sel-t' + (empty ? ' faint' : '') }, text), icon('chevronDown', 15));
}
/** Пункты для choicePop: проекты (клиенты — с подписью «клиент») и люди (короткое имя, компания, должность). */
const projectItems = (list) => list.map((p) => ({ v: p.id, title: p.name, note: [KIND_ONE[p.kind], p.archived_at ? 'архив' : null].filter(Boolean).join(' · '),
  keys: p.aliases || [], lead: () => dot(p.id) }));
const personItems = (list) => list.map((p) => ({ v: p.id, title: p.name, lead: () => avatar(p),
  note: [p.short && p.short !== p.name ? p.short : null, orgLabel(p.org_id)?.name, p.role].filter(Boolean).join(' · '), keys: personKeys(p) }));
const pickNote = (items, pick, levels) => (pickOn(pick, levels) && !items.length ? el('div', { class: 'empty' }, `У «${pick.title}» здесь дел нет.`) : null);

/** «Сохранить фильтр»: что показано и как сгруппировано — в «Фильтры» слева. Имя — предложенное, можно своё. */
async function addFilter(scope, levels, pick) {
  const one = pickOn(pick, levels) ? pick : null;
  const name = (prompt('Название фильтра', `${SCOPES[scope]}: ${levelsName(levels)}` + (one ? ' — ' + one.title : '')) || '').trim();
  if (!name) return;
  const f = { id: 'f-' + Date.now().toString(36), name, scope, levels, ...(one ? { pick: one } : {}) };
  try { await saveFilters([...filters(), f]); toast('Фильтр сохранён: ' + name); go('#/f/' + encodeURIComponent(f.id)); } catch (e) { fail(e); }
}

function renderMain(r) {
  S.order = [];
  S.sugOrder = []; // предложения «Нового» в порядке экрана: П1, П2… — так их видит и Claude
  if (r.kind === 'project') return renderProject(r.id);
  if (r.kind === 'person') return renderPerson(r.id);
  if (r.kind === 'deal') return renderDeal(r.id);
  if (r.kind === 'search') return renderSearch(r.q);
  if (r.kind === 'new') return renderNew(r.batch);
  if (r.kind === 'settings') return renderSettings();
  // «Телефон» — своим файлом (static/phone.js, отдаётся хвостом app.js — api.TAILS); нет его — словами, а не пустотой.
  if (r.kind === 'phone') return typeof renderPhone === 'function' ? renderPhone()
    : [head('Телефон'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Страница «Телефон» не загрузилась — сервер отдал app.js без phone.js. Обнови страницу; не помогло — сервер Дел старый.'))];
  // «Claude: траты» — своим файлом (static/spend.js, хвостом app.js), как «Телефон».
  if (r.kind === 'spend') return typeof renderSpend === 'function' ? renderSpend()
    : [head('Claude: траты'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Страница трат не загрузилась — сервер отдал app.js без spend.js. Обнови страницу; не помогло — сервер Дел старый.'))];
  // «Дайджест» — своим файлом (static/digest.js, хвостом app.js), как «Телефон» и траты.
  if (r.kind === 'digests' || r.kind === 'digest') return typeof renderDigest === 'function' ? renderDigest(r)
    : [head('Дайджест'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Страница дайджеста не загрузилась — сервер отдал app.js без digest.js. Обнови страницу; не помогло — сервер Дел старый.'))];
  if (r.kind === 'stats') return renderStats();
  if (r.kind === 'crm') return renderPipeline();
  if (r.kind === 'clients') return renderClients();
  if (r.kind === 'people') return renderPeople();
  if (r.kind === 'ties') return renderTies();
  if (r.kind === 'money') return renderMoney();
  if (r.kind === 'meetings') return renderMeetings();
  if (r.kind === 'meeting') return renderMeeting(r.id);
  if (r.kind === 'now') return renderNow();
  if (r.kind === 'today') return renderToday();
  if (r.kind === 'krug') return renderKrug();
  if (r.kind === 'tree') return renderTree();
  if (r.kind === 'filter') return renderFilter(r.id);
  if (r.kind === 'all') {
    const lv = levelsOf('all');
    const every = deeds().filter((t) => isMine(t) && inSphere(t) && (S.showDone || isOpen(t)));
    const items = picked(every, lv, S.pick.all);
    // «Деревом» (15.10.2026, владелец: «вложенность одного в другое и показывать тоже в дереве»): группируются только
    // корни, дела внутри идут за родителем отступом, куда бы их ни развели уровни (иначе время растащило бы их врозь).
    const forest = S.allTree && nestOn() ? nestForest(items) : null;
    const base = forest ? forest.roots : items;
    // «Просрочено» — сверху своей группой (13.10.2026): само оно ни в «Сейчас», ни в «Сегодня» не попадает и живёт
    // здесь; в уровнях ниже его уже нет. Строки — со всеми подписями: группа не названа уровнями.
    const late = base.filter(isLate).sort(byTime);
    const rest = base.filter((t) => !isLate(t));
    const tb = el('div', { class: 'toolbar' }, levelsBar(lv, setLevels('all')), sphereSel(), doneToggle(),
      nestOn() ? el('button', { class: 'chip-btn' + (S.allTree ? ' on' : ''), 'aria-pressed': S.allTree ? 'true' : 'false',
        title: 'Дела внутри дел — под родителем, отступом; перетащи дело на дело — положишь внутрь',
        onclick: () => { S.allTree = !S.allTree; LS.set('allTree', S.allTree); render(); } }, 'Деревом') : null,
      el('button', { class: 'chip-btn', onclick: () => addFilter('all', lv, S.pick.all) }, 'Сохранить фильтр'));
    NEST = forest && forest.kids;
    try {
      return [head(VIEWS.all.title, [plural(items.filter(isOpen).length, 'открытое', 'открытых', 'открытых'),
        items.filter(isLate).length ? el('span', { class: 'late' }, 'просрочено ' + items.filter(isLate).length) : null]),
        el('div', { class: 'body' + (forest ? ' nest-tree' : '') }, quickAdd({}), tb, pickBar(every, lv, S.pick.all, setPick('all')),
          pickNote(items, S.pick.all, lv) || [
            late.length ? lateBox(late) : null,
            rest.length ? nested(rest, lv) : late.length ? null : el('div', { class: 'empty' }, 'Пусто.')])];
    } finally { NEST = null; }
  }
  return [head('Дела'), el('div', { class: 'body' })];
}

/** Сколько закрыто сегодня (по Москве) — в шапку «Сейчас» и «Сегодня». */
const doneTodayN = () => deeds().filter((t) => t.status === 'done' && isMine(t) && t.completed_at && MSK_DAY(t.completed_at).day === S.today).length;
/** «просрочено 3» в шапке — ссылкой во «Все дела»: само просроченное ни в «Сейчас», ни в «Сегодня» не попадает. */
const lateLink = () => { const n = lateTasks().length; return n ? el('a', { class: 'late link', href: '#/all', title: 'Просроченное — во «Всех делах», сверху' }, 'просрочено ' + n) : null; };
/** Фильтр «Отбить — по людям» / «Мониторить — по людям»: заводской по id, свой — по группе; нет — «Все дела». */
const scopeHref = (id, scope) => {
  const f = filters().find((x) => x.id === id) || filters().find((x) => x.scope === scope && !pickOn(x.pick, x.levels || []));
  return f ? '#/f/' + encodeURIComponent(f.id) : '#/all';
};
/** «ждут от тебя без срока: N · жду больше недели: M» в шапке «Сейчас» (аудит №5) — ссылками на фильтры по людям:
 *  перед звонком человеку видно всё, что ему должен и что жду от него. Нуль — части нет. */
const waitLinks = () => {
  const owed = owedNoDue().length, stale = waitingStale().length;
  return [owed ? el('a', { class: 'link', href: scopeHref('f-owed-people', 'owed'),
    title: '«Отбить» без срока: человек ждёт ответа или дела, а срока нет — в «Сегодня» само не встанет' }, 'ждут от тебя без срока: ' + owed) : null,
  stale ? el('a', { class: 'link', href: scopeHref('f-check-people', 'check'),
    title: `«Мониторить»: ждёшь от человека дольше ${WAITING_STALE_DAYS} дней, напоминание не назначено — пора напомнить` },
  'жду больше недели: ' + stale) : null];
};

/** «Сейчас» — главная страница (владелец 13.10.2026: «В делах давай разделим: Сейчас (то, что отметил) и Сегодня
 *  (с датой сегодня)», порядок — Сейчас → Новое → Сегодня). Блоки сверху вниз: что владелец сам взял на сегодня (до
 *  пяти, во всех сферах сразу); вопросы автоматики с теми же кнопками, что в «Новом»; дела со сроком сегодня, кроме
 *  уже взятых в «Сейчас». Просроченное сюда само не попадает — в шапке только его число ссылкой во «Все дела». */
function renderNow() {
  const now = nowTasks().sort(byTime);
  // Дела внутри дел из «Сейчас» (15.10.2026: «Для этого» у дела в «Сейчас» ставит дело внутри на сегодня, а не в
  // «Сейчас»): открытые — прямо над родителем в блоке «Сейчас», отступом; в предел пяти не считаются и в «Сегодня»
  // ниже не повторяются. Одно правило с телефоном (DelaSteps.inner, «Сейчас» вкладки).
  const inner = nestOn() ? innerOf(now) : [];
  const depth = new Map(inner.map(([t, d]) => [t.id, d]));
  const taken = new Set([...now.map((t) => t.id), ...depth.keys()]);
  const today = todayTasks().filter((t) => !taken.has(t.id));
  const done = doneTodayN();
  return [head('Сейчас', [D.long(S.today).toLowerCase(), `в «Сейчас» ${now.length} из ${NOW_MAX}`, done ? 'закрыто ' + done : null, lateLink(),
    ...waitLinks()]),
    el('div', { class: 'body' },
      principleLine(),
      quickAdd({}, { placeholder: '«это в Сейчас», «П1 поставь, срок пятница», «Гуркин ждёт звонка — на сегодня»' }),
      groupBox('Сейчас', [...now, ...inner.map(([t]) => t)], { cls: 'grp-now', n: false, sub: 'отметил сам — сделать сегодня', lead: () => icon('bolt', 17),
        rowOpt: (t) => (depth.has(t.id) ? { indent: depth.get(t.id) } : {}),
        before: now.length ? null : el('div', { class: 'hint-line' },
          'Пусто. Возьми до пяти дел на сегодня — молния у дела или в карточке, галочки и «В «Сейчас»» внизу или «!сейчас» в строке.') }),
      newBox(),
      todayBox(today, taken.size))];
}

/** «Новое» на главной: «Подскажи» с кнопками, как в «Новом» (П-номера — те же, их видит Claude); остальное «Нового»
 *  (добавлено само, сделано само) — строкой-ссылкой: оно ответа не ждёт. Пусто совсем — блока нет. */
function newBox() {
  const asks = sortedAsks(pendingSugs());
  const since = Date.now() - FEED_DAYS * 86400e3;
  const fed = openMine().filter((t) => Date.parse(t.created_at) > since && !feedHidden().has(t.id)).length;
  const auto = autoDone(7).length;
  const rest = [fed ? `добавлено само за ${FEED_DAYS} дня: ${fed}` : null, auto ? `сделано само: ${auto}` : null].filter(Boolean).join(' · ');
  const link = (text) => el('a', { class: 'new-link', href: '#/new' }, icon('inbox', 17), text);
  if (!asks.length) return rest ? link('Новое: вопросов нет · ' + rest) : null;
  return [asksBox(asks, { title: 'Новое', sub: 'подскажи — без тебя не поставлю', n: false }), rest ? link('Всё «Новое»: ' + rest) : null];
}

/** «Сегодня» на главной — дела со сроком сегодня, кроме уже взятых в «Сейчас»; внутри — три группы. */
function todayBox(items, taken) {
  const h = groupHead('h2', 'Сегодня', items.length, { lead: () => icon('sun', 17), href: '#/today', n: false,
    sub: 'срок сегодня' + (taken ? ' · что уже в «Сейчас» — выше' : '') });
  if (!items.length) return el('div', { class: 'group grp-today' }, h,
    el('div', { class: 'hint-line' }, taken ? 'Остальное со сроком на сегодня — уже в «Сейчас».' : 'Со сроком на сегодня — ничего.'));
  // Первый уровень — сам блок «Сегодня», второй — группы: подзаголовками внутри одной панели.
  return el('div', { class: 'group nest grp-today' }, h, nested(items, ['today', 'grp'], 1));
}

/** «Просрочено» — сверху «Всех дел» (13.10.2026): срок прошёл, само ни в «Сейчас», ни в «Сегодня» не встаёт. */
const lateBox = (items) => groupBox('Просрочено', items, { late: true, cls: 'grp-late', lead: () => icon('alert', 17),
  sub: 'срок прошёл — сделать, перенести или взять в «Сейчас»' }, null);

/** «Сегодня» — свои открытые дела со сроком ровно сегодня (13.10.2026; до того — котёл с просроченным, «Сейчас»,
 *  «отбить» без срока и напоминаниями). Три группы по приоритету: ждут от меня, сам, проверить. Солнце у дела —
 *  «на завтра». Ниже — «Моё» Круга: что двигает жизнь, но срока на сегодня не имеет, — подсказка, что взять в «Сейчас». */
function renderToday() {
  const lv = levelsOf('today');
  const every = todayTasks();
  const items = picked(every, lv, S.pick.today);
  // «Моё» (Круг жизни, 12.10.2026) — без того, что и так в «Сегодня» или уже в «Сейчас» (mineTasks).
  const mine = mineTasks();
  const done = doneTodayN();
  const n = newCount();
  return [head('Сегодня', [D.long(S.today).toLowerCase(), every.length ? 'со сроком сегодня ' + every.length : null, done ? 'закрыто ' + done : null, lateLink()]),
    el('div', { class: 'body' },
      principleLine(),
      quickAdd({}, { placeholder: 'Скажи, что сделать: «Гуркин ждёт звонка по фонду», «это на завтра», «это мониторить»' }),
      el('div', { class: 'toolbar' }, levelsBar(lv, setLevels('today')), sphereSel(),
        el('button', { class: 'chip-btn', onclick: () => addFilter('today', lv, S.pick.today) }, 'Сохранить фильтр')),
      pickBar(every, lv, S.pick.today, setPick('today')),
      pickNote(items, S.pick.today, lv)
        || (items.length ? nested(items, lv) : el('div', { class: 'empty' }, done ? 'Со сроком на сегодня всё закрыто.' : 'Со сроком на сегодня дел нет.')),
      mine.length ? groupBox('Моё', mine, { cls: 'grp-mine', sub: 'никто не ждёт — двигает жизнь; берёшься сегодня — в «Сейчас»', lead: () => icon('user', 17) }, lv) : null,
      n ? el('a', { class: 'new-link', href: '#/new' }, icon('inbox', 17), 'Новое из встреч и чатов: ' + plural(n, 'вопрос', 'вопроса', 'вопросов')) : null)];
}

/** Сохранённый фильтр: что показать и уровни. Поменял — «Сохранить» запишет, «Как было» — вернёт. */
function renderFilter(id) {
  const f = filters().find((x) => x.id === id);
  if (!f) return [head('Фильтр'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Такого фильтра нет — его удалили.'))];
  const d = S.fdraft[id] || {};
  const scope = d.scope || f.scope, lv = d.levels || f.levels || [];
  const pick = 'pick' in d ? d.pick : f.pick || null;
  const one = pickOn(pick, lv) ? pick : null;
  const changed = scope !== f.scope || lv.join() !== (f.levels || []).join() || JSON.stringify(one) !== JSON.stringify(pickOn(f.pick, f.levels || []) ? f.pick : null);
  const every = scopeItems(scope);
  const items = picked(every, lv, pick);
  const draft = (patch) => { S.fdraft[id] = { scope, levels: lv, pick, ...patch }; S.toTop = true; render(); };
  const scopeSel = el('select', { 'aria-label': 'Что показать' }, Object.entries(SCOPES).map(([k, l]) => el('option', { value: k, selected: k === scope }, l)));
  scopeSel.addEventListener('change', () => draft({ scope: scopeSel.value }));
  const put = async (patch, msg) => {
    try { await saveFilters(filters().map((x) => (x.id === id ? { ...x, ...patch } : x))); delete S.fdraft[id]; toast(msg); render(); } catch (e) { fail(e); }
  };
  const acts = [
    changed ? el('button', { class: 'chip-btn on', onclick: () => put({ scope, levels: lv, pick: one }, 'Фильтр сохранён') }, 'Сохранить') : null,
    changed ? el('button', { class: 'chip-btn', onclick: () => { delete S.fdraft[id]; render(); } }, 'Как было') : null,
    el('button', { class: 'chip-btn', onclick: () => { const name = (prompt('Название фильтра', f.name) || '').trim(); if (name) put({ name }, 'Переименовал'); } }, 'Переименовать'),
    el('button', { class: 'chip-btn', onclick: async () => {
      if (!confirm(`Удалить фильтр «${f.name}»? Дела останутся.`)) return;
      try { await saveFilters(filters().filter((x) => x.id !== id)); toast('Фильтр удалён'); go('#/now'); } catch (e) { fail(e); }
    } }, 'Удалить')];
  return [head(f.name, [SCOPES[scope], levelsName(lv) + (one ? ' — ' + one.title : ''), plural(items.length, 'дело', 'дела', 'дел')]),
    el('div', { class: 'body' },
      quickAdd({}),
      el('div', { class: 'toolbar' }, el('label', {}, 'Показать', scopeSel),
        levelsBar(lv, (next) => draft({ levels: next })), sphereSel(), ...acts),
      pickBar(every, lv, pick, (pk) => draft({ pick: pk })),
      pickNote(items, pick, lv) || (items.length ? nested(items, lv) : el('div', { class: 'empty' }, 'Здесь пусто.')))];
}

const doneToggle = () => el('button', { class: 'chip-btn' + (S.showDone ? ' on' : ''), onclick: () => { S.showDone = !S.showDone; render(); } }, 'Сделанные');

/** Ключ дела на уровне: [порядок, заголовок, вид заголовка]. «Время» — по whenDay: когда дело делать. */
function levelKey(t, lv) {
  if (lv === 'grp') {
    const g = grpOf(t);
    return [{ owed: '1', self: '2', check: '3' }[g], GRP[g], { sub: GRP_SUB[g], cls: 'grp-' + g, find: [GRP[g], GRP_SUB[g]], lead: () => icon(GRP_ICON[g], 17) }];
  }
  // Ключ — имя и id: две «Наташи» — две ветки, а не одна (тогда заголовком — полное имя, opts.full).
  // Уровень «Зачем» (до 13.10.2026 — «проекты»): цель, проект клиента (под клиентом) или проект и раздел.
  if (lv === 'project') {
    const z = zachemOf(t);
    if (!z) return ['9', 'Без «Зачем»', { lead: () => icon('inbox', 16), find: ['без зачем', 'без проекта'] }];
    if (z.kind === 'goal') return ['3' + z.title + '\u0001' + z.id, z.title, { note: z.path.join(' › '), lead: () => icon('target', 16), find: [z.title, ...z.path] }];
    if (z.kind === 'deal') return ['2' + (z.c ? z.c.name : '') + '\u0001' + z.title + '\u0001' + z.id, z.title,
      { id: z.id, href: '#/d/' + z.id, note: z.c ? z.c.name : '', lead: () => el('span', { class: 'st-dot st-' + z.d.stage }), find: [z.title, z.c && z.c.name, ...renamedOf(z.id)].filter(Boolean) }];
    return ['1' + z.title + '\u0001' + z.id, z.title, { id: z.id, href: '#/p/' + z.id, lead: () => dot(z.id), find: [...projectKeys(z.p), ...renamedOf(z.id)] }];
  }
  if (lv === 'person') {
    const p = person(t.person_id);
    const org = p && orgLabel(p.org_id);
    return p ? ['1' + personName(p) + '\u0001' + p.id, personName(p), { id: p.id, href: '#/h/' + p.id, lead: () => avatar(p), full: p.name,
      note: [p.short && p.short !== p.name ? p.name : null, p.role, org && org.name].filter(Boolean).join(' · '),
      find: [...personKeys(p), p.role, org && org.name].filter(Boolean) }] : ['9', 'Без человека', { find: ['без человека'] }];
  }
  if (lv === 'deal') {
    const dl = t.deal_id ? S.deals.get(t.deal_id) : null;
    const dp = dl && project(dl.project_id);
    return dl ? ['1' + dl.name + '\u0001' + dl.id, dl.name, { id: dl.id, href: '#/d/' + dl.id, note: dp ? dp.name : '', find: [dl.name, dp && dp.name].filter(Boolean) }]
      : ['9', 'Без сделки', { find: ['без сделки'] }];
  }
  if (lv === 'dir') {
    // Направление — главное (у корня цепочки «Зачем»); по кругу: сверху Здоровье.
    const d = dirBy(dirsOf(t)[0]);
    return d ? [String((+d.pos || 0) % 12).padStart(2, '0') + d.key, d.name, { sub: d.formula, find: [d.name, d.short].filter(Boolean) }]
      : ['99', 'Без направления', { find: ['без направления'] }];
  }
  if (lv === 'date') {
    if (!isOpen(t)) return ['9done', 'Сделано', {}];
    const d = whenDay(t);
    if (!d) return ['8none', 'Без срока', {}];
    const n = D.diff(d, S.today);
    if (n < 0) return ['0late', 'Просрочено', { late: true }];
    if (n === 0) return ['1today', 'Сегодня', { sub: D.long(S.today) }];
    if (n === 1) return ['2tomorrow', 'Завтра', { sub: D.long(d) }];
    if (n < 7) return ['3' + d, D.long(d), {}];
    return n < 14 ? ['5next', 'Следующая неделя', {}] : ['6later', 'Позже', {}];
  }
  return ['0', null, {}];
}

/** opts.n === false — без числа: блоки главной «Сейчас» (14.10.2026, как блоки приложения — «уберём эти количества
 *  дел везде»). У групп списков число остаётся — оно в строке заголовка и места не отнимает. */
function groupHead(tag, title, n, opts = {}) {
  const lead = typeof opts.lead === 'function' ? opts.lead() : opts.lead;
  return el(tag, { class: opts.late ? 'late' : '' }, lead || null,
    opts.href ? el('a', { class: 'link', href: opts.href }, title) : title, opts.n === false ? null : el('span', { class: 'n' }, n),
    opts.sub ? el('span', { class: 'gsub' }, opts.sub) : null);
}

/** Дела уровнями: первый — разделом (.group), второй и третий — подзаголовками внутри (.subgroup), строки —
 *  плашкой. Внутри последнего уровня — по времени. Пустых групп не бывает: они собираются из самих дел. */
function nested(items, levels, depth = 0) {
  items = [...items].sort(byTime);
  const lv = levels[depth];
  if (!lv) return depth === 0 ? groupBox(null, items, {}, levels) : el('div', { class: 'rows' }, taskRows(items, levels));
  // Дело внутри дела — в группе своего верхнего предка из этого списка (15.10.2026), кроме уровня «время»: там срок
  // дела внутри и есть суть («сегодня», а родитель — в пятницу).
  const top = lv === 'date' ? null : topIn(items);
  const buckets = new Map();
  for (const t of items) {
    const [key, title, opts] = levelKey(top ? top(t) : t, lv);
    if (!buckets.has(key)) buckets.set(key, { title, opts, items: [] });
    buckets.get(key).items.push(t);
  }
  const seen = new Map();
  for (const g of buckets.values()) seen.set(g.title, (seen.get(g.title) || 0) + 1);
  for (const g of buckets.values()) if (seen.get(g.title) > 1 && g.opts.full) g.title = g.opts.full;
  return [...buckets.entries()].sort((a, b) => a[0].localeCompare(b[0], 'ru')).map(([, g]) => {
    const cls = g.opts.cls ? ' ' + g.opts.cls : '';
    if (depth === 0 && !levels[1]) return groupBox(g.title, g.items, g.opts, levels);
    if (depth === 0) return el('div', { class: 'group nest' + cls }, groupHead('h2', g.title, g.items.length, g.opts), nested(g.items, levels, 1));
    return el('div', { class: 'subgroup d' + depth + cls }, groupHead(depth === 1 ? 'h3' : 'h4', g.title, g.items.length, { ...g.opts, sub: null }),
      nested(g.items, levels, depth + 1));
  });
}
/** Одним уровнем — для страниц, где уровни не выбирают (поиск, сделка). */
const grouped = (items, by) => nested(items, Array.isArray(by) ? by : by === 'none' ? [] : [by]);

function groupBox(title, items, opts = {}, by) {
  const lead = typeof opts.lead === 'function' ? opts.lead() : opts.lead;
  const h = title ? el('h2', { class: opts.late ? 'late' : '' }, lead || null,
    opts.href ? el('a', { class: 'link', href: opts.href }, title) : title, opts.n === false ? null : el('span', { class: 'n' }, items.length),
    opts.sub ? el('span', { class: 'gsub' }, opts.sub) : null,
    opts.sum && !opts.act ? el('span', { class: 'sum' }, opts.sum) : null,
    opts.act ? el('span', { class: 'act' }, opts.act) : opts.late && items.length > 1 ? el('span', { class: 'act' },
      el('button', { class: 'chip-btn', onclick: (e) => reschedulePop(e.currentTarget, items.map((t) => t.id)) }, 'Перенести все')) : null) : null;
  return el('div', { class: 'group' + (opts.cls ? ' ' + opts.cls : '') }, h, opts.before || null, taskRows(items, by, opts.chips, opts.rowOpt));
}

/** «Деревом» во «Всех делах» (15.10.2026): родитель → дела внутри него из того же списка — на время отрисовки вида. */
let NEST = null;
/** Строки дел одного списка. Плоско — дело внутри стоит прямо перед своим родителем («Сейчас», «Сегодня», группы);
 *  «Деревом» — родитель, под ним отступом дела внутри по месту, плюсик сворачивает (помнит браузер). */
function taskRows(items, by, chips, rowOpt) {
  if (!NEST) return stepsFirst(items).map((t) => taskRow(t, by, chips && chips(t), rowOpt ? rowOpt(t) : {}));
  const out = [];
  const walk = (t, depth, seen) => {
    const kids = (NEST.get(t.id) || []).filter((c) => !seen.has(c.id));
    const open = !S.closed['nest:' + t.id];
    const row = taskRow(t, by, chips && chips(t), { indent: depth || 0, nest: true,
      fold: kids.length ? { open, toggle: () => { S.closed['nest:' + t.id] = open; LS.set('closed', S.closed); render(); } } : null });
    out.push(dropTo(dragFrom(row, 'task', t.id), { nest: t.id }));
    if (open) for (const c of kids) { seen.add(c.id); walk(c, depth + 1, seen); }
  };
  for (const t of stepsFirst(items)) walk(t, 0, new Set([t.id])); // корни: соседи без родителя в списке — по месту
  return out;
}

/** «Сейчас» — не больше пяти: шестое не встаёт, пока не убрал одно (владелец, 06.10.2026). */
function nowRoom(ids) {
  const have = new Set(nowTasks().map((t) => t.id));
  const add = ids.filter((id) => !have.has(id)).length;
  if (have.size + add <= NOW_MAX) return true;
  toast(`В «Сейчас» уже ${have.size} из ${NOW_MAX}` + (add > 1 ? ` — влезет ещё ${Math.max(0, NOW_MAX - have.size)}` : ' — сначала убери одно'));
  return false;
}
// Солнца «на сегодня / на завтра» (toggleToday, 10.10–14.10.2026) больше нет. Владелец 14.10.2026: «Если нажимаю на
// „сейчас“, то дело пропадает и не ставится в сейчас… Надо оставить только сейчас». Солнце у строки принимали за
// «Сейчас», а у дела со сроком сегодня оно ставило срок на завтра и снимало «Сейчас» — дело уезжало. Теперь у строки,
// в карточке и в панели выбранных одна отметка — молния; срок меняют «Когда» и «Перенести».
/** Можно ли взять дело в «Сейчас» — своё открытое, не цель. У чужого и у цели молнии нет: кнопка, которая всегда
 *  отказывает, читается как поломка. */
const canNow = (t) => isOpen(t) && isMine(t) && !t.goal;
/** «Сейчас» у дела (13.10.2026, владелец: «Сейчас — то, что отметил»): отметил — взял на сегодня, не больше пяти
 *  (шестое не встаёт — nowRoom; предел держит и сервер, store._now_guard); снял — дело остаётся, где было. В операции
 *  ТОЛЬКО focus_on: ни взятие, ни снятие не трогают срок (тест сервера test_now_mark_leaves_due_alone читает и это). */
function toggleNow(ids) {
  const list = ids.map((id) => S.tasks.get(id)).filter(Boolean);
  if (list.length && list.every(isNow)) {
    setFields(list.map((t) => t.id), { focus_on: null }, list.length > 1 ? `Убрал из «Сейчас»: ${list.length}` : 'Убрал из «Сейчас»');
    return;
  }
  // Встаёт только своё открытое дело (цель — не дело): «Сейчас» — то, что владелец сам берёт на день.
  const can = list.filter((t) => canNow(t) && !isNow(t)).map((t) => t.id);
  if (!can.length) { toast('В «Сейчас» встают только свои открытые дела'); return; }
  if (!nowRoom(can)) return;
  setFields(can, { focus_on: S.today }, can.length > 1 ? `В «Сейчас»: ${can.length}` : 'В «Сейчас»');
}

// Строка дела — как TaskRow в Делах на телефоне (Правка 4.0): молния «Сейчас» (с 14.10.2026; 10.10–14.10 тут было
// солнце «на сегодня»), кольцо-галочка (зона касания 44), «Кто: действие» и вторая строка meta через «·» — срок,
// проект, кто ждёт или от кого жду, минуты. Просроченное — жирным «просрочено 2 дн» и толстым кольцом, без красного.
// extra — подписи от места, где строка стоит. opt.compact — короткая строка «Нового» (владелец 08.10.2026:
// «ужасно всё засоряет»): только срок и проект, а справа вместо номера — значок, откуда дело (opt.mark).
function taskRow(t, by, extra, opt = {}) {
  S.order.push(t.id);
  const grouped_ = (k) => (Array.isArray(by) ? by.includes(k) : by === k); // уровень уже назван заголовком
  const p = project(t.project_id);
  const who = person(t.person_id);
  const due = dueLabel(t);
  const now = isNow(t);
  const stop = (e) => e.stopPropagation();
  const m = (cls, ...kids) => el('span', { class: 'm' + (cls ? ' ' + cls : '') }, ...kids);
  const chips = [];
  if (due && due.cls === 'late') chips.push(m('late', `просрочено ${D.diff(S.today, t.due_date)} дн`), m('', D.ddmm(t.due_date)));
  else if (due && !grouped_('date')) chips.push(m(due.cls, due.text));
  const rem = remindChip(t);
  if (rem) chips.push(m('remind', rem));
  // «Зачем» — одно (13.10.2026): цель, проект клиента или проект; нет — не тупик, а вопрос: щелчок — выбрать.
  // Дело внутри другого дела (15.10.2026) — вместо «Зачем» подпись «для: <дело>» (stepChips ниже).
  const par = parentOf(t);
  const z = par ? null : zachemOf(t);
  if (opt.compact) {
    if (z) chips.push(zachemLink(z));
  } else if (!par && !grouped_('project') && !(opt.tree && z && z.kind === 'goal')
    && !(route().kind === 'deal' && z && z.kind === 'deal') && !(route().kind === 'project' && z && z.kind === 'project')) {
    if (z) chips.push(zachemLink(z));
    else if (!t.goal) chips.push(el('button', { class: 'm noproj', title: 'Ради чего это дело — выбрать «Зачем»', onclick: (e) => { stop(e); zachemPop(e.currentTarget, [t.id]); } }, 'без «Зачем»'));
  }
  // Круг жизни: направления (своё — ярче, наследованное — тише), «Зачем» — к цели; без направления — вопрос.
  if (krugOn() && isMine(t) && !opt.compact) {
    const ks = dirsOf(t);
    if (ks.length && !grouped_('dir')) chips.push(m('dir' + ((t.directions || []).length ? '' : ' inherited'), ks.map(dirName).join(', ')));
    else if (!ks.length && isOpen(t)) chips.push(el('button', { class: 'm noproj nodir', title: 'Выбрать цель или направление', onclick: (e) => { stop(e); dirPop(e.currentTarget, t); } }, 'без направления'));
    const steps = isOpen(t) ? stepsOf(t.id).length : 0;
    if (t.goal) chips.push(m('goal', t.rhythm ? 'практика · ' + t.rhythm : steps ? 'цель · ' + plural(steps, 'шаг', 'шага', 'шагов') : 'цель'));
  }
  // Дела внутри дела (15.10.2026): «для: <дело>» у вложенного, «сначала: <первое открытое>» у родителя (до 15.10 у
  // родителя было «цель · N шагов» — целью он не стал). В дереве вложенность видна отступом — подписи не нужны.
  if (!opt.tree && !opt.nest) chips.push(...stepChips(t));
  // В короткой строке группа и человек — в самом названии («Кто: действие»), минуты и метки — в карточке.
  // «Отбить» — сколько ждут: «Гуркин ждёт 3 дн» (с того дня, как дело завели); «мониторить» — «жду Наташу 5 дн».
  const g = grpOf(t), name = who ? personName(who) : '';
  if (opt.compact) { /* ничего */ } else if (g === 'owed') {
    const d = t.created_at ? D.diff(S.today, MSK_DAY(t.created_at).day) : 0;
    chips.push(m('grp-owed', (name ? name + ' ждёт' : 'ждут') + (d > 0 ? ` ${d} дн` : '')));
  } else if (g === 'check') {
    const w = t.waiting_since ? D.diff(S.today, t.waiting_since) : 0;
    chips.push(m('ball-waiting', 'жду' + (name ? ' ' + name : '') + (w > 0 ? ` ${w} дн` : '')));
  } else if (t.ball === 'agenda') {
    chips.push(m('ball-agenda', 'при встрече' + (name ? ' с ' + name : '')));
  } else if (who && !grouped_('person')) {
    chips.push(el('a', { class: 'm link', href: '#/h/' + who.id, onclick: stop }, '@' + personName(who)));
  }
  if (t.estimate_min && !opt.compact) chips.push(m('', dur(t.estimate_min)));
  for (const l of opt.compact ? [] : t.labels || []) chips.push(el('span', { class: 'tag' }, l));
  if (extra) chips.push(...[].concat(extra).filter(Boolean));
  const pick = el('input', { type: 'checkbox', class: 'pick', title: 'Выбрать (Shift — диапазон)' });
  pick.checked = S.sel.has(t.id);
  // «Деревом»: у родителя — плюсик свернуть/раскрыть, у остальных — пусто той же ширины (строки стоят ровно).
  const fold = opt.fold ? el('button', { class: 'caret-btn fold-btn', title: opt.fold.open ? 'Свернуть дела внутри' : 'Раскрыть дела внутри',
    'aria-expanded': opt.fold.open ? 'true' : 'false', onclick: (e) => { stop(e); opt.fold.toggle(); } }, caret(opt.fold.open))
    : opt.nest ? el('span', { class: 'caret-btn fold-btn none', 'aria-hidden': 'true' }) : null;
  pick.addEventListener('click', (e) => { e.stopPropagation(); togglePick(t.id, e.shiftKey); });
  const row = el('div', {
    class: 'task ball-' + t.ball + ' grp-' + g + (opt.compact ? ' compact' : '') + (isOpen(t) ? '' : ' done') + (isNow(t) ? ' now' : '') + (isLate(t) ? ' late' : '')
      + (S.cardId === t.id ? ' open-now' : '') + (S.sel.has(t.id) ? ' sel' : '') + (opt.indent ? ' step' : ''),
    'data-id': t.id,
    onclick: () => openCard(t.id),
  },
  pick, fold,
  // Молния — сразу, срок не трогает; у чужого и у цели — пустое место той же ширины (строки стоят ровно).
  // Рядом «Для этого» (15.10.2026): строка ввода — новое дело внутри, и «Вложить в…», «Вынуть», «Выше» / «Ниже».
  el('div', { class: 'lead' }, canStep(t) ? el('button', { class: 'now-btn step-btn', title: 'Для этого — завести дело внутри; вложить, вынуть, порядок',
    'aria-label': 'Для этого', onclick: (e) => { e.stopPropagation(); stepPop(e.currentTarget, t); } }, icon('plus', 18)) : null,
  canNow(t)
    ? el('button', { class: 'now-btn' + (now ? ' on' : ''), title: now ? 'Из «Сейчас» — срок не меняется' : `В «Сейчас» — до ${NOW_MAX} дел`,
      'aria-label': now ? 'Из «Сейчас»' : 'В «Сейчас»', 'aria-pressed': now ? 'true' : 'false',
      onclick: (e) => { e.stopPropagation(); toggleNow([t.id]); } }, icon('bolt', 18))
    : el('span', { class: 'now-btn off', 'aria-hidden': 'true' })),
  el('button', { class: 'tick' + (isOpen(t) ? '' : ' done'), title: isOpen(t) ? 'Сделано' : 'Вернуть', onclick: (e) => { e.stopPropagation(); toggleDone([t]); } }),
  el('div', { class: 'main' }, el('div', { class: 'title' }, t.title), chips.length ? el('div', { class: 'chips' }, chips) : null),
  opt.mark || null);
  if (opt.indent) row.style.setProperty('--indent', opt.indent); // шаг к цели — отступом под ней (дерево Круга)
  return row;
}

// ── Дерево жизни (13.10.2026) ────────────────────────────────────────────
// Владелец: «помимо круга жизни — вид „дерево жизни“, когда всё это разделяется. Это может быть как девять колонок
// со списками, так и просто список с фильтрами, так и список, где плюсиками раскрывается каждая — и ты так ходишь
// до каждого дела». Три вида одного дерева: «Дерево» (раскрывается плюсиками), «Колонки» (девять направлений рядом),
// «Список» (дела с фильтрами по направлению и цели). Перетащил дело на цель — у него новое «Зачем» (бриф, §7).
/** Дерево из синка (как krug.tree на сервере). Ключ родителя: 't:<id>' — цель или дело, 'p:<id>' — проект,
 *  'd:<ключ>' — направление ('d:' — без направления). Место дела — «Зачем»; нет — проект, если он в Круге; нет —
 *  главное направление («без зачем»). */
function treeModel() {
  const open = all().filter((t) => isOpen(t) && isMine(t));
  const live = new Set(open.map((t) => t.id));
  const parentKey = (t) => {
    if (t.why_id && live.has(t.why_id)) return 't:' + t.why_id;
    if (!t.goal && t.project_id && S.krug.proj.has(t.project_id)) return 'p:' + t.project_id;
    return 'd:' + (dirsOf(t)[0] || '');
  };
  const projKey = (id) => {
    const p = S.krug.proj.get(id) || {};
    return p.why_id && live.has(p.why_id) ? 't:' + p.why_id : 'd:' + ((p.directions || [])[0] || '');
  };
  const kids = new Map();
  const push = (k, item) => { if (!kids.has(k)) kids.set(k, []); kids.get(k).push(item); };
  for (const t of open) push(parentKey(t), { kind: t.goal ? 'goal' : 'task', t, key: 't:' + t.id });
  for (const id of S.krug.proj.keys()) {
    const p = project(id);
    if (p && !p.archived_at) push(projKey(id), { kind: 'project', p, key: 'p:' + id });
  }
  const openN = new Map();
  const openUnder = (key) => {
    if (openN.has(key)) return openN.get(key);
    openN.set(key, 0);
    const n = (kids.get(key) || []).reduce((s, c) => s + (c.kind === 'task' ? 1 : 0) + openUnder(c.key), 0);
    openN.set(key, n);
    return n;
  };
  const moved = new Map();
  const since = D.add(S.today, -6);
  for (const t of all()) {
    if (t.status !== 'done' || !isMine(t) || !t.completed_at || MSK_DAY(t.completed_at).day < since) continue;
    for (let k = parentKey(t), n = 0; k && n < 30; n++) {
      moved.set(k, (moved.get(k) || 0) + (t.goal ? 3 : 1));
      if (k.startsWith('t:')) { const x = S.tasks.get(k.slice(2)); k = x ? parentKey(x) : null; } else if (k.startsWith('p:')) k = projKey(k.slice(2)); else k = null;
    }
  }
  const rank = { goal: 0, project: 1, task: 2 };
  const sorted = (key) => (kids.get(key) || []).slice().sort((a, b) => rank[a.kind] - rank[b.kind]
    || (a.kind === 'project' ? a.p.name.localeCompare(b.p.name, 'ru') : a.kind === 'goal' ? a.t.num - b.t.num
      : parentOf(a.t) ? byPos(a.t, b.t) : byTime(a.t, b.t))); // внутри дела — по месту среди соседей (15.10.2026)
  return { kids: sorted, open: openUnder, moved: (k) => moved.get(k) || 0, parentKey };
}

/** Свёрнуто ли: своё у каждой ветки, помнится в браузере. С завода направления и цели раскрыты, проекты — свёрнуты. */
const isFolded = (key, def) => { const v = S.closed['tree:' + key]; return v === undefined ? def : !!v; };
const setFolded = (key, v) => { S.closed['tree:' + key] = v; LS.set('closed', S.closed); S.treeLevel = null; LS.set('treeLevel', null); };
const caret = (open) => el('span', { class: 'caret' + (open ? ' open' : '') }, open ? '−' : '+');
/** Перетаскивание: дело или цель — на цель (новое «Зачем») или на направление («без зачем»); проект — на цель. */
function dragFrom(node, kind, id) {
  node.draggable = true;
  node.addEventListener('dragstart', (e) => { e.stopPropagation(); e.dataTransfer.setData('text/x-dela', kind + ':' + id); e.dataTransfer.effectAllowed = 'move'; node.classList.add('dragging'); });
  node.addEventListener('dragend', () => node.classList.remove('dragging'));
  return node;
}
function dropTo(node, target) {
  node.addEventListener('dragover', (e) => { if (![...e.dataTransfer.types].includes('text/x-dela')) return; e.preventDefault(); e.stopPropagation(); node.classList.add('drop'); });
  node.addEventListener('dragleave', (e) => { if (!node.contains(e.relatedTarget)) node.classList.remove('drop'); });
  node.addEventListener('drop', (e) => {
    e.preventDefault(); e.stopPropagation(); node.classList.remove('drop');
    const [kind, id] = (e.dataTransfer.getData('text/x-dela') || '').split(':');
    if (id) moveTo(kind, id, target);
  });
  return node;
}
async function moveTo(kind, id, target) {
  if (target.nest) { if (kind === 'task') nestInto([id], target.nest); return; } // «Деревом»: дело на дело — внутрь
  const goal = target.goal ? S.tasks.get(target.goal) : null;
  if (kind === 'project') {
    if (!goal) return toast('Проект вешается на цель, а не прямо на направление');
    try { await op1({ op: 'project.set', id, set: { why_id: goal.id } }); toast('Проект — к цели «' + goal.title + '»'); render(); } catch (e) { fail(e); }
    return;
  }
  const t = S.tasks.get(id);
  if (!t) return;
  if (goal) {
    if (goal.no_steps) return toast('Под этой целью дел не заводят — она ориентир');
    if (belowOf(t.id).has(goal.id)) return toast('Цель не может быть шагом своего шага');
    if (t.why_id === goal.id) return;
    setFields([t.id], { why_id: goal.id }, 'Зачем: ' + goal.title);
  } else if (target.dir) {
    setFields([t.id], { why_id: null, directions: t.goal || !t.project_id || !S.krug.proj.has(t.project_id) ? [target.dir] : t.directions || [] },
      'Направление: ' + dirName(target.dir));
  }
}

/** Строка цели в дереве: плюсик (если есть что раскрыть), название (щелчок — карточка), практика, открыто и движение. */
function goalHead(m, c, depth, open, toggle) {
  const t = c.t, n = m.open(c.key), mv = m.moved(c.key), steps = m.kids(c.key).length;
  const row = el('div', { class: 'tree-goal' + (open ? '' : ' folded') + (S.cardId === t.id ? ' open-now' : ''), style: '--indent:' + depth, 'data-id': t.id },
    steps ? el('button', { class: 'caret-btn', title: open ? 'Свернуть' : 'Раскрыть', onclick: (e) => { e.stopPropagation(); toggle(); } }, caret(open)) : el('span', { class: 'caret-btn none' }),
    el('a', { class: 'g-title', href: '#', onclick: (e) => { e.preventDefault(); openCard(t.id); } }, t.title),
    t.rhythm ? el('span', { class: 'm practice', title: 'Практика: повторяется и не закрывается' }, t.rhythm) : null,
    t.no_steps ? el('span', { class: 'm faint', title: 'Ориентир, а не задача: дел под ним не заводят' }, 'ориентир') : null,
    el('span', { class: 'g-n', title: 'открытых дел под целью' }, n || (steps ? '' : 'пусто')),
    mv ? el('span', { class: 'g-mv', title: 'событий движения за 7 дней: дело — 1, цель — 3' }, '+' + mv) : null);
  return dropTo(dragFrom(row, 'task', t.id), { goal: t.id });
}
/** Ветка «Дерева»: цель (плюсиком), проект (свёрнут), дело (строкой дела) — и всё, что под ними. */
function treeBranch(m, c, depth) {
  if (c.kind === 'task') {
    const row = dropTo(dragFrom(taskRow(c.t, ['dir'], null, { indent: depth, tree: true }), 'task', c.t.id), { nest: c.t.id });
    const below = m.kids(c.key);
    return [row, ...below.flatMap((x) => treeBranch(m, x, depth + 1))];
  }
  if (c.kind === 'project') {
    const n = m.open(c.key);
    if (!n) return [];
    const open = !isFolded(c.key, true);
    const head = el('div', { class: 'tree-proj' + (open ? '' : ' folded'), style: '--indent:' + depth },
      el('button', { class: 'caret-btn', onclick: () => { setFolded(c.key, open); render(); } }, caret(open)),
      dot(c.p.id), el('a', { class: 'link', href: '#/p/' + c.p.id }, c.p.name), el('span', { class: 'g-n' }, n),
      m.moved(c.key) ? el('span', { class: 'g-mv' }, '+' + m.moved(c.key)) : null);
    return [dragFrom(head, 'project', c.p.id), ...(open ? m.kids(c.key).flatMap((x) => treeBranch(m, x, depth + 1)) : [])];
  }
  const open = !isFolded(c.key, false);
  return [goalHead(m, c, depth, open, () => { setFolded(c.key, open); render(); }),
    ...(open ? m.kids(c.key).flatMap((x) => treeBranch(m, x, depth + 1)) : [])];
}
function treeOutline(m, dirs) {
  return dirs.map((d) => {
    const key = 'd:' + d.key, open = !isFolded(key, false);
    const head = el('h2', { class: 'krug-h tree-h', onclick: () => { setFolded(key, open); render(); } },
      caret(open), el('span', { class: 'krug-pos' }, d.zone || ''), d.name, el('span', { class: 'n' }, m.open(key)),
      el('span', { class: 'sum', title: 'событий движения за 7 дней' }, m.moved(key) ? 'за неделю +' + m.moved(key) : 'за неделю —'),
      d.formula ? el('span', { class: 'gsub' }, d.formula) : null);
    return el('div', { class: 'group tree-dir' + (open ? '' : ' folded') }, dropTo(head, { dir: d.key }),
      open ? el('div', { class: 'rows tree-rows' }, m.kids(key).flatMap((c) => treeBranch(m, c, 0))) : null,
      open && !m.kids(key).length ? el('div', { class: 'empty' }, 'Здесь пока ничего — цели заводятся в карточке дела («Сделать целью») или словами Claude.') : null);
  });
}
/** «Колонки»: девять направлений рядом, в каждой — цели лесенкой, проекты и дела коротко; перетаскивай между ними. */
function treeCols(m, dirs) {
  const item = (c, depth) => {
    if (c.kind === 'project') {
      const n = m.open(c.key);
      if (!n) return [];
      const open = !isFolded(c.key, true);
      return [dragFrom(el('div', { class: 'col-proj', style: '--indent:' + depth, onclick: () => { setFolded(c.key, open); render(); } },
        caret(open), c.p.name, el('span', { class: 'g-n' }, n)), 'project', c.p.id),
      ...(open ? m.kids(c.key).flatMap((x) => item(x, depth + 1)) : [])];
    }
    const t = c.t;
    if (c.kind === 'goal') {
      const open = !isFolded(c.key, false);
      const row = el('div', { class: 'col-goal' + (t.rhythm ? ' practice' : '') + (S.cardId === t.id ? ' open-now' : ''), style: '--indent:' + depth, onclick: () => openCard(t.id) },
        m.kids(c.key).length ? el('button', { class: 'caret-btn', onclick: (e) => { e.stopPropagation(); setFolded(c.key, open); render(); } }, caret(open)) : el('span', { class: 'caret-btn none' }),
        el('span', { class: 'g-title' }, t.title),
        m.open(c.key) ? el('span', { class: 'g-n' }, m.open(c.key)) : null);
      return [dropTo(dragFrom(row, 'task', t.id), { goal: t.id }), ...(open ? m.kids(c.key).flatMap((x) => item(x, depth + 1)) : [])];
    }
    const row = el('div', { class: 'col-task' + (inToday(t) ? ' today' : '') + (S.cardId === t.id ? ' open-now' : ''), style: '--indent:' + depth, onclick: () => openCard(t.id) },
      el('button', { class: 'tick mini', title: 'Сделано', onclick: (e) => { e.stopPropagation(); toggleDone([t]); } }), el('span', {}, t.title));
    return [dragFrom(row, 'task', t.id), ...m.kids(c.key).flatMap((x) => item(x, depth + 1))];
  };
  return el('div', { class: 'tree-cols' }, dirs.map((d) => {
    const key = 'd:' + d.key;
    return dropTo(el('section', { class: 'tree-col' },
      el('div', { class: 'col-h', title: d.formula || '' }, el('span', { class: 'zone' }, d.zone || ''), el('b', {}, d.name),
        el('span', { class: 'g-n' }, m.open(key)), m.moved(key) ? el('span', { class: 'g-mv' }, '+' + m.moved(key)) : null),
      el('div', { class: 'col-body' }, m.kids(key).flatMap((c) => item(c, 0)))), { dir: d.key });
  }));
}
/** «Список»: открытые дела (без целей) плоско, с фильтрами — направление, цель (со всем, что под ней), «без зачем». */
function treeList() {
  const f = S.treeF || (S.treeF = {});
  const goals = all().filter((x) => isOpen(x) && isMine(x) && x.goal).map((x) => ({ x, p: goalPath(x) })).sort((a, b) => a.p.localeCompare(b.p, 'ru'));
  const goalSel = choiceBtn(f.goal && S.tasks.get(f.goal) ? S.tasks.get(f.goal).title : 'Любая цель', (a) => choicePop(a, { title: 'Цель', current: f.goal || null,
    none: 'Любая цель', items: goals.map(({ x, p }) => ({ v: x.id, title: x.title, note: p.split(' › ').slice(0, -1).join(' › ') })),
    onPick: (v) => { f.goal = v; render(); } }), { empty: !f.goal });
  const under = f.goal ? belowOf(f.goal) : null;
  const loose = (t) => !upOf(t).slice(1).some((x) => x.kind !== 'project');
  const items = deeds().filter((t) => isOpen(t) && isMine(t) && inSphere(t)
    && (!S.treeDir || dirsOf(t).includes(S.treeDir)) && (!under || under.has(t.id)) && (!f.loose || loose(t)))
    .map((t) => ({ t, p: (() => { const g = upOf(t).slice(1).find((x) => x.kind !== 'project'); return g ? goalPath(g) : dirsOf(t).map(dirName)[0] || ''; })() }))
    .sort((a, b) => a.p.localeCompare(b.p, 'ru') || byTime(a.t, b.t));
  const bar = el('div', { class: 'toolbar' }, el('label', {}, 'Цель', goalSel),
    el('button', { class: 'chip-btn' + (f.loose ? ' on' : ''), title: 'Дела, у которых нет цели: висят прямо на направлении', onclick: () => { f.loose = !f.loose; render(); } }, 'Без зачем'));
  const rows = items.map(({ t, p }) => taskRow(t, ['dir'], p ? el('span', { class: 'm path', title: 'Место в дереве' }, p) : null, { tree: true }));
  return [bar, rows.length ? el('div', { class: 'rows' }, rows) : el('div', { class: 'empty' }, 'Под этот фильтр дел нет.')];
}

const TREE_MODES = { tree: 'Дерево', cols: 'Колонки', list: 'Список' };
function renderTree() {
  if (!krugOn()) return [head('Дерево жизни'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Круг жизни не настроен: в Своде нет направлений (krug.directions).'))];
  const mode = TREE_MODES[S.treeMode] ? S.treeMode : 'tree';
  const m = treeModel();
  const dirs = S.krug.dirs.filter((d) => !S.treeDir || d.key === S.treeDir);
  const goals = all().filter((x) => isOpen(x) && isMine(x) && x.goal).length;
  const dirSel = choiceBtn(S.treeDir ? dirName(S.treeDir) : 'Все направления', (a) => choicePop(a, { title: 'Направление', current: S.treeDir || null,
    none: 'Все направления', items: S.krug.dirs.map((d) => ({ v: d.key, title: d.name, note: d.zone || '', keys: [d.short, d.formula].filter(Boolean) })),
    onPick: (v) => { S.treeDir = v; LS.set('treeDir', S.treeDir); render(); } }), { empty: !S.treeDir });
  // Уровни (владелец 13.10.2026: «наверху — какой уровень нужен: первый — только направления, второй… нажал — и всё
  // сжимается до этого уровня»). Направление — уровень 1, его цели, проекты и дела — 2, и так вниз; «Всё» — раскрыть всё.
  const level = (n) => () => {
    const walk = (k, lv) => m.kids(k).forEach((c) => { if (c.kind !== 'task') S.closed['tree:' + c.key] = !!n && lv >= n; walk(c.key, lv + 1); });
    for (const d of dirs) { S.closed['tree:d:' + d.key] = !!n && n <= 1; walk('d:' + d.key, 2); }
    S.treeLevel = n; LS.set('treeLevel', n); LS.set('closed', S.closed);
    render();
  };
  const bar = el('div', { class: 'toolbar' },
    el('div', { class: 'quick-dates' }, Object.entries(TREE_MODES).map(([k, l]) => el('button', { class: 'chip-btn' + (k === mode ? ' on' : ''),
      onclick: () => { S.treeMode = k; LS.set('treeMode', k); render(); } }, l))),
    el('label', {}, dirSel),
    mode !== 'list' ? el('div', { class: 'quick-dates tree-levels', title: 'До какого уровня раскрыть дерево' }, el('span', { class: 'lv-l' }, 'Уровень'),
      [1, 2, 3, 4, 0].map((n) => el('button', { class: 'chip-btn' + (S.treeLevel === n ? ' on' : ''), onclick: level(n) }, n ? String(n) : 'Всё'))) : null);
  const none = m.kids('d:').filter((c) => c.kind !== 'project');
  const body = mode === 'cols' ? treeCols(m, dirs) : mode === 'list' ? treeList() : treeOutline(m, dirs);
  return [head('Дерево жизни', [plural(deeds().filter((t) => isOpen(t) && isMine(t)).length, 'дело', 'дела', 'дел'), plural(goals, 'цель', 'цели', 'целей'),
    mode !== 'list' ? 'перетащи дело на цель — поменяешь «Зачем»' : null]),
  el('div', { class: 'body tree tree-mode-' + mode }, principleLine(), bar, body,
    none.length && !S.treeDir && mode !== 'list' ? groupBox('Без направления', none.filter((c) => c.kind === 'task').map((c) => c.t).sort(byTime),
      { sub: 'разборщик предложит цель в ближайшие полчаса — или перетащи на цель сам' }) : null)];
}

// ── Страница «Круг жизни»: девять направлений обзором ─────────────────────
// Направления по кругу (Здоровье сверху, через 40°), три зоны по три: Я, Мир, Семья. Циферблат — движение за неделю
// точкой на оси (радиус точки min(10, 3·√N)); состояние многоугольником появится с первой оценкой месяца. У
// направления — формула, сколько открыто, цели верхнего уровня; щелчок — его дерево.
function dialSvg(mv) {
  const ns = 'http://www.w3.org/2000/svg', R = 120, C = 160;
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '-80 -6 480 332'); // поля под подписи осей слева и справа
  svg.setAttribute('class', 'dial');
  const add = (tag, attrs, text) => { const e = document.createElementNS(ns, tag); for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v); if (text) e.textContent = text; svg.append(e); return e; };
  for (const f of [1 / 3, 2 / 3, 1]) add('circle', { cx: C, cy: C, r: R * f, class: 'ring' });
  const n = S.krug.dirs.length;
  S.krug.dirs.forEach((d, i) => {
    const a = ((d.pos != null && n === 9 ? +d.pos : i) * (360 / n) - 90) * Math.PI / 180;
    const x = C + Math.cos(a) * R, y = C + Math.sin(a) * R;
    add('line', { x1: C, y1: C, x2: x, y2: y, class: 'axis' });
    const r = (mv[d.key] || {}).radius || 0;
    if (r) add('circle', { cx: x, cy: y, r: 3 + r * 1.4, class: 'move' }).append(Object.assign(document.createElementNS(ns, 'title'), { textContent: `${d.name}: за неделю ${(mv[d.key] || {}).done || 0}` }));
    const lx = C + Math.cos(a) * (R + 22), ly = C + Math.sin(a) * (R + 22);
    const tx = add('text', { x: lx, y: ly + 4, class: 'lbl', 'text-anchor': Math.abs(Math.cos(a)) < 0.3 ? 'middle' : Math.cos(a) > 0 ? 'start' : 'end' }, d.short || d.name);
    tx.addEventListener('click', () => { S.treeDir = d.key; LS.set('treeDir', d.key); go('#/tree'); });
  });
  return svg;
}
function renderKrug() {
  if (!krugOn()) return [head('Круг жизни'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Круг жизни не настроен: в Своде нет направлений (krug.directions).'))];
  const mv = movement();
  const m = treeModel();
  const zones = [];
  for (const d of S.krug.dirs) {
    const z = d.zone || '';
    if (!zones.length || zones[zones.length - 1].z !== z) zones.push({ z, dirs: [] });
    zones[zones.length - 1].dirs.push(d);
  }
  const card = (d) => {
    const key = 'd:' + d.key, x = mv[d.key] || { done: 0 };
    const goals = m.kids(key).filter((c) => c.kind === 'goal');
    const open = () => { S.treeDir = d.key; LS.set('treeDir', d.key); go('#/tree'); };
    return dropTo(el('div', { class: 'krug-card' },
      el('div', { class: 'kc-h', onclick: open }, el('b', {}, d.name), el('span', { class: 'n' }, m.open(key)),
        el('span', { class: 'sum', title: 'закрыто за 7 дней; закрытая цель — три события' }, x.done ? 'за неделю ' + x.done : 'за неделю —')),
      d.formula ? el('div', { class: 'kc-f' }, d.formula) : null,
      goals.length ? el('div', { class: 'kc-goals' }, goals.map((c) => el('a', { class: 'link', href: '#', onclick: (e) => { e.preventDefault(); openCard(c.t.id); } },
        c.t.title, m.open(c.key) ? el('span', { class: 'g-n' }, m.open(c.key)) : null))) : el('div', { class: 'kc-f faint' }, 'Целей пока нет.')), { dir: d.key });
  };
  const none = m.kids('d:').filter((c) => c.kind === 'task').map((c) => c.t);
  const box = el('div', { class: 'body krug' }, principleLine(),
    el('div', { class: 'krug-top' }, dialSvg(mv), el('div', { class: 'dial-note' },
      'Точка на оси — движение за неделю: закрытое дело — одно событие, цель — три. Состояние многоугольником появится с первой оценкой месяца.',
      el('a', { class: 'link', href: '#/tree' }, 'Дерево жизни целиком'))),
    zones.map(({ z, dirs }) => el('div', { class: 'krug-zone' }, z ? el('h3', { class: 'zone-h' }, z) : null, el('div', { class: 'krug-cards' }, dirs.map(card)))));
  if (none.length) box.append(groupBox('Без направления', none.sort(byTime), { sub: 'разборщик предложит цель в ближайшие полчаса — или выбери сам' }));
  return [head('Круг жизни', [plural(S.krug.dirs.length, 'направление', 'направления', 'направлений'),
    plural(all().filter((x) => isOpen(x) && isMine(x) && x.goal).length, 'цель', 'цели', 'целей')]), box];
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

/** Дело внутри снова открыто — подъём родителя отменён, срок вернулся (fell, 15.10.2026): «срок «…» — снова 20.10». */
const fellWords = (res) => (res || []).map((r) => r && r.fell).filter(Boolean)
  .map((r) => `срок «${r.title}» — снова ` + (r.due_date ? D.ddmm(r.due_date) : 'без срока')).join(' · ');
async function toggleDone(list) {
  const again = list.filter((t) => finishing.has(t.id));
  if (again.length) {
    again.forEach((t) => { finishing.delete(t.id); markDone(t.id, false); });
    await Promise.allSettled(again.map((t) => inflight.get(t.id)));
    try {
      const back = fellWords(await ops(again.map((t) => ({ op: 'task.reopen', id: t.id }))));
      toast('Вернул в работу' + (back ? ' · ' + back : ''));
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
    const res = await sent.finally(() => target.forEach((t) => { if (inflight.get(t.id) === sent) inflight.delete(t.id); }));
    // Сделано последнее дело внутри (15.10.2026): родитель не закрылся, а поднялся — сервер говорит, какой (raised).
    const up = (res || []).map((r) => r && r.raised).filter(Boolean);
    const upWords = up.map((r) => `всё внутри сделано — теперь: «${r.title}»` + (r.moved ? ', на сегодня' : '')).join(' · ')
      || fellWords(res);
    if (open.length && !target.some((t) => finishing.has(t.id))) return; // передумал, пока сервер отвечал
    S.sel.clear();
    renderBulk();
    const pts = open.length ? target.reduce((sum, t) => sum + pointsFor(t), 0) : 0;
    toast((open.length ? (target.length > 1 ? `Сделано: ${target.length}` : 'Сделано: ' + target[0].title) : 'Вернул в работу') + (pts ? ` · +${pts}` : '')
      + (upWords ? ' · ' + upWords : ''),
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

/** set — поля для всех дел или функция id → поля (своё у каждого: «Зачем» у дела внутри дела не снимается). */
async function setFields(ids, set, msg) {
  const sets = new Map(ids.map((id) => [id, typeof set === 'function' ? set(id) : set]));
  const before = ids.map((id) => ({ ...S.tasks.get(id) }));
  try {
    await ops(ids.map((id) => ({ op: 'task.set', id, set: sets.get(id), was: Object.fromEntries(Object.keys(sets.get(id)).map((k) => [k, S.tasks.get(id)[k] ?? null])) })));
    if (msg) S.sel.clear();
    render();
    if (msg) {
      toast(msg, () => ops(before.map((t) => ({ op: 'task.set', id: t.id, set: Object.fromEntries(Object.keys(sets.get(t.id)).map((k) => [k, t[k] ?? null])) }))));
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
  // Якорь и окно — в экранных px, left/top и размеры меню — в раскладочных: при «Размере текста» (zoom у body) их
  // надо свести, иначе меню уезжает от кнопки на 8–16 %.
  const z = zk(document.body), r = anchor.getBoundingClientRect();
  const w = pop.offsetWidth, h = pop.offsetHeight, W = window.innerWidth / z, H = window.innerHeight / z;
  const top = r.top / z, bottom = r.bottom / z, right = r.right / z;
  pop.style.left = Math.max(8, Math.min(W - w - 8, right - w)) + 'px';
  pop.style.top = (bottom + h + 8 > H ? Math.max(8, top - h - 4) : bottom + 4) + 'px';
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

/** Группа для выбранных: «Отбить», «Запустить», «Мониторить». */
function grpPop(anchor, ids) {
  popAt(anchor, Object.entries(GRP).map(([g, label]) => el('button', { onclick: async () => {
    closePop();
    const before = ids.map((id) => ({ ...S.tasks.get(id) }));
    try {
      await ops(ids.map((id) => ({ op: 'task.set', id, set: grpSet(S.tasks.get(id), g) })));
      S.sel.clear(); render();
      toast(label + ': ' + ids.length, () => ops(before.map((t) => ({ op: 'task.set', id: t.id, set: { ball: t.ball, owed: !!t.owed } }))));
    } catch (e) { fail(e); render(); }
  } }, label)));
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
    el('button', { onclick: (e) => zachemPop(e.currentTarget, ids) }, 'Зачем…'),
    nestOn() && ids.every((id) => isMine(S.tasks.get(id))) ? el('button', { onclick: (e) => nestPop(e.currentTarget, ids) }, 'Вложить в…') : null,
    el('button', { onclick: () => toggleNow(ids) }, ids.length && ids.every((id) => isNow(S.tasks.get(id))) ? 'Из «Сейчас»' : 'В «Сейчас»'),
    el('button', { onclick: (e) => grpPop(e.currentTarget, ids) }, 'Группа'),
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
  take(/\s!(?:отбить|ждут)(?=\s)/gi, () => { out.set.ball = 'owed'; out.tags.push('отбить'); });
  take(/\s!(?:мониторить|жду)(?=\s)/gi, () => { out.set.ball = 'waiting'; out.tags.push('мониторить'); });
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
  ['жду', 'делает человек: жду от него'], ['повестка', 'поднять при встрече или звонке'],
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

// ── Строка «Саша, говори дела» — на каждой странице ──────────────────────
// Владелец, 06.10.2026: «везде должен быть сверху красивый текстбокс… скажи, что сделать с этим делом,
// с этим клиентом — я наговариваю, и он внутри всё правит». Claude видит дела на экране (в карточке
// клиента, сделки, человека — и саму карточку, это Opus), правит их, заводит новые, в «Новом» решает
// предложения. 08.10.2026: «знак Клода уберём… слева микрофончик, справа send»: микрофон — нажал, говоришь,
// нажал ещё раз — ушло Claude; написал — стрелка или Enter. «+» больше нет: одно дело как написано, с разметкой, —
// Alt+Enter. В Правке 4.0 строка стояла внизу экрана; с общим видом Ковчега (13.10.2026, макет «Сегодня») — белая
// панель вверху страницы, под заголовком и девизом, справа квадратная клавиша акцентом: пустое поле — голос,
// с текстом — «отдать Claude». Подсказка короткая («Саша, говори дела»), примеры команд — во всплывающей
// подсказке поля. label — о чём строка («с клиентом «Альфа»»), placeholder — пример команды для этой страницы.
function quickAdd(defaults, { placeholder = null, label = null } = {}) {
  const busy = !!S.parse;
  // О чём строка: открытое дело, карточка (клиент, проект, человек) или дела на экране.
  const opened = S.cardId && S.tasks.get(S.cardId);
  const about = opened ? `с делом «${opened.title}»` : label || 'с делами на экране';
  // Строка — разговор (заход Д, 13.10.2026): спросить, поручить, надиктовать новые дела; ответ — лентой под ней.
  const talk = pageTalk();
  const input = el('textarea', { id: 'quick', rows: 1, autocomplete: 'off', disabled: busy,
    'aria-label': `${S.me.name || 'Саша'}, что ты хочешь спросить? (Claude видит то, что ${about.replace(/^с /, 'на экране: ')})`,
    placeholder: talk && talk.turns.length ? 'Продолжить разговор…' : `${S.me.name || 'Саша'}, что ты хочешь спросить?`,
    title: placeholder || 'Спроси («что сегодня важнее всего?»), поручи («все просроченные — на пятницу») или надиктуй новые дела' });
  input.value = S.quickText || '';
  const preview = el('div', { class: 'preview' });
  const help = el('div', { class: 'help hidden' },
    'Enter или стрелка — Claude · Shift+Enter — новая строка · Alt+Enter — одно дело как написано: ',
    '+проект  @человек  *метка  !отбить  !мониторить  !хочу  !15м · сегодня, завтра, в пятницу, 12.10');
  const status = busy && !S.parse.card ? el('div', { class: 'claude-status' }, 'Claude думает… Можно уходить на другие страницы — изменения появятся сами.')
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
      toast('Записал: ' + r.task.title);
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

  // Справа — одна квадратная клавиша акцентом (макет 13.10.2026): пустое поле — микрофон, с текстом — «отдать
  // Claude», пока слушаю — «стоп». Микрофон при тексте отходит влево тихим значком: дописать голосом. Пока Claude
  // думает — заливка по строке, без крутилки.
  const sendBtn = el('button', { type: 'button', class: 'q-btn send-btn' + (busy ? ' busy' : ''), disabled: busy,
    title: !S.me.claude ? 'Claude на сервере ещё не настроен' : busy ? 'Claude думает…' : 'Отдать Claude (Enter)',
    'aria-label': 'Отдать Claude', onclick: () => claudeParse(input, defaults) }, icon('up', 20));
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
    } }, icon(hearing ? 'stop' : 'mic', 20));
  if (input.value) setTimeout(grow);
  const card = pageScope(defaults).card;
  // Над строкой — о чём она; в карточке клиента, проекта, человека — что Claude (Opus) видит её целиком.
  const say = el('label', { class: 'say-h', for: 'quick' }, el('span', {}, opened ? `Claude видит экран и открытое дело «${opened.title}»` : 'Claude видит экран ' + about),
    card ? el('span', { class: 'say-model', title: 'В карточке Claude видит её целиком: проекты, людей, команду, описание, хронологию — и правит их' }, 'Opus · видит карточку') : null);
  const box = el('div', { class: 'quick-box' + (busy ? ' busy' : '') + (hearing ? ' hearing' : '') + (input.value.trim() ? ' has-text' : '') }, input, micBtn, sendBtn);
  return el('div', { class: 'quick say' + (card || opened ? ' card-scope' : '') },
    say,
    el('div', { class: 'quick-wrap' }, box, ac),
    status, preview, help,
    talkFeed(talk));
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
  const title = p ? `${KIND[p.kind] || 'Проект'}: ${p.name}` : h ? `Человек: ${h.name}` : dl ? `Зачем клиента (сделка): ${dl.name}`
    : r.kind === 'search' ? `Поиск: ${r.q}`
      : r.kind === 'new' && r.batch ? `Новое: одна пачка — ${one?.batch_title || r.batch}`
        : r.kind === 'filter' ? 'Фильтр: ' + ((filters().find((f) => f.id === r.id) || {}).name || r.id)
          : (VIEWS[r.kind] || CRM_VIEWS[r.kind] || { title: 'Дела' }).title;
  const rows = [...document.querySelectorAll('main.list .task[data-id]')];
  const uniq = (xs) => [...new Set(xs)];
  // В окне — между низом того, что липнет к верху колонки (вкладки разделов), и низом окна; строка, от которой
  // видна лишь кромка, не в счёт: её названия не видно.
  const top = stuckBottom();
  const bottom = Math.min(window.innerHeight, document.querySelector('main.list')?.getBoundingClientRect().bottom || window.innerHeight);
  const main = document.querySelector('main.list')?.getBoundingClientRect();
  const seen = (x) => {
    const b = x.getBoundingClientRect();
    // Доска Воронки шире окна и листается вбок: колонка за краем — не «в окне».
    const c = x.closest('.board')?.getBoundingClientRect() || main;
    const left = Math.max(c ? c.left : 0, main ? main.left : 0), right = Math.min(c ? c.right : innerWidth, main ? main.right : innerWidth);
    return b.height > 0 && Math.min(b.bottom, bottom) - Math.max(b.top, top) >= b.height * 0.6
      && Math.min(b.right, right) - Math.max(b.left, left) >= b.width * 0.6;
  };
  const inView = rows.filter(seen);
  const scope = { title, task_ids: uniq(rows.length ? rows.map((x) => x.dataset.id) : S.order).slice(0, 300),
    visible_ids: uniq(inView.map((x) => x.dataset.id)).slice(0, 300) };
  // Сделки на экране (08.10.2026): Воронка, клиенты, человек — «убери Альфу — не работаем» Claude правит прямо отсюда.
  const deals = [...document.querySelectorAll('main.list [data-deal]')];
  if (deals.length) Object.assign(scope, { deal_ids: uniq(deals.map((x) => x.dataset.deal)).slice(0, 200),
    visible_deal_ids: uniq(deals.filter(seen).map((x) => x.dataset.deal)).slice(0, 200) });
  if (S.sel.size) scope.selected_ids = [...S.sel].slice(0, 300);
  if (S.cardId && S.tasks.has(S.cardId)) scope.open = S.cardId;
  // П-номера — в «Новом» и на главной «Сейчас» (там «Подскажи» тоже с кнопками, 13.10.2026).
  if (r.kind === 'new' || (r.kind === 'now' && S.sugOrder.length)) scope.suggestion_ids = S.sugOrder.slice(0, 150);
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
/** Что поменялось — словами: «срок 07.10, в «Сейчас», жду Наташа». */
function describe(c) {
  const a = c.after || {}, out = [];
  if ('title' in a) out.push(`название «${a.title}»`);
  if ('due_date' in a) out.push(a.due_date ? 'срок ' + D.ddmm(a.due_date) + (a.due_time ? ' ' + a.due_time : '') : 'без срока');
  else if ('due_time' in a) out.push(a.due_time ? 'время ' + a.due_time : 'без времени');
  if ('focus_on' in a) out.push(a.focus_on ? 'в «Сейчас»' : 'из «Сейчас»');
  if ('project_id' in a) out.push(a.project_id ? 'проект ' + (project(a.project_id)?.name || '?') : 'без проекта');
  if (a.deal_id) out.push('сделка ' + (S.deals.get(a.deal_id)?.name || '?'));
  if ('ball' in a || 'owed' in a || 'person_id' in a) {
    const who = 'person_id' in a ? personName(person(a.person_id)) : '';
    out.push(('ball' in a || 'owed' in a ? grpWord(a) : 'человек') + (who ? ' ' + who : ''));
  }
  if ('notes' in a) out.push('дописал заметку');
  if ('why_id' in a) out.push(a.why_id ? 'внутрь «' + ((S.tasks.get(a.why_id) || {}).title || 'дела') + '»' : 'вынул из дела');
  if ('estimate_min' in a) out.push(a.estimate_min ? a.estimate_min + ' мин' : 'без оценки');
  if ('labels' in a) out.push(a.labels.length ? 'метки ' + a.labels.join(', ') : 'без меток');
  if (a.remind_at) out.push('напомню в Telegram ' + remindWords(a.remind_at));
  else if (a.remind_place) out.push('напомню, когда приедешь: ' + a.remind_place);
  else if ('remind_at' in a || 'remind_place' in a) out.push('без напоминания');
  if (c.status) out.push({ done: 'сделано', cancelled: 'отменено', open: 'в работу' }[c.status[1]]);
  return out.join(', ');
}

const DECIDED = { create: 'заведено', close: 'закрыто', update: 'уточнено', assign: 'взято себе' };

// ── Разговор с Claude прямо под строкой (владелец 13.10.2026, заход Д) ─
// «Строка вверху — „Саша, что ты хочешь спросить?“. Разговор идёт прямо под строкой, лентой, можно продолжать сколько
// угодно; в нём же можно надиктовать новые дела, поправить, спросить. Claude видит то, что на экране». Всплывающего
// окна больше нет (было с 09.10.2026): реплика уходит из той же строки вместе с разговором (scope.history) и его номером
// (talk_id — экран разговора на сервере один, вторая реплика читает его из кэша). Свежая реплика — сверху, сразу под
// строкой. Другой экран — новый разговор; прошлый сворачивается строкой под лентой. В карточке дела — свой разговор
// под её строкой («Что поправить в этом деле?»).
const TALK_KEEP = 8; // реплик разговора, которые видит Claude
const screenKey = () => { const r = route(); return [r.kind, r.id || '', r.batch || '', r.q || ''].join(':'); };
/** Разговор этой страницы; ушёл на другой экран — прошлый сворачивается, здесь — с чистого листа. */
function pageTalk() {
  if (S.talk && S.talk.key !== screenKey()) {
    if (S.talk.turns.length) S.talkOld = [S.talk, ...(S.talkOld || [])].slice(0, 5);
    S.talk = null;
  }
  return S.talk;
}
async function claudeSay(text, defaults, cont, card) {
  if (S.parse) return;
  let talk;
  if (card) {
    talk = S.cardTalk && S.cardTalk.card.id === card.id ? S.cardTalk : { id: uuid(), card, turns: [] };
    S.cardTalk = talk;
  } else {
    talk = pageTalk() || { id: uuid(), key: screenKey(), title: pageScope(defaults).title, defaults, turns: [] };
    S.talk = talk;
  }
  const one = talk.card;
  // Карточка дела (13.10.2026): Claude видит только это дело — экран ему не нужен.
  const scope = one ? { title: 'Карточка дела', card: 'task', open: one.id, ...(card && card.kstati ? { kstati: true } : {}),
    ...(card && card.step ? { step: true } : {}) }
    : pageScope(defaults); // экран — до перерисовки: что в окне, видно сейчас
  scope.talk_id = talk.id;
  if (talk.turns.length) scope.history = talk.turns.slice(-TALK_KEEP).map(turnMemo);
  talk.busy = text;
  S.parse = { text, card: one ? one.id : null };
  if (one) S.cardSay[one.id] = ''; else S.quickText = '';
  render();
  try {
    const d = await runJob('/api/ask', { text, scope });
    S.parse = null;
    talk.busy = null;
    crmDirty(); // карточку (хронологию, людей, сделки) Claude мог поправить на сервере
    for (const t of d.tasks || []) S.tasks.set(t.id, t);
    // Ничего не сделал и не ответил — команда возвращается в поле: дополнить и отправить ещё раз, а не говорить
    // заново (08.10.2026: «нажимаю на микрофон, и всё стирается»).
    const did = ['changed', 'tasks', 'decided', 'crm', 'notes', 'refs'].some((k) => (d[k] || []).length);
    if (!did && !d.reply) { if (one) S.cardSay[one.id] = text; else S.quickText = text; }
    if (one && (did || d.reply)) { S.kstati = false; S.cardStep = false; }
    talk.turns.push({ said: text, d });
    await sync().catch(() => {});
    render();
    // Свежая реплика — сразу под строкой: показать её, не прыгая.
    document.querySelector((one ? '.card-say' : '.quick.say') + ' .talk-feed .cr-turn')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  } catch (e) {
    talk.busy = null;
    if (one) S.cardSay[one.id] = text; else S.quickText = text;
    S.parse = null;
    if (!talk.turns.length) { if (one) S.cardTalk = null; else S.talk = null; }
    render();
    fail(e);
  }
}
/** Дела, о которых ответ (refs), — ссылками под ним: щелчок открывает карточку. */
function refRow(r) {
  const x = S.tasks.get(r.id);
  const z = x && zachemOf(x), due = x && dueLabel(x);
  const meta = x ? [z ? z.title : null, due ? due.text : null, isOpen(x) ? null : x.status === 'done' ? 'сделано' : 'отменено'].filter(Boolean).join(' · ') : '';
  return el('button', { class: 'cr-task cr-ref', onclick: () => openCard(r.id) }, el('div', {}, x ? x.title : r.title), meta ? el('div', { class: 'cr-meta' }, meta) : null);
}
/** Лента разговора под строкой: свежее сверху; «Новый разговор» — с чистого листа; прошлые — свёрнуто ниже. */
function talkFeed(talk, { inCard = false } = {}) {
  const old = inCard ? [] : S.talkOld || [];
  const live = talk && (talk.turns.length || talk.busy);
  if (!live && !old.length) return null;
  const end = () => {
    if (inCard) S.cardTalk = null;
    else { if (S.talk && S.talk.turns.length) S.talkOld = [S.talk, ...(S.talkOld || [])].slice(0, 5); S.talk = null; }
    render();
  };
  return el('div', { class: 'talk-feed' + (inCard ? ' in-card' : '') },
    live ? el('div', { class: 'tf-head' }, el('span', { class: 'tf-t' }, inCard ? 'Разговор об этом деле' : 'Разговор с Claude'),
      talk.turns.length ? el('span', { class: 'cr-meta' }, plural(talk.turns.length, 'реплика', 'реплики', 'реплик')) : null,
      talk.busy ? null : el('button', { class: 'link-btn', title: 'Закончить: следующий вопрос — с чистого листа', onclick: end }, 'Новый разговор')) : null,
    live && talk.busy ? el('div', { class: 'cr-turn pending' }, el('div', { class: 'cr-said' }, talk.busy), el('div', { class: 'cr-think' }, 'Claude думает…')) : null,
    live ? [...talk.turns].reverse().map((t) => turnView(t)) : null,
    old.map((o) => el('details', { class: 'tf-old' },
      el('summary', {}, 'Прошлый разговор · ' + (o.title || 'другой экран') + ' · ' + plural(o.turns.length, 'реплика', 'реплики', 'реплик')),
      [...o.turns].reverse().map((t) => turnView(t)))));
}

/** Что сделано в реплике — коротко, для памяти разговора на сервере. */
function turnMemo(t) {
  const d = t.d, out = [];
  if (d.route === 'new') {
    if ((d.tasks || []).length) out.push('завёл: ' + d.tasks.map((x) => x.title).join(', '));
    if ((d.notes || []).length) out.push('в хронологию: ' + d.notes.map((x) => x.summary).join('; '));
  } else {
    for (const c of d.changed || []) out.push(`${c.after?.title || c.title}: ${describe(c)}`);
    if ((d.tasks || []).length) out.push('завёл: ' + d.tasks.map((x) => x.title).join(', '));
    for (const x of d.decided || []) out.push(`П${x.n} ${x.decision === 'accept' ? DECIDED[x.kind] || 'принято' : 'отклонено'}`);
    for (const c of d.crm || []) out.push(c.what);
  }
  if ((d.errors || []).length) out.push('не вышло: ' + d.errors.join('; '));
  return { said: t.said.slice(0, 600), reply: (d.reply || '').slice(0, 900),
    done: (out.join('; ') || 'ничего не менял').slice(0, 700) + (t.undone ? ' — Саша вернул всё это как было' : '') };
}

/** Отмена реплики: правки дел — как было, заведённое — отменой, карточка — её «как было». */
function turnUndo(d) {
  const back = [];
  if (d.route === 'new') {
    for (const t of d.tasks || []) back.push({ op: 'task.cancel', id: t.id });
    return back;
  }
  const took = (d.decided || []).filter((x) => x.decision === 'accept' && x.id);
  for (const c of [...(d.changed || []), ...took.filter((x) => !x.created)]) {
    if (Object.keys(c.before || {}).length) back.push({ op: 'task.set', id: c.id, set: c.before });
    if (c.status) back.push({ op: { open: 'task.reopen', done: 'task.done', cancelled: 'task.cancel' }[c.status[0]], id: c.id });
  }
  for (const t of [...(d.tasks || []), ...took.filter((x) => x.created)]) back.push({ op: 'task.cancel', id: t.id });
  for (const c of d.crm || []) back.push(...(c.undo || [])); // карточка: хронология, люди, сделки — как было
  return back;
}

/** Реплика: что сказал, ответ Claude словами и что он сделал — дела открываются карточкой. */
function turnView(t) {
  const d = t.d;
  const open = (id) => () => id && openCard(id);
  const items = [];
  if (d.route === 'new') {
    const meta = (x) => [project(x.project_id)?.name,
      x.person_id ? ((grpOf(x) !== 'self' || x.ball === 'agenda' ? grpWord(x) + ' ' : '') + personName(person(x.person_id))) : null,
      x.due_date ? dueLabel(x).text : null, x.estimate_min ? x.estimate_min + ' мин' : null].filter(Boolean).join(' · ');
    if ((d.tasks || []).length) items.push(el('div', { class: 'cr-sub' }, 'Записал:'));
    items.push((d.tasks || []).map((x) => el('button', { class: 'cr-task', onclick: open(x.id) }, el('div', {}, x.title),
      meta(x) ? el('div', { class: 'cr-meta' }, meta(x)) : null)));
    if ((d.notes || []).length) items.push(el('div', { class: 'cr-sub' }, 'В хронологию:'));
    items.push((d.notes || []).map((x) => el('div', { class: 'cr-note' }, x.summary,
      project(x.project_id) ? el('span', { class: 'cr-meta' }, ' · ' + project(x.project_id).name) : null)));
  } else {
    // Предложение словами — как в «Новом», пока оно есть у веба; иначе — как его видел Claude.
    const sugTitle = (x) => (S.sugs.get(x.sid) ? sugText(S.sugs.get(x.sid)).title : x.what);
    items.push((d.decided || []).map((x) => (x.decision === 'accept'
      ? el('button', { class: 'cr-task', onclick: open(x.id) },
        el('div', {}, `П${x.n} ${DECIDED[x.kind] || 'принято'}` + (x.id ? `: ${x.title}` : '')),
        describe(x) ? el('div', { class: 'cr-meta' }, describe(x)) : null)
      : el('div', { class: 'cr-task' }, el('div', {}, `П${x.n} отклонено: ${sugTitle(x)}`),
        x.reason ? el('div', { class: 'cr-meta' }, x.reason) : null))));
    items.push((d.changed || []).map((c) => el('button', { class: 'cr-task', onclick: open(c.id) },
      el('div', {}, c.after?.title || c.title), el('div', { class: 'cr-meta' }, describe(c)))));
    if ((d.tasks || []).length) items.push(el('div', { class: 'cr-sub' }, 'Новые:'));
    items.push((d.tasks || []).map((x) => el('button', { class: 'cr-task', onclick: open(x.id) }, el('div', {}, x.title))));
    if ((d.crm || []).length) items.push(el('div', { class: 'cr-sub' }, 'Проекты, люди, хронология:'));
    items.push((d.crm || []).map((c) => el('div', { class: 'cr-note' }, c.what)));
  }
  const back = turnUndo(d);
  const dropped = (d.decided || []).some((x) => x.decision === 'reject');
  const nothing = !items.flat().length && !(d.errors || []).length && !(d.refs || []).length;
  return el('div', { class: 'cr-turn' + (t.undone ? ' undone' : '') },
    el('div', { class: 'cr-said' }, t.said),
    d.reply ? el('div', { class: 'cr-reply' }, d.reply) : nothing ? el('div', { class: 'cr-reply faint' }, 'Ничего не поменял.') : null,
    (d.refs || []).length ? el('div', { class: 'cr-refs' }, d.refs.map(refRow)) : null,
    items,
    (d.errors || []).length ? el('div', { class: 'cr-err' }, 'Не вышло: ' + d.errors.join('; ')) : null,
    t.undone ? el('div', { class: 'cr-meta cr-undone' }, 'Вернул как было')
      : back.length ? el('div', { class: 'cr-turn-acts' }, el('button', { class: 'link-btn',
        title: dropped ? 'Отклонённое остаётся отклонённым — вернуть его нельзя' : 'Вернуть всё, что сделано этой репликой',
        onclick: async () => {
          // «Вернуть» сделанного последним дела внутри возвращает и срок родителя — это делает сервер (fell).
          try { const fw = fellWords(await ops(back)); t.undone = true; render(); toast('Вернул как было' + (fw ? ' · ' + fw : '')); } catch (e) { fail(e); }
        } }, dropped ? 'Вернуть принятое' : 'Вернуть')) : null);
}

// ── Проект, человек, поиск, «Новое» ───────────────────────────
function renderProject(id) {
  const p = project(id);
  if (!p) return [head('Проект'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Нет такого проекта или он не виден.'))];
  const deals = [...S.deals.values()].filter((d) => d.project_id === id).sort((a, b) => (a.stage === 'archive') - (b.stage === 'archive') || a.name.localeCompare(b.name, 'ru'));
  const g = levelsOf('project').filter((x) => x !== 'project');
  let every = all().filter((t) => t.project_id === id && (S.showDone || isOpen(t)));
  if (S.dealFilter) every = every.filter((t) => t.deal_id === S.dealFilter);
  const items = picked(every, g, S.pick.project);
  const picker = [levelsBar(g, setLevels('project'), ['project']), pickBar(every, g, S.pick.project, setPick('project'))];
  const open = items.filter(isOpen);
  const fav = S.favs.includes(id);
  // Избранное — закладкой (значки Ковчега), залитой, когда проект в избранном.
  const star = el('button', { class: 'icon star' + (fav ? ' on' : ''), title: fav ? 'Убрать из избранного' : 'В избранное',
    'aria-label': fav ? 'Убрать из избранного' : 'В избранное', 'aria-pressed': fav ? 'true' : 'false',
    onclick: () => { S.favs = fav ? S.favs.filter((x) => x !== id) : [...S.favs, id]; LS.set('favs', S.favs); render(); } }, icon('bookmark', 18));
  const sub = [KIND[p.kind] || p.kind, plural(open.length, 'открытое', 'открытых', 'открытых')];
  const late = open.filter(isLate).length;
  if (late) sub.push(el('span', { class: 'late' }, 'просрочено: ' + late));
  if (p.aliases && p.aliases.length) sub.push('ещё зовут: ' + p.aliases.join(', '));
  const dealBox = deals.length ? el('div', { class: 'deals' }, deals.map((d) => el('div', {
    class: 'deal' + (S.dealFilter === d.id ? ' on' : ''), title: 'Показать только дела этой сделки',
    onclick: () => { S.dealFilter = S.dealFilter === d.id ? null : d.id; render(); },
  }, el('div', {}, d.name), el('div', { class: 'stage' }, STAGE[d.stage] || d.stage, d.fee_kop ? ' · fee ' + Math.round(d.fee_kop / 100).toLocaleString('ru') + ' ₽' : '')))) : null;
  if (p.kind === 'client' && crmOn()) return renderClientCard(p, { picker, g, items, sub, star });
  return [head(p.name, sub, star),
    el('div', { class: 'body' },
      quickAdd({ project_id: id, ...(S.dealFilter ? { deal_id: S.dealFilter } : {}) }, { label: `с проектом «${p.name}»` }),
      dealBox, el('div', { class: 'toolbar' }, picker, doneToggle()),
      items.length ? grouped(items, g) : el('div', { class: 'empty' }, 'Дел нет. Следующий шаг — скажи в строке Claude.'))];
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
/** Откуда он и должность; extra — ещё действия места, где стоит плашка («Ведёт проект»). */
function personPop(anchor, x, extra) {
  setTimeout(() => {
    const { clients, other } = orgChoices();
    const sel = choiceBtn(orgLabel(x.org_id)?.name || 'ни откуда', (a) => choicePop(a, { title: `Откуда ${personName(x)}`, current: x.org_id || null,
      none: 'Ни откуда', items: [...clients.map((o) => ({ v: o.id, title: o.name, note: 'клиент' })), ...other.map((o) => ({ v: o.id, title: o.name, note: 'компания' }))],
      onPick: (v) => personSet(x, { org_id: v }, `${personName(x)}: ` + (v ? 'откуда — ' + orgLabel(v)?.name : 'без компании')) }), { empty: !x.org_id });
    const role = el('input', { value: x.role || '', placeholder: 'должность: CFO, партнёр…' });
    role.addEventListener('keydown', (e) => { if (e.key === 'Enter') { closePop(); personSet(x, { role: role.value.trim() || null }, `${personName(x)}: ` + (role.value.trim() || 'без должности')); } });
    popAt(anchor, [el('div', { class: 'pop-h' }, x.name), el('div', { class: 'pop-l' }, 'Откуда он'), sel,
      el('div', { class: 'pop-l' }, 'Должность (Enter)'), role,
      el('div', { class: 'sep' }), extra || null, el('button', { onclick: () => { closePop(); go('#/h/' + x.id); } }, 'Открыть карточку', el('span', { class: 'k' }, '→'))]);
  }, 0);
}
/** Проекты человека — с какой он стороны: ведёт, в команде, от клиента, привёл. Плашками-ссылками
 *  (владелец 09.10.2026: «в людях указывать, какие проекты с ними, чтобы можно было нажимать»). */
function personDeals(pid) {
  const out = [];
  for (const d of S.deals.values()) {
    const role = d.lead_person_id === pid ? 'ведёт' : (d.team_ids || []).includes(pid) ? 'в команде'
      : (d.person_ids || []).includes(pid) ? 'от клиента' : d.source_person_id === pid ? 'привёл' : null;
    if (role) out.push({ d, role });
  }
  return out.sort((a, b) => (a.d.stage === 'archive') - (b.d.stage === 'archive') || a.d.name.localeCompare(b.d.name, 'ru'));
}
function dealChip(d, role, stop) {
  return el('a', { class: 'pchip proj' + (d.stage === 'archive' ? ' arch' : ''), href: '#/d/' + d.id, 'data-deal': d.id, title: STAGE[d.stage] + (role ? ' · ' + role : ''),
    onclick: stop ? (e) => e.stopPropagation() : null },
  el('span', { class: 'st-dot st-' + d.stage }), el('span', { class: 'pc-name' }, d.name), role ? el('span', { class: 'pc-role' }, role) : null);
}

function renderPerson(id) {
  const p = person(id);
  if (!p) return [head('Человек'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Нет такого человека или он не виден.'))];
  const open = all().filter((t) => isOpen(t));
  const his = open.filter((t) => t.person_id === id || t.requested_by === id);
  const secs = [
    ['Отбить: ждёт от меня', his.filter((t) => grpOf(t) === 'owed' || (t.requested_by === id && grpOf(t) === 'self'))],
    ['Запустить: о нём и с ним', his.filter((t) => grpOf(t) === 'self' && t.requested_by !== id)],
    ['Мониторить: жду от него', his.filter((t) => grpOf(t) === 'check')],
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
  // Пометки о человеке (аудит №10): видит только тот, кто видит карточку целиком (владелец, автор) — сервер другим их не
  // отдаёт (crm.v_people). Владельцу — под «Только тебе» с замком: команда этого не видит.
  const marks = [];
  const mark = (k, v) => { if (v) marks.push(el('span', {}, k), el('span', {}, v)); };
  const whole = personWhole(p);
  (whole && privacyOn() ? mark : row)('День рождения', p.birth_day ? `${p.birth_day} ${MONTHS[p.birth_month - 1]}` + (p.birth_year ? ' ' + p.birth_year : '') : null);
  const put = whole && privacyOn() ? mark : row;
  put('Как часто', { month: 'раз в месяц', quarter: 'раз в 2–3 месяца', year: 'раз в год', none: 'не видимся' }[p.cadence]);
  put('Ищет', p.seeks); put('Предлагает', p.offers); put('Характер', p.traits); put('Откуда', p.source); put('Заметка', p.note);
  return [head(p.name, p.short && p.short !== p.name ? ['в задачах — ' + p.short] : null),
    el('div', { class: 'body' },
      quickAdd({ person_id: id }, { label: `с человеком «${p.name}»`,
        placeholder: '«он теперь CFO в Бете», «созвонились, пришлёт модель в пятницу», «жду от него договор»' }),
      info.length ? el('div', { class: 'person-info' }, info) : null,
      marks.length ? el('div', { class: 'only-you' }, el('div', { class: 'only-you-h' }, icon('lock', 13), ' Только тебе'),
        el('div', { class: 'person-info' }, marks)) : null,
      mergeRow(p),
      crmOn() && personDeals(id).length ? el('div', { class: 'pchips projs' }, el('span', { class: 'pc-label' }, 'Зачем'),
        personDeals(id).map(({ d, role }) => dealChip(d, role))) : null,
      secs.map(([t, items]) => (items.length ? groupBox(t, items.sort(sortTasks)) : null)),
      secs.every(([, i]) => !i.length) ? el('div', { class: 'empty' }, 'Открытых дел с ним нет.') : null,
      crmOn() && !p.user_id ? personCrmBlock(p) : null)];
}

/** «Это тот же, что…» (аудит 15.10.2026, №23; телефон — PersonNamesCard): найти карточку и после листа подтверждения
 *  слить эту в неё (person.merge). Только владельцу: склеивает людей он (закон 3) — сервер держит то же правило
 *  (crm.person_merge), остальным кнопки нет. Отмены у слияния нет: дела, проекты, встречи, хронология и ждущие вопросы
 *  этой карточки переезжают, имена и номера дописываются, эта уходит в архив; поэтому лист говорит это словами, а
 *  «Наоборот» оставляет эту и сливает в неё найденную. Слитая карточка — строкой «слит в …» со ссылкой на живую. */
function mergeRow(p) {
  if (p.merged_into) {
    const live = person(p.merged_into);
    return el('div', { class: 'merge-row faint' }, 'Слит в ', live ? el('a', { class: 'link', href: '#/h/' + live.id }, live.name) : 'другую карточку',
      ' — дела и хронология теперь там.');
  }
  if (!S.me || S.me.role !== 'owner' || p.archived_at) return null;
  const ask = S.merge && S.merge.from === p.id ? person(S.merge.to) : null;
  if (ask) {
    const run = async (a, b) => {
      S.merge = null;
      try {
        const r = await op1({ op: 'person.merge', id: a.id, into: b.id });
        const skipped = (r && r.skipped) || [];
        toast(`Слито: «${a.name}» → «${b.name}»` + (skipped.length
          ? `; не дописал другим именем: ${skipped.map((x) => `«${x.alias}» (так зовут ${x.name})`).join(', ')}` : ''));
        go('#/h/' + b.id);
      } catch (e) { fail(e); render(); }
    };
    return el('div', { class: 'merge-sheet' },
      el('div', { class: 'ms-h' }, `Слить «${p.name}» в «${ask.name}»?`),
      el('div', { class: 'ms-t' }, `Дела, проекты, встречи, хронология и ждущие вопросы «${p.name}» перейдут к «${ask.name}», имена `
        + `и номера допишутся, «${p.name}» уйдёт в архив. Отменить нельзя.`),
      el('div', { class: 'ms-acts' },
        el('button', { class: 'btn small ok', onclick: () => run(p, ask) }, `Слить в «${ask.name}»`),
        el('button', { class: 'btn small', onclick: () => run(ask, p) }, `Наоборот: оставить «${p.name}»`),
        el('button', { class: 'btn small', onclick: () => { S.merge = null; render(); } }, 'Не сливать')));
  }
  // С завода — похожие: тот же кусок имени (фамилия, имя) в имени, коротком или другом имени.
  const words = norm(p.name).split(/\s+/).filter((w) => w.length >= 3);
  const near = () => livePeople().filter((x) => x.id !== p.id && !x.merged_into
    && words.some((w) => [x.name, x.short, ...(x.aliases || [])].some((k) => k && norm(k).includes(w)))).slice(0, 14);
  return el('div', { class: 'merge-row' }, el('button', { class: 'chip-btn', title: 'Это одна и та же карточка человека дважды — слить',
    onclick: (e) => personSearchPop(e.currentTarget, { title: `«${p.name}» — это тот же, что…`, base: near, exclude: [p.id],
      empty: 'Похожих не нашёл — набери имя', pick: (to) => { S.merge = { from: p.id, to }; render(); } }) }, 'Это тот же, что…'));
}

/** Общий поиск — сразу, по мере набора: клиенты и проекты справочника, проекты клиентов (сделки),
 *  люди, дела. Всё уже в памяти — считается тут, без запросов. Каждое слово запроса должно найтись. */
const SEARCH_SHOWN = 8;
function renderSearch(q) {
  const words = norm(q).split(/\s+/).filter(Boolean);
  const hit = (...parts) => words.length > 0 && words.every((w) => norm(parts.flat().filter(Boolean).join(' ')).includes(w));
  const projs = [...S.projects.values()].filter((p) => hit(p.name, p.aliases || [], p.note, renamedOf(p.id)))
    .sort((a, b) => !!a.archived_at - !!b.archived_at || a.name.localeCompare(b.name, 'ru'));
  const deals = [...S.deals.values()].filter((d) => hit(d.name, project(d.project_id)?.name, d.deal_type, renamedOf(d.id)))
    .sort((a, b) => (a.stage === 'archive') - (b.stage === 'archive') || a.name.localeCompare(b.name, 'ru'));
  const ppl = livePeople().filter((x) => !x.merged_into && hit(x.name, x.short, x.aliases || [], x.role, orgLabel(x.org_id)?.name))
    .sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  const tasks = all().filter((t) => (S.showDone || isOpen(t)) && hit(t.title, t.notes, project(t.project_id)?.name,
    personName(person(t.person_id)), t.deal_id && S.deals.get(t.deal_id)?.name, t.labels || [], renamedOf(t.id),
    t.why_id && S.tasks.get(t.why_id)?.title, t.deal_id && renamedOf(t.deal_id)));
  const openBy = (pid) => all().filter((t) => t.project_id === pid && isOpen(t)).length;
  const part = (title, list, row) => (list.length ? el('div', { class: 'group' }, el('h2', {}, title, el('span', { class: 'n' }, list.length)),
    list.slice(0, SEARCH_SHOWN).map(row),
    list.length > SEARCH_SHOWN ? el('div', { class: 'faint pad' }, `и ещё ${list.length - SEARCH_SHOWN} — уточни запрос`) : null) : null);
  const box = el('input', { id: 'main-search', class: 'main-search inline-search', type: 'search', placeholder: 'Что найти: клиент, проект, человек, дело', value: q });
  box.addEventListener('input', () => liveSearch(box.value));
  const total = projs.length + deals.length + ppl.length + tasks.length;
  return [head(q ? 'Поиск: ' + q : 'Поиск', words.length ? [plural(total, 'находка', 'находки', 'находок'),
    deals.length ? deals.length + ' «Зачем»' : null, ppl.length ? plural(ppl.length, 'человек', 'человека', 'человек') : null,
    plural(tasks.length, 'дело', 'дела', 'дел')] : ['набирай в поле «Поиск» слева — результаты появляются сразу']),
  el('div', { class: 'body' }, box,
    part('Клиенты и разделы', projs, (p) => el('div', { class: 'crow', onclick: () => go('#/p/' + p.id) }, dot(p.id),
      el('div', { class: 'main' }, el('div', { class: 'title' }, p.name, p.archived_at ? el('span', { class: 'faint' }, ' · архив') : null),
        el('div', { class: 'chips' }, el('span', {}, KIND[p.kind] || p.kind), openBy(p.id) ? el('span', {}, plural(openBy(p.id), 'открытое дело', 'открытых дела', 'открытых дел')) : null)))),
    part('«Зачем» клиентов', deals, (d) => el('div', { class: 'crow', onclick: () => openDeal(d.id) }, el('span', { class: 'st-dot st-' + d.stage }),
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

/** Что предлагает автоматика — словами: завести, закрыть или поправить дело. */
function sugText(s) {
  const p = s.payload || {};
  // Новый человек (dela_0019): кто он и откуда; что спросить — вопросом автоматики (askWhy), ответ — кнопками.
  if (s.kind === 'person') {
    const d = p.person || {};
    const was = p.person_id ? S.people.get(p.person_id) : null;
    return { title: p.title || d.name || '', hint: [p.where, was && !p.rename ? 'похоже: ' + was.name : null, d.role].filter(Boolean).join(' · ') };
  }
  if (s.kind === 'create') {
    return { title: p.title || s.quote || '',
      hint: [grpWord(p), p.project_name, p.person_name, p.due_date && 'срок ' + D.ddmm(p.due_date)].filter(Boolean).join(' · ') };
  }
  const t = S.tasks.get(s.task_id);
  const ref = t ? t.title : 'дело не видно';
  if (s.kind === 'close') return { title: 'Закрыть: ' + ref, hint: t?.project_name || '' };
  if (s.kind === 'assign') return { title: 'Взять себе: ' + ref, hint: t?.project_name || '' };
  // Вопрос разборщика Круга: какое направление у дела (выбор — переключателями в самой карточке «Подскажи»).
  if (s.source === 'krug') return { title: ref, hint: p.goal_path ? 'цель: ' + p.goal_path : t?.project_name || '' };
  // Уточнение: что поменяется в деле; подробности (note) при принятии лягут комментарием.
  const what = [p.title ? `название: «${p.title}»` : null,
    p.due_date ? 'срок ' + (t?.due_date ? D.ddmm(t.due_date) + ' → ' : '') + D.ddmm(p.due_date) : null,
    p.ball ? grpWord(p) + (p.person_name ? ' ' + p.person_name : '') : (p.person_name ? 'человек: ' + p.person_name : null)]
    .filter(Boolean).join(' · ');
  return { title: 'Уточнить: ' + ref, hint: what, add: p.note || null };
}
/** Что поменяло уточнение, принятое само: было → стало (result.was — как было, result — как стало). */
function autoWords(s) {
  const r = s.result || {}, was = r.was || {}, p = s.payload || {};
  return [
    'title' in was ? `название: «${r.title}»` : null,
    'due_date' in was ? 'срок ' + (was.due_date ? D.ddmm(was.due_date) + ' → ' : '') + (r.due_date ? D.ddmm(r.due_date) : 'без срока') : null,
    'ball' in was || 'owed' in was || 'person_id' in was ? grpWord(r) + (p.person_name ? ' ' + p.person_name : '') : null,
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
  krug: ['circle', 'var(--plan)', 'разборщик Круга'], contacts: ['phone', 'var(--plan)', 'телефонная книжка'],
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
    return el('span', { class: 'src-mark', title: `поставил(а) ${who ? personName(who) : t.created_by} · ${whenWords(t.created_at)}` },
      who ? avatar(who, 'tiny') : icon('user', 16));
  }
  const [ic, col, word] = origin(t.source);
  return el('span', { class: 'src-mark', title: [word + (s && s.batch_title ? ': ' + s.batch_title : ''), whenWords(t.created_at)].join(' · ') },
    tint(icon(ic, 16), col));
}
/** Значок источника у предложения: встреча — ссылкой на неё, цитата — подсказкой. */
function sugMark(s) {
  const [ic, col, word] = origin(s.source);
  const title = [s.batch_title || word, s.quote ? '«' + s.quote.slice(0, 200) + '»' : null].filter(Boolean).join('\n');
  // Ссылка — встреча: у дела её адрес в source_ref, у вопроса о человеке со встречи (№25) — в payload.speaker.url.
  const href = [s.source_ref, ((s.payload || {}).speaker || {}).url].find((u) => /^https:\/\//.test(u || ''));
  return href
    ? el('a', { class: 'src-mark', href, target: '_blank', rel: 'noopener noreferrer', title, onclick: (e) => e.stopPropagation() }, tint(icon(ic, 16), col))
    : el('span', { class: 'src-mark', title }, tint(icon(ic, 16), col));
}
/** Откуда дело — в карточке, сразу под названием: наговорка дословно или встреча и Telegram с цитатой (origin у
 *  /api/task). Пока подробности не пришли — хотя бы откуда и когда (источник есть у самого дела). */
function originLine(t) {
  const o = extras.get(t.id)?.origin;
  if (!o) {
    const [ic, , word] = origin(t.source);
    return el('div', { class: 'origin' }, icon(ic, 18), el('div', {}, el('span', { class: 'o-h' }, word[0].toUpperCase() + word.slice(1)
      + (t.created_at ? ' · ' + whenWords(t.created_at) : ''))));
  }
  const [ic, , word] = origin(o.kind === 'dictation' ? 'voice' : o.source);
  const top = o.kind === 'dictation' ? 'Наговорка · ' + whenWords(o.at) : [o.batch_title || word, o.auto ? 'поставлено само' : null].filter(Boolean).join(' · ');
  const text = o.kind === 'dictation' ? o.text : o.quote;
  return el('div', { class: 'origin' }, icon(ic, 18),
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
    // Слова из переписки и встреч — только владельцу (аудит №8): комментарий приватный, команда дела его не видит.
    const priv = privacyOn() && PRIVATE_SOURCES.includes(s.source);
    await ops([{ op: 'comment.add', comment: { task_id: t.id, text: `${s.batch_title || SUG_FROM[s.source] || 'Автоматика'}: ${said}`, ...(priv ? { private: true } : {}) } },
      { op: 'suggestion.decide', id: s.id, decision: 'reject', reason: `уже есть: ${t.title}` }]);
    toast(`Срослось с «${t.title}»: слова — комментарием к делу`);
    render();
  } catch (e) { fail(e); render(); }
}

/** Кто назван именем — тем же правилом, что сервер (crm.match_name, dela_0020; аудит 15.10.2026, №1): живые карточки,
 *  ярусами — у людей сначала своё имя или короткое имя, потом другое имя (у проектов имя и другие имена — один ярус).
 *  Один на ярусе — он (hit); двое и больше — не узнан, many — кто они. До того веб брал первого попавшегося
 *  (list.find) и молчал там, где сервер не узнавал. */
const PERSON_TIERS = [(x) => [x.name, x.short], (x) => x.aliases || []];
const PROJECT_TIERS = [(x) => [x.name, ...(x.aliases || [])]];
function byName(list, name, tiers) {
  const n = norm(name).replace(/\s+/g, ' ');
  if (!n) return { hit: null, many: [] };
  const live = list.filter((x) => !x.archived_at && !x.merged_into);
  for (const tier of tiers) {
    const got = live.filter((x) => tier(x).some((a) => a && norm(a).replace(/\s+/g, ' ') === n));
    if (got.length === 1) return { hit: got[0], many: [] };
    if (got.length > 1) return { hit: null, many: got };
  }
  return { hit: null, many: [] };
}
const orWords = (names) => (names.length > 1 ? names.slice(0, -1).join(', ') + ' или ' + names[names.length - 1] : names[0] || '');
/** Часть held про имя (проект или человека), которое теперь узнаётся однозначно по справочнику (byName): записано
 *  сервером при создании предложения, а справочник с тех пор пополнился — часть устарела. */
function heldResolved(p, x) {
  if (p.project_name && !p.project_id && x.includes(`«${p.project_name}»`) && /проект/.test(x))
    return !!byName([...S.projects.values()], p.project_name, PROJECT_TIERS).hit;
  if (p.person_name && !p.person_id && x.includes(`«${p.person_name}»`) && !/проект/.test(x))
    return !!byName([...S.people.values()], p.person_name, PERSON_TIERS).hit;
  return false;
}
/** Почему предложение ждёт человека: вопрос самой автоматики или что не узналось по имени. */
function askWhy(s) {
  const p = s.payload || {};
  if (p.ask) return p.ask;
  if (typeof p.held === 'string') {
    // Сервер уже посмотрел имена (held есть, хоть пустой): его вердикт сильнее своего точного сравнения — «Женя Соколов»
    // он узнаёт уверенным «кто это», а byName только точным именем. Спрашиваем то, что не узнал он.
    // Только пока имя и сейчас не узнаётся: владелец мог завести недостающий проект или человека после того, как
    // сервер записал held, — тогда вопрос снят (при «Да» сервер имя уже узнает; проверка 15.10.2026).
    const parts = p.held.split('; ').filter((x) => !heldResolved(p, x));
    if (p.project_name && !p.project_id && parts.some((x) => x.includes(`«${p.project_name}»`) && /проект/.test(x)))
      return parts.find((x) => x.startsWith('не один проект')) ? `Какой проект — «${p.project_name}»?` : `Не знаю проект «${p.project_name}» — куда?`;
    if (p.person_name && !p.person_id && parts.some((x) => x.includes(`«${p.person_name}»`) && !/проект/.test(x)))
      return `Кто это — «${p.person_name}»?`;
    return null;
  }
  if (p.project_name && !p.project_id) {
    const r = byName([...S.projects.values()], p.project_name, PROJECT_TIERS);
    if (r.many.length) return `Какой проект — «${p.project_name}»: ${orWords(r.many.slice(0, 3).map((x) => x.name))}?`;
    if (!r.hit) return `Не знаю проект «${p.project_name}» — куда?`;
  }
  if (p.person_name && !p.person_id) {
    const r = byName([...S.people.values()], p.person_name, PERSON_TIERS);
    if (r.many.length) return `Кто это — «${p.person_name}»: ${orWords(r.many.slice(0, 3).map((x) => x.name))}?`;
    if (!r.hit) return `Кто это — «${p.person_name}»?`;
  }
  return null;
}
/** Почему не встало само — строкой сервера (payload.held: причины через «; »), без того, что уже сказал вопрос: у вопроса
 *  сервера «Кто это — X или Y?» (pick) часть про это имя и есть вопрос. */
function heldLine(s, q) {
  const p = s.payload || {};
  const nq = norm(q || '');
  // Часть про имя, о котором уже спрашивает вопрос («Кто это — «X»?» и «не знаю человека «X»»), — повтор: не пишем.
  const asked = (x) => [p.person_name, p.project_name].some((n) => n && x.includes(`«${n}»`) && nq.includes(norm(`«${n}»`)));
  const left = String(p.held || '').split('; ').map((x) => x.trim()).filter((x) => x && !nq.includes(norm(x))
    && !(p.pick && p.person_name && x.includes(`«${p.person_name}»`)) && !asked(x) && !heldResolved(p, x)
    // вопрос о человеке со встречи («Это A или B?», а похожих больше двух — «кто это: A, B или C?», №25) уже называет
    // похожих — строка о них повторила бы его
    && !(s.kind === 'person' && p.ask && x.startsWith('похожие уже есть')));
  return left.length ? 'Ждёт: ' + left.join('; ') : null;
}
const ASK_YES = { create: 'Поставить', close: 'Закрыть', update: 'Да, согласен', assign: 'Взять', person: 'Завести' };
/** Варианты ответа кнопками (13.10.2026): у вопроса автоматики — её собственные ответы («Родительство» / «Комфортный быт»),
 *  а не «Да / Не надо»; сервер без флага options их не примет — тогда как раньше. */
const sugOptions = (s) => (((S.me && S.me.features) || []).includes('options') && Array.isArray((s.payload || {}).options)
  && s.payload.options.length >= 2 ? s.payload.options : null);
async function answer(s, i) {
  const o = s.payload.options[i];
  try { await ops([{ op: 'suggestion.decide', id: s.id, option: i }]); toast((o.reject ? 'Не надо: ' : 'Ответ: ') + o.label); render(); } catch (e) { fail(e); render(); }
}
/** «Сам допишу» у уточнения: своё слово — в заметку дела, а предложение уходит с этим ответом (урок автоматике). Пусто —
 *  просто «не надо». */
async function selfWrite(s, text) {
  const t = S.tasks.get(s.task_id);
  const list = [];
  if (text && t) list.push({ op: 'task.set', id: t.id, set: { notes: ((t.notes || '').trimEnd() ? t.notes.trimEnd() + '\n' : '') + text }, was: { notes: t.notes ?? null } });
  list.push({ op: 'suggestion.decide', id: s.id, decision: 'reject', reason: text ? 'сам: ' + text : null });
  try { await ops(list); delete S.selfText[s.id]; S.selfOpen = null; toast(text ? 'Дописал к делу' : 'Не надо'); render(); } catch (e) { fail(e); render(); }
}
function askItem(s) {
  const { title, hint, add } = sugText(s);
  S.sugOrder.push(s.id);
  const twin = s.source === 'krug' ? null : twinOf(s);
  const q = askWhy(s);
  const held = heldLine(s, q);
  // Свои ответы кнопками (13.10.2026) — есть варианты, нет ни «Да», ни переключателей.
  const opts = sugOptions(s);
  // «Уточнить» (не Круг): «Да, согласен» или «Сам допишу» — поле, его слово ляжет в заметку дела.
  const upd = !opts && s.kind === 'update' && s.source !== 'krug';
  const writing = upd && S.selfOpen === s.id;
  let selfBox = null;
  if (writing) {
    const area = el('textarea', { id: 'self-' + s.id, class: 'self-in', rows: 2, placeholder: 'Как на самом деле — допишу к делу' }, S.selfText[s.id] || '');
    area.addEventListener('input', () => { S.selfText[s.id] = area.value; });
    area.addEventListener('keydown', (e) => { e.stopPropagation(); if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); selfWrite(s, area.value.trim()); }
      if (e.key === 'Escape') { S.selfOpen = null; render(); } });
    selfBox = el('div', { class: 'self-box' }, area, el('div', { class: 'self-acts' },
      el('button', { class: 'btn small ok', onclick: () => selfWrite(s, area.value.trim()) }, 'Готово'),
      el('span', { class: 'faint' }, 'пусто — значит, не надо')));
  }
  // Разборщик Круга не уверен: предложенные направления горят, щелчок — поправить; «Да» ставит выбранное.
  const kr = s.source === 'krug' && krugOn() && !opts ? (S.kpick[s.id] || (s.payload || {}).directions || []) : null;
  const krPick = kr ? dirChips({ directions: kr }, (next) => { S.kpick[s.id] = next; render(); }) : null;
  // Поправил направление — значит, не та цель: «Зачем» из предложения не ставим, дело ляжет на направление.
  const krSet = kr && JSON.stringify(kr) !== JSON.stringify((s.payload || {}).directions || []) ? { directions: kr, why_id: null } : undefined;
  // Карточка предложения как в Делах · Новое (screens/07): сверху источник — кремовый значок и пачка.
  return el('div', { class: 'sug' + (s.kind !== 'create' ? ' ' + s.kind : '') },
    el('div', { class: 'src-line' }, sugMark(s), el('span', {}, s.batch_title || origin(s.source)[2])),
    el('div', { class: 'main' }, el('div', {}, el('span', { class: 'sug-n', title: 'Номер для Claude: «П' + S.sugOrder.length + ' прими»' }, 'П' + S.sugOrder.length), title),
      q ? el('div', { class: 'q' }, q) : null,
      held ? el('div', { class: 'hint held' }, held) : null,
      hint ? el('div', { class: 'hint' }, hint) : null,
      krPick,
      add ? el('div', { class: 'add' }, add) : null,
      s.quote && s.quote !== title ? el('div', { class: 'hint quote' }, '«' + s.quote.slice(0, 160) + (s.quote.length > 160 ? '…' : '') + '»') : null,
      twin ? el('div', { class: 'twin' }, icon('link', 12), 'похоже, это уже есть: ',
        el('a', { class: 'link', href: '#', onclick: (e) => { e.preventDefault(); openCard(twin.id); } }, twin.title),
        el('button', { class: 'chip-btn', title: 'Не заводить второе: слова — комментарием к делу', onclick: () => mergeInto(s, twin) }, 'Это оно')) : null),
    writing ? selfBox : null,
    el('div', { class: 'acts' + (opts ? ' opts' : '') }, opts
      ? [opts.map((o, i) => el('button', { class: 'btn small' + (o.reject ? ' bad' : i === 0 ? ' ok' : ''), title: 'Ответить: ' + o.label,
        onclick: () => answer(s, i) }, o.label)),
      opts.some((o) => o.reject) ? null : el('button', { class: 'link-btn quiet', onclick: () => decide([s.id], 'reject', null) }, 'Не надо')]
      : upd ? [el('button', { class: 'btn small ok', onclick: () => decide([s.id], 'accept') }, 'Да, согласен'),
        el('button', { class: 'btn small' + (writing ? ' on' : ''), onclick: () => { S.selfOpen = writing ? null : s.id; render(); if (!writing) setTimeout(() => document.getElementById('self-' + s.id)?.focus()); } }, 'Сам допишу')]
        : [el('button', { class: 'btn small ok', disabled: kr && !kr.length && !(s.payload || {}).why_id, onclick: () => { delete S.kpick[s.id]; decide([s.id], 'accept', null, krSet); } },
          kr ? 'Да' : ASK_YES[s.kind] || 'Да'),
        s.kind === 'create' ? el('button', { class: 'btn small', onclick: () => { S.draft = s; S.cardId = null; render(); } }, 'Поправить') : null,
        s.kind === 'person' ? el('button', { class: 'btn small bad', title: 'Больше не спрошу про этого человека', onclick: () => decide([s.id], 'reject', 'не для CRM') }, 'Не для CRM')
          : el('button', { class: 'btn small bad', onclick: () => { const r = prompt(s.kind === 'create' ? 'Почему не дело? (можно пусто)' : 'Почему нет? (можно пусто)'); if (r !== null) decide([s.id], 'reject', r || null); } }, 'Не надо')]));
}

/** Убрать дело из «Добавлено автоматически» (владелец 13.10.2026): только из списка — дело живёт дальше, удаляют его из
 *  карточки. Помним у человека на сервере (settings.feed_hidden): другой компьютер покажет то же; старше списка — забываем. */
const feedHidden = () => new Set(((S.me && S.me.settings) || {}).feed_hidden || []);
async function feedHide(t, back) {
  const keep = (id) => { const x = S.tasks.get(id); return x && Date.now() - Date.parse(x.created_at) < (FEED_DAYS + 1) * 86400e3; };
  const cur = [...feedHidden()].filter(keep);
  const next = back ? cur.filter((id) => id !== t.id) : [...cur.filter((id) => id !== t.id), t.id];
  try {
    await op1({ op: 'user.settings', settings: { feed_hidden: next } });
    S.me.settings = { ...(S.me.settings || {}), feed_hidden: next };
    render();
    if (!back) toast('Убрал из списка: ' + t.title, () => feedHide(t, true));
  } catch (e) { fail(e); }
}
/** «Добавлено автоматически» (до 13.10.2026 — «Поставил»): новые дела, свежие сверху, по дням; сделанные не показываем. */
function feedBlock(list, from) {
  const days = new Map();
  for (const t of list.slice(0, FEED_MAX)) {
    const d = MSK_DAY(t.created_at).day;
    if (!days.has(d)) days.set(d, []);
    days.get(d).push(t);
  }
  const dayName = (d) => { const n = D.diff(S.today, d); return n === 0 ? 'Сегодня' : n === 1 ? 'Вчера' : D.long(d); };
  return el('div', { class: 'group feed' },
    el('h2', {}, icon('inbox', 17), 'Добавлено автоматически', el('span', { class: 'n' }, list.length),
      el('span', { class: 'gsub' }, 'откуда — значок справа, что сказано — в карточке; крестик — убрать из списка')),
    [...days].map(([d, items]) => [el('div', { class: 'feed-day' }, dayName(d)),
      items.map((t) => taskRow(t, null, null, { compact: true, mark: el('span', { class: 'feed-end' }, originMark(t, from.get(t.id)), el('button', { class: 'feed-x icon-btn',
        title: 'Убрать из этого списка (дело останется)', 'aria-label': 'Убрать из списка', onclick: (e) => { e.stopPropagation(); feedHide(t); } }, icon('x', 14))) }))]),
    list.length > FEED_MAX ? el('a', { class: 'faint pad link', href: '#/all' }, `и ещё ${list.length - FEED_MAX} — во «Всех делах»`) : null);
}

/** Закрыть и уточнить автоматика может сама — свёрнуто одной строкой; «Понятно» убирает, «Вернуть» — как было. */
function autoBlock(auto, batch) {
  if (!auto.length) return null;
  const open = !!batch || S.autoOpen;
  const seen = async (ids) => { try { await op1({ op: 'suggestion.seen', ids }); render(); } catch (e) { fail(e); } };
  const nc = auto.filter((s) => s.kind === 'close').length;
  const np = auto.filter((s) => s.kind === 'person').length;
  const nadd = auto.filter((s) => s.kind === 'person' && (s.result || {}).created === false).length; // дописал в живую
  const what = [nc ? `закрыл ${nc}` : null, auto.length - nc - np ? `уточнил ${auto.length - nc - np}` : null,
    np - nadd ? `завёл людей: ${np - nadd}` : null, nadd ? `дописал в карточки: ${nadd}` : null].filter(Boolean).join(', ');
  return el('div', { class: 'group auto-done' + (open ? '' : ' folded') },
    el('h2', {}, icon('check', 17), 'Сделано само', el('span', { class: 'n' }, auto.length),
      el('span', { class: 'gsub' }, what + ' ' + autoFrom(auto)),
      el('span', { class: 'act' },
        open && auto.length > 1 ? el('button', { class: 'chip-btn', title: 'Видел, согласен — убрать отсюда', onclick: () => seen(auto.map((s) => s.id)) }, 'Понятно, все') : null, ' ',
        batch ? null : el('button', { class: 'chip-btn', onclick: () => { S.autoOpen = !S.autoOpen; render(); } }, open ? 'Свернуть' : 'Показать'))),
    open ? auto.map((s) => autoItem(s, seen)) : null);
}
/** Счётчик откатов автоматики за неделю (аудит 15.10.2026, №29) — строкой под «Сделано само»: что поставлено, закрыто,
 *  уточнено и заведено само и сколько из этого ты отменил или вернул (в скобках), как ответил на её вопросы. Без него
 *  через месяц не ответить, «не слишком ли смело автоматика ставит и закрывает». Считает сервер (вид week,
 *  store.automation — без журнала); старый сервер поля не знает — строки нет. Частые «нет» — подсказкой. */
function autoWeekLine() {
  const v = crmGet('/api/view/week', 60000);
  const b = v && v.automation && v.automation.all;
  if (!b) return null;
  const ans = [['yes', 'да'], ['edited', 'с правкой'], ['no', 'нет'], ['expired', 'погасло']].filter(([k]) => b[k]).map(([k, w]) => `${w} ${b[k]}`);
  const parts = [b.created ? `поставлено само ${b.created} (отменил ${b.created_undone})` : null,
    b.closed ? `закрыто ${b.closed} (вернул ${b.closed_undone})` : null,
    b.updated ? `уточнено ${b.updated} (${b.updated_undone})` : null,
    b.people ? `людей ${b.people} (${b.people_undone})` : null,
    b.asked ? `вопросов ${b.asked}: ${ans.join(', ')}` : null].filter(Boolean);
  if (!parts.length) return null;
  const no = (b.no_reasons || []).map((x) => `«${x.reason}» — ${x.n}`).join('; ');
  return el('div', { class: 'auto-week', title: 'В скобках — сколько из сделанного само ты отменил или вернул' + (no ? '. Частые «нет»: ' + no : '') },
    'за неделю: ' + parts.join(' · '));
}
/** Откуда сделанное само — словами под заголовком: «по встречам и Telegram», «из Telegram и телефонной книжки». С
 *  15.10.2026 людей заводят и встречи (аудит №25): прежнее «люди — только из Telegram и телефона» стало неправдой. */
function autoFrom(auto) {
  const has = (src) => auto.some((s) => s.source === src || (src === 'telegram' && s.source === 'userbot'));
  const words = [has('meeting') ? 'встречам' : null, has('telegram') ? 'Telegram' : null, has('contacts') ? 'телефонной книжке' : null]
    .filter(Boolean);
  return words.length ? 'по ' + orWords(words).replace(' или ', ' и ') : '';
}
/** Человек, заведённый сам (dela_0019): «Понятно» — согласен; «Вернуть» — карточку в архив (и компанию, если её завели
 *  ради него и больше в ней никого нет). Строки не удаляются (правило 8) — архив и есть «как было». */
function autoPerson(s, seen) {
  const r = s.result || {};
  const p = S.people.get(r.person_id);
  // created: false — сервер не заводил карточку, а дописал номер, Telegram или почту в уже живую (ключ нашёлся у
  // неё): «Вернуть» с архивом убрал бы настоящую карточку, и имя со встреч потом не завелось бы («уже убран»).
  const made = r.created !== false;
  const live = made && p && !p.archived_at;
  const undo = async () => {
    const now = new Date().toISOString();
    const list = [{ op: 'person.set', id: p.id, set: { archived_at: now } }];
    const others = [...S.people.values()].some((x) => x.id !== p.id && x.org_id === r.org_id && !x.archived_at);
    if (r.org_created && r.org_id && !others) list.push({ op: 'org.set', id: r.org_id, set: { archived_at: now } });
    list.push({ op: 'suggestion.seen', ids: [s.id] });
    try { await ops(list); toast('Убрал в архив: ' + p.name); render(); } catch (e) { fail(e); }
  };
  return el('div', { class: 'sug auto' },
    el('div', { class: 'main' }, el('div', {}, made ? 'Новый человек: ' : 'Дописано в карточку: ',
      p ? el('a', { class: 'link', href: '#/h/' + p.id }, p.name) : ((s.payload || {}).title || 'карточка не видна')),
      el('div', { class: 'hint' }, [(s.payload || {}).where, s.quote ? '«' + s.quote.slice(0, 160) + '»' : null].filter(Boolean).join(' · '))),
    el('div', { class: 'acts' },
      el('button', { class: 'btn small ok', title: 'Согласен — убрать отсюда', onclick: () => seen([s.id]) }, 'Понятно'),
      live ? el('button', { class: 'btn small', title: 'Не нужен в CRM — карточку в архив', onclick: undo }, 'Вернуть') : null),
    sugMark(s));
}
function autoItem(s, seen) {
  if (s.kind === 'person') return autoPerson(s, seen);
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
    try {
      const fw = fellWords(await ops(list)); // закрытое само было последним внутри — родителю вернулся срок (fell)
      toast((s.kind === 'close' ? 'Вернул в работу: ' : 'Вернул как было: ') + (was.title || t.title) + (fw ? ' · ' + fw : '')); render();
    } catch (e) { fail(e); }
  };
  const words = s.kind === 'update' ? autoWords(s) : '';
  return el('div', { class: 'sug auto' },
    el('div', { class: 'main' }, el('div', {}, (s.kind === 'close' ? 'Закрыто: ' : 'Уточнено: ') + (t ? t.title : 'дело не видно')),
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
/** Вопросы «Подскажи» — свежие сверху: по дате встречи, потом по появлению. */
const sortedAsks = (list) => [...list].sort((a, b) => sugAt(b).localeCompare(sugAt(a)) || b.created_at.localeCompare(a.created_at));
/** «Подскажи» — панель вопросов с кнопками; одна на «Новое» и на главную «Сейчас» (там она зовётся «Новое»). */
function asksBox(asks, { title = 'Подскажи', sub = 'без тебя не поставлю', n = true } = {}) {
  const ids = asks.map((s) => s.id);
  return el('div', { class: 'group asks' },
    el('h2', {}, tint(icon('help', 17), 'var(--warn)'), title, n ? el('span', { class: 'n' }, asks.length) : null,
      el('span', { class: 'gsub' }, sub),
      ids.length > 1 && !asks.some(sugOptions) ? el('span', { class: 'act' },
        el('button', { class: 'chip-btn', onclick: () => decide(ids, 'accept') }, 'Поставить все'), ' ',
        el('button', { class: 'chip-btn', onclick: () => decide(ids, 'reject') }, 'Не надо все')) : null),
    asks.map(askItem));
}
function renderNew(batch) {
  const asks = sortedAsks(pendingSugs().filter((s) => !batch || s.batch_ref === batch));
  const from = sugByTask();
  const since = Date.now() - FEED_DAYS * 86400e3;
  const feed = (batch ? all().filter((t) => isOpen(t) && from.get(t.id)?.batch_ref === batch)
    : openMine().filter((t) => Date.parse(t.created_at) > since && !feedHidden().has(t.id))).sort((a, b) => b.created_at.localeCompare(a.created_at));
  const auto = batch ? autoDone(365).filter((s) => s.batch_ref === batch) : autoDone(7);
  const box = el('div', { class: 'body' });
  if (batch) box.append(el('div', { class: 'toolbar' }, el('a', { class: 'chip-btn', href: '#/new' }, 'Всё «Новое»')));
  // Строка Claude: «первое поставь, срок пятница; второе не надо» — П-номера ниже те же, что видит он.
  box.append(quickAdd({}, { label: 'с новым', placeholder: asks.length
    ? '«П1 поставь, срок пятница; П2 не надо — делает Ольга»'
    : '«последние три — в Альфу», «полку — на субботу»' }));
  if (asks.length) box.append(asksBox(asks));
  if (feed.length) box.append(feedBlock(feed, from));
  const done = autoBlock(auto, batch);
  if (done) box.append(done);
  const week = batch ? null : autoWeekLine();
  if (week) box.append(week);
  if (!asks.length && !feed.length && !auto.length) {
    box.append(el('div', { class: 'empty' }, batch ? 'Эта пачка уже разобрана.' : 'Пусто. Новое появится, когда наговоришь или придёт со встречи и из Telegram.'));
  }
  return [head('Новое', [asks.length ? `подскажи: ${asks.length}` : 'вопросов нет',
    feed.length ? (batch ? `добавлено: ${feed.length}` : `добавлено само за ${FEED_DAYS} дня: ${feed.length}`) : null]), box];
}

// ── Настройки: Claude (у человека на сервере), голос (на этом устройстве), траты (владельцу) ──
const voiceAuto = () => LS.get('voiceAuto', true);
const EFFORT_INFO = {
  low: ['Быстро', 'обычные правки: сроки, люди, проекты'],
  medium: ['Вдумчиво', 'много дел сразу или сложная команда'],
  high: ['Глубоко', 'редко нужно: заметно дольше и дороже'],
};
/** «claude-opus-5-5» и «low» — «Opus 5.5, быстро». */
const modelWord = (r) => r.model.replace('claude-', '').replace(/-(\d)-(\d)$/, ' $1.$2').replace(/^./, (c) => c.toUpperCase())
  + ', ' + ((EFFORT_INFO[r.effort] || [r.effort])[0] || r.effort).toLowerCase();
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
  const kinds = { ask: 'строка и разговор', parse: 'разбор надиктовки', task: 'карточка дела', when: '«Когда»', krug: 'разборщик Круга', meetings: 'разборщик встреч', zachem: '«Зачем»' };
  return [head('Настройки', ['Claude в Делах, голос и траты' + (v.ui ? ', вид и меню Ковчега' : '')]),
    el('div', { class: 'body settings' },
      el('div', { class: 'group' }, el('h2', {}, icon('chat', 17), 'Claude в Делах'),
        el('div', { class: 'hint-line' }, 'Одна строка вверху каждой страницы: спросить, поручить, надиктовать новые дела — разговор идёт прямо под ней. Claude видит, что на экране и в окне, что выбрано галочками и открыто в карточке, — и все остальные открытые дела коротко.'),
        v.claude ? null : el('div', { class: 'cr-err' }, 'Claude на сервере не настроен — нет ключа.'),
        el('div', { class: 'hint-line' }, Object.entries({ talk: 'Строка и разговор', task: 'Карточка дела и «Кстати»', when: '«Когда», что не понял свой разбор' })
          .filter(([k]) => (v.routes || {})[k]).map(([k, w]) => `${w}: ${modelWord(v.routes[k])}`).join(' · ')),
        el('div', { class: 'hint-line faint' }, 'Модель и глубину выбирает владелец — одной записью Свода models.dela.')),
      el('div', { class: 'group' }, el('h2', {}, icon('mic', 17), 'Голос', el('span', { class: 'gsub' }, 'на этом устройстве')),
        el('div', { class: 'hint-line' }, 'Нажми микрофон — говори сколько нужно, хоть с паузами — нажми ещё раз.'),
        el('label', { class: 'set-check' }, auto, 'Второе нажатие сразу отдаёт Claude', el('span', { class: 'faint' }, ' — иначе текст ждёт в поле стрелку справа или Enter')),
        el('div', { class: 'hint-line' }, 'Речь распознаёт браузер (Chrome — и на телефоне). На компьютере можно диктовать и Wispr Flow прямо в поле.')),
      el('div', { class: 'group' }, el('h2', {}, icon('flame', 17), 'Путь: очки, серия, уровень'),
        el('div', { class: 'hint-line' }, 'Очки — только за доведённое: закрыл, отменил ненужное, разобрал «Новое». Карточка пути — вверху боковой панели, подробно — «Статистика».'),
        choices('game_theme', { torah: ['Тора', '42 стоянки странствий, серия — манна; суббота не рвёт'], greek: ['Греция', 'подвиги Геракла и путь Одиссея, серия — огонь Прометея'] }, s.game_theme || 'torah')),
      cost ? el('div', { class: 'group' }, el('h2', {}, icon('coin', 17), 'Траты Claude в Делах'),
        el('div', { class: 'tiles' },
          el('div', { class: 'tile' }, el('div', { class: 'tl' }, 'Сегодня'), el('div', { class: 'tv' }, usd(cost.today))),
          el('div', { class: 'tile' }, el('div', { class: 'tl' }, 'За 30 дней'), el('div', { class: 'tv' }, usd(cost.month))),
          el('div', { class: 'tile' }, el('div', { class: 'tl' }, 'Всего с 05.10'), el('div', { class: 'tv' }, usd(cost.total)))),
        el('div', { class: 'hint-line' }, [...Object.entries(cost.by || {}).map(([k, x]) => `${kinds[k] || k} ${usd(x)}`),
          ...Object.entries(cost.models || {}).map(([k, x]) => `${k.replace('claude-', '').replace(/-(\d)-(\d)$/, ' $1.$2')} ${usd(x)}`)].join(' · ') || 'Пока ничего.')) : null,
      v.privacy && privacyOn() ? privacyBox(v.privacy) : null,
      v.ui ? el('div', { class: 'group' }, el('h2', {}, icon('menu', 17), 'Вид и меню', el('span', { class: 'gsub' }, 'всего Ковчега')),
        uiEditor(v)) : null)];
}

/** «Что видит команда» (аудит №9, правило 26 карты Дел) — только владельцу (сервер отдаёт privacy в /api/settings ему
 *  одному): закон одной фразой и переключатель встреч — Свод privacy.team; записи встреч сервер переписывает сразу. */
const TEAM_SEES = {
  fact: ['Встречи: только факт', 'команда видит «Клиент: тема», когда, сколько и с кем — без итога и договорённостей'],
  summary: ['Встречи: факт и итог', 'команда видит и итог встречи с договорённостями'],
};
function privacyBox(pv) {
  const pick = async (val) => {
    if (pv.meetings === val) return;
    try {
      const r = await op1({ op: 'svod.set', key: 'privacy.team', value: { meetings: val }, author: 'web',
        reason: 'Настройки → Что видит команда' });
      const n = ['обновлена', 'заведена'].reduce((a, k) => a + ((r.touched || {})[k] || 0), 0);
      pv.meetings = val;
      crmDirty(); toast(`${TEAM_SEES[val][0]} · записи встреч переписаны: ${n}`); render();
    } catch (e) { fail(e); }
  };
  return el('div', { class: 'group' }, el('h2', {}, icon('lock', 17), 'Что видит команда'),
    el('div', { class: 'hint-line' }, 'Команда видит работу по проекту: дела, клиента и сделку, людей клиента и факт касания. '
      + 'Переписка Telegram, расшифровки, итоги встреч, пометки о людях и «Только тебе» — только ты.'),
    el('div', { class: 'choices' }, Object.entries(TEAM_SEES).map(([val, [title, hint]]) =>
      el('button', { class: 'choice' + (pv.meetings === val ? ' on' : ''), onclick: () => pick(val) }, el('b', {}, title), el('span', {}, hint)))));
}

// ── «Вид и меню» (Правка 5.0, этап 7, 14.10.2026): меню значка Ковчега и акцент — Свод ui.menu и ui.look, одни на
// веб, телефон и все страницы Ковчега. Форма, а не свободный текст: подписи направлений, их порядок, порядок сервисов и
// куда какой сервис, акцент обеих тем; «Как JSON» — для правки целиком. Сломать меню отсюда нельзя: та же проверка, что
// у сервера (checkMenu), — до отправки, сервер проверяет ещё раз до записи; «Сохранить» шлёт с base_rev (вторая вкладка
// не затрёт первую), «Вернуть заводское» — заводское сервера. Только владельцу: сервер отдаёт ui в /api/settings ему одному.
const UI_KEY = /^[a-z][a-z0-9_-]{0,23}$/, UI_ICON = /^[a-z][a-zA-Z0-9]{0,23}$/, UI_BAD = /[\u0000-\u001f\u007f<>]/;
const uiText = (s) => String(s == null ? '' : s).trim().replace(/\s+/g, ' ');
/** Проверка меню — словами, как у сервера (kovcheg_ui.check_menu); '' — годится. Подсказка до отправки, не защита. */
function checkMenu(m, catalog) {
  if (!m || typeof m !== 'object' || Array.isArray(m)) return 'нужен объект {big, directions, create}';
  const seen = new Set();
  const text = (s, what, most, need = true) => {
    if (s != null && typeof s !== 'string') return what + ': нужен текст';
    const t = uiText(s);
    if (need && !t) return what + ': пусто';
    if (t.length > most) return `${what}: длиннее ${most} знаков`;
    return UI_BAD.test(t) ? what + ': угловые скобки нельзя' : '';
  };
  const item = (x, where) => {
    const it = typeof x === 'string' ? { key: x } : x;
    if (!it || typeof it !== 'object') return where + ': нужен сервис {key, name, icon}';
    if (!UI_KEY.test(it.key || '')) return where + ': ключ сервиса — латиницей: sport, dela, kino';
    if (seen.has(it.key)) return `«${it.key}» в меню дважды — сервис живёт в одном месте`;
    seen.add(it.key);
    const cat = catalog[it.key] || {};
    return text(it.name || cat.name, `${where} (${it.key}): название`, 24)
      || (UI_ICON.test(it.icon || cat.icon || '') ? '' : `${where} (${it.key}): значок — имя из набора латиницей`)
      || text(it.sub, `${where} (${it.key}): подпись`, 40, false);
  };
  const group = (d, where) => {
    if (!d || typeof d !== 'object' || Array.isArray(d)) return where + ': нужен объект {name, sub, items}';
    const items = d.items || [];
    if (!Array.isArray(items) || items.length > 10) return where + ': сервисов — до 10';
    return text(d.name, where + ': название', 24) || text(d.sub, where + ': подпись', 60, false)
      || items.map((x, i) => item(x, `«${uiText(d.name)}», сервис ${i + 1}`)).find(Boolean) || '';
  };
  const big = m.big || [];
  if (!Array.isArray(big) || big.length > 4) return 'больших кнопок — до 4';
  const e1 = big.map((x, i) => item(x, 'большая кнопка ' + (i + 1))).find(Boolean);
  if (e1) return e1;
  const dirs = m.directions;
  if (!Array.isArray(dirs) || !dirs.length || dirs.length > 12) return 'направлений — от 1 до 12';
  const e2 = dirs.map((d, i) => group(d, 'направление ' + (i + 1))).find(Boolean);
  if (e2) return e2;
  const names = dirs.map((d) => uiText(d.name).toLowerCase());
  if (new Set(names).size !== names.length) return 'два направления с одним названием';
  const e3 = m.create ? group(m.create, '«Творчество»') : '';
  if (e3) return e3;
  return seen.size > 40 ? 'больше 40 пунктов' : '';
}
/** Смесь цветов #rrggbb — как kovcheg_ui.mix на сервере: показать мягкий акцент до сохранения. */
function mixHex(a, b, wa) {
  const p = (h, i) => parseInt(h.slice(1 + 2 * i, 3 + 2 * i), 16);
  return '#' + [0, 1, 2].map((i) => Math.round(p(a, i) * wa + p(b, i) * (1 - wa)).toString(16).padStart(2, '0')).join('');
}
const UI_SOFT = { light: ['#ffffff', 0.10], dark: ['#161513', 0.22] };
const uiClone = (x) => JSON.parse(JSON.stringify(x));

function uiEditor(v) {
  const ui = v.ui, catalog = Object.fromEntries(ui.catalog.map((c) => [c.key, c]));
  const fac = ui.factory, base = ui.menu_rev + ':' + ui.tokens_rev;
  // Черновик живёт, пока его не сохранили или не отменили; новая версия с сервера заменяет только нетронутый.
  if (!S.ui || (S.ui.base !== base && !S.ui.dirty)) {
    S.ui = { base, dirty: false, menu: uiClone(ui.menu), json: null, err: '',
      accent: { light: (ui.tokens.light || {}).accent || fac.look.light.accent, dark: (ui.tokens.dark || {}).accent || fac.look.dark.accent } };
  }
  const st = S.ui, m = st.menu;
  m.big = m.big || [];
  const status = el('div', { class: 'ui-status', 'aria-live': 'polite' });
  const save = el('button', { class: 'btn main', onclick: () => uiSave(v) }, 'Сохранить');
  const undo = el('button', { class: 'btn', onclick: () => { S.ui = null; render(); } }, 'Отменить правки');
  const refresh = () => {
    st.err = checkMenu(m, catalog);
    status.replaceChildren(st.err ? el('span', { class: 'when-bad' }, st.err)
      : el('span', { class: 'faint' }, st.dirty ? 'Есть несохранённые правки.' : 'Сохранено.'));
    save.disabled = !st.dirty || !!st.err;
    undo.disabled = !st.dirty;
  };
  const touch = (redraw) => { st.dirty = true; st.json = null; if (redraw) render(); else refresh(); };
  const input = (obj, field, max, ph, label) => el('input', { class: 'ui-in', value: obj[field] || '', maxlength: max, placeholder: ph,
    'aria-label': label, oninput: (e) => { obj[field] = e.target.value; touch(false); } });
  const arrow = (list, i, d, label) => el('button', { class: 'icon ui-arrow', title: label, 'aria-label': label,
    disabled: i + d < 0 || i + d >= list.length, onclick: () => { [list[i], list[i + d]] = [list[i + d], list[i]]; touch(true); } },
  icon(d < 0 ? 'up' : 'down', 15));
  // Куда можно унести сервис: направления по порядку, «Творчество», «не в меню».
  const homes = () => [...m.directions.map((d, j) => ['d' + j, uiText(d.name) || 'направление ' + (j + 1)]),
    ...(m.create ? [['c', uiText(m.create.name) || 'Творчество']] : []), ['x', '— не в меню']];
  const listOf = (h) => (h === 'c' ? m.create.items : h[0] === 'd' ? m.directions[+h.slice(1)].items : null);
  const itemRow = (list, i, here) => {
    const it = list[i] = typeof list[i] === 'string' ? { key: list[i] } : list[i];
    const cat = catalog[it.key] || {};
    const move = el('select', { class: 'ui-move', 'aria-label': 'Куда перенести «' + (it.name || cat.name || it.key) + '»',
      onchange: (e) => {
        const to = e.target.value;
        if (to === here) return;
        list.splice(i, 1);
        const dest = listOf(to);
        if (dest) dest.push(it);
        touch(true);
      } }, homes().map(([h, t]) => el('option', { value: h, selected: h === here || null }, t)));
    return el('div', { class: 'ui-item' }, icon(it.icon || cat.icon || 'list', 16),
      input(it, 'name', 24, cat.name || it.key, 'Название сервиса ' + it.key),
      el('span', { class: 'faint ui-key' }, it.key), arrow(list, i, -1, 'Выше'), arrow(list, i, 1, 'Ниже'), move);
  };
  const groupBox = (d, j) => el('div', { class: 'ui-dir' },
    el('div', { class: 'ui-head' },
      input(d, 'name', 24, 'Название', 'Название направления'), input(d, 'sub', 60, 'подпись из формулы', 'Подпись направления'),
      j == null ? null : [arrow(m.directions, j, -1, 'Направление выше'), arrow(m.directions, j, 1, 'Направление ниже'),
        el('button', { class: 'icon ui-arrow', title: 'Убрать пустое направление', 'aria-label': 'Убрать направление',
          disabled: (d.items || []).length > 0 || m.directions.length < 2,
          onclick: () => { m.directions.splice(j, 1); touch(true); } }, icon('x', 15))]),
    (d.items = d.items || []).length ? d.items.map((_, i) => itemRow(d.items, i, j == null ? 'c' : 'd' + j))
      : el('div', { class: 'faint ui-empty' }, 'Пусто — в меню не видно.'));
  // Сервисы, которых нет в меню: их можно вернуть в любое направление.
  const placed = new Set([...m.big, ...m.directions.flatMap((d) => d.items || []), ...((m.create && m.create.items) || [])]
    .map((x) => (typeof x === 'string' ? x : x.key)));
  const outside = ui.catalog.filter((c) => !placed.has(c.key) && !PAGE_OF[c.key]);
  const backIn = outside.length ? el('div', { class: 'ui-dir' }, el('div', { class: 'ui-head' }, el('b', {}, 'Не в меню')),
    outside.map((c) => el('div', { class: 'ui-item' }, icon(c.icon, 16), el('span', {}, c.name), el('span', { class: 'faint ui-key' }, c.key),
      el('select', { class: 'ui-move', 'aria-label': 'Куда поставить «' + c.name + '»', onchange: (e) => {
        const dest = listOf(e.target.value);
        if (dest) { dest.push({ key: c.key, name: c.name, icon: c.icon }); touch(true); }
      } }, el('option', { value: 'x', selected: true }, 'поставить в…'), homes().filter(([h]) => h !== 'x').map(([h, t]) => el('option', { value: h }, t)))))) : null;
  const accentRow = (theme, title) => {
    const a = st.accent[theme], [bg, share] = UI_SOFT[theme];
    const pickA = el('input', { type: 'color', value: a, 'aria-label': 'Акцент: ' + title, oninput: (e) => {
      st.accent[theme] = e.target.value; touch(false);
      sample.style.setProperty('--a', e.target.value); sample.style.setProperty('--s', mixHex(e.target.value, bg, share));
    } });
    const sample = el('span', { class: 'ui-sample ' + theme, style: `--a:${a};--s:${mixHex(a, bg, share)}` }, el('b', {}, 'Выбранное'), ' ссылка');
    return el('div', { class: 'ui-accent' }, el('span', { class: 'ui-at' }, title), pickA, sample,
      el('button', { class: 'chip-btn', disabled: a === fac.look[theme].accent || null,
        onclick: () => { st.accent[theme] = fac.look[theme].accent; touch(true); } }, 'заводской'));
  };
  // «Как JSON»: весь черновик меню текстом — правка целиком (переименовать сервис, новый ключ); проверка до «Взять».
  const area = el('textarea', { class: 'ui-json', rows: 14, spellcheck: 'false', 'aria-label': 'Меню целиком, JSON',
    oninput: (e) => { st.json = e.target.value; } });
  area.value = st.json != null ? st.json : JSON.stringify(m, null, 1);
  const jsonErr = el('span', { class: 'when-bad' });
  const take = el('button', { class: 'btn small', onclick: () => {
    let got;
    try { got = JSON.parse(area.value); } catch (e) { jsonErr.textContent = 'не JSON: ' + e.message; return; }
    const err = checkMenu(got, catalog);
    if (err) { jsonErr.textContent = err; return; }
    if (!Array.isArray(got.big)) got.big = uiClone(fac.menu.big);
    if (got.create === undefined) got.create = uiClone(fac.menu.create);
    st.menu = got; touch(true);
  } }, 'Взять из JSON');
  const box = el('div', { class: 'ui-edit' },
    // Одним span: строка подсказки — flex, и каждый кусок текста иначе встал бы отдельным блоком.
    el('div', { class: 'hint-line' }, el('span', {}, 'Меню значка Ковчега и акцент — одни на веб, телефон и все страницы Ковчега (Свод ',
      el('code', {}, 'ui.menu'), ' и ', el('code', {}, 'ui.look'), '). Телефон возьмёт правку со следующим синком, остальные страницы — за минуту-две: их перепекает сборщик «Дома».')),
    el('div', { class: 'hint-line faint' }, `Сейчас: меню — ${ui.menu_from === 'svod' ? 'из Свода, версия ' + ui.menu_rev : 'заводское'}; вид — ${ui.tokens_from === 'svod' ? 'из Свода, версия ' + ui.tokens_rev : 'заводской'}.`),
    el('h3', { class: 'ui-h' }, 'Большие кнопки'),
    el('div', { class: 'ui-dir' }, m.big.map((b, i) => el('div', { class: 'ui-item' }, icon(b.icon || (catalog[b.key] || {}).icon || 'list', 16),
      input(b, 'name', 24, (catalog[b.key] || {}).name || b.key, 'Название большой кнопки'), input(b, 'sub', 40, 'подпись', 'Подпись большой кнопки'),
      arrow(m.big, i, -1, 'Левее'), arrow(m.big, i, 1, 'Правее')))),
    el('h3', { class: 'ui-h' }, 'Направления — по порядку'),
    m.directions.map((d, j) => groupBox(d, j)),
    el('div', { class: 'toolbar' }, el('button', { class: 'chip-btn', disabled: m.directions.length >= 12 || null,
      onclick: () => { m.directions.push({ name: 'Новое направление'.slice(0, 24), sub: '', items: [] }); touch(true); } }, '+ направление')),
    m.create ? [el('h3', { class: 'ui-h' }, 'Строка ниже'), groupBox(m.create, null)] : null,
    backIn,
    el('h3', { class: 'ui-h' }, 'Акцент'),
    accentRow('light', 'Светлая тема'), accentRow('dark', 'Тёмная тема'),
    el('div', { class: 'hint-line faint' }, 'Мягкая подложка выбранного и цвет текста на кнопке выводятся из акцента сами.'),
    el('details', { class: 'ui-more', open: st.json != null || null }, el('summary', {}, 'Как JSON'), area, el('div', { class: 'toolbar' }, take, jsonErr)),
    el('div', { class: 'toolbar ui-actions' }, save, undo,
      el('button', { class: 'btn bad', onclick: () => {
        if (!confirm('Вернуть заводские меню и акцент? Своё меню уйдёт в историю Свода.')) return;
        st.menu = uiClone(fac.menu); st.accent = { light: fac.look.light.accent, dark: fac.look.dark.accent }; touch(true);
      } }, 'Вернуть заводское')),
    status);
  refresh();
  return box;
}

async function uiSave(v) {
  const st = S.ui, ui = v.ui;
  const err = checkMenu(st.menu, Object.fromEntries(ui.catalog.map((c) => [c.key, c])));
  if (err) { toast('Не сохранил: ' + err); return; }
  const items = [];
  if (JSON.stringify(st.menu) !== JSON.stringify(ui.menu)) {
    items.push({ op: 'svod.set', key: 'ui.menu', value: st.menu, author: 'web', reason: 'веб Дел: «Вид и меню»', base_rev: ui.menu_rev });
  }
  // Вид: свои токены (если их ставил Claude) остаются, меняется только акцент — мягкий, текст на нём и info сервер выведет.
  const look = { v: 1 };
  for (const th of ['light', 'dark']) {
    const was = { ...((ui.tokens || {})[th] || {}) };
    if (st.accent[th] !== was.accent) { delete was['accent-soft']; delete was['accent-ink']; delete was.info; }
    look[th] = { ...was, accent: st.accent[th] };
  }
  if (['light', 'dark'].some((th) => st.accent[th] !== ((ui.tokens || {})[th] || {}).accent)) {
    items.push({ op: 'svod.set', key: 'ui.look', value: look, author: 'web', reason: 'веб Дел: акцент', base_rev: ui.tokens_rev });
  }
  if (!items.length) { S.ui = null; render(); return; }
  try {
    await ops(items);
    S.ui = null;
    crmDirty();
    S.me = await api('/api/me');
    S.top = null;   // шапка строится один раз — с новым меню её надо собрать заново
    rememberLook();
    toast('Сохранил в Свод: телефон — со следующим синком, страницы Ковчега — за минуту-две');
  } catch (e) {
    // «На сервере уже версии N» — правили в другой вкладке: свежая версия придёт с настройками, черновик — пока жив.
    toast('Не сохранил: ' + e.message);
    crmDirty();
  }
  render();
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
// Открыть дело — и показать карточку, даже если её спрятали язычком: щелчок по делу и значит «покажи его».
function openCard(id) {
  S.cardId = id; S.draft = null;
  if (S.cardHidden) { S.cardHidden = false; LS.set('cardHidden', false); }
  render(); loadCardExtras(id);
}
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
/** «Когда» (13.10.2026, владелец: «срок и напомнить — одна текстовая строка, которую разбирают»): «завтра в 11», «в пятницу
 *  вечером», «через 2 часа», «когда приеду на дачу». Пока печатаешь — свой разбор сервера (when.py, без модели);
 *  Enter — поставить, а непонятое сервер спросит у Claude. Как понято — строкой под полем, до сохранения. Кнопки под
 *  строкой — подсказки, ставят сразу. dateOnly — нужен только день («Напомнить ему»). */
async function whenAsk(q, { local = false, date = false } = {}) {
  const r = await fetch('/api/view/when?' + new URLSearchParams({ q, ...(local ? { local: '1' } : {}), ...(date ? { date: '1' } : {}) }),
    { credentials: 'same-origin', headers: { 'X-Dela': '1' } });
  if (r.status === 401) { S.me = null; renderLogin(); throw new Error('нужен вход'); }
  let d = {};
  try { d = await r.json(); } catch (e) { /* пусто */ }
  if (!r.ok && d.ok === undefined) throw new Error(r.status === 404 ? 'сервер не знает этот адрес (404) — нужен путь в настройках сайта' : 'ошибка ' + r.status);
  return d;
}
function whenLine(key, { dateOnly = false, apply, hints = [], example = '' }) {
  const input = el('input', { class: 'when-in', value: S.whenText[key] || '', autocomplete: 'off', enterkeyhint: 'done',
    placeholder: dateOnly ? 'в пятницу, через неделю, 20.10' : 'завтра в 11, в пятницу вечером, через 2 часа, когда приеду домой',
    'aria-label': dateOnly ? 'Когда напомнить ему — словами' : 'Когда — словами: срок и напоминание' });
  const said = el('div', { class: 'when-said', 'aria-live': 'polite' });
  let seq = 0, timer = null, last = null;
  const go = async () => {
    const q = input.value.trim();
    if (!q) { input.focus(); return; }
    const r = last && last.q === q && last.r.ok ? last.r : await look(true);
    if (!r || !r.ok) return;
    S.whenText[key] = '';
    await apply(r.set, r.words);
  };
  const show = (r, q) => {
    if (!q) { said.replaceChildren(); return; }
    if (!r) { said.replaceChildren(el('span', { class: 'faint' }, 'Спрашиваю Claude…')); return; }
    if (r.ok) {
      said.replaceChildren(...[el('span', { class: 'when-ok' }, r.words || 'понял'),
        r.by === 'claude' ? el('span', { class: 'faint' }, '· понял Claude') : null,
        el('button', { class: 'link-btn', onmousedown: (e) => { e.preventDefault(); go(); } }, 'Поставить ↵')].filter(Boolean));
    } else {
      said.replaceChildren(el('span', { class: r.need_model ? 'faint' : 'when-bad' }, r.need_model ? 'Сам не понял — Enter, спрошу Claude' : r.error || 'не понял'));
    }
  };
  const look = async (model) => {
    const q = input.value.trim();
    const my = ++seq;
    if (!q) { last = null; show(null, ''); return null; }
    if (model) show(null, q);
    try {
      const r = await whenAsk(q, { local: !model, date: dateOnly });
      if (my !== seq) return null;
      last = { q, r };
      show(r, q);
      return r;
    } catch (e) {
      if (my === seq) said.replaceChildren(el('span', { class: 'when-bad' }, e.message));
      return null;
    }
  };
  input.addEventListener('input', () => { S.whenText[key] = input.value; clearTimeout(timer); timer = setTimeout(() => look(false), 220); });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); go(); }
    if (e.key === 'Escape' && input.value) { e.preventDefault(); e.stopPropagation(); input.value = S.whenText[key] = ''; look(false); }
  });
  if (input.value) setTimeout(() => look(false));
  return el('div', { class: 'when-box' }, input, said, hints.length ? el('div', { class: 'quick-dates when-hints' }, hints, example ? el('span', { class: 'when-ex' }, example) : null)
    : example ? el('div', { class: 'when-ex' }, example) : null);
}
/** Срок и напоминание сейчас — словами: «Срок: пт 16.10 11:00 · ⏰ напомню в Telegram 11:00». */
function whenNow(src, t) {
  const due = src.due_date ? dueLabel({ ...src, status: src.status || 'open' }) : null;
  const rem = t ? remindState(t) : '';
  if (!due && !rem) return el('div', { class: 'when-now faint' }, 'Срока нет');
  return el('div', { class: 'when-now' }, due ? el('span', { class: due.cls === 'late' ? 'late' : '' }, 'Срок: ' + due.text) : null,
    rem ? el('span', { class: 'when-rem' }, '⏰ ' + rem) : null);
}
/** Строка «Когда» у дела или предложения: поставить — как любую правку поля (у предложения — до «Принять»; напоминания у
 *  него нет, только срок). Кнопки — частые фразы тем же разбором и места телефона. */
function whenRow(src, t) {
  const apply = async (set, words) => {
    if (t) { await setFields([t.id], set, 'Когда: ' + words); return; }
    for (const k of ['due_date', 'due_time']) if (k in set) src[k] = set[k];
    render();
  };
  const phrase = (label, q) => el('button', { class: 'chip-btn', onclick: async () => {
    try { const r = await whenAsk(q, { local: true }); if (r.ok) await apply(r.set, r.words); else toast(r.error || 'не понял'); } catch (e) { fail(e); }
  } }, label);
  // Подсказки — только «Сегодня» и «Завтра» (владелец 13.10.2026: остальное — словами); пример — строкой под полем.
  const hints = [phrase('Сегодня', 'сегодня'), phrase('Завтра', 'завтра')];
  const place = t && remindOn() ? ' · когда приеду ' + (myPlaces()[0] ? (norm(myPlaces()[0]) === 'дом' ? 'домой' : 'в ' + myPlaces()[0]) : 'домой') : '';
  return [whenNow(src, t), whenLine(t ? t.id : 'draft', { apply, hints,
    example: 'Можно словами: в пятницу вечером · через 2 часа' + place + ' · «нет» — убрать' })];
}

/** «Зачем» дела (владелец 13.10.2026, заход Г: «проекты и цели у нас одно и то же, и мы путаемся — называть это одним
 *  большим словом „Зачем“»): ради чего дело — одна строка. Цель в дереве («Зачем» дела, why_id), иначе проект клиента
 *  (сделка — «Помочь с хеджем» под клиентом), иначе проект или раздел (клиент целиком, ЗФ, Семья). Путь — что над ним:
 *  направление и цели выше, у проекта клиента — клиент. Данные те же (why_id, deal_id, project_id): телефон, коннектор и
 *  их контракты не меняются — «Зачем» считается из них. */
function zachemOf(t, seen = new Set()) {
  if (!t) return null;
  // «Зачем» — цель дерева. Дело внутри обычного дела (15.10.2026, правило 27: why_id — родитель) целью его не делает:
  // у такого «Зачем» — своя сделка или проект (их и так ставит «Для этого»), нет их — «Зачем» родителя.
  const w = t.why_id && isMine(t) ? S.tasks.get(t.why_id) : null;
  if (w && w.goal) return { kind: 'goal', id: w.id, title: w.title, path: goalPath(w).split(' › ').slice(0, -1), g: w };
  const d = !t.goal && t.deal_id ? S.deals.get(t.deal_id) : null;
  if (d) { const c = project(d.project_id); return { kind: 'deal', id: d.id, title: d.name, path: c ? [c.name] : [], d, c }; }
  const p = !t.goal ? project(t.project_id) : null;
  if (p) {
    const k = isMine(t) ? S.krug.proj.get(p.id) : null;
    const pg = k && k.why_id ? S.tasks.get(k.why_id) : null;
    return { kind: 'project', id: p.id, title: p.name, path: pg ? goalPath(pg).split(' › ') : [], p };
  }
  if (w && !seen.has(w.id) && seen.size < 60) { seen.add(t.id); return zachemOf(w, seen); } // круга база не пускает — страховка
  return null;
}
/** Открыть сам «Зачем»: цель — карточкой, проект клиента — его страницей, проект — своей. */
const zachemGo = (z) => (z.kind === 'goal' ? openCard(z.id) : z.kind === 'deal' ? openDeal(z.id) : go('#/p/' + z.id));
/** «Зачем» ссылкой: в строке дела, крошке карточки. */
function zachemLink(z, cls = 'm link') {
  const text = z.kind === 'deal' && z.c && !norm(z.title).startsWith(norm(z.c.name)) ? z.c.name + ' · ' + z.title : z.title;
  return el('a', { class: cls + (z.kind === 'goal' ? ' why' : ''), href: z.kind === 'deal' ? '#/d/' + z.id : z.kind === 'project' ? '#/p/' + z.id : '#',
    title: [...z.path, z.title].join(' › '), onclick: (e) => { e.stopPropagation(); if (z.kind === 'goal') { e.preventDefault(); openCard(z.id); } } }, text);
}
/** Выбор «Зачем» — один поповер с поиском: цели дерева (с путём), проекты клиентов (под клиентом), проекты и разделы;
 *  набрал новое — новая цель. Выбрал цель — «Зачем» дела (направление встанет по ней — правило сервера), проект клиента —
 *  клиент и проект, проект — он; соперник уходит: «Зачем» одно. Цели — только своим делам (Круг личный); у самой цели
 *  «Зачем» — только цель (цель живёт без проекта). Черновик предложения — без целей: у него их нет. */
function zachemPop(anchor, ids, { draft = null } = {}) {
  const list = ids.map((id) => S.tasks.get(id)).filter(Boolean);
  const mine = !draft && list.length && list.every(isMine) && krugOn();
  const goalsOnly = list.length && list.every((x) => x.goal);
  const below = new Set(list.flatMap((x) => [...belowOf(x.id)]));
  // Только цели дерева: дело с делами внутри целью не стало (15.10.2026) — вкладывают «Вложить в…», не «Зачем».
  const goals = mine ? all().filter((x) => isOpen(x) && isMine(x) && !below.has(x.id) && !x.no_steps && x.goal)
    .map((x) => ({ v: 'g:' + x.id, title: x.title, note: goalPath(x).split(' › ').slice(0, -1).join(' › ') || 'цель', lead: () => icon('target', 16) })) : [];
  const deals = goalsOnly ? [] : [...S.deals.values()].filter((d) => d.stage !== 'archive' && project(d.project_id))
    .map((d) => ({ v: 'd:' + d.id, title: d.name, note: project(d.project_id).name + ' · ' + stageWord(d), keys: [project(d.project_id).name, ...renamedOf(d.id)],
      lead: () => el('span', { class: 'st-dot st-' + d.stage }) }));
  const projs = goalsOnly ? [] : [...S.projects.values()].filter((p) => !p.archived_at)
    .map((p) => ({ v: 'p:' + p.id, title: p.name, note: KIND_ONE[p.kind] || '', keys: [...(p.aliases || []), ...renamedOf(p.id)], lead: () => dot(p.id) }));
  const first = list[0] || draft || {};
  const z = draft ? zachemOf({ ...draft, why_id: null }) : list.length === 1 ? zachemOf(first) : null;
  const cur = z ? { goal: 'g:', deal: 'd:', project: 'p:' }[z.kind] + z.id : null;
  // Соперник уходит — «Зачем» одно; но дело внутри дела (why_id — родитель, а не цель) из родителя не вынимаем: это не
  // «Зачем», а место дела (15.10.2026). Вынуть — «Вынуть», вложить — «Вложить в…».
  const dropWhy = (t) => (mine && t && t.why_id && !parentOf(t) ? { why_id: null } : {});
  const set = (v, t) => {
    if (v == null) return { deal_id: null, project_id: null, ...dropWhy(t) };
    const [k, id] = [v.slice(0, 1), v.slice(2)];
    if (k === 'g') return { why_id: id, deal_id: null };
    if (k === 'd') { const d = S.deals.get(id); return { project_id: d.project_id, deal_id: id, ...dropWhy(t) }; }
    return { project_id: id, deal_id: null, ...dropWhy(t) };
  };
  const word = (v) => (v == null ? 'Без «Зачем»' : 'Зачем: ' + ([...goals, ...deals, ...projs].find((x) => x.v === v) || {}).title);
  choicePop(anchor, { title: 'Зачем', placeholder: mine ? 'Ради чего это? Найти или написать новое' : 'Найти…',
    items: [...goals, ...deals, ...projs], current: cur, none: 'Без «Зачем»',
    create: mine ? (title) => setFields(ids, { why_title: title, deal_id: null }, 'Новая цель: ' + title) : null,
    onPick: (v) => {
      if (draft) { const s = set(v, null); delete s.why_id; Object.assign(draft, s); render(); return; }
      setFields(ids, (id) => set(v, S.tasks.get(id)), word(v));
    } });
}
/** Старые названия «Зачем» (переименование 13.10.2026, Свод zachem.renamed) — чтобы поиск по старому имени находил. */
const renamedOf = (id) => (S.renamed && S.renamed.get(id)) || [];


/** Подписи строки (15.10.2026): у дела внутри — «для: <родитель>», у родителя — «сначала: <первое открытое внутри>»
 *  (и сколько ещё). Ссылками: щелчок — карточка. */
function stepChips(t) {
  const out = [];
  const go = (id) => (e) => { e.preventDefault(); e.stopPropagation(); openCard(id); };
  const p = parentOf(t);
  if (p) out.push(el('a', { class: 'm link for', href: '#', title: 'Внутри дела «' + p.title + '» — открыть его', onclick: go(p.id) },
    'для: ' + shortTitle(p.title)));
  if (isOpen(t) && !t.goal) {
    const k = insideOf(t.id);
    if (k.length) out.push(el('a', { class: 'm link first', href: '#', title: 'Следующее дело внутри — открыть', onclick: go(k[0].id) },
      'сначала: ' + shortTitle(k[0].title) + (k.length > 1 ? ` (ещё ${k.length - 1})` : '')));
  }
  return out;
}
/** «Для этого» у строки: открытое дело, не цель (у чужого — заведётся рядом, вложить в чужое нельзя). */
const canStep = (t) => isOpen(t) && !t.goal;
/** Вложить, вынуть, поменять место — только своё дело в своё (правило базы: «зачем» — только своё дело). */
const canNest = (t) => nestOn() && isMine(t) && !t.goal;

/** «Для этого» у строки дела (15.10.2026, владелец: «и в карточке дела, и в обычном списке»): короткая строка ввода —
 *  тем же запросом, что строка карточки в режиме «Для этого» (scope.step): всё названное — дела внутри этого, само дело
 *  не меняется; без ключа Claude — одно дело, названием как написано (сервер). Ниже — «Вложить в…», «Вынуть», место. */
function stepPop(anchor, t) {
  setTimeout(() => {
    const q = el('textarea', { class: 'ps-q step-q', rows: 2, placeholder: 'Что сделать для этого? — заведу внутри', 'aria-label': 'Что сделать для этого дела' });
    const status = el('div', { class: 'step-status faint' });
    const send = async () => {
      const text = q.value.trim();
      if (!text) { q.focus(); return; }
      q.disabled = true;
      status.textContent = S.me.claude ? 'Claude думает…' : 'Завожу…';
      try {
        const d = await runJob('/api/ask', { text, scope: { title: 'Строка дела', card: 'task', open: t.id, step: true } });
        for (const x of d.tasks || []) S.tasks.set(x.id, x);
        closePop();
        await sync().catch(() => {});
        render();
        const made = d.tasks || [];
        const err = (d.errors || []).length ? ' Не вышло: ' + d.errors[0] : '';
        toast((d.reply || (made.length ? 'Завёл: ' + made.map((x) => x.title).join(', ') : 'Ничего не завёл')) + err,
          made.length ? () => ops(made.map((x) => ({ op: 'task.cancel', id: x.id }))) : null);
      } catch (e) { q.disabled = false; status.textContent = ''; fail(e); }
    };
    q.addEventListener('keydown', (e) => {
      e.stopPropagation();
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
      if (e.key === 'Escape') { e.preventDefault(); closePop(); }
    });
    const pop = popAt(anchor, [el('div', { class: 'pop-h' }, 'Для этого: ' + shortTitle(t.title, 48)), q,
      el('div', { class: 'step-acts' }, el('button', { class: 'chip-btn on', onclick: send }, 'Завести внутри'), ...nestActs(t, true)), status]);
    pop.classList.add('step-pop');
    q.focus();
  }, 0);
}
/** Вложить, вынуть и место среди соседей — кнопками (меню строки и карточка). */
function nestActs(t, inPop = false) {
  if (!canNest(t)) return [];
  const p = parentOf(t);
  const sibs = p ? insideOf(p.id) : [];
  const i = sibs.findIndex((x) => x.id === t.id);
  const act = (label, fn, title) => el('button', { class: 'chip-btn', title, onclick: (e) => { if (inPop) closePop(); fn(e); } }, label);
  return [
    act('Вложить в…', (e) => nestPop(inPop ? anchorOf(t) : e.currentTarget, [t.id]), 'Положить это дело внутрь другого'),
    p ? act('Вынуть', () => unnest([t.id]), `Вынуть из «${p.title}» — дело станет само по себе`) : null,
    p && i > 0 ? act('Раньше', () => moveInside(t, -1), 'Поставить раньше среди дел внутри «' + p.title + '»') : null,
    p && i >= 0 && i < sibs.length - 1 ? act('Позже', () => moveInside(t, 1), 'Поставить позже') : null,
  ].filter(Boolean);
}
/** Якорь для выбора после закрытого меню строки: строка дела на экране, иначе — середина экрана. */
const anchorOf = (t) => document.querySelector(`.task[data-id="${t.id}"] .main`) || document.querySelector('main.list') || document.body;
/** «Вложить в…» — выбор дела с поиском: свои открытые, кроме самого дела и того, что уже внутри него (иначе круг). */
function nestPop(anchor, ids) {
  const below = new Set(ids.flatMap((id) => [...belowOf(id)]));
  const items = deeds().filter((x) => isOpen(x) && isMine(x) && !below.has(x.id) && !x.no_steps)
    .sort((a, b) => a.title.localeCompare(b.title, 'ru'))
    .map((x) => {
      const p = parentOf(x), z = p ? null : zachemOf(x), due = dueLabel(x);
      return { v: x.id, title: x.title, note: [p ? 'внутри «' + shortTitle(p.title, 30) + '»' : z && z.title, due && due.text].filter(Boolean).join(' · '),
        lead: () => icon('list', 16) };
    });
  choicePop(anchor, { title: ids.length > 1 ? `Вложить ${plural(ids.length, 'дело', 'дела', 'дел')} в…` : 'Вложить в…', placeholder: 'Найти дело…',
    items, onPick: (v) => v && nestInto(ids, v) });
}
/** Положить дела внутрь дела: «Зачем» — оно. Круг и чужие дела не пускает и сервер (tasks.krug_rules). */
function nestInto(ids, pid) {
  const p = S.tasks.get(pid);
  if (!p) return;
  const list = ids.filter((id) => id !== pid && S.tasks.get(id) && S.tasks.get(id).why_id !== pid);
  if (!list.length) return;
  if (list.some((id) => belowOf(id).has(pid))) { toast('Дело не ложится внутрь своего же вложенного — сначала вынь то'); return; }
  if (!isMine(p) || !list.every((id) => isMine(S.tasks.get(id)))) { toast('Вкладывать можно только свои дела в свои'); return; }
  setFields(list, { why_id: pid }, `Внутри «${shortTitle(p.title)}»` + (list.length > 1 ? ': ' + list.length : ''));
}
/** Вынуть из дела: «Зачем» снимается — дело само по себе. */
function unnest(ids) {
  const p = parentOf(S.tasks.get(ids[0]));
  setFields(ids, { why_id: null }, p ? `Вынул из «${shortTitle(p.title)}»` : 'Вынул');
}
/** Раньше / позже среди дел внутри родителя: соседи нумеруются 1, 2, 3… (pos), шлются только сдвинутые. */
async function moveInside(t, dir) {
  const p = parentOf(t);
  if (!p) return;
  const list = insideOf(p.id);
  const i = list.findIndex((x) => x.id === t.id), j = i + dir;
  if (i < 0 || j < 0 || j >= list.length) return;
  [list[i], list[j]] = [list[j], list[i]];
  const moved = list.map((x, n) => ({ x, pos: n + 1 })).filter(({ x, pos }) => x.pos !== pos);
  try {
    await ops(moved.map(({ x, pos }) => ({ op: 'task.set', id: x.id, set: { pos }, was: { pos: x.pos ?? null } })));
    render();
  } catch (e) { fail(e); render(); }
}
/** «Для: <дело>» под названием в карточке дела внутри: ссылкой, место среди соседей и «Вынуть». */
function forLine(t) {
  const p = parentOf(t);
  if (!p) return null;
  const sibs = insideOf(p.id), i = sibs.findIndex((x) => x.id === t.id);
  return el('div', { class: 'for-line' }, el('span', { class: 'faint' }, 'для: '),
    el('a', { class: 'link', href: '#', title: 'Открыть дело, внутри которого это', onclick: (e) => { e.preventDefault(); openCard(p.id); } }, p.title),
    i >= 0 && sibs.length > 1 ? el('span', { class: 'faint' }, ` · ${i + 1} из ${sibs.length}`) : null,
    canNest(t) ? el('button', { class: 'link-btn', title: 'Вынуть — дело станет само по себе', onclick: () => unnest([t.id]) }, 'Вынуть') : null);
}
/** «Внутри» в карточке (15.10.2026, владелец: «блок в карточке — „Внутри“»): открытые дела внутри по месту — флажок
 *  «сделано», название (карточка), срок, «раньше» / «позже»; сделанные — числом. Новое внутрь — строкой «Для этого». */
function insideBox(t) {
  if (t.goal) return null;
  const kids = insideOf(t.id);
  const done = all().filter((x) => x.why_id === t.id && x.status === 'done' && !x.goal).length;
  if (!kids.length && !done) return null;
  return el('div', { class: 'inside-box' },
    el('div', { class: 'place-h' }, 'Внутри', kids.length ? el('span', { class: 'n' }, String(kids.length)) : null),
    kids.map((k, i) => {
      const due = dueLabel(k);
      return el('div', { class: 'in-row' + (i === 0 ? ' next' : ''), 'data-id': k.id },
        el('button', { class: 'tick', title: 'Сделано', 'aria-label': 'Сделано: ' + k.title, onclick: () => toggleDone([k]) }),
        el('a', { class: 'in-t', href: '#', onclick: (e) => { e.preventDefault(); openCard(k.id); } }, k.title),
        due ? el('span', { class: 'in-due ' + (due.cls || '') }, due.text) : null,
        canNest(k) && kids.length > 1 ? el('span', { class: 'in-move' },
          el('button', { class: 'icon-btn', disabled: i === 0, title: 'Раньше', 'aria-label': 'Раньше', onclick: () => moveInside(k, -1) }, icon('up', 15)),
          el('button', { class: 'icon-btn', disabled: i === kids.length - 1, title: 'Позже', 'aria-label': 'Позже', onclick: () => moveInside(k, 1) }, icon('down', 15))) : null);
    }),
    done ? el('div', { class: 'faint in-done' }, 'сделано внутри: ' + done) : null);
}

/** Claude в карточке дела (13.10.2026): строка «Что поправить в этом деле?» — текстом и голосом, как строка страницы, но
 *  Claude видит только это дело (поля, журнал, место в дереве, проект и клиента). «Кстати» — та же строка вдогонку:
 *  Claude сам решает — уточнить это дело, завести рядом новое или записать факт клиенту. Ответ — в окне разговора,
 *  с «Вернуть». Модель — одна настройка на сервере (llm.ROUTES «task», Свод models.dela). */
function cardSay(t, { stepOnly = false } = {}) {
  const busy = !!S.parse;
  const mine = busy && S.parse.card === t.id;
  // «Для этого» (15.10.2026): всё сказанное — новые дела внутри этого (scope.step). Без ключа Claude строка только такая.
  const st = stepOnly || S.cardStep;
  const k = !st && S.kstati;
  const input = el('textarea', { id: 'card-say', rows: 1, disabled: busy, autocomplete: 'off',
    placeholder: st ? 'Что сделать для этого? — заведу внутри' : k ? 'Кстати… — допишу к делу или заведу рядом' : 'Что поправить в этом деле?',
    'aria-label': st ? 'Для этого — что сделать для этого дела' : k ? 'Кстати — мысль вдогонку к этому делу' : 'Что поправить в этом деле' });
  input.value = S.cardSay[t.id] || '';
  const send = () => {
    const x = input.value.trim();
    if (S.parse) return;
    if (!x) { input.focus(); return; }
    claudeSay(x, {}, false, { id: t.id, kstati: k, step: st });
  };
  const grow = () => { input.style.height = ''; if (input.scrollHeight > input.clientHeight + 2) input.style.height = Math.min(input.scrollHeight, 200) + 'px'; };
  input.addEventListener('input', () => { S.cardSay[t.id] = input.value; box.classList.toggle('has-text', !!input.value.trim()); grow(); });
  input.addEventListener('keydown', (e) => {
    e.stopPropagation();
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
    if (e.key === 'Escape') { e.preventDefault(); input.value = S.cardSay[t.id] = ''; box.classList.remove('has-text'); input.blur(); }
  });
  const hearing = !!(rec && S.listenCard);
  const mic = el('button', { type: 'button', class: 'q-btn mic' + (hearing ? ' on' : ''), disabled: busy,
    title: hearing ? 'Готово — отдать Claude' : 'Сказать голосом: нажми, говори, нажми ещё раз',
    'aria-label': hearing ? 'Закончить и отдать Claude' : 'Сказать голосом', 'aria-pressed': hearing ? 'true' : 'false',
    onclick: () => {
      if (hearing) { stopListening(); return; }
      const base = input.value.trim();
      S.listenCard = true;
      const ok = listen((txt) => {
        S.cardSay[t.id] = (base ? base + ' ' : '') + txt;
        const i = document.getElementById('card-say');
        if (i) { i.value = S.cardSay[t.id]; i.dispatchEvent(new Event('input')); }
      }, () => {
        S.listenCard = false;
        const x = (S.cardSay[t.id] || '').trim();
        if (x && x !== base && voiceAuto()) claudeSay(x, {}, false, { id: t.id, kstati: k, step: st }); else render();
      });
      if (!ok) S.listenCard = false;
      render();
    } }, icon(hearing ? 'stop' : 'mic', 18));
  const box = el('div', { class: 'quick-box' + (mine ? ' busy' : '') + (hearing ? ' hearing' : '') + (input.value.trim() ? ' has-text' : '') },
    input, mic, el('button', { type: 'button', class: 'q-btn send-btn', disabled: busy,
      title: busy ? 'Claude думает…' : 'Отдать Claude (Enter)', 'aria-label': 'Отдать Claude', onclick: send }, icon('up', 18)));
  setTimeout(grow);
  const focusSay = () => setTimeout(() => document.getElementById('card-say')?.focus());
  const kBtn = stepOnly ? null : el('button', { class: 'chip-btn kstati' + (k ? ' on' : ''), 'aria-pressed': k ? 'true' : 'false', disabled: busy,
    title: k ? 'Вернуть строку к правке дела' : 'Мысль вдогонку: Claude сам решит — дописать к делу или завести рядом',
    onclick: () => { S.kstati = !S.kstati; S.cardStep = false; render(); focusSay(); } }, 'Кстати');
  const sBtn = stepOnly ? null : el('button', { class: 'chip-btn step' + (st ? ' on' : ''), 'aria-pressed': st ? 'true' : 'false', disabled: busy,
    title: st ? 'Вернуть строку к правке дела' : 'Что надо сделать до этого или для этого — заведу делом внутри этого',
    onclick: () => { S.cardStep = !S.cardStep; S.kstati = false; render(); focusSay(); } }, 'Для этого');
  return el('div', { class: 'say card-say' + (k ? ' kstati-on' : '') + (st ? ' step-on' : '') },
    el('div', { class: 'cs-h' }, el('label', { for: 'card-say' }, st ? (stepOnly ? 'Для этого: заведу внутри — названием, как написано (Claude не настроен)'
      : 'Для этого: что назовёшь — заведу делами внутри этого') : k ? 'Кстати: Claude сам решит — дописать к делу или завести рядом'
      : 'Claude видит только это дело'), sBtn, kBtn),
    box,
    mine ? el('div', { class: 'claude-status' }, stepOnly ? 'Завожу…' : 'Claude думает над этим делом…')
      : hearing ? el('div', { class: 'claude-status listening' }, 'Слушаю — нажми микрофон ещё раз, и отдам Claude.') : null,
    talkFeed(S.cardTalk && S.cardTalk.card.id === t.id ? S.cardTalk : null, { inCard: true }));
}

/** Место дела в дереве жизни (владелец 13.10.2026: «в каждом деле должно быть это дерево и его место в нём, чтобы
 *  я понимал, зачем я это делаю и что будет дальше, когда я это сделаю»): направление, цели над делом лесенкой,
 *  само дело среди соседних шагов, его шаги — и строка «дальше». */
function placeBlock(t) {
  const up = upOf(t).slice(1);
  const main = dirsOf(t)[0];
  const chain = up.slice().reverse();
  const parent = up[0];
  const goalOf = (x) => x && x.kind !== 'project' ? x : null;
  const rows = [el('div', { class: 'pt-row pt-dir', style: '--d:0' }, main ? (dirBy(main) || {}).name || main : 'без направления',
    (t.directions || []).filter((k) => k !== main).length ? el('span', { class: 'faint' }, ' · метки: ' + (t.directions || []).filter((k) => k !== main).map(dirName).join(', ')) : null)];
  chain.forEach((x, i) => rows.push(x.kind === 'project'
    ? el('a', { class: 'pt-row pt-proj', style: '--d:' + (i + 1), href: '#/p/' + x.id }, '▣ ' + ((project(x.id) || {}).name || 'проект'))
    : el('a', { class: 'pt-row pt-goal' + (x.rhythm ? ' practice' : ''), style: '--d:' + (i + 1), href: '#', onclick: (e) => { e.preventDefault(); openCard(x.id); } },
      x.title, x.rhythm ? el('span', { class: 'faint' }, ' · ' + x.rhythm) : null)));
  const d = chain.length + 1;
  const sibs = goalOf(parent) ? stepsOf(parent.id).filter((s) => s.id !== t.id).sort((a, b) => (b.goal ? 1 : 0) - (a.goal ? 1 : 0) || byTime(a, b)) : [];
  rows.push(el('div', { class: 'pt-row pt-me', style: '--d:' + d }, t.goal ? null : el('span', { class: 'me-dot' }), t.title));
  const link = (s, dd, cls) => el('a', { class: 'pt-row ' + cls, style: '--d:' + dd, href: '#', onclick: (e) => { e.preventDefault(); openCard(s.id); } },
    s.goal ? null : el('span', { class: 'sib-dot' }), s.title);
  // У цели — её шаги и подцели; у дела его дела внутри — в блоке «Внутри» ниже (15.10.2026), здесь не повторяем.
  const kids = t.goal ? stepsOf(t.id).sort((a, b) => (b.goal ? 1 : 0) - (a.goal ? 1 : 0) || byTime(a, b)) : [];
  kids.slice(0, 12).forEach((s) => rows.push(link(s, d + 1, 'pt-kid')));
  if (kids.length > 12) rows.push(el('div', { class: 'pt-row faint', style: '--d:' + (d + 1) }, 'и ещё ' + (kids.length - 12)));
  sibs.slice(0, 6).forEach((s) => rows.push(link(s, d, 'pt-sib')));
  if (sibs.length > 6) rows.push(el('div', { class: 'pt-row faint', style: '--d:' + d }, 'рядом ещё ' + (sibs.length - 6)));
  // «Дальше»: что изменится, когда это сделаешь.
  const g = goalOf(parent), gg = g ? goalOf(upOf(g)[1]) : null;
  let next;
  if (t.goal) {
    const sub = kids.filter((s) => s.goal).length;
    const parts = [sub ? plural(sub, 'подцель', 'подцели', 'подцелей') : null,
      kids.length - sub ? plural(kids.length - sub, 'шаг', 'шага', 'шагов') : null].filter(Boolean).join(' и ');
    next = parts ? `Под целью ${parts}` + (g ? ` — а сама она ведёт к «${g.title}»` : '')
      : 'Пустая цель — сигнал, а не мусор: какой первый шаг к ней? Впиши его ниже.';
  } else if (g && !g.goal) {
    // Дело внутри дела (15.10.2026): родитель не закроется сам — поднимется, когда сделано всё внутри.
    const left = sibs.filter((s) => !s.goal).length;
    next = left ? `Сделаешь — внутри «${g.title}» останется ещё ${left}`
      : `Последнее открытое внутри «${g.title}» — сделаешь, и оно поднимется: без срока или позже — на сегодня`;
  } else if (g) {
    const left = sibs.filter((s) => !s.goal).length;
    next = left ? `Сделаешь — к цели «${g.title}» останется ${plural(left, 'шаг', 'шага', 'шагов')}`
      : `Это последний открытый шаг к цели «${g.title}»` + (gg ? ` — дальше «${gg.title}»` : '');
  } else if (parent && parent.kind === 'project') {
    const pg = goalOf(upOf(parent)[1]);
    next = `Двигает проект «${(project(parent.id) || {}).name || ''}»` + (pg ? ` — а он ведёт к «${pg.title}»` : '');
  } else {
    next = main ? 'Висит прямо на направлении — «без зачем». Повесь на цель, и будет видно, куда это ведёт.' : 'Ни цели, ни направления: разборщик предложит место сам, или задай «Зачем».';
  }
  return el('div', { class: 'place' }, rows, el('div', { class: 'pt-next' }, next));
}

/** Круг в карточке дела: «Зачем», цель или дело, ритм практики, направления (метки). Строки «Шаги» и подсказки
 *  «своих нет — направление по дереву…» больше нет (владелец 13.10.2026): шаги видны в «Месте в дереве», данные целы. */
/** Под «Зачем» — само дело это или цель (узел дерева) и ритм практики. */
function krugKind(t) {
  const rhythm = el('input', { class: 'rhythm', placeholder: 'ежедневно, 2 раза в неделю…', value: t.rhythm || '' });
  rhythm.addEventListener('change', () => setFields([t.id], { rhythm: rhythm.value.trim() || null }, rhythm.value.trim() ? 'Практика: ' + rhythm.value.trim() : 'Уже не практика'));
  const kindBtn = treeOn() ? el('button', { class: 'chip-btn' + (t.goal ? ' on' : ''),
    title: t.goal ? 'Вернуть в дела: появится в списках' : 'Цель — состояние, к которому идёшь; в списках дел её нет, она живёт в дереве',
    onclick: () => {
      if (!t.goal && t.project_id && !confirm('Цель живёт без проекта — её видишь только ты. Убрать дело из проекта и сделать целью?')) return;
      setFields([t.id], { goal: !t.goal }, t.goal ? 'Снова дело' : 'Теперь это цель');
    } }, t.goal ? 'Это цель' : 'Сделать целью') : null;
  return el('div', { class: 'why-row' }, kindBtn, t.goal ? el('label', { class: 'rhythm-l', title: 'Цель с ритмом — практика: повторяется и не закрывается' }, 'Ритм', rhythm) : null);
}
function krugProps(t, row) {
  const own = t.directions || [];
  return [
    row('Направления', dirChips(t, (next) => setFields([t.id], { directions: next })),
      own.length && t.why_id ? el('div', { class: 'hint-line krug-hint' }, 'главное — по цели, остальные — метки (до двух)') : null),
  ];
}

function renderCard() {
  const close = () => { S.cardId = null; S.draft = null; render(); };
  const sug = S.draft;
  const t = sug ? null : S.tasks.get(S.cardId);
  if (!t && !sug) return el('section', { class: 'card-pane' });
  if (sug && !sug._src) {
    const b = (sug.payload || {}).ball || 'mine'; // в предложении группа одним ball: owed — «ждут от меня»
    sug._src = { ...(sug.payload || {}), ball: b === 'owed' ? 'mine' : b, owed: b === 'owed' };
    // Проект — тем же правилом, что сервер и вопрос (byName): два проекта с одним именем — не первый попавшийся, а пусто,
    // иначе «Принять» молча брало первый, хотя askWhy на той же карточке спрашивал «Какой проект — A или B?».
    const hit = sug._src.project_id ? null : byName([...S.projects.values()], sug._src.project_name, PROJECT_TIERS).hit;
    if (hit) sug._src.project_id = hit.id;
  }
  const src = t || sug._src;
  // Поле сохраняется само при изменении (как в Vikunja); у предложения — по кнопке «Принять».
  const save = (field, value) => { if (t) setFields([t.id], { [field]: value }); else src[field] = value; };
  // Длинные списки — выбором со строкой поиска (как «Показать только»), не системным <select> (владелец 13.10.2026).
  const people = [...S.people.values()].filter((p) => !p.archived_at && !p.merged_into).sort((a, b) => personName(a).localeCompare(personName(b), 'ru'));
  const who0 = person(src.person_id);
  const personSel = choiceBtn(who0 ? (who0.short && who0.short !== who0.name ? `${who0.short} — ${who0.name}` : who0.name) : '—', (a) => choicePop(a, {
    title: 'Человек', items: personItems(people), current: src.person_id || null, none: 'Никто', onPick: (v) => { save('person_id', v); if (!t) render(); } }), { empty: !who0 });
  // Минуты — чипами 15, 30, 60 (владелец 13.10.2026: без свободного поля); нажал выбранное — снял.
  const minutes = el('div', { class: 'quick-dates' }, [15, 30, 60].map((m) => el('button', { class: 'chip-btn' + (src.estimate_min === m ? ' on' : ''),
    onclick: () => { save('estimate_min', src.estimate_min === m ? null : m); if (!t) render(); } }, m + ' мин')),
  src.estimate_min && ![15, 30, 60].includes(src.estimate_min) ? el('span', { class: 'faint' }, dur(src.estimate_min)) : null);
  const title = el('textarea', { class: 'title-edit', rows: 1 }, src.title || '');
  const fit = () => { title.style.height = ''; title.style.height = title.scrollHeight + 'px'; }; // название — сколько строк надо
  title.addEventListener('input', fit);
  setTimeout(fit);
  title.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); title.blur(); } });
  title.addEventListener('change', () => title.value.trim() && save('title', title.value.trim()));
  const notes = el('textarea', { class: 'notes', placeholder: 'Заметки' }, src.notes || '');
  notes.addEventListener('change', () => save('notes', notes.value.trim() || null));
  // Группа — три кнопки (10.10.2026): «Отбить», «Запустить», «Мониторить»; человек — кто ждёт или от кого жду.
  const g = grpOf(src);
  const grpPick = el('div', { class: 'quick-dates grp-pick' }, Object.entries(GRP).map(([k, l]) =>
    el('button', { class: 'chip-btn' + (g === k ? ' on' : ''), onclick: () => {
      const set = grpSet(src, k);
      if (t) setFields([t.id], set); else { Object.assign(src, set); render(); }
    } }, l)));

  // Свойства — строка на поле: подпись слева, значение справа и переносится внутри своей строки; тонкая линия — у каждой
  // строки (владелец 13.10.2026: «строки наезжают, разделители пропадают» — была одна сетка на всё, без строк).
  const row = (label, ...value) => el('div', { class: 'prop' }, el('div', { class: 'prop-l' }, label), el('div', { class: 'prop-v' }, value));
  // «Зачем» — одна строка вместо «Проекта», «Сделки» и цели (владелец 13.10.2026, заход Г): путь сверху тихо, само
  // «Зачем» — кнопкой выбора с поиском, стрелка — открыть его.
  const zc = zachemOf(t || { ...src, why_id: null });
  const zachem = el('div', { class: 'zachem' },
    zc && zc.path.length ? el('div', { class: 'z-path' }, zc.path.join(' › ')) : null,
    el('div', { class: 'z-line' },
      choiceBtn(zc ? zc.title : 'Без «Зачем» — выбрать', (a) => zachemPop(a, t ? [t.id] : [], { draft: t ? null : src }), { empty: !zc, cls: 'z-pick' }),
      zc && t ? el('button', { class: 'icon-btn z-go', title: 'Открыть «Зачем»', 'aria-label': 'Открыть «Зачем»', onclick: () => zachemGo(zc) }, icon('chevron', 16)) : null),
    t && isMine(t) && krugOn() ? krugKind(t) : null);
  // «Напомнить ему» — день словами («в пятницу», «через неделю») тем же разбором, что «Когда»: без поля даты браузера.
  const nudge = () => [src.nudge_on ? el('div', { class: 'when-now' }, 'Напомнить: ' + (dueLabel({ due_date: src.nudge_on, status: 'open' }) || {}).text) : null,
    whenLine((t ? t.id : 'draft') + ':nudge', { dateOnly: true,
      apply: async (set) => { if ('due_date' in set) save('nudge_on', set.due_date); if (!t) render(); },
      hints: src.nudge_on ? [el('button', { class: 'chip-btn', onclick: () => { save('nudge_on', null); if (!t) render(); } }, 'не напоминать')] : [] })];
  const props = el('div', { class: 'props' },
    row('Зачем', zachem),
    row('Группа', grpPick),
    row({ owed: 'Кто ждёт', check: 'От кого жду' }[g] || 'Человек', personSel),
    row('Когда', ...whenRow(src, t)),
    g === 'check' ? row('Напомнить ему', ...nudge()) : null,
    row('Минут', minutes),
    t && isMine(t) && krugOn() ? krugProps(t, row) : null);

  // Карточка (общий вид Ковчега): сверху проект ссылкой, название, ряд действий («В «Сейчас»» / «Из «Сейчас»» и
  // «Сделано» акцентом), свойства — строками через тонкую линию: подпись слева, поле справа.
  const crumb = zc ? el('a', { class: 'crumb', href: '#', title: 'Зачем: ' + [...zc.path, zc.title].join(' › '), onclick: (e) => { e.preventDefault(); zachemGo(zc); } },
    [...zc.path.slice(-1), zc.title].join(' › ')) : el('span', { class: 'crumb' }, 'Без «Зачем»');
  const acts = t ? el('div', { class: 'card-acts' },
    isOpen(t) ? [
      // «Сейчас» — отметка владельца (13.10.2026): только своё и не цель, сразу, срок не трогает. Солнца
      // «На сегодня» / «На завтра» нет с 14.10.2026 — срок ставит строка «Когда» ниже.
      canNow(t) ? el('button', { class: 'btn' + (isNow(t) ? ' on' : ''), 'aria-pressed': isNow(t) ? 'true' : 'false',
        title: isNow(t) ? 'Убрать из «Сейчас» — срок не меняется' : `Взять в «Сейчас» на сегодня — до ${NOW_MAX} дел`, onclick: () => toggleNow([t.id]) },
      icon('bolt', 17), isNow(t) ? 'Из «Сейчас»' : 'В «Сейчас»') : null,
      canNest(t) && !parentOf(t) ? el('button', { class: 'btn', title: 'Положить это дело внутрь другого', onclick: (e) => nestPop(e.currentTarget, [t.id]) },
        icon('list', 17), 'Вложить в…') : null,
      el('button', { class: 'btn main done-key', title: 'Сделано', onclick: () => toggleDone([t]) }, icon('check', 17), 'Сделано'),
    ] : el('button', { class: 'btn', onclick: () => toggleDone([t]) }, 'Вернуть в работу')) : null;
  const card = el('div', { class: 'card' },
    el('div', { class: 'card-top' },
      el('button', { class: 'icon-btn card-back', title: 'Назад', 'aria-label': 'Назад к списку', onclick: close }, icon('arrowLeft', 20)),
      sug ? el('span', { class: 'crumb' }, 'Предложение') : crumb,
      t && t.status !== 'open' ? el('span', { class: 'faint' }, t.status === 'done' ? 'сделано' : 'отменено') : null,
      el('button', { class: 'icon-btn card-x', title: 'Закрыть (Esc)', 'aria-label': 'Закрыть', onclick: close }, icon('x', 18))),
    // Откуда дело (наговор, встреча, Telegram…) — сразу под названием и заметно: владельцу это нравится (13.10.2026).
    title, t ? forLine(t) : null, t ? originLine(t) : null, acts,
    // Claude только про это дело и «Кстати» (13.10.2026); нет ключа на сервере — строка только «Для этого» (15.10.2026):
    // дело внутри заводится и без Claude, названием, как написано.
    t ? (S.me.claude ? cardSay(t) : canStep(t) ? cardSay(t, { stepOnly: true }) : null) : null,
    // Дела внутри этого (15.10.2026): по месту, флажок, раньше / позже.
    t ? insideBox(t) : null,
    // Место в дереве жизни: зачем это дело и что будет, когда сделаешь (13.10.2026).
    t && isMine(t) && krugOn() ? el('div', { class: 'place-box' }, el('div', { class: 'place-h' }, 'Место в дереве'), placeBlock(t)) : null,
    props,
    notes);
  const btns = el('div', { class: 'btns' });
  let personLink = null;
  if (t) {
    if (isOpen(t)) btns.append(el('button', { class: 'btn bad', onclick: async () => { if (confirm('Отменить дело? Оно останется в журнале.')) { try { await op1({ op: 'task.cancel', id: t.id }); render(); } catch (e) { fail(e); } } } }, 'Отменить дело'));
    const who = person(t.person_id); // проект — ссылкой наверху карточки
    if (who) {
      const org = orgLabel(who.org_id);
      personLink = el('a', { class: 'person-link', href: '#/h/' + who.id }, avatar(who),
        el('span', { class: 't' }, who.name, org ? el('span', { class: 'faint' }, ' · ' + org.name) : null, who.role ? el('span', { class: 's' }, who.role) : null),
        icon('chev', 20));
    }
  } else {
    btns.append(el('button', {
      class: 'btn main',
      onclick: () => {
        const set = {};
        for (const k of ['project_id', 'deal_id', 'ball', 'owed', 'person_id', 'due_date', 'nudge_on', 'estimate_min', 'money', 'labels', 'focus_on', 'want']) {
          if (src[k] !== undefined && src[k] !== null && src[k] !== '') set[k] = src[k];
        }
        // Выбрал «Никто» у названного человека — так и скажем серверу: без этого «Да» при двусмысленном имени он
        // отклонит («Кто это — X или Y?», аудит 15.10.2026, №1).
        if (src.person_id === null && (sug.payload || {}).person_name) set.person_id = null;
        set.title = title.value.trim() || src.title;
        // Заметку — только если её правили (аудит №8): заметка машины из переписки и встреч ложится приватным
        // комментарием, а не в дело; та же заметка в set.notes была бы «словом владельца». Сервер сверяет и сам.
        const said = (sug.payload || {}).notes || '';
        if (notes.value.trim().split(/\s+/).join(' ') !== said.trim().split(/\s+/).join(' ')) set.notes = notes.value.trim() || null;
        decide([sug.id], 'accept', null, set);
      },
    }, 'Принять'), el('button', { class: 'btn', onclick: close }, 'Закрыть'));
  }
  card.append(btns, personLink || '');
  if (t) {
    const x = extras.get(t.id);
    const add = el('input', { placeholder: 'Комментарий…' });
    // «Только мне» (аудит №8): приватный комментарий — команда, которая видит дело, его не видит. Только при флаге privacy.
    const mine = privacyOn() ? el('input', { type: 'checkbox', title: 'Команда этого комментария не увидит' }) : null;
    const send = async () => {
      if (!add.value.trim()) return;
      const c = { task_id: t.id, text: add.value.trim(), ...(mine && mine.checked ? { private: true } : {}) };
      try { await op1({ op: 'comment.add', comment: c }); add.value = ''; loadCardExtras(t.id); } catch (e) { fail(e); }
    };
    add.addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });
    card.append(el('h3', {}, 'Комментарии'),
      el('div', {}, (x ? x.comments : []).map((c) => el('div', { class: 'comment' + (c.private ? ' private' : '') },
        el('div', { class: 'who' }, `${c.author_id} · ${c.created_at.slice(0, 16).replace('T', ' ')}`, c.private ? lockMark('только тебе') : null), c.text))),
      el('div', { class: 'add-comment' }, add, mine ? el('label', { class: 'only-me' }, mine, ' только мне') : null,
        el('button', { class: 'btn small', onclick: send }, 'Добавить')));
    if (x) {
      card.append(el('details', {}, el('summary', {}, 'Журнал правок'), x.history.map((h) =>
        el('div', { class: 'hist' }, `${h.at.slice(0, 16).replace('T', ' ')} · ${h.actor} (${h.via}) · ${histWhat(h)}`))));
    }
  }
  return el('section', { class: 'card-pane', 'data-key': 'task:' + (S.cardId || 'draft') }, card);
}

/** Что в строке журнала карточки: поля дела; у строки Круга (её сервер отдаёт только хозяину, 15.10.2026) —
 *  «вложено в «…»», «вынуто», «Зачем: …», направления — словами: так видно, когда и куда дело вложили. */
function histWhat(h) {
  const a = h.after || {};
  if (h.entity === 'tasks.krug' && ('why_id' in a || 'directions' in a || 'goal' in a)) {
    const out = [];
    if ('why_id' in a) {
      const w = a.why_id ? S.tasks.get(a.why_id) : null;
      out.push(!a.why_id ? (h.before && h.before.why_id ? 'вынуто, без «Зачем»' : 'без «Зачем»')
        : w && !w.goal ? 'вложено в «' + w.title + '»' : 'Зачем: ' + (w ? w.title : 'цель'));
    }
    if ('directions' in a && (h.op === 'update' || (a.directions || []).length)) {
      out.push('направления: ' + ((a.directions || []).map(dirName).join(', ') || 'свои сняты'));
    }
    if ('goal' in a && (h.op === 'update' || a.goal)) out.push(a.goal ? 'стало целью' : 'стало делом');
    if (out.length) return out.join(', ');
  }
  return h.op === 'update' ? Object.keys(a).join(', ') : h.op;
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
  crm: { title: 'Воронка', icon: 'funnel' },
  clients: { title: 'Клиенты', icon: 'briefcase' },
  people: { title: 'Люди', icon: 'users' },
  ties: { title: 'Связи', icon: 'link' },
  money: { title: 'Деньги', icon: 'wallet', money: true },
  // Встречи (12.10.2026): все встречи службы с единым названием «Клиент: тема» — только владельцу (их видит он один).
  meetings: { title: 'Встречи', icon: 'mic', owner: true },
};
const narrow = () => window.matchMedia('(max-width: 900px)').matches; // телефон: пустые колонки воронки не показываем
/** Сервер держит настройки телефона (флаг phone, dela_0015): есть страница «Телефон» и её пункты в меню. */
const phoneOn = () => ((S.me && S.me.features) || []).includes('phone');
// Закон приватности (15.10.2026, аудит №8–11): «только мне», «скрыть от команды», «Только тебе». Без флага старый сервер
// отверг бы операцию с полем private целиком («нет таких полей») — поэтому всё новое только при нём.
const privacyOn = () => ((S.me && S.me.features) || []).includes('privacy');
// Личные каналы владельца: из них машина пишет только в приватное (store.PRIVATE_SOURCES).
const PRIVATE_SOURCES = ['meeting', 'telegram', 'userbot', 'contacts'];
const lockMark = (title = 'видишь только ты') => el('span', { class: 'lock-mark', title }, icon('lock', 12), ' ' + title);
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
  // Ошибка без данных — повтор не чаще раза в 5 с: до 12.10.2026 каждая перерисовка звала вид заново, и страница с
  // ошибкой слала запрос за запросом без паузы.
  if (c && c.error && !c.loading && Date.now() - c.at < 5000) return c.data;
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
function dealTile(d, { drag } = {}) {
  const meta = [];
  if (d.fee_kop) meta.push(el('span', { class: 'money' }, rubShort(d.fee_kop) + (d.p_eff != null && ['lead', 'proposal'].includes(d.stage) ? ` · ${d.p_eff}%` : '')));
  if (d.lead_person_id) meta.push(el('span', {}, pName(d.lead_person_id)));
  if (d.minutes_30) meta.push(el('span', { title: 'время из Засечки за 30 дней' }, hrs(d.minutes_30)));
  const next = d.next_task
    ? el('div', { class: 'next' }, d.next_task.title + (d.next_task.due_date ? ' · ' + D.ddmm(d.next_task.due_date) : ''),
      // Дело клиента без проекта — шаг этого проекта, пока он у клиента один (аудит №2): сервер так и считает «застыла».
      d.next_task.via_client ? el('span', { class: 'via-client', title: 'Дело клиента без проекта: проект у клиента один — это его шаг' }, ' · дело клиента') : null)
    : (d.stage !== 'archive' ? el('div', { class: 'next none' }, 'нет следующего дела') : null);
  const quiet = d.stage !== 'archive' && d.quiet_days > 30 ? el('span', { class: 'quiet' }, `тишина ${d.quiet_days} дн.`) : null;
  const tile = el('div', { class: 'dtile' + (route().id === d.id ? ' on' : '') + (d.stale ? ' stale' : ''), 'data-deal': d.id, onclick: () => openDeal(d.id),
    draggable: drag ? 'true' : null },
    el('div', { class: 'dname' }, d.name),
    el('div', { class: 'dclient' }, d.project_name, d.deal_type ? ' · ' + d.deal_type : ''),
    meta.length || quiet ? el('div', { class: 'dmeta' }, meta, quiet) : null, next,
    d.stage === 'archive' ? el('div', { class: 'dmeta' }, el('span', { class: 'out-' + (d.outcome || 'none') }, stageWord(d)),
      d.closed_on ? el('span', {}, D.ddmm(d.closed_on)) : null, d.lost_reason ? el('span', {}, d.lost_reason) : null) : null);
  if (drag) {
    tile.addEventListener('dragstart', (e) => { e.dataTransfer.setData('text/plain', d.id); e.dataTransfer.effectAllowed = 'move'; tile.classList.add('dragging'); });
    tile.addEventListener('dragend', () => tile.classList.remove('dragging'));
  }
  return tile;
}
/** Колонка доски принимает проект: перетащил — новая стадия (владелец 09.10.2026). Карточка встаёт на место
 *  сразу, не дожидаясь ответа сервера: вид воронки считает сервер, и без этого она прыгала бы назад. */
function dropStage(col, stage) {
  col.addEventListener('dragover', (e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; col.classList.add('drop'); });
  col.addEventListener('dragleave', (e) => { if (!col.contains(e.relatedTarget)) col.classList.remove('drop'); });
  col.addEventListener('drop', (e) => {
    e.preventDefault();
    col.classList.remove('drop');
    const id = e.dataTransfer.getData('text/plain');
    const c = crmCache.get('/api/view/pipeline');
    const d = c && c.data && c.data.deals.find((x) => x.id === id);
    if (!d || d.stage === stage) return;
    const was = d.stage;
    d.stage = stage;
    render();
    dealOp({ op: 'deal.set', id, set: { stage } }).then((r) => {
      if (!r) { d.stage = was; render(); return; }
      toast(`${d.name}: ${STAGE[stage]}`, () => dealOp({ op: 'deal.set', id, set: { stage: was } }));
    });
  });
  return col;
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
    return dropStage(el('div', { class: 'col' },
      el('div', { class: 'col-h' }, el('b', {}, STAGE[s]), el('span', { class: 'n' }, items.length),
        v.money && st.fee_kop ? el('span', { class: 'sum' }, rubShort(st.fee_kop)) : null),
      items.map((d) => dealTile(d, { drag: !narrow() }))), s);
  };
  const body = S.crmList
    ? OPEN_STAGES.map((s) => { const items = live.filter((d) => d.stage === s); return items.length ? el('div', { class: 'group' }, el('h2', {}, STAGE[s], el('span', { class: 'n' }, items.length)), el('div', { class: 'dlist' }, items.map((d) => dealTile(d)))) : null; })
    : el('div', { class: 'board' }, OPEN_STAGES.filter((s) => !narrow() || live.some((d) => d.stage === s)).map(col));
  if (!S.crmList && !narrow()) sub.push(el('span', { class: 'faint' }, 'проект перетаскивается между стадиями'));
  return [head('Воронка', sub),
    el('div', { class: 'body wide' }, tb, body,
      closed.length ? el('div', { class: 'group' }, el('h2', {}, 'Закрыты за 90 дней', el('span', { class: 'n' }, closed.length)),
        el('div', { class: 'dlist' }, closed.map((d) => dealTile(d)))) : null,
      !live.length && !closed.length ? el('div', { class: 'empty' }, 'Сделок нет. Новая — со страницы клиента, кнопкой «+ Сделка».') : null)];
}

// ── Клиенты ─────────────────────────────────────────────────────────────
// Проект клиента — его сделка в CRM («ПТИЦ: хедж юаня» у клиента «ПТИЦ»): владелец зовёт их проектами.
const clientDeals = (id) => [...S.deals.values()].filter((d) => d.project_id === id)
  .sort((a, b) => (a.stage === 'archive') - (b.stage === 'archive') || OPEN_STAGES.indexOf(a.stage) - OPEN_STAGES.indexOf(b.stage) || a.name.localeCompare(b.name, 'ru'));
/** Пока проект (сделка) у клиента один живой, открытые дела клиента без проекта — его шаги («дело клиента»): то же
 *  правило, что у сервера (crm.STEP, crm.only_deal; аудит 15.10.2026, №2) — иначе Воронка говорила «застыла» при живом
 *  деле клиента. Сервер присылает своё слово (deal.solo) — ему и верим. */
const dealSolo = (d) => (d.solo != null ? !!d.solo
  : d.stage !== 'archive' && clientDeals(d.project_id).filter((x) => x.stage !== 'archive').length === 1);
const clientSteps = (d) => (dealSolo(d) ? all().filter((t) => !t.deal_id && t.project_id === d.project_id && isOpen(t) && !t.goal) : []);
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
  // Порядок — с сервера: сверху те, с кем свежее всего были дела (владелец 09.10.2026).
  let rows = v.clients.filter((c) => (S.clientArch ? true : !c.archived_at) && (!S.clientRel || c.status === 'relations'));
  if (q) rows = rows.filter((c) => norm(c.name + ' ' + (c.aliases || []).join(' ') + ' ' + (c.org || '')).includes(q));
  const search = el('input', { class: 'inline-search', type: 'search', placeholder: 'Найти клиента', value: S.clientQ || '' });
  search.addEventListener('input', () => { S.clientQ = search.value; const pos = search.selectionStart; render(); const s2 = document.querySelector('.inline-search'); if (s2) { s2.focus(); s2.setSelectionRange(pos, pos); } });
  // Проекты клиента (сделки CRM) раскрываются под строкой клиента — так же, как в меню слева.
  const dealsOf = clientDeals, toggle = toggleClientOpen;
  const allOpen = rows.length && rows.every((c) => S.clientOpen.has(c.id));
  const tb = el('div', { class: 'toolbar' }, search,
    el('button', { class: 'chip-btn' + (allOpen ? ' on' : ''), onclick: () => {
      rows.forEach((c) => (allOpen ? S.clientOpen.delete(c.id) : S.clientOpen.add(c.id))); LS.set('clientOpen', [...S.clientOpen]); render();
    } }, allOpen ? 'Свернуть «Зачем»' : 'Раскрыть «Зачем»'),
    el('button', { class: 'chip-btn' + (S.clientRel ? ' on' : ''), title: 'Уже не лид, но и не сделка: работали, держим связь',
      onclick: () => { S.clientRel = !S.clientRel; render(); } }, 'Поддержание отношений'),
    el('button', { class: 'chip-btn' + (S.clientArch ? ' on' : ''), onclick: () => { S.clientArch = !S.clientArch; render(); } }, 'И архив'),
    el('button', { class: 'chip-btn', onclick: () => newClient() }, '+ Клиент'));
  const dealLine = (d) => {
    const next = all().filter((t) => t.deal_id === d.id && isOpen(t)).sort(sortTasks)[0] || clientSteps(d).sort(sortTasks)[0];
    return el('div', { class: 'cdeal' + (d.stage === 'archive' ? ' arch' : ''), 'data-deal': d.id, onclick: (e) => { e.stopPropagation(); openDeal(d.id); } },
      el('span', { class: 'cd-stage st-' + d.stage }, stageWord(d)),
      el('span', { class: 'cd-name' }, dealShort(d, project(d.project_id))),
      next ? el('span', { class: 'cd-next' + (isLate(next) ? ' late' : '') }, next.title + (next.due_date ? ' · ' + D.ddmm(next.due_date) : ''),
        next.deal_id ? null : el('span', { class: 'via-client' }, ' · дело клиента'))
        : d.stage !== 'archive' ? el('span', { class: 'cd-next none' }, 'нет следующего дела') : null);
  };
  const row = (c) => {
    const bits = [];
    if (c.status === 'relations') bits.push(el('span', { class: 'rel' }, CLIENT_STATUS.relations));
    if (c.stages && c.stages.length) bits.push(el('span', {}, c.stages.map((s) => STAGE[s]).join(', ')));
    else if (c.status !== 'relations') bits.push(el('span', { class: 'faint' }, c.all_deals ? 'проекты в архиве' : 'без проектов'));
    bits.push(el('span', { class: c.last_touch && D.diff(S.today, c.last_touch.slice(0, 10)) > 45 ? 'late' : '' }, c.last_touch ? 'контакт ' + ago(c.last_touch) : 'контактов нет'));
    if (v.money && c.paid_year_kop) bits.push(el('span', { class: 'money' }, 'за год ' + rubShort(c.paid_year_kop)));
    if (v.money && c.invoiced_kop) bits.push(el('span', { class: 'late' }, 'ждём ' + rubShort(c.invoiced_kop)));
    if (c.minutes_90) bits.push(el('span', { title: 'Засечка, 90 дней' }, hrs(c.minutes_90)));
    const deals = dealsOf(c.id);
    const shownDeals = deals.filter((d) => d.stage !== 'archive' || S.clientArch);
    const open = S.clientOpen.has(c.id);
    return el('div', { class: 'cblock' + (open ? ' open' : '') },
      el('div', { class: 'crow', onclick: () => go('#/p/' + c.id) },
        el('button', { class: 'caret-btn', title: open ? 'Свернуть «Зачем»' : 'Показать «Зачем»', disabled: !deals.length,
          'aria-expanded': open ? 'true' : 'false', onclick: (e) => { e.stopPropagation(); toggle(c.id); } }, deals.length ? icon('chevron', 15) : null),
        el('div', { class: 'main' },
          el('div', { class: 'title' }, c.name, c.org && norm(c.org) !== norm(c.name) ? el('span', { class: 'faint' }, ' · ' + c.org) : null,
            c.archived_at ? el('span', { class: 'faint' }, ' · архив') : null,
            deals.length ? el('span', { class: 'faint' }, ' · «Зачем»: ' + deals.length) : null),
          el('div', { class: 'chips' }, bits),
          !open && c.next_task ? el('div', { class: 'next' }, c.next_task.title + (c.next_task.due_date ? ' · ' + D.ddmm(c.next_task.due_date) : ''))
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
        waitBy.get(x.id) ? el('span', { class: 'ball-waiting' }, 'жду: ' + waitBy.get(x.id)) : null),
      (() => {
        const ds = personDeals(x.id).filter(({ d }) => d.stage !== 'archive');
        return ds.length ? el('div', { class: 'row-projs' }, ds.slice(0, 4).map(({ d, role }) => dealChip(d, role, true)),
          ds.length > 4 ? el('span', { class: 'faint' }, `ещё ${ds.length - 4}`) : null) : null;
      })()),
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
  const alias = (anchor, name) => {
    const projs = liveProjects().filter((p) => p.kind === 'client').sort((a, b) => a.name.localeCompare(b.name, 'ru'));
    const ppl = livePeople().sort((a, b) => personName(a).localeCompare(personName(b), 'ru'));
    choicePop(anchor, { title: `«${name}» — это:`, items: [...projectItems(projs).map((x) => ({ ...x, v: 'p:' + x.v })), ...personItems(ppl).map((x) => ({ ...x, v: 'h:' + x.v }))],
      onPick: async (v) => {
      if (!v) return;
      const [k, id] = v.split(':');
      const x = k === 'p' ? project(id) : person(id);
      try {
        await op1({ op: k === 'p' ? 'project.set' : 'person.set', id, set: { aliases: [...(x.aliases || []), name] } });
        crmDirty(); toast(`«${name}» теперь ${k === 'p' ? 'клиент' : 'человек'} ${x.name} — часы встали на место`); render();
      } catch (e) { fail(e); }
    } });
  };
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
    // Счёт выставлен — сразу кому и как отправили: встаёт в хронологию (владелец 09.10.2026).
    if (!p.invoiced_on) acts.push(el('button', { class: 'btn small', onclick: (e) => { e.stopPropagation(); invoicePop(e.currentTarget, p); } }, 'Счёт выставлен'));
    acts.push(el('button', { class: 'btn small ok', onclick: (e) => { e.stopPropagation(); payOp(p.id, { paid_on: S.today }, 'Оплата получена'); } }, 'Оплачено'));
  }
  return el('div', { class: 'prow' + (p.cancelled_at ? ' cancelled' : ''), onclick: withDeal ? () => openDeal(p.deal_id) : null },
    el('div', { class: 'main' },
      el('div', {}, el('b', {}, rub(p.amount_kop)), ' ', PAY_KIND[p.kind] || p.kind, p.title ? ' · ' + p.title : '',
        withDeal ? el('span', { class: 'faint' }, ' · ' + [p.project_name, p.deal_name].filter(Boolean).join(' / ')) : null),
      el('div', { class: 'chips' }, st, p.invoiced_on && (p.sent_to || p.sent_via) ? el('span', {}, [p.sent_to && 'кому: ' + p.sent_to, p.sent_via].filter(Boolean).join(' ')) : null,
        p.cancelled_at ? el('span', {}, 'отменено') : null, p.note ? el('span', {}, p.note) : null)),
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
        el('div', { class: 'dlist' }, v.unplanned.map((d) => dealTile(d)))) : null,
      sec('Пришло за 4 месяца', v.paid),
      !v.overdue.length && !v.invoiced.length && !v.expected.length && !v.later.length && !v.paid.length
        ? el('div', { class: 'empty' }, 'Оплат пока нет. Их заводят в карточке сделки: аванс, этапы, ретейнер, success fee.') : null)];
}

// ── Карточки клиента и проекта (владелец 09.10.2026) ────────────────────
// Одно устройство у обеих: сверху описание — правится тут же или словами в строке Claude, — и статус; под
// ними с двух сторон люди клиента и команда; дальше одно под другим — проекты (у клиента), дела, хронология
// (звонки, встречи с короткими итогами и ссылками, счета: кому и как ушли), деньги, файлы. Пилюли в шапке
// листают к разделу. Тексты Notion («Следующий шаг», «Мяч», «Лог») веб больше не показывает.
function sec(key, title, extra, ...kids) {
  return el('section', { class: 'sec', id: 'sec-' + key },
    title ? el('h2', { class: 'sec-h' }, title, extra || null) : null, kids);
}
const secN = (n) => (n ? el('span', { class: 'n' }, n) : null);
/** «Встречи» карточки сделки и клиента (аудит 15.10.2026, №3): карточка сделки не видела ни одной из своих встреч —
 *  только хронологию, куда запись встречи ложилась без сделки. Список — с сервера (вид deal или client: meetings,
 *  meetings_n — встречи по карточке встречи, свежие сверху), строки — как на странице «Встречи» (meetRow), под ними —
 *  «все встречи … →» на ту страницу с отбором. Видит только владелец встреч: команде поле пустое — блока нет. */
function meetingsSec(v, href, what) {
  const items = (v && v.meetings) || [];
  if (!items.length) return null;
  const n = v.meetings_n || items.length;
  return sec('meetings', 'Встречи', secN(n), el('div', { class: 'rows mrows' }, items.slice(0, MEET_CARD).map(meetRow)),
    el('a', { class: 'link meet-more', href }, `все встречи ${what}` + (n > MEET_CARD ? ` · ${n}` : '') + ' →'));
}
const MEET_CARD = 8; // в карточке — свежие; остальные — страницей «Встречи» с отбором

// ── «Только тебе» (dela_0027, аудит №8–11; место этапа 6) ───────────────
// Наблюдения владельца — оценки людей, несостыковки, неуверенность, «кто за столом», что уточнить: видит только он
// (сервер отдаёт поле insights видов только ему; команде — []). Пишет рукой «+ только мне» и разбор встреч и дайджеста
// (этап 6). «Видел», «Мимо», «Убрать»; «Мимо» и «Неверно» свёрнуты строкой «ещё N». Только при флаге privacy.
const INSIGHT_KIND = { note: 'заметка', conflict: 'несостыковка', numbers: 'цифры', hedge: 'неуверенность', self: 'о себе',
  objection: 'возражение', table: 'кто за столом', ask: 'уточнить', bypass: 'в обход', signal: 'сигнал', risk: 'риск',
  mandate: 'мандат', position: 'позиция', personal: 'личное', occasion: 'повод' };
const INSIGHT_OFF = ['dismissed', 'wrong'];
async function insightOp(op, msg) {
  try { await op1(op); crmDirty(); if (msg) toast(msg); render(); } catch (e) { fail(e); }
}
function insightRow(x) {
  const from = x.meeting_id ? el('a', { class: 'src', href: '#/m/' + x.meeting_id }, ' встреча →')
    : x.source === 'digest' && x.source_ref ? el('a', { class: 'src', href: '#/digest/' + encodeURIComponent(x.source_ref) }, ' сводка →') : null;
  return el('div', { class: 'ins-row' + (x.state === 'new' ? ' new' : '') },
    el('div', { class: 'tl-when' }, D.ddmm(String(x.at).slice(0, 10))),
    el('div', { class: 'tl-what' },
      el('div', { class: 'tl-head' }, el('span', { class: 'tl-kind' }, INSIGHT_KIND[x.kind] || x.kind), ' ', x.text),
      x.quote ? el('div', { class: 'ins-quote' }, '«' + x.quote + '»') : null,
      el('div', { class: 'tl-meta' }, (x.people || []).join(', '), from,
        x.state === 'new' ? el('button', { class: 'link-btn', onclick: () => insightOp({ op: 'insight.set', id: x.id, set: { state: 'seen' } }) }, ' видел') : null,
        INSIGHT_OFF.includes(x.state) ? null : el('button', { class: 'link-btn', onclick: () => insightOp({ op: 'insight.set', id: x.id, set: { state: 'dismissed' } }, 'Мимо — свернул') }, ' мимо'),
        el('button', { class: 'link-btn', onclick: () => insightOp({ op: 'insight.delete', id: x.id }, 'Убрал') }, ' убрать'))));
}
/** Блок «Только тебе» карточки: v — вид (client, deal, dossier, meeting) с полем insights; about — о ком новая запись. */
function insightsSec(v, about) {
  if (!privacyOn() || !S.me || S.me.role !== 'owner' || !v || !Array.isArray(v.insights)) return null;
  const key = 'ins:' + JSON.stringify(about);
  const live = v.insights.filter((x) => !INSIGHT_OFF.includes(x.state));
  const off = v.insights.filter((x) => INSIGHT_OFF.includes(x.state));
  const open = S.insOpen === key;
  const add = el('input', { placeholder: '+ только мне: что заметил, как оцениваю, что уточнить…' });
  const send = () => {
    const text = add.value.trim();
    if (text) insightOp({ op: 'insight.add', insight: { kind: 'note', source: 'web', text, ...about } }, 'Записал — видишь только ты');
  };
  add.addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });
  return sec('only-you', null, null, el('div', { class: 'only-you' },
    el('div', { class: 'only-you-h' }, icon('lock', 13), ' Только тебе', live.length ? el('span', { class: 'n' }, ' ' + live.length) : null,
      el('span', { class: 'faint' }, ' · команда этого не видит')),
    el('div', { class: 'rows tl-rows' }, live.map(insightRow)),
    off.length ? el('button', { class: 'link-btn', onclick: () => { S.insOpen = open ? null : key; render(); } }, open ? 'свернуть' : `ещё ${off.length}`) : null,
    open ? el('div', { class: 'rows tl-rows faint' }, off.map(insightRow)) : null,
    el('div', { class: 'add-comment' }, add, el('button', { class: 'btn small', onclick: send }, 'Записать'))));
}

/** Описание — поле, которое растёт по тексту и сохраняется, когда из него вышел. */
function aboutBox(value, placeholder, save) {
  const ta = el('textarea', { class: 'about', rows: 2, placeholder }, value || '');
  const fit = () => { ta.style.height = ''; ta.style.height = Math.max(ta.scrollHeight + 2, 60) + 'px'; };
  ta.addEventListener('input', fit);
  setTimeout(fit);
  ta.addEventListener('change', () => { const x = ta.value.trim() || null; if (x !== (value || null)) save(x); });
  return ta;
}

// ── Люди: с двух сторон, добавление — поиском ───────────────────────────
/** Колонка людей: плашки (щелчок — откуда он и должность, крестик — убрать отсюда) и «+ человек». */
function sideCol(title, list, { remove, removable, add, mark, empty, note } = {}) {
  return el('div', { class: 'side-col' },
    el('div', { class: 'sc-h' }, title, list.length ? el('span', { class: 'n' }, list.length) : null),
    el('div', { class: 'sc-chips' },
      list.map((x) => el('span', { class: 'pchip' },
        el('button', { class: 'pc-main', title: 'Откуда он, должность', onclick: (e) => personPop(e.currentTarget, x, mark && mark.acts ? mark.acts(x) : null) },
          avatar(x), el('span', { class: 'pc-name' }, personName(x)),
          mark && mark.word(x) ? el('span', { class: 'pc-role lead' }, mark.word(x)) : x.role ? el('span', { class: 'pc-role' }, x.role) : null),
        remove && (!removable || removable(x)) ? el('button', { class: 'pc-x', title: 'Убрать отсюда', onclick: () => remove(x) }, icon('x', 11)) : null)),
      !list.length ? el('span', { class: 'faint' }, empty || 'никого') : null,
      add ? el('button', { class: 'pchip add', onclick: (e) => add(e.currentTarget) }, icon('plus', 12), 'человек') : null),
    note ? el('div', { class: 'sc-note' }, note) : null);
}

/** «+ человек» — поисковая строка, а не выпадающий список (владелец 09.10.2026): сразу видны свои (команда
 *  или люди этого клиента), набираешь — ищет среди всех по имени, компании, должности; нет такого — «Новый». */
function personSearchPop(anchor, { title, base, exclude = [], pick, create, empty }) {
  setTimeout(() => {
    const q = el('input', { class: 'ps-q', placeholder: 'Имя, компания, должность…', autocomplete: 'off' });
    const list = el('div', { class: 'ps-list' });
    let items = [], at = 0;
    const keys = (x) => [x.name, x.short, ...(x.aliases || []), x.role, orgLabel(x.org_id)?.name].filter(Boolean);
    const draw = () => {
      const words = norm(q.value).split(/\s+/).filter(Boolean);
      const pool = livePeople().filter((x) => !x.merged_into && !exclude.includes(x.id));
      if (!words.length) items = base().filter((x) => x && !exclude.includes(x.id)).slice(0, 14);
      else {
        const score = (x) => {
          const ks = keys(x).map(norm);
          let sum = 0;
          for (const w of words) {
            const r = ks.some((k) => k.startsWith(w)) ? 0 : ks.some((k) => k.split(/[\s\-–«»"().,]+/).some((p) => p.startsWith(w))) ? 1
              : ks.some((k) => k.includes(w)) ? 2 : -1;
            if (r < 0) return -1;
            sum += r;
          }
          return sum;
        };
        items = pool.map((x) => [x, score(x)]).filter(([, s]) => s >= 0)
          .sort((a, b) => a[1] - b[1] || personName(a[0]).localeCompare(personName(b[0]), 'ru')).map(([x]) => x).slice(0, 14);
      }
      const typed = q.value.trim();
      at = Math.min(at, Math.max(0, items.length - (typed && create ? 0 : 1)));
      list.replaceChildren(...[
        ...items.map((x, i) => el('button', { class: 'ps-item' + (i === at ? ' on' : ''), onmousedown: (e) => { e.preventDefault(); done(x); } },
          avatar(x), el('span', { class: 'ps-t' }, el('span', { class: 'ps-n' }, x.name),
            el('span', { class: 'ps-s' }, [x.short && x.short !== x.name ? x.short : null, x.role, orgLabel(x.org_id)?.name].filter(Boolean).join(' · '))))),
        !items.length && !typed ? el('div', { class: 'ps-empty' }, empty || 'Набери имя — найду среди всех людей') : null,
        !items.length && typed && !create ? el('div', { class: 'ps-empty' }, 'Никого не нашёл') : null,
        // Без create («Это тот же, что…») — только выбор: нового человека здесь не заводят.
        typed && create ? el('button', { class: 'ps-item ps-new' + (at === items.length ? ' on' : ''), onmousedown: (e) => { e.preventDefault(); closePop(); create(typed); } },
          icon('plus', 16), el('span', { class: 'ps-t' }, el('span', { class: 'ps-n' }, `Новый: «${typed}»`), el('span', { class: 'ps-s' }, 'завести в справочнике'))) : null,
      ].filter(Boolean));
    };
    const done = (x) => { closePop(); pick(x.id); };
    q.addEventListener('input', () => { at = 0; draw(); });
    q.addEventListener('keydown', (e) => {
      const n = items.length + (q.value.trim() && create ? 1 : 0);
      if (e.key === 'ArrowDown' && n) { e.preventDefault(); at = (at + 1) % n; draw(); }
      if (e.key === 'ArrowUp' && n) { e.preventDefault(); at = (at - 1 + n) % n; draw(); }
      if (e.key === 'Enter') {
        e.preventDefault();
        if (at < items.length) done(items[at]); else if (q.value.trim() && create) { closePop(); create(q.value.trim()); }
      }
      if (e.key === 'Escape') { e.stopPropagation(); closePop(); }
    });
    draw();
    const pop = popAt(anchor, [el('div', { class: 'pop-h' }, title), q, list]);
    pop.classList.add('ps-pop');
    q.focus();
  }, 0);
}
/** Люди этого клиента: его организация и люди его проектов. */
function clientPeopleOf(p) {
  const ids = new Set();
  for (const d of S.deals.values()) if (d.project_id === p.id) (d.person_ids || []).forEach((x) => ids.add(x));
  return livePeople().filter((x) => !x.merged_into && ((p.org_id && x.org_id === p.org_id) || ids.has(x.id)))
    .sort((a, b) => personName(a).localeCompare(personName(b), 'ru'));
}

// ── Хронология: звонки, встречи, счета, Засечка ─────────────────────────
const TL_SHOWN = 10;
const SENT_VIA = ['почтой', 'в Telegram', 'через ЭДО', 'лично', 'курьером'];
/** Итог встречи — коротко: «Тема: итог» → тема строкой, итог в три строки (щелчок — целиком), договорённости
 *  списком (владелец 09.10.2026: «звонки, встречи с короткими итогами со ссылками на встречи»). */
function tlLine(r, showDeal) {
  if (r.e) {
    const e = r.e;
    return el('div', { class: 'tl-row work' }, el('div', { class: 'tl-when' }, D.ddmm(e.day)),
      el('div', { class: 'tl-what' }, el('span', { class: 'tl-kind' }, icon('clock', 12), ' Засечка · ' + dur(e.minutes)), ' ', e.title || 'работа'));
  }
  if (r.pay) {
    const p = r.pay;
    const what = [rub(p.amount_kop), PAY_KIND[p.kind] || p.kind, p.title, showDeal && p.deal_name].filter(Boolean).join(' · ');
    if (r.what === 'paid') {
      return el('div', { class: 'tl-row pay' }, el('div', { class: 'tl-when' }, D.ddmm(p.paid_on)),
        el('div', { class: 'tl-what' }, el('span', { class: 'tl-kind' }, 'оплата пришла'), ' ', what));
    }
    const sent = [p.sent_to && 'кому: ' + p.sent_to, p.sent_via].filter(Boolean).join(' · ');
    return el('div', { class: 'tl-row pay' }, el('div', { class: 'tl-when' }, D.ddmm(p.invoiced_on)),
      el('div', { class: 'tl-what' }, el('span', { class: 'tl-kind' }, 'счёт'), ' ', what,
        el('div', { class: 'tl-meta' }, sent || 'кому и как отправили — не записано',
          el('button', { class: 'link-btn', onclick: (ev) => invoicePop(ev.currentTarget, p) }, sent ? ' поправить' : ' записать'))));
  }
  const i = r.i;
  const who = (i.person_ids || []).map((x) => personName(person(x))).filter(Boolean);
  const sum = (i.summary || '').trim();
  // Тема — до последнего «: » в начале (до первой точки, не дальше 110 знаков): у встреч в теме бывает своё
  // двоеточие — «Клиент: тема: итог».
  const stop = Math.min(110, sum.indexOf('. ') < 0 ? sum.length : sum.indexOf('. '));
  const cut = sum.slice(0, stop).lastIndexOf(': ');
  const m = cut >= 3 ? [null, sum.slice(0, cut), sum.slice(cut + 2)] : null;
  const steps = (i.next_step || '').split(/\s*;\s*|\n+/).map((x) => x.replace(/^[→\-–•\s]+/, '').replace(/[.;]\s*$/, '').trim()).filter(Boolean);
  const open = S.tlOpen === i.id;
  const meeting = /^https:\/\//.test(i.source_ref || '') ? i.source_ref : null;
  // Пункт дайджеста Telegram (dela_0018): видит только владелец; «сводка →» — сама сводка, откуда пункт.
  const dg = i.digest_ref || null;
  return el('div', { class: 'tl-row' + (open ? ' open' : '') + (i.mention ? ' mention' : ''), onclick: (ev) => { if (ev.target.closest('a, button')) return; S.tlOpen = open ? null : i.id; render(); } },
    el('div', { class: 'tl-when' }, D.ddmm(i.at.slice(0, 10))),
    el('div', { class: 'tl-what' },
      el('div', { class: 'tl-head' }, el('span', { class: 'tl-kind' }, dg ? icon('send', 12) : null, dg ? ' ' : null, IKIND[i.kind] || i.kind), ' ', m ? el('b', {}, m[1]) : null),
      el('div', { class: 'tl-body' }, m ? m[2] : sum),
      steps.length ? el('ul', { class: 'tl-steps' }, (open ? steps : steps.slice(0, 2)).map((x) => el('li', {}, x)),
        !open && steps.length > 2 ? el('li', { class: 'more' }, `ещё ${steps.length - 2}`) : null) : null,
      el('div', { class: 'tl-meta' }, [showDeal && i.deal_name, who.length && who.join(', '), i.duration_min && dur(i.duration_min),
        i.source === 'meeting' && 'из встречи', dg && 'из дайджеста',
        // Упоминание (аудит №28): клиента назвали в сводке, а его чата в окне не было — в «контакт» не идёт.
        i.mention && 'упоминание — не контакт'].filter(Boolean).join(' · '),
        // Закон приватности (аудит №9): приватная запись — «видишь только ты», запись встречи, где команда видит только
        // факт, — «команде — без итога» (поля вида crm.v_interactions; старый сервер их не шлёт — пометки нет).
        i.private || dg ? lockMark('видишь только ты') : i.team_sees === 'fact' ? lockMark('команде — без итога') : null,
      meeting ? el('a', { class: 'src', href: meeting, target: '_blank', rel: 'noopener noreferrer' }, ' встреча →') : null,
      dg ? el('a', { class: 'src', href: '#/digest/' + encodeURIComponent(dg) }, ' сводка →') : null,
      // «Скрыть от команды» — своя общая запись (хозяин записи); вернуть нельзя — правило базы.
      privacyOn() && !i.private && !dg && i.owner_id === S.me.user ? el('button', { class: 'link-btn', onclick: async () => {
        if (!confirm('Скрыть запись от команды? Команда перестанет видеть эту запись. Вернуть нельзя.')) return;
        await dealOp({ op: 'interaction.set', id: i.id, set: { private: true } });
        toast('Запись видишь только ты');
      } }, ' скрыть от команды') : null)),
    // Запись встречи и пункт дайджеста убирает только их хозяин (аудит №9): сотруднику крестика нет — сервер откажет.
    (i.source === 'meeting' || dg) && i.owner_id !== S.me.user ? null : el('button', { class: 'icon-btn tl-del', title: 'Убрать из хронологии', onclick: async () => {
      if (!confirm(dg ? 'Убрать запись из хронологии? Сводка останется в дайджесте, повтор её сюда не вернёт.'
        : 'Убрать запись из хронологии? Она останется в журнале.')) return;
      await dealOp({ op: 'interaction.delete', id: i.id });
    } }, icon('x', 12)));
}
/** Хронология вперемешку, свежие сверху: записи (звонки, встречи, заметки), счета и оплаты (тем, кому открыты
 *  деньги), куски Засечки (владельцу). Первые десять — сразу, остальное — «Показать всё». Записывать — словами
 *  в строке Claude: «созвонились с Иваном, ждут модель к пятнице». */
function timelineBlock(key, { items, entries, pays, showDeal }) {
  const rows = [
    ...(items || []).map((i) => ({ at: i.at, i })),
    ...(pays || []).filter((p) => !p.cancelled_at).flatMap((p) => [
      p.invoiced_on ? { at: p.invoiced_on + 'T12:00:00', pay: p, what: 'invoice' } : null,
      p.paid_on ? { at: p.paid_on + 'T12:00:01', pay: p, what: 'paid' } : null]).filter(Boolean),
    ...(entries || []).map((e) => ({ at: e.day + 'T' + (e.start_local || '00:00').slice(-5) + ':00', e })),
  ].sort((a, b) => b.at.localeCompare(a.at));
  const full = S.tlAll === key;
  const shown = full ? rows : rows.slice(0, TL_SHOWN);
  return [el('div', { class: 'rows tl-rows' }, shown.length ? shown.map((r) => tlLine(r, showDeal))
    : el('div', { class: 'faint pad' }, 'Пока пусто. Скажи в строке Claude: «созвонились с Иваном, ждут модель к пятнице» — запишу сюда.')),
  rows.length > TL_SHOWN ? el('button', { class: 'chip-btn tl-more', onclick: () => { S.tlAll = full ? null : key; render(); } },
    full ? 'Свернуть' : `Показать всё · ${rows.length}`) : null];
}

/** Счёт выставлен: когда, кому и как отправили — встаёт в хронологию. */
function invoicePop(anchor, p) {
  setTimeout(() => {
    const day = el('input', { type: 'date', value: p.invoiced_on || S.today });
    const to = el('input', { placeholder: 'кому: бухгалтерия, Иван…', value: p.sent_to || '' });
    const via = el('select', {}, ['', ...SENT_VIA, ...(p.sent_via && !SENT_VIA.includes(p.sent_via) ? [p.sent_via] : [])]
      .map((x) => el('option', { value: x, selected: (p.sent_via || '') === x }, x || 'как отправили…')));
    const save = () => { closePop(); payOp(p.id, { invoiced_on: day.value || S.today, sent_to: to.value.trim() || null, sent_via: via.value || null }, 'Счёт: ' + (to.value.trim() || 'записал')); };
    to.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); });
    popAt(anchor, [el('div', { class: 'pop-h' }, 'Счёт: ' + rub(p.amount_kop)), el('div', { class: 'pop-l' }, 'Когда выставили'), day,
      el('div', { class: 'pop-l' }, 'Кому'), to, el('div', { class: 'pop-l' }, 'Как'), via,
      el('div', { class: 'sep' }), el('button', { onclick: save }, 'Записать', el('span', { class: 'k' }, 'Enter'))]);
    to.focus();
  }, 0);
}

// ── Файлы: папка и документы ссылками ───────────────────────────────────
const FILE_KIND = { contract: 'Договор', invoice: 'Счёт', nda: 'NDA', act: 'Акт', other: 'Документ' };
const withScheme = (v) => (v && !/^https?:\/\//i.test(v) ? 'https://' + v : v);
const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch (e) { return u; } };
function filesBlock(row, save) {
  const files = row.files || [];
  const folder = el('input', { class: 'f-in', type: 'url', placeholder: 'Ссылка на папку: Яндекс Диск, Google Drive…', value: row.folder_url || '' });
  folder.addEventListener('change', () => save({ folder_url: withScheme(folder.value.trim()) || null }, 'Папка: ' + (folder.value.trim() ? 'записал' : 'убрал')));
  const kind = el('select', {}, Object.entries(FILE_KIND).map(([k, l]) => el('option', { value: k }, l)));
  const title = el('input', { class: 'f-in', placeholder: 'название: «Договор №12», «Счёт за октябрь»' });
  const url = el('input', { class: 'f-in', type: 'url', placeholder: 'ссылка на файл' });
  const add = () => {
    const u = withScheme(url.value.trim());
    if (!u) { url.focus(); return; }
    save({ files: [...files, { kind: kind.value, title: title.value.trim() || null, url: u, at: S.today }] }, (title.value.trim() || FILE_KIND[kind.value]) + ' — в файлах');
  };
  url.addEventListener('keydown', (e) => { if (e.key === 'Enter') add(); });
  return el('div', { class: 'rows files' },
    el('div', { class: 'f-row f-folder' }, icon('folder', 18),
      row.folder_url ? el('a', { class: 'link f-open', href: row.folder_url, target: '_blank', rel: 'noopener noreferrer' }, 'Открыть папку ↗') : null, folder),
    files.map((f, k) => el('div', { class: 'f-row' }, el('span', { class: 'f-kind' }, FILE_KIND[f.kind] || 'Документ'),
      el('a', { class: 'link f-name', href: f.url, target: '_blank', rel: 'noopener noreferrer' }, f.title || hostOf(f.url)),
      f.at ? el('span', { class: 'faint' }, D.ddmm(f.at)) : null,
      el('button', { class: 'icon-btn', title: 'Убрать из файлов', onclick: () => save({ files: files.filter((_, j) => j !== k) }, 'Убрал: ' + (f.title || FILE_KIND[f.kind])) }, icon('x', 12)))),
    el('div', { class: 'f-row f-add' }, kind, title, url, el('button', { class: 'btn small', onclick: add }, '+ Документ')));
}

// ── Клиент на странице проекта ──────────────────────────────────────────
const CLIENT_STATUS = { relations: 'поддержание отношений' };
async function projectSet(p, set, msg) {
  const before = Object.fromEntries(Object.keys(set).map((k) => [k, p[k] ?? null]));
  try { await op1({ op: 'project.set', id: p.id, set }); crmDirty(); render(); if (msg) toast(msg, () => op1({ op: 'project.set', id: p.id, set: before })); } catch (e) { fail(e); render(); }
}
/** Команда клиента — те, кто ведёт его живые проекты и в их командах. */
function clientTeam(deals) {
  const ids = [];
  for (const d of deals) for (const x of [d.lead_person_id, ...(d.team_ids || [])]) if (x && !ids.includes(x)) ids.push(x);
  return ids.map(person).filter(Boolean);
}
/** В команду клиента: проект один — в него, несколько — выбрать, в какой. */
function addToTeam(anchor, deals, pid) {
  const put = (d) => { const ids = d.team_ids || []; if (!ids.includes(pid) && d.lead_person_id !== pid) setDeal(d.id, { team_ids: [...ids, pid] }); };
  if (deals.length === 1) { put(deals[0]); return; }
  setTimeout(() => popAt(anchor, [el('div', { class: 'pop-h' }, `${pName(pid)} — в команду какого проекта?`),
    ...deals.map((d) => el('button', { onclick: () => { closePop(); put(d); } }, dealShort(d, project(d.project_id)), el('span', { class: 'k' }, STAGE[d.stage])))]), 0);
}

function renderClientCard(p, { picker, g, items, sub, star }) {
  const v = crmGet('/api/view/client?project_id=' + p.id);
  const deals = clientDeals(p.id);
  const live = deals.filter((d) => d.stage !== 'archive');
  const full = v ? new Map(v.deals.map((d) => [d.id, d])) : new Map();
  const money = S.me.money;
  if (v && v.months && v.months.length) {
    const total = v.months.reduce((s, m) => s + m.minutes, 0);
    sub.push(el('span', { title: 'время из Засечки' }, icon('clock', 12), ' ' + dur(total) + ' всего · ' + v.months.slice(0, 3).map((m) => `${MONTHS[+m.month.slice(5) - 1].slice(0, 3)} ${dur(m.minutes)}`).join(', ')));
  }
  const stages = OPEN_STAGES.filter((s) => live.some((d) => d.stage === s)).map((s) => STAGE[s]);
  const status = el('div', { class: 'stage-row client-status' },
    el('span', { class: 'cs-word' }, 'Статус'),
    stages.length ? el('span', { class: 'cs-now' }, 'проекты: ' + stages.join(', ')) : null,
    // «Поддержание отношений» — уже не лид, но и не сделка (владелец 09.10.2026).
    el('button', { class: 'chip-btn' + (p.status === 'relations' ? ' on' : ''), title: 'Уже не лид, но и не сделка: работали, держим связь',
      onclick: () => projectSet(p, { status: p.status === 'relations' ? null : 'relations' }, p.status === 'relations' ? 'Статус — по проектам' : 'Поддержание отношений') },
    'Поддержание отношений'),
    // Переименовать (13.10.2026): дела, сделки и хронология показывают клиента ссылкой, встречи — база (dela_0012);
    // старое имя ложится в другие имена клиента.
    el('button', { class: 'chip-btn', title: 'Новое имя — везде: дела, сделки, хронология, названия встреч; старое останется другим именем',
      onclick: () => {
        const name = (prompt('Новое имя клиента', p.name) || '').trim();
        if (!name || name === p.name) return;
        const twin = [...S.projects.values()].find((x) => x.id !== p.id && !x.archived_at && x.name.toLowerCase() === name.toLowerCase());
        if (twin) { toast(`«${twin.name}» — уже другой клиент`); return; }
        projectSet(p, { name }, `Теперь «${name}» — и во встречах`);
      } }, 'Переименовать'),
    !stages.length && p.status !== 'relations' ? el('span', { class: 'faint' }, 'проектов в работе нет') : null);
  const people = clientPeopleOf(p);
  const team = clientTeam(live);
  const sides = el('div', { class: 'sides' },
    sideCol('Люди клиента', people, {
      empty: 'пока никого',
      remove: (x) => leaveClient(x, p, v),
      add: (a) => personSearchPop(a, { title: 'Кто из «' + p.name + '»', base: () => people, exclude: people.map((x) => x.id),
        pick: (id) => joinClient(person(id), p), create: (name) => joinClient(null, p, name) }),
    }),
    sideCol('Команда', team, {
      empty: live.length ? 'никого' : 'появится с проектом',
      mark: { word: (x) => (live.some((d) => d.lead_person_id === x.id) ? 'ведёт' : '') },
      add: live.length ? (a) => personSearchPop(a, { title: 'Кто из команды', base: teamPeople, exclude: team.map((x) => x.id),
        pick: (id) => addToTeam(a, live, id), create: async (name) => {
          const r = await dealOp({ op: 'person.create', data: { name } });
          if (r && r.row) addToTeam(a, live, r.row.id);
        } }) : null,
      note: live.length > 1 ? 'по всем живым проектам; убрать — в карточке проекта' : null,
    }));
  const tiles = el('div', { class: 'dlist' }, deals.filter((d) => d.stage !== 'archive' || S.clientArch)
    .map((d) => dealTile({ ...d, project_name: p.name, ...(full.get(d.id) || {}) })),
  el('button', { class: 'dtile add', onclick: () => newDeal(p.id) }, icon('plus', 14), ' Зачем'));
  const archived = deals.length - live.length;
  const meets = meetingsSec(v, '#/meetings/client=' + p.id, 'клиента');
  const nav = [['about', 'О клиенте'], ['deals', 'Зачем'], ['tasks', 'Дела'], ['timeline', 'Хронология'], meets ? ['meetings', 'Встречи'] : null,
    money ? ['money', 'Деньги'] : null, ['files', 'Файлы']].filter(Boolean);
  const pays = v ? v.payments || [] : [];
  return [head(p.name, sub, star, nav),
    el('div', { class: 'body card-body' },
      quickAdd({ project_id: p.id }, { label: `с клиентом «${p.name}»`,
        placeholder: '«Иван — их CFO, добавь его», «Лена с нами на проекте», «созвонились, ждут модель к пятнице», «поддерживаем отношения»' }),
      sec('about', 'О клиенте', null,
        aboutBox(p.note, 'Кто они, чем занимаются, как пришли, о чём договорённость. Можно сказать словами в строке Claude.', (x) => projectSet(p, { note: x }, 'Описание сохранено')),
        status),
      sides,
      sec('deals', 'Зачем', [secN(live.length), archived ? el('button', { class: 'link-btn', onclick: () => { S.clientArch = !S.clientArch; render(); } },
        S.clientArch ? 'скрыть архив' : `и в архиве: ${archived}`) : null], tiles),
      sec('tasks', 'Дела', secN(items.filter(isOpen).length), el('div', { class: 'toolbar' }, picker, doneToggle()),
        items.length ? grouped(items, g) : el('div', { class: 'empty' }, 'Дел нет. Следующий шаг — скажи в строке Claude.')),
      sec('timeline', 'Хронология', null, v ? timelineBlock('c:' + p.id, { items: v.timeline, entries: v.entries, pays: money ? pays : [], showDeal: true }) : loading()),
      insightsSec(v, { project_id: p.id }),
      meets,
      money ? sec('money', 'Деньги', null, clientMoney(pays)) : null,
      sec('files', 'Файлы', secN((p.files || []).length), filesBlock(p, (set, msg) => projectSet(p, set, msg))))];
}
/** Деньги клиента: оплаты всех его проектов — что пришло, что выставлено, чего ждём. */
function clientMoney(pays) {
  const live = pays.filter((x) => !x.cancelled_at);
  if (!live.length) return el('div', { class: 'empty' }, 'Оплат пока нет — их заводят в карточке проекта.');
  const sum = (f) => live.filter(f).reduce((s, x) => s + x.amount_kop, 0);
  return [el('div', { class: 'money-sum' }, [sum((x) => x.paid_on) && 'получено ' + rub(sum((x) => x.paid_on)),
    sum((x) => !x.paid_on && x.invoiced_on) && 'счета ' + rub(sum((x) => !x.paid_on && x.invoiced_on)),
    sum((x) => !x.paid_on && !x.invoiced_on) && 'план ' + rub(sum((x) => !x.paid_on && !x.invoiced_on))].filter(Boolean).join(' · ')),
  el('div', { class: 'rows pays' }, live.map((x) => payRow({ ...x, project_name: '', deal_name: x.deal_name }, true)))];
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
    crmDirty();
    render();
  } catch (e) { fail(e); }
}
/** Убрать человека из клиента: из его организации и из людей его сделок. Строки не удаляются — «Вернуть». */
async function leaveClient(x, p) {
  if (!confirm(`Убрать ${x.name} из «${p.name}»? Карточка человека останется.`)) return;
  const go_ = [], back = [];
  if (x.org_id && x.org_id === p.org_id) {
    go_.push({ op: 'person.set', id: x.id, set: { org_id: null } });
    back.push({ op: 'person.set', id: x.id, set: { org_id: x.org_id } });
  }
  for (const d of clientDeals(p.id)) {
    const ids = d.person_ids || [];
    if (!ids.includes(x.id)) continue;
    go_.push({ op: 'deal.set', id: d.id, set: { person_ids: ids.filter((i) => i !== x.id) } });
    back.push({ op: 'deal.set', id: d.id, set: { person_ids: ids } });
  }
  if (!go_.length) { toast(`${x.name} связан с «${p.name}» иначе — поправь в его карточке`); return; }
  try { await ops(go_); render(); toast(`${personName(x)} больше не в «${p.name}»`, () => ops(back)); } catch (e) { fail(e); }
}

// ── Проект клиента (сделка) — страницей ─────────────────────────────────
// Владелец, 06.10.2026: «если я нажимаю на проект, он должен быть не просто сайдбаром, а таким же, как клиент
// и человек». 09.10.2026 — то же устройство, что у клиента: описание сверху и правится, статус, люди клиента
// и команда с двух сторон, дела, хронология под делами, деньги, файлы.
function renderDeal(id) {
  const d0 = S.deals.get(id);
  const v = crmGet('/api/view/deal?deal_id=' + id, 15000);
  if (!d0) return [head('Проект'), el('div', { class: 'body' }, el('div', { class: 'empty' }, 'Проект не виден.'))];
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
  const fact = (label, ctl) => el('label', { class: 'fact' }, el('span', {}, label), ctl);

  // Что за проект: описание (видят все, кто видит проект) и короткие факты.
  const facts = el('div', { class: 'facts' },
    fact('Тип', sel('deal_type', DEAL_TYPES.map((x) => [x, x]).concat(d.deal_type && !DEAL_TYPES.includes(d.deal_type) ? [[d.deal_type, d.deal_type]] : []), d.deal_type)),
    fact('Вероятность, %', inp('probability', 'number', d.probability, (x) => Math.max(0, Math.min(100, Math.round(+x))))),
    fact('Решение ждём', inp('expected_on', 'date', d.expected_on)),
    fact('Дедлайн', inp('deadline', 'date', d.deadline)),
    fact('Привёл', choiceBtn(person(d.source_person_id)?.name || '—', (a) => choicePop(a, { title: 'Кто привёл', current: d.source_person_id || null, none: 'Никто',
      items: personItems(livePeople().filter((x) => !x.merged_into).sort((a2, b2) => personName(a2).localeCompare(personName(b2), 'ru'))),
      onPick: (v) => save('source_person_id', v) }), { empty: !d.source_person_id })));
  // «Как я вижу» и «Идеи» — старые заметки владельца: видны, только если в них что-то есть.
  const notes = [d.my_view ? ['Как я вижу (только мне)', 'my_view'] : null, d.ideas ? ['Идеи', 'ideas'] : null].filter(Boolean);
  const more = notes.length ? el('details', { class: 'deal-more', open: S.dealMore ? true : null, ontoggle: (e) => { S.dealMore = e.currentTarget.open; } },
    el('summary', {}, notes.map(([l]) => l.replace(' (только мне)', '').toLowerCase()).join(' и ')),
    notes.map(([label, f]) => el('div', { class: 'legacy' }, el('div', { class: 'faint' }, label), aboutBox(d[f], '', (x) => save(f, x))))) : null;

  // Статус: лид, КП… и «Закрыть…» с итогом.
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
    : el('div', { class: 'stage-row' }, el('span', { class: 'cs-word' }, 'Статус'), stages, el('button', { class: 'btn small', onclick: (e) => closeDeal(e.currentTarget) }, 'Закрыть…'));

  // С двух сторон: люди клиента и команда (ведущий — первым, с пометкой).
  const clientSide = (d.person_ids || []).map(person).filter(Boolean);
  // Состав команды правит хозяин проекта (аудит №11, crm.grants_project): владелец Дел или хозяин клиента. Старый сервер
  // (без флага privacy) этого правила не знает — тогда как раньше.
  const grants = !privacyOn() || S.me.role === 'owner' || (p && p.owner_id === S.me.user);
  const teamIds = [d.lead_person_id, ...(d.team_ids || [])].filter((x, k, a) => x && a.indexOf(x) === k);
  const teamSide = teamIds.map(person).filter(Boolean);
  const newPerson = async (name, field) => {
    const org = field === 'person_ids' && p ? await clientOrg(p).catch(() => null) : null;
    const r = await dealOp({ op: 'person.create', data: { name, ...(org ? { org_id: org } : {}) } });
    if (r && r.row) save(field, [...(d[field] || []), r.row.id]);
  };
  const sides = el('div', { class: 'sides' },
    sideCol('Люди клиента', clientSide, {
      remove: (x) => save('person_ids', (d.person_ids || []).filter((y) => y !== x.id)),
      add: (a) => personSearchPop(a, { title: 'Кто от клиента', base: () => (p ? clientPeopleOf(p) : []), exclude: d.person_ids || [],
        pick: (pid) => save('person_ids', [...(d.person_ids || []), pid]), create: (name) => newPerson(name, 'person_ids') }),
    }),
    grants ? sideCol('Команда', teamSide, {
      mark: {
        word: (x) => (x.id === d.lead_person_id ? 'ведёт' : ''),
        acts: (x) => (x.id === d.lead_person_id ? null : el('button', { onclick: () => { closePop(); setDeal(d.id, { lead_person_id: x.id, team_ids: [...new Set([...(d.team_ids || []), d.lead_person_id].filter((y) => y && y !== x.id))] }); } }, 'Ведёт проект', el('span', { class: 'k' }, '★'))),
      },
      remove: (x) => (x.id === d.lead_person_id ? save('lead_person_id', null) : save('team_ids', (d.team_ids || []).filter((y) => y !== x.id))),
      add: (a) => personSearchPop(a, { title: 'Кто из команды', base: teamPeople, exclude: teamIds,
        pick: (pid) => (d.lead_person_id ? save('team_ids', [...(d.team_ids || []), pid]) : save('lead_person_id', pid)),
        create: (name) => newPerson(name, 'team_ids') }),
      empty: 'никого — первый станет ведущим',
    }) : sideCol('Команда', teamSide, {
      // Не хозяину проекта (аудит №11): кто из команды на сделке — тем и открыт клиент, это решает хозяин. Себя убрать —
      // можно; коллег ставить, убирать и назначать ведущим — нет (сервер откажет словами и сам).
      mark: { word: (x) => (x.id === d.lead_person_id ? 'ведёт' : ''), acts: () => null },
      removable: (x) => x.user_id === S.me.user || x.id === S.me.person_id,
      remove: (x) => (x.id === d.lead_person_id ? save('lead_person_id', null) : save('team_ids', (d.team_ids || []).filter((y) => y !== x.id))),
      empty: 'никого',
      note: 'кого из команды ставить на проект — решает Саша',
    }));

  // Дела: следующий шаг — открытое дело проекта; без него проект застынет. Пока проект у клиента один живой, его шаги —
  // и открытые дела клиента без проекта (аудит №2, то же правило у сервера): отдельной группой «Дела клиента».
  const items = all().filter((t) => t.deal_id === d.id && (S.showDone || isOpen(t)));
  const open = items.filter(isOpen);
  const viaClient = clientSteps(d);
  const tasks = sec('tasks', 'Дела', [secN(open.length + viaClient.length), el('span', { class: 'act' }, doneToggle())],
    open.length || viaClient.length || d.stage === 'archive' ? null : el('div', { class: 'empty' }, 'Нет следующего дела — проект без шага застынет. Скажи его в строке Claude.'),
    items.length ? nested(items, ['grp', 'date']) : null,
    viaClient.length ? groupBox('Дела клиента', viaClient, { sub: 'без проекта — пока проект у клиента один, это его шаги' }, ['date']) : null);

  const pays = v ? v.payments : [...S.pays.values()].filter((x) => x.deal_id === d.id);
  let moneySec = null;
  if (money) {
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
    moneySec = sec('money', 'Деньги', null,
      el('div', { class: 'facts' }, fact('Модель', sel('fee_kind', Object.entries(FEE_KIND), d.fee_kind)), fact('Гонорар, ₽', rubIn('fee_kop')),
        fact('Ретейнер, ₽/мес', rubIn('retainer_kop')), fact('Успех, %', inp('success_pct', 'number', d.success_pct, (x) => +x))),
      el('div', { class: 'money-sum' }, [paid && 'получено ' + rub(paid), inv && 'счета ' + rub(inv), plan && 'план ' + rub(plan),
        d.fee_kop && ['mandate', 'active', 'closing'].includes(d.stage) ? 'осталось ' + rub(Math.max(d.fee_kop - paid, 0)) : null,
        d.fee_kop && paid + inv + plan < d.fee_kop && d.stage !== 'archive' ? 'не расписано ' + rub(d.fee_kop - paid - inv - plan) : null].filter(Boolean).join(' · ') || 'оплат пока нет'),
      pays.length ? el('div', { class: 'rows pays' }, pays.map((x) => payRow(x, false))) : null,
      el('div', { class: 'pay-add' }, kind, amount, due, el('button', { class: 'btn small', onclick: addPay }, '+ Оплата')));
  }

  const sub = [p ? el('a', { class: 'link', href: '#/p/' + p.id }, dot(p.id), ' ', p.name) : null, stageWord(d),
    d.fee_kop && money ? rubShort(d.fee_kop) : null, v && v.minutes_all ? el('span', { title: 'время из Засечки по делам проекта' }, icon('clock', 12), ' ' + dur(v.minutes_all)) : null];
  const meets = meetingsSec(v, '#/meetings/deal=' + d.id, 'проекта');
  const nav = [['about', 'О проекте'], ['tasks', 'Дела'], ['timeline', 'Хронология'], meets ? ['meetings', 'Встречи'] : null,
    money ? ['money', 'Деньги'] : null, ['files', 'Файлы']].filter(Boolean);
  const body = el('div', { class: 'body card-body' },
    quickAdd({ project_id: d.project_id, deal_id: d.id }, { label: `с проектом «${d.name}»`,
      placeholder: '«подписали мандат», «добавь Наташу в команду», «Ольга — их CFO», «созвон: ждут модель к пятнице», «этого уже нет — закрой»' }),
    sec('about', 'О проекте', null,
      aboutBox(d.description, 'Что за проект: суть, цель, о чём договорились. Можно сказать словами в строке Claude.', (x) => save('description', x)),
      facts, more),
    stageRow,
    sides,
    tasks,
    sec('timeline', 'Хронология', null, v ? timelineBlock('d:' + d.id, { items: v.timeline, entries: v.entries, pays: money ? pays : [] }) : loading()),
    insightsSec(v, { project_id: d.project_id, deal_id: d.id }),
    meets,
    moneySec,
    sec('files', 'Файлы', secN((d.files || []).length), filesBlock(d, (set, msg) => dealOp({ op: 'deal.set', id: d.id, set }).then((r) => r && msg && toast(msg)))));
  if (v && v.history.length) {
    body.append(el('details', { class: 'deal-more' }, el('summary', {}, 'Журнал проекта'), v.history.map((h) => {
      const a = h.after || {};
      const what = h.op === 'insert' ? 'заведён' : a.stage ? 'стадия: ' + (STAGE[a.stage] || a.stage) + (a.outcome ? ' — ' + OUTCOME[a.outcome] : '') : Object.keys(a).join(', ');
      return el('div', { class: 'hist' }, `${h.at.slice(0, 16).replace('T', ' ')} · ${h.actor} · ${what}`);
    })));
  }
  return [head(d.name, sub, null, nav), body];
}

// ── Человек в CRM (на его странице) ─────────────────────────────────────
/** Видит ли смотрящий карточку целиком (ярус owner: владелец Дел, автор карточки — crm.v_people, аудит №10). Старый
 *  сервер яруса не шлёт — тогда по роли: пометки там видит всякий, кто видит карточку. */
const personWhole = (p) => (p.tier ? p.tier === 'owner' : S.me.role === 'owner' || p.owner_id === S.me.user);
function personCrmBlock(p) {
  const v = crmGet('/api/view/dossier?person_id=' + p.id);
  // «Теплота» и «хаб» — пометки владельца: только тому, кто видит карточку целиком (у остальных правку отвергнет база).
  if (!personWhole(p)) {
    return el('div', { class: 'crm-person' },
      sec('timeline', 'Хронология', null, v ? timelineBlock('h:' + p.id, { items: v.timeline, showDeal: true }) : loading()));
  }
  const cad = el('select', {}, [['', '—'], ...Object.entries(CADENCE)].map(([k, l]) => el('option', { value: k, selected: (p.cadence || '') === k }, l)));
  cad.addEventListener('change', async () => { try { await op1({ op: 'person.set', id: p.id, set: { cadence: cad.value || null } }); crmDirty(); render(); } catch (e) { fail(e); } });
  const hub = el('input', { type: 'checkbox' });
  hub.checked = !!p.hub;
  hub.addEventListener('change', async () => { try { await op1({ op: 'person.set', id: p.id, set: { hub: hub.checked } }); crmDirty(); } catch (e) { fail(e); } });
  return el('div', { class: 'crm-person' },
    el('div', { class: 'toolbar' }, el('label', {}, 'Теплота', cad), el('label', {}, hub, 'хаб — через него идут темы')),
    insightsSec(v, { person_ids: [p.id] }),
    sec('timeline', 'Хронология', null, v ? timelineBlock('h:' + p.id, { items: v.timeline, showDeal: true }) : loading()));
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
const pointsFor = (t) => (isNow(t) || t.owed || (t.due_date && t.due_date <= S.today) ? 15 : 10); // как stats.py: обещанное +5

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
      el('div', { class: 'group' }, el('h2', {}, icon('chart', 17), 'Очки по дням', el('span', { class: 'gsub' }, '30 дней, субботы светлее')),
        dayChart(v.days)),
      v.by_project.length ? el('div', { class: 'group' }, el('h2', {}, 'Что закрывал за 30 дней'),
        v.by_project.map((x) => {
          const b = el('span', { class: 'hb' });
          b.style.width = Math.round(x.done / v.by_project[0].done * 100) + '%';
          return el('div', { class: 'hrow' }, el('span', { class: 'hn' }, x.name), el('span', { class: 'hbar' }, b), el('span', { class: 'hv' }, x.done));
        }),
        el('div', { class: 'hint-line' }, 'Откуда были дела: ' + v.by_source.map((x) => `${x.name} ${x.done}`).join(' · '))) : null,
      el('div', { class: 'hint-line rules' }, `Очки: закрыл — ${v.rules.done}, обещанное (ждали от тебя, в «Сейчас» или срок пришёл) — ещё ${v.rules.promised}, отменил ненужное — ${v.rules.cancel}, разобрал «Новое» — ${v.rules.triage}. Заводить дела — бесплатно. ${th.streak[0].toUpperCase() + th.streak.slice(1)} — дни подряд с закрытым делом; суббота не рвёт.`))];
}

// ── Встречи (12.10.2026) ────────────────────────────────────────────────
// Владелец: «у встречи должно быть единообразное название: клиент, основная тема, кто присутствовал, дата и
// duration… в списке встреч сортировка по клиентам, по времени, по присутствующим… встречи поправить на личные и
// рабочие». Название — «Клиент: тема» (сервер, meetings.title_of); дата, длительность и люди — рядом, по ним
// сортировка и группы. Поиск — сервером: по названию, сводке и расшифровке, словоформами. Что владелец поправил
// в карточке, разборщик больше не трогает (замок).
const MT_SORT = { date: 'по времени', client: 'по клиенту', duration: 'по длительности', people: 'по числу людей', relevance: 'где больше сказано' };
const MT_GROUP = { none: 'без групп', client: 'по клиентам', month: 'по месяцам', person: 'по людям', sphere: 'рабочие и личные' };
const MT_SPHERE = { '': 'Все', work: 'Рабочие', home: 'Личные', none: 'Не разобраны' };
const MT_KIND = { zoom: 'Zoom', meeting: 'встреча', call: 'звонок', other: '' };
const MT_LOCK = { title: 'название', topic: 'тема', project: 'клиент', sphere: 'рабочая или личная', people: 'участники' };
const MONTHS_NOM = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'];
S.mt = { sphere: '', sort: 'date', group: 'none', client: '', person: '', arch: false, ...LS.get('mt', {}) };
S.mtQ = '';
const mtSave = (patch) => { S.mt = { ...S.mt, ...patch }; LS.set('mt', S.mt); render(); };
/** Время встречи по Москве: служба встреч и владелец — там. */
const mtTime = (iso) => new Date(iso).toLocaleTimeString('ru', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Moscow' });
/** Сниппет поиска: «…» сервера (ts_headline) — подсветкой. */
const markSnip = (t) => String(t || '').split(/(«[^»]*»)/).map((x) => (x.startsWith('«') && x.endsWith('»') ? el('mark', {}, x.slice(1, -1)) : x));
const markIn = (text, q) => {
  if (!q) return text;
  const i = norm(text).indexOf(norm(q));
  return i < 0 ? text : [text.slice(0, i), el('mark', {}, text.slice(i, i + q.length)), text.slice(i + q.length)];
};
const short = (t, n) => (t && t.length > n ? t.slice(0, n).replace(/\s+\S*$/, '') + '…' : t);
let mtTimer = null;

function meetRow(m) {
  const who = [...(m.people || []), ...(m.guests || [])];
  const bits = [
    m.client ? el('span', { class: 'tag' }, m.client) : el('span', { class: 'faint' }, 'без клиента'),
    who.length ? el('span', {}, who.join(', ')) : null,
    m.duration_min ? el('span', {}, dur(m.duration_min)) : null,
    MT_KIND[m.kind] ? el('span', {}, MT_KIND[m.kind]) : null,
  ];
  return el('div', { class: 'crow mrow' + (m.archived ? ' arch' : ''), onclick: () => go('#/m/' + m.id) },
    el('div', { class: 'mdate' }, el('b', {}, D.ddmm(m.day)), el('span', {}, WD_SHORT[D.wd(m.day)] + ' ' + mtTime(m.started_at))),
    el('div', { class: 'main' },
      el('div', { class: 'title' }, m.title || m.source_title || 'Встреча без названия',
        m.sphere === 'home' ? el('span', { class: 'badge' }, 'личная') : null,
        !m.named ? el('span', { class: 'badge', title: 'Разбор ещё не дал ей клиента и тему' }, 'не разобрана') : null,
        m.question ? el('span', { class: 'badge ask', title: m.question }, 'вопрос') : null),
      el('div', { class: 'chips' }, bits),
      m.hits ? el('div', { class: 'mhits' }, m.hits.map((h) => el('div', {}, h.speaker ? el('b', {}, h.speaker + ': ') : null, markSnip(h.text))),
        m.hits_n > m.hits.length ? el('div', { class: 'faint' }, `и ещё ${m.hits_n - m.hits.length}`) : null)
        : m.summary ? el('div', { class: 'next' }, short(m.summary, 200)) : null));
}

/** Группы встреч — считает веб: встреч сотни. Клиенты и люди — свежие сверху, месяцы — новые сверху. */
function meetGroups(items, by) {
  const groups = new Map();
  const add = (key, title, m) => { if (!groups.has(key)) groups.set(key, { title, items: [] }); groups.get(key).items.push(m); };
  for (const m of items) {
    if (by === 'client') add(m.client || '', m.client || 'Без клиента', m);
    else if (by === 'month') add(m.day.slice(0, 7), MONTHS_NOM[+m.day.slice(5, 7) - 1] + ' ' + m.day.slice(0, 4), m);
    else if (by === 'sphere') add(m.sphere || 'none', { work: 'Рабочие', home: 'Личные', none: 'Не разобраны' }[m.sphere || 'none'], m);
    else if (by === 'person') {
      const who = [...(m.people || []), ...(m.guests || [])];
      if (!who.length) add('', 'Без участников', m);
      for (const w of who) add(w, w, m);
    }
  }
  const last = (g) => g.items.reduce((a, m) => (m.started_at > a ? m.started_at : a), '');
  const list = [...groups.entries()];
  if (by === 'month') list.sort((a, b) => b[0].localeCompare(a[0]));
  else if (by === 'person') list.sort((a, b) => b[1].items.length - a[1].items.length || last(b[1]).localeCompare(last(a[1])));
  else if (by !== 'sphere') list.sort((a, b) => (a[0] === '') - (b[0] === '') || last(b[1]).localeCompare(last(a[1])));
  return list.map(([, g]) => g);
}

function renderMeetings() {
  const f = S.mt;
  // Из карточки проекта или клиента («все встречи … →», аудит №3) — только его встречи: свои отборы страницы (сфера,
  // клиент, человек) на это время не действуют, «✕» у отбора — снова вся страница.
  const r = route();
  const only = r.deal ? { key: 'deal_id', id: r.deal, name: S.deals.get(r.deal)?.name, word: 'проект' }
    : r.client ? { key: 'project_id', id: r.client, name: project(r.client)?.name, word: 'клиент' } : null;
  const qs = new URLSearchParams({ sort: S.mtQ && f.sort === 'date' ? 'relevance' : f.sort });
  if (only) qs.set(only.key, only.id);
  if (f.sphere && !only) qs.set('sphere', f.sphere);
  if (f.client && !only) qs.set('project_id', f.client);
  if (f.person && !only) qs.set('person_id', f.person);
  if (f.arch) qs.set('archived', '1');
  if (S.mtQ.trim()) qs.set('q', S.mtQ.trim());
  const v = crmGet('/api/view/meetings?' + qs, 20000);
  const search = el('input', { id: 'main-search', class: 'inline-search', type: 'search', placeholder: 'Найти в разговорах: счёт, Яблоков, залог…', value: S.mtQ });
  search.addEventListener('input', () => { S.mtQ = search.value; clearTimeout(mtTimer); mtTimer = setTimeout(render, 350); });
  const sel = (key, opts, label) => {
    const s = el('select', { 'aria-label': label }, Object.entries(opts).map(([k, l]) => el('option', { value: k, selected: f[key] === k }, l)));
    s.addEventListener('change', () => mtSave({ [key]: s.value }));
    return s;
  };
  const spheres = Object.entries(MT_SPHERE).map(([k, l]) => {
    const n = v && v.counts ? { '': v.counts.all, work: v.counts.work, home: v.counts.home, none: v.counts.none }[k] : null;
    if (k === 'none' && !n) return null;
    return el('button', { class: 'chip-btn' + (f.sphere === k ? ' on' : ''), onclick: () => mtSave({ sphere: k }) }, l, n ? el('b', {}, ' ' + n) : null);
  });
  // Клиенты и люди встреч — выбором с поиском: их десятки.
  const mClients = (v ? v.clients : []).map((c) => ({ v: c.id || 'none', title: c.name, note: plural(c.n, 'встреча', 'встречи', 'встреч') }));
  const mPeople = (v ? v.people : []).map((x) => ({ v: x.id, title: x.short || x.name, note: [x.short && x.short !== x.name ? x.name : null,
    plural(x.n, 'встреча', 'встречи', 'встреч')].filter(Boolean).join(' · '), keys: [x.name] }));
  const clientSel = choiceBtn(mClients.find((c) => c.v === f.client)?.title || 'все клиенты', (a) => choicePop(a, { title: 'Клиент', current: f.client || null,
    none: 'Все клиенты', items: mClients, onPick: (x) => mtSave({ client: x || '' }) }), { empty: !f.client });
  const personSel = choiceBtn(mPeople.find((x) => x.v === f.person)?.title || 'все люди', (a) => choicePop(a, { title: 'Человек', current: f.person || null,
    none: 'Все люди', items: mPeople, onPick: (x) => mtSave({ person: x || '' }) }), { empty: !f.person });
  const onlyChip = only ? el('a', { class: 'chip-btn on', href: '#/meetings', title: 'Снять отбор — все встречи' },
    `${only.word}: ${only.name || 'не виден'}`, ' ', icon('x', 12)) : null;
  const tb = el('div', { class: 'toolbar mt-top' }, search, only ? onlyChip : spheres);
  const tb2 = el('div', { class: 'toolbar mt-tools' }, el('label', {}, 'Сортировать'), sel('sort', MT_SORT, 'Сортировка'),
    el('label', {}, 'Группы'), sel('group', MT_GROUP, 'Группы'), only ? null : clientSel, only ? null : personSel,
    el('button', { class: 'chip-btn' + (f.arch ? ' on' : ''), onclick: () => mtSave({ arch: !f.arch }) }, 'И убранные'));
  if (!v) return [head('Встречи'), el('div', { class: 'body' }, tb, tb2, loading())];
  const items = v.items;
  const c = v.counts;
  // Счёт сервера — по всем встречам (для чипов сфер); под отбором проекта или клиента — его встречи.
  const mins = items.reduce((a, m) => a + (m.duration_min || 0), 0);
  const sub = only ? [plural(items.length, 'встреча', 'встречи', 'встреч'), mins ? dur(mins) : null]
    : [plural(c.all, 'встреча', 'встречи', 'встреч'), c.minutes ? dur(c.minutes) : null,
      c.unnamed ? el('span', { class: 'late' }, `ждут разбора: ${c.unnamed}`) : null, c.asks ? `с вопросом: ${c.asks}` : null];
  let body;
  if (!items.length) body = el('div', { class: 'empty' }, S.mtQ ? 'Не нашлось ни в названиях, ни в разговорах.'
    : c.all ? 'Под эти фильтры встреч нет.' : 'Встреч в базе пока нет: их присылает служба встреч (ZF-Meetings) — после её обновления сюда приедут все встречи разом.');
  else if (f.group === 'none' || !MT_GROUP[f.group]) body = el('div', { class: 'group' }, items.map(meetRow));
  else body = meetGroups(items, f.group).map((g) => el('div', { class: 'group' },
    el('h2', {}, g.title, el('span', { class: 'n' }, g.items.length),
      el('span', { class: 'sum' }, dur(g.items.reduce((a, m) => a + (m.duration_min || 0), 0)))),
    g.items.map(meetRow)));
  return [head('Встречи', sub), el('div', { class: 'body' }, tb, tb2, body)];
}

async function meetOp(op, msg) {
  try { const r = await op1(op); crmDirty(); if (msg) toast(msg); render(); return r; } catch (e) { fail(e); render(); return null; }
}

function renderMeeting(id) {
  const path = '/api/view/meeting?id=' + id;
  const v = crmGet(path, 15000);
  const err = crmCache.get(path)?.error;
  if (!v) return [head('Встреча'), el('div', { class: 'body' }, err ? el('div', { class: 'empty' }, 'Не открылась: ' + err) : loading())];
  const set = (s, msg) => meetOp({ op: 'meeting.set', id, set: s }, msg);
  // Клиента нет в справочнике, а его подпись стоит ещё у встреч — поменять у всех (13.10.2026, владелец:
  // «переименую клиента — чтобы и в истории встреч переименовалось»); сервер: meeting.set everywhere.
  const setClient = (s, msg) => (v.same_label && confirm(`Встреч с «${v.label}» ещё ${v.same_label}. Поменять у всех?`)
    ? meetOp({ op: 'meeting.set', id, set: s, everywhere: true }, `${msg} — у всех ${v.same_label + 1}`) : set(s, msg));
  const fact = (label, ctl) => el('label', { class: 'fact' }, el('span', {}, label), ctl);
  const inp = (field, ph) => {
    const i = el('input', { value: v[field] || '', placeholder: ph || '' });
    i.addEventListener('change', () => { if (i.value.trim() !== (v[field] || '')) (field === 'label' ? setClient : set)({ [field]: i.value.trim() || null }, 'Записал — разбор это больше не трогает'); });
    return i;
  };
  const projects = [...S.projects.values()].filter((p) => !p.archived_at || p.id === v.project_id).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  const client = choiceBtn(project(v.project_id)?.name || 'без клиента', (a) => choicePop(a, { title: 'Клиент встречи', current: v.project_id || null,
    none: 'Без клиента', items: projectItems(projects),
    onPick: (x) => (v.project_id ? set : setClient)({ project_id: x }, x ? 'Клиент: ' + project(x)?.name : 'Без клиента') }), { empty: !v.project_id });
  const deals = v.project_id ? clientDeals(v.project_id) : [];
  const deal = el('select', { disabled: !deals.length }, el('option', { value: '' }, deals.length ? '— не про сделку —' : 'у клиента нет сделок'),
    deals.map((d) => el('option', { value: d.id, selected: d.id === v.deal_id }, d.name)));
  deal.addEventListener('change', () => set({ deal_id: deal.value || null }));
  const sphere = el('div', { class: 'stages' }, [['work', 'Рабочая'], ['home', 'Личная']].map(([k, l]) =>
    el('button', { class: v.sphere === k ? 'on' : '', onclick: () => set({ sphere: k }, l) }, l)));
  const ids = v.person_ids || [];
  const peopleChips = (v.people_cards || []).map((x) => el('span', { class: 'pchip' },
    el('a', { class: 'pc-main', href: '#/h/' + x.id }, avatar(x), el('span', { class: 'pc-name' }, x.short || x.name)),
    el('button', { class: 'pc-x', title: 'Не был на встрече', onclick: () => set({ person_ids: ids.filter((y) => y !== x.id) }) }, icon('x', 12))));
  const guestChips = (v.guests || []).map((g) => el('span', { class: 'pchip' },
    el('span', { class: 'pc-main' }, el('span', { class: 'pc-name' }, g), el('span', { class: 'pc-role' }, 'нет в справочнике')),
    el('button', { class: 'pc-x', title: 'Убрать', onclick: () => set({ guests: v.guests.filter((y) => y !== g) }) }, icon('x', 12))));
  const addPerson = el('button', { type: 'button', class: 'pchip add', onclick: (e) => choicePop(e.currentTarget, { title: 'Кто был на встрече',
    items: personItems(livePeople().filter((x) => !x.merged_into && !ids.includes(x.id)).sort((a, b) => personName(a).localeCompare(personName(b), 'ru'))),
    onPick: (x) => { if (x) set({ person_ids: [...ids, x] }); } }) }, icon('plus', 12), 'участник');
  const locks = (v.locked || []).map((k) => MT_LOCK[k]).filter(Boolean);
  const when = `${D.long(v.day)}, ${mtTime(v.started_at)}` + (v.duration_min ? ' · ' + dur(v.duration_min) : '') + (MT_KIND[v.kind] ? ' · ' + MT_KIND[v.kind] : '');
  const card = sec('card', null, null,
    v.question ? el('div', { class: 'mq' }, el('b', {}, 'Разбор спрашивает: '), v.question, el('div', { class: 'faint' }, 'Ответь делом: выбери клиента или «рабочая / личная» — вопрос уйдёт.')) : null,
    el('div', { class: 'facts' },
      fact('Название', inp('title', 'Клиент: тема')),
      fact('Клиент', client),
      v.project_id ? fact('Сделка', deal) : fact('Кто в названии', inp('label', 'Экспобанк, ЗФ, Личное…')),
      fact('Тема', inp('topic', 'о чём договаривались'))),
    el('div', { class: 'stage-row' }, el('span', { class: 'cs-word' }, 'Встреча'), sphere),
    el('div', { class: 'pchips' }, peopleChips, guestChips, addPerson),
    el('div', { class: 'mlocks' }, !v.named ? 'Ещё не разобрана: разборщик назовёт её в ближайшие минуты.'
      : v.named_by && v.named_by.startsWith('error') ? 'Разбор не вышел: ' + v.named_by.replace(/^error:\s*/, '') + '. Нажми «Разобрать заново».'
        : 'Назвал Claude' + (locks.length ? ` · ты поправил сам: ${locks.join(', ')} — это разбор больше не трогает` : '')),
    el('div', { class: 'mt-actions' },
      el('button', { class: 'chip-btn', title: 'Claude прочтёт встречу ещё раз; что ты поправил сам, останется', onclick: () => meetOp({ op: 'meeting.set', id, rename: true }, 'Отдал на разбор') }, 'Разобрать заново'),
      locks.length ? el('button', { class: 'chip-btn', title: 'Снять твои правки и отдать всё разбору', onclick: () => meetOp({ op: 'meeting.set', id, unlock: (v.locked || []).filter((k) => k !== 'touch'), rename: true }, 'Всё — разбору') }, 'Вернуть всё разбору') : null,
      el('button', { class: 'chip-btn', onclick: () => set({ archived: !v.archived }, v.archived ? 'Вернул в список' : 'Убрал из списка') }, v.archived ? 'Вернуть в список' : 'Убрать из списка'),
      v.url ? el('a', { class: 'chip-btn', href: v.url, target: '_blank', rel: 'noopener' }, 'У службы встреч') : null));
  const agree = (v.agreements || []).map((a) => (typeof a === 'string' ? a : a.text || a.title || JSON.stringify(a)));
  const summary = sec('sum', 'Сводка', null,
    v.summary ? el('div', { class: 'rows' }, el('div', { class: 'msum' }, v.summary)) : el('div', { class: 'empty' }, 'Сводки служба не прислала.'),
    agree.length ? [el('div', { class: 'msub' }, 'Договорённости'), el('div', { class: 'rows' }, el('ul', { class: 'magree' }, agree.map((a) => el('li', {}, a))))] : null,
    // Запись встречи в хронологии ведёт сервер по карточке (аудит №6, 15.10.2026); убрал или поправил её сам владелец —
    // замок touch: сервер её больше не трогает. «Вернуть» — снова по карточке (meeting.set unlock touch).
    (v.locked || []).includes('touch') ? el('div', { class: 'msub' },
      v.timeline.length ? 'Запись в хронологии клиента ты поправил сам — по карточке её не меняю · ' : 'Убрано из хронологии клиента — ',
      el('button', { class: 'link-btn', title: 'Запись встречи в хронологии клиента — снова по карточке встречи',
        onclick: () => meetOp({ op: 'meeting.set', id, unlock: ['touch'] }, 'Запись в хронологии — снова по карточке') },
        v.timeline.length ? 'вернуть по карточке' : 'вернуть')) : null,
    v.timeline.length ? el('div', { class: 'msub' }, 'В хронологии клиента') : null,
    v.timeline.length ? el('div', { class: 'rows' }, v.timeline.map((i) => el('div', { class: 'tl-row' },
      el('span', { class: 'tl-when' }, D.ddmm(i.at.slice(0, 10))), el('div', {}, i.summary, i.next_step ? el('div', { class: 'tl-next' }, i.next_step) : null),
      i.project ? el('span', { class: 'tl-kind' }, i.project) : null))) : null);
  const tasks = v.tasks.length ? sec('tasks', 'Дела из встречи', secN(v.tasks.length), el('div', { class: 'rows' }, v.tasks.map((t) =>
    el('div', { class: 'crow' + (t.status !== 'open' ? ' arch' : ''), onclick: () => { const x = S.tasks.get(t.id); if (x) openCard(x.id); } },
      el('div', { class: 'main' }, el('div', { class: 'title' }, t.title),
        el('div', { class: 'chips' }, t.status === 'done' ? el('span', {}, 'сделано') : t.status === 'cancelled' ? el('span', {}, 'отменено') : el('span', { class: 'today' }, 'открыто'),
          t.project_name ? el('span', {}, t.project_name) : null, t.due_date ? el('span', {}, D.ddmm(t.due_date)) : null)))))) : null;
  const lq = S.mtLineQ || '';
  const box = el('input', { id: 'main-search', class: 'inline-search', type: 'search', placeholder: 'Найти в расшифровке', value: lq });
  box.addEventListener('input', () => { S.mtLineQ = box.value; render(); });
  const mmss = (x) => (x == null ? '' : `${Math.floor(x / 60)}:${String(Math.floor(x % 60)).padStart(2, '0')}`);
  let prev = null;
  // Кто говорит — своим цветом из палитры «чей это» Ковчега (--sp0…7), по порядку первой реплики во всей встрече:
  // поиск по расшифровке цвета не перекрашивает.
  const spk = new Map();
  for (const x of v.lines || []) if (x.speaker && !spk.has(x.speaker)) spk.set(x.speaker, spk.size % 8);
  const lines = (v.lines || []).filter((x) => !lq || norm(x.text + ' ' + (x.speaker || '')).includes(norm(lq))).map((x) => {
    const who = x.speaker && (x.speaker !== prev || lq) ? tint(el('span', { class: 'tl-s' }, x.speaker), `var(--sp${spk.get(x.speaker)})`) : null;
    prev = x.speaker || prev;
    return el('div', { class: 'tline' }, el('span', { class: 'tl-t' }, mmss(x.start_sec)), el('div', {}, who, markIn(x.text, lq)));
  });
  const text = sec('text', 'Расшифровка', secN(v.lines_n), el('div', { class: 'toolbar' }, box),
    lines.length ? el('div', { class: 'rows' }, lines) : el('div', { class: 'empty' }, v.lines_n ? 'В расшифровке такого нет.' : 'Расшифровки служба не прислала.'));
  const nav = [['card', 'Карточка'], ['sum', 'Сводка'], v.tasks.length ? ['tasks', 'Дела'] : null, ['text', 'Расшифровка']].filter(Boolean);
  // «Только тебе» по встрече (личной — нет: сервер такие строки не держит).
  const mine = v.sphere === 'home' ? null : insightsSec(v, { meeting_id: v.id });
  return [head(v.title || v.source_title || 'Встреча', [when], null, nav), el('div', { class: 'body' }, card, summary, mine, tasks, text)];
}

// ── Клавиши ─────────────────────────────────────────────────────────────
document.addEventListener('keydown', (e) => {
  const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName);
  if (e.key === 'Escape') {
    if (document.querySelector('.kv-menu:not([hidden])')) return; // меню шестерёнки закрывается само
    if (document.querySelector('.pop')) { closePop(); return; }
    if (rec) { stopListening(); S.listenQuick = false; render(); return; }
    if (S.sideOpen) { S.sideOpen = false; render(); return; }
    if (S.cardId || S.draft) { S.cardId = null; S.draft = null; render(); return; }
    if (S.sel.size) { S.sel.clear(); render(); }
    return;
  }
  if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
  if (e.key === '/') { e.preventDefault(); document.querySelector('.side-search')?.focus(); }
  if (e.key === 'n' || e.key === 'т') { e.preventDefault(); document.getElementById('quick')?.focus(); }
  // m — «Сейчас», t — «Сегодня», i — «Новое», a — «Все дела» (13.10.2026; «Предстоящего» и «Жду» с 10.10 нет).
  const jump = { m: 'now', t: 'today', i: 'new', a: 'all' }[e.key];
  if (jump) go('#/' + jump);
});

boot();
