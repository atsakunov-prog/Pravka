// Режим отладки Дел (15.10.2026, debug.py, миграция dela_0031, правило 28): строка «Что не так?» вверху каждой страницы и
// страница #/debug — записки владельца о багах логики вместе с тем, что было на экране.
//
// Владелец: «Давай сделаем в делах накопление багов. Прямо такой debug mode. Наверху где-то прямо текстбокс в телефоне и
// в компе. И туда я просто накидываю баги, причём они идут с тем, что у меня сейчас на экране». Записка (debug.add) несёт
// снимок экрана ДАННЫМИ — маршрут, заголовок, режим и фильтры, выбранное, открытую карточку полями, видимые строки
// списка, свёрнутые блоки, «Новое» на экране, разговор с Claude — и кольцо последних ~30 событий страницы: какие
// операции ушли, что сказано Claude и что он ответил и поменял, тосты, ошибки сети, переходы. Главное для багов логики —
// «сказал X — сделал Y». Разбирает сессия Claude на компе (коннектор dela_debug, `python -m pravka_dela debug`): статус и
// ответ видны здесь, на странице #/debug.
//
// Строка видна, когда режим включён (users.settings.debug владельца; «Настройки» → «Режим отладки» или
// `python -m pravka_dela debug on`), сервер знает флаг debug и человек — владелец. Свой файл, хвостом app.js (api.TAILS):
// своего адреса нет. Крючков в app.js нет НАРОЧНО — ветку собирают в поезд с другими, и общий файл не должен спорить:
// маршрут, страница, строка над страницей, пункт в колонке, тумблер в «Настройках» и кольцо событий ставятся обёртками
// функций app.js (route, renderMain, render, renderSide, renderSettings, api, ops, runJob, toast) — те зовут друг
// друга по имени, и обёртка видна всем.
'use strict';

const DEBUG = (() => {
  const RING = 30;            // событий страницы в записке
  const ROWS = 200;           // строк списка в снимке
  const ring = [];
  let info = null;            // поле debug синка: {on, new, last, answered}
  let text = '';              // набранное в строке, пока не ушло
  let sending = false, hearing = false;
  let bar = null, input = null, micBtn = null, sendBtn = null, count = null;
  let filter = LS.get('dbgFilter', 'open');
  let openId = null;          // раскрытая записка на странице
  const rawOn = new Set();    // записки, показанные «как есть»

  // Слова статусов — владельца (16.10.2026: «новое / разбираю / починено / не баг / потом»), как debug.STATUS_WORD.
  const STATUS = { new: 'новое', taken: 'разбираю', fixed: 'починено', not_bug: 'не баг', later: 'потом' };
  const FILTERS = [['open', 'Новое и разбираю'], ['all', 'Все'], ['fixed', 'Починено'], ['not_bug', 'Не баг'], ['later', 'Потом']];
  const KIND = { op: 'операция', op_fail: 'сервер не принял', op_result: 'сервер сделал', ask: 'сказал Claude', parse: 'разбор',
    reply: 'Claude ответил', toast: 'подсказка', error: 'ошибка', nav: 'переход', event: 'событие',
    // Со страниц соседних сервисов Ковчега (debugin.js, 16.10.2026): нажатия, запросы страницы, поля, формы, console.error.
    click: 'нажал', net: 'запрос', change: 'поле', submit: 'форма', console: 'console.error' };

  const owner = () => !!(S.me && S.me.role === 'owner' && (S.me.features || []).includes('debug'));
  /** Включён ли режим: свежее слово — синк (команда `debug on` с компа видна без перезагрузки), иначе /api/me. */
  const on = () => owner() && (info ? !!info.on : !!((S.me.settings || {}).debug));
  const cut = (s, n) => { s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
  const safe = (fn, d) => { try { return fn(); } catch (e) { return d; } };

  /** Значение — коротко, чтобы одно событие не съело записку: строки до 300, списки до 20, глубина 3. */
  function shrink(v, depth = 0) {
    if (v == null || typeof v === 'number' || typeof v === 'boolean') return v;
    if (typeof v === 'string') return cut(v, 300);
    if (Array.isArray(v)) return depth > 2 ? `[${v.length}]` : [...v.slice(0, 20).map((x) => shrink(x, depth + 1)), ...(v.length > 20 ? [`… ещё ${v.length - 20}`] : [])];
    if (typeof v === 'object') {
      if (depth > 2) return '{…}';
      const o = {};
      for (const [k, x] of Object.entries(v)) if (x !== undefined && k !== 'search') o[k] = shrink(x, depth + 1);
      return o;
    }
    return String(v);
  }

  function note(kind, said, data) {
    const x = { at: new Date().toISOString(), kind, text: cut(said, 600) };
    if (data !== undefined) x.data = shrink(data);
    ring.push(x);
    while (ring.length > RING) ring.shift();
  }

  // ── Кольцо событий: обёртки сети, Claude и подсказок ──────────────────
  const taskName = (id) => (S.tasks.get(id) || {}).title || (S.deals.get(id) || {}).name || (S.people.get(id) || {}).name
    || (S.projects.get(id) || {}).name || '';
  const numTitle = (t) => (t.num ? '#' + t.num + ' ' : '') + cut(t.title || '', 80);
  /** Вложенность словами (правило 27, поезд 16.10.2026): «внутрь «…»», «вынуть из дела», «место 2» — иначе в записке
   *  только uuid в why_id, и «вложил — а оно не там» Claude не прочтёт. why_id на цель — это «Зачем», а не «внутрь». */
  function nestWords(o) {
    const s = o.op === 'task.set' ? o.set || {} : o.op === 'task.create' ? o.task || {} : {};
    const out = [];
    if ('why_id' in s && (o.op === 'task.set' || s.why_id)) {
      const p = s.why_id ? S.tasks.get(s.why_id) : null;
      out.push(!s.why_id ? 'вынуть из дела' : p && p.goal ? 'Зачем — цель «' + cut(p.title, 60) + '»' : 'внутрь «' + (p ? numTitle(p) : s.why_id) + '»');
    }
    if ('pos' in s) out.push(s.pos == null ? 'место снято' : 'место ' + s.pos);
    return out.join(', ');
  }
  function opWords(o) {
    const t = (o.task && o.task.title) || (o.data && (o.data.name || o.data.title || o.data.summary)) || (o.id ? taskName(o.id) : '')
      || (Array.isArray(o.ids) ? o.ids.map(taskName).filter(Boolean).slice(0, 3).join(', ') : '');
    const n = safe(() => nestWords(o), '');
    return (o.op || '?') + (t ? ' «' + cut(t, 80) + '»' : '') + (n ? ' — ' + n : '');
  }
  function scopeShort(s) {
    if (!s) return undefined;
    return { title: s.title, card: s.card, open: s.open ? taskName(s.open) || s.open : undefined, kstati: s.kstati || undefined,
      step: s.step || undefined, // «Для этого» (правило 27): всё сказанное — дела внутри открытого
      on_screen: (s.task_ids || []).length, in_view: (s.visible_ids || []).length, selected: (s.selected_ids || []).length || undefined,
      suggestions: (s.suggestion_ids || []).length || undefined, history: (s.history || []).length || undefined };
  }
  function replyData(d) {
    return { route: d.route, changed: (d.changed || []).map((c) => ({ id: c.id, title: (c.after && c.after.title) || c.title,
      what: safe(() => describe(c), ''), status: c.status })),
      tasks: (d.tasks || []).map((t) => ({ id: t.id, num: t.num, title: t.title })),
      decided: d.decided && d.decided.length ? d.decided : undefined, crm: (d.crm || []).map((c) => c.what || c),
      notes: d.notes && d.notes.length ? d.notes : undefined, errors: d.errors && d.errors.length ? d.errors : undefined,
      // Дела внутри дела (правило 27): что завёл внутри, чей срок поднял сервер, кому вернул — их reply пишет сервер.
      steps: (d.steps || []).length ? d.steps.map((id) => taskName(id) || id) : undefined,
      raised: (d.raised || []).length ? d.raised.map((r) => numTitle(r) + (r.moved ? ' → на сегодня' : '')) : undefined,
      fell: (d.fell || []).length ? d.fell.map((r) => numTitle(r) + ' → срок ' + (r.due_date || 'снят')) : undefined };
  }

  const api0 = api;
  api = async function (path, body) { // eslint-disable-line no-global-assign
    try {
      const d = await api0(path, body);
      if (typeof path === 'string' && path.startsWith('/api/sync') && d && d.debug !== undefined) {
        const was = on();
        info = d.debug;
        if (was !== on()) setTimeout(softRender);
      }
      return d;
    } catch (e) {
      if (!(typeof path === 'string' && path.startsWith('/api/view/debug'))) note('error', String(path).split('?')[0] + ': ' + e.message);
      throw e;
    }
  };
  const ops0 = ops;
  ops = async function (items) { // eslint-disable-line no-global-assign
    for (const o of items) note('op', opWords(o), o);
    let res;
    try { res = await ops0(items); } catch (e) { note('op_fail', e.message); throw e; }
    // Сделано последнее дело внутри — родитель поднялся (raised); дело снова открыто — родителю вернули срок (fell).
    for (const r of res || []) {
      if (r && r.raised) note('op_result', 'всё внутри сделано — поднят «' + numTitle(r.raised) + '»' + (r.raised.moved ? ', на сегодня' : ''), r.raised);
      if (r && r.fell) note('op_result', 'родителю вернули срок: «' + numTitle(r.fell) + '» — ' + (r.fell.due_date || 'без срока'), r.fell);
    }
    return res;
  };
  const runJob0 = runJob;
  runJob = async function (path, body) { // eslint-disable-line no-global-assign
    note(path === '/api/ask' ? 'ask' : path === '/api/parse' ? 'parse' : 'job', (body && body.text) || path, scopeShort(body && body.scope));
    try {
      const d = await runJob0(path, body);
      note('reply', d.reply || (d.status === 'done' ? '(без ответа словами)' : d.status || ''), replyData(d));
      return d;
    } catch (e) { note('error', 'Claude: ' + e.message); throw e; }
  };
  const toast0 = toast;
  toast = function (t, undo) { note('toast', t); return toast0(t, undo); }; // eslint-disable-line no-global-assign
  window.addEventListener('hashchange', () => note('nav', location.hash || '#/'));

  // ── Снимок экрана данными ─────────────────────────────────────────────
  const txt = (node) => (node ? node.innerText || node.textContent || '' : '').replace(/\s+/g, ' ').trim();
  /** Текст узла без [skip] внутри: на узком строка «Что не так?» стоит в листе карточки — её «№N · новых» в текст
   *  карточки не идёт. */
  const txtBut = (node, skip) => (!node || !skip || !node.contains(skip) ? txt(node)
    : [...node.children].filter((n) => n !== skip).map((n) => txtBut(n, skip)).filter(Boolean).join(' '));
  /** Заголовок группы без числа, кнопок и пояснения: «Отбить», «Ромашка». */
  function headText(h) {
    if (!h) return '';
    const c = h.cloneNode(true);
    c.querySelectorAll('.n, .act, .sum, .gsub').forEach((x) => x.remove());
    return (c.textContent || '').replace(/\s+/g, ' ').trim();
  }
  function rowGroup(row) {
    const parts = [];
    const g = row.closest('.group');
    if (g) parts.push(headText(g.querySelector(':scope > h2')));
    const sub = row.closest('.subgroup');
    if (sub) parts.push(headText(sub.querySelector(':scope > h3, :scope > h4')));
    return parts.filter(Boolean).join(' › ');
  }
  function rowData(row, vis) {
    const id = row.dataset.id;
    const t = S.tasks.get(id);
    const x = { id, num: t && t.num, title: t ? t.title : txt(row.querySelector('.title, .rem-title')), group: rowGroup(row) || undefined };
    if (t) {
      if (t.due_date) x.due = t.due_date + (t.due_time ? ' ' + t.due_time : '');
      // Захват (правило 29, поезд 16.10.2026): напоминание — не дело, в свой час или по месту; «потом» — день возврата.
      if (t.kind === 'remind') x.remind = 'напоминание ' + (t.remind_at || (t.remind_place ? 'по месту «' + t.remind_place + '»' : ''));
      else if (t.back_on && !t.due_date) x.back = 'потом · вернётся ' + t.back_on + (t.back_said ? ' («' + t.back_said + '»)' : '');
      const p = person(t.person_id), pr = project(t.project_id), z = safe(() => zachemOf(t), null);
      if (p) x.person = personName(p);
      if (pr) x.project = pr.name;
      if (z && z.title && (!pr || z.title !== pr.name)) x.why = z.title;
      if (t.status !== 'open') x.status = t.status;
      if (safe(() => isNow(t), false)) x.now = true;
      x.grp = safe(() => grpWord(t), undefined);
      // Дела внутри дела (правило 27): родитель, место среди соседей, сколько внутри открыто — данными, не подписью.
      const par = safe(() => parentOf(t), null);
      if (par) x.in = numTitle(par);
      if (t.pos != null) x.pos = t.pos;
      const kids = safe(() => insideOf(t.id), []);
      if (kids.length) x.inside = kids.length;
    }
    // Отступ на экране («Деревом», дела внутри под родителем в «Сейчас», шаги к цели в дереве) и свёрнутый родитель.
    const depth = +(row.style.getPropertyValue('--indent') || 0);
    if (depth) x.depth = depth;
    const fold = row.querySelector('.fold-btn:not(.none)');
    if (fold && fold.getAttribute('aria-expanded') === 'false') x.folded = true;
    const meta = [...row.querySelectorAll('.chips > *')].map(txt).filter(Boolean).join(' · ');
    if (meta) x.meta = cut(meta, 300);
    if (S.sel.has(id)) x.selected = true;
    x.in_view = vis.has(id);
    return x;
  }
  const FIELD_SKIP = new Set(['search', 'seq', 'rev', 'krug_seq', 'owner_id', 'created_by']);
  function fieldsOf(o) {
    const out = {};
    for (const [k, v] of Object.entries(o || {})) if (!FIELD_SKIP.has(k) && v !== null && v !== '' && !(Array.isArray(v) && !v.length)) out[k] = v;
    return out;
  }
  function cardOf(r) {
    if (S.cardId && S.tasks.has(S.cardId)) {
      const t = S.tasks.get(S.cardId);
      const f = fieldsOf(t);
      if (t.person_id) f.person_name = personName(person(t.person_id));
      if (t.project_id) f.project_name = (project(t.project_id) || {}).name;
      const z = safe(() => zachemOf(t), null);
      if (z) f.zachem = z.title;
      // Дела внутри дела (правило 27): внутри чего это дело и что внутри него — по месту, как блок «Внутри» карточки.
      const par = safe(() => parentOf(t), null);
      if (par) f.for = numTitle(par);
      const kids = safe(() => insideOf(t.id), []);
      if (kids.length) f.inside = kids.map((k, i) => (i + 1) + '. ' + numTitle(k) + (k.due_date ? ' · срок ' + k.due_date : '')
        + (k.pos != null ? ' · pos ' + k.pos : ''));
      const doneIn = safe(() => all().filter((k) => k.why_id === t.id && !isOpen(k) && !k.goal).length, 0);
      if (doneIn) f.inside_done = doneIn;
      // Комментарии строками «дата · автор · [только тебе · ]текст» (поезд 16.10.2026; как `DelaDebug.comments` телефона):
      // приватность правила 26 — данными, а не только текстом карточки.
      const cs = safe(() => (extras.get(t.id) || {}).comments || [], []);
      if (cs.length) f.comments = cs.slice(-30).map((c) => [String(c.created_at || '').slice(0, 16).replace('T', ' '), c.author_id,
        c.private ? 'только тебе' : '', cut(String(c.text || '').trim(), 300)].filter(Boolean).join(' · '));
      return { kind: t.goal ? 'goal' : 'task', id: t.id, num: t.num, title: t.title, fields: f,
        text: cut(txtBut(document.querySelector('section.card-pane'), bar), 3000) || undefined };
    }
    const page = () => cut(txt(document.querySelector('main.list')), 4000);
    if (r.kind === 'deal') { const d = S.deals.get(r.id); return { kind: 'deal', id: r.id, title: d && d.name, fields: fieldsOf(d), text: page() }; }
    if (r.kind === 'project') { const p = project(r.id); return { kind: p && p.kind === 'client' ? 'client' : 'project', id: r.id, title: p && p.name, fields: fieldsOf(p), text: page() }; }
    if (r.kind === 'person') { const p = person(r.id); return { kind: 'person', id: r.id, title: p && p.name, fields: fieldsOf(p), text: page() }; }
    if (r.kind === 'meeting') return { kind: 'meeting', id: r.id, title: txt(document.querySelector('main.list .kv-head h1')), text: page() };
    return null;
  }
  function modeOf(r) {
    const key = ['today', 'all', 'project'].includes(r.kind) ? r.kind : r.kind === 'filter' ? 'f:' + r.id : null;
    const f = r.kind === 'filter' ? filters().find((x) => x.id === r.id) : null;
    const m = { sphere: S.sphere || 'все', show_done: S.showDone || undefined, search: r.q || undefined,
      levels: key ? safe(() => (f ? (S.fdraft[r.id] || f).levels : levelsOf(key)), undefined) : undefined,
      pick: key ? (f ? (S.fdraft[r.id] || f).pick : S.pick[r.kind]) || undefined : undefined,
      filter: f ? { id: f.id, name: f.name, scope: f.scope, draft: S.fdraft[r.id] ? true : undefined } : undefined,
      deal_filter: S.dealFilter || undefined, tab: txt(document.querySelector('main.list .pills .on')) || undefined,
      side_hidden: S.sideHidden || undefined, card_hidden: S.cardHidden || undefined, narrow: NARROW.matches || undefined };
    if (CRM_VIEWS[r.kind] || r.kind === 'clients') Object.assign(m, { crm_list: S.crmList, crm_mine: S.crmMine || undefined, client_q: S.clientQ || undefined, client_arch: S.clientArch || undefined });
    if (r.kind === 'tree' || r.kind === 'krug') Object.assign(m, { tree_mode: S.treeMode, tree_dir: S.treeDir, tree_level: S.treeLevel, tree_filter: S.treeF || undefined });
    // «Деревом» во «Всех делах» (правило 27): дела внутри — под родителем отступом, а не плоско перед ним.
    if (r.kind === 'all' && safe(() => nestOn(), false)) m.all_tree = !!S.allTree;
    if (r.kind === 'meetings' || r.kind === 'meeting') m.meetings = S.mt;
    return m;
  }
  function snapshot() {
    const r = route();
    const errors = [];
    const part = (name, fn) => { try { return fn(); } catch (e) { errors.push(name + ': ' + e.message); return undefined; } };
    const sc = part('scope', () => pageScope({})) || {};
    const vis = new Set(sc.visible_ids || []);
    const rows = [], seen = new Set();
    let total = 0;
    part('rows', () => {
      // Строки дел и строки «Напоминания» / «Вернулось» (захват: .rem-row) — по порядку экрана. Строк захвата нет в
      // pageScope (это не дела экрана для Claude), поэтому «в окне» у них — тем же правилом, что у строк дел: видно не
      // меньше 60 % высоты между тем, что липнет к верху, и низом колонки.
      const main = document.querySelector('main.list');
      const top = safe(() => stuckBottom(), 0);
      const bottom = Math.min(window.innerHeight, main ? main.getBoundingClientRect().bottom : window.innerHeight);
      const inWin = (x) => { const b = x.getBoundingClientRect(); return b.height > 0 && Math.min(b.bottom, bottom) - Math.max(b.top, top) >= b.height * 0.6; };
      for (const row of document.querySelectorAll('main.list .task[data-id], main.list .rem-row[data-id]')) {
        if (seen.has(row.dataset.id)) continue;
        seen.add(row.dataset.id);
        total++;
        if (rows.length >= ROWS) continue;
        const x = rowData(row, vis);
        if (row.classList.contains('rem-row')) x.in_view = inWin(row);
        rows.push(x);
      }
    });
    const s = {
      v: 1, route: location.hash || '#/now', kind: r.kind,
      title: txt(document.querySelector('main.list .kv-head h1')) || sc.title || '',
      sub: txt(document.querySelector('main.list .kv-head .h-sub')) || undefined,
      mode: part('mode', () => modeOf(r)),
      selected: S.sel.size ? [...S.sel].map((id) => ({ id, title: taskName(id) })).slice(0, 100) : undefined,
      card: part('card', () => cardOf(r)) || undefined,
      rows: rows.length ? rows : undefined,
      rows_total: rows.length ? total : undefined,
      deals: (sc.deal_ids || []).length ? sc.deal_ids.slice(0, 60).map((id) => ({ id, name: (S.deals.get(id) || {}).name,
        in_view: (sc.visible_deal_ids || []).includes(id) })) : undefined,
      folds: part('folds', () => ({ closed: Object.keys(S.closed).filter((k) => S.closed[k]).slice(0, 60)
        .map((k) => (k.startsWith('nest:') && S.tasks.has(k.slice(5)) ? 'внутри ' + numTitle(S.tasks.get(k.slice(5))) : k)),
        auto_open: S.autoOpen || undefined, client_open: [...S.clientOpen].map((id) => (project(id) || {}).name || id).slice(0, 30) })),
      new: part('new', () => (S.sugOrder || []).slice(0, 60).map((id, i) => {
        const g = S.sugs.get(id), w = g ? safe(() => sugText(g), {}) : {};
        return { n: 'П' + (i + 1), id, kind: g && g.kind, source: g && g.source, title: w.title, hint: w.hint || undefined, status: g && g.status,
          day: g && g.source === 'plan' ? (g.payload || {}).for_date : undefined };
      })),
      // «Подскажи» двумя заходами (шаг 2 петли, фича asks; поезд 16.10.2026): на главной «Сейчас» и #/day — фаза, ближайший
      // заход, свёрнутая строка или «раскрыто», числа и группы очереди в порядке вечера; вопросы на экране — в «new».
      // Свёрнуто — вопросов на экране нет, и без этого ключа записка «не вижу вопросов» была бы непонятна.
      asks: part('asks', () => {
        if (!asksOn() || !(r.kind === 'now' || (r.kind === 'twin' && r.page === 'day'))) return undefined;
        const q = asksQueue();
        if (!q.total) return undefined;
        const open = asksExpanded(q);
        const line = open ? undefined : q.phase === 'today' && q.urgent.length ? (q.rest ? 'к вечеру: ' + q.rest : undefined) : asksFoldText(q);
        return { phase: q.phase, next: q.next ? q.next.label : undefined, open: open && q.phase !== 'evening' ? true : undefined,
          line, total: q.total, urgent: q.urgent.length, rest: q.rest, plan: q.plan || undefined,
          clock: LS.get('asksAt', null) || undefined,
          groups: q.groups.slice(0, 20).map((g) => ({ kind: g.kind, title: g.title, n: g.items.length, urgent: g.urgent || undefined })) };
      }),
      // «Сейчас на завтра» (planBox): шапка, «N дня в «Сейчас»», отмеченное заранее; кандидаты — в «new» П-номерами.
      plan: part('plan', () => {
        const b = document.querySelector('main.list .plan-box');
        if (!b) return undefined;
        const lines = (sel) => [...b.querySelectorAll(sel)].map(txt).filter(Boolean);
        return { head: txt(b.querySelector('h2')), stuck: lines('.hint-line'), marked: lines('.plan-marked') };
      }),
      talk: part('talk', () => (S.talk && S.talk.turns.length ? S.talk.turns.slice(-8).map(turnMemo) : undefined)),
      card_talk: part('card_talk', () => (S.cardTalk && S.cardTalk.turns.length ? S.cardTalk.turns.slice(-8).map(turnMemo) : undefined)),
      claude_busy: S.parse ? S.parse.text : undefined,
      quick_text: S.quickText || undefined,
      today: S.today, seq: S.seq,
    };
    if (!rows.length && !s.card) s.page_text = cut(txt(document.querySelector('main.list')), 6000);
    if (s.new && !s.new.length) delete s.new;
    if (errors.length) s.errors = errors;
    return JSON.parse(JSON.stringify(s)); // undefined — прочь
  }
  function version() {
    const e = safe(() => performance.getEntriesByType('resource').find((x) => /\/static\/app\.js(\?|$)/.test(x.name)), null);
    const kb = e && e.decodedBodySize ? Math.round(e.decodedBodySize / 1024) + ' КБ' : '?';
    const m = navigator.userAgent.match(/(Edg|OPR|YaBrowser|Firefox|Chrome|Version)\/(\d+)/);
    return cut(`app.js ${kb} · ${m ? (m[1] === 'Version' ? 'Safari' : m[1]) + ' ' + m[2] : 'браузер'}`, 80);
  }
  function screenWords() {
    const z = +getComputedStyle(document.body).zoom || 1;
    return cut(`${innerWidth}×${innerHeight}` + (devicePixelRatio !== 1 ? ` @${+devicePixelRatio.toFixed(2)}` : '')
      + (NARROW.matches ? ' узкий' : '') + (z !== 1 ? ` текст ×${+z.toFixed(2)}` : ''), 80);
  }

  // ── Строка «Что не так?» ──────────────────────────────────────────────
  async function send() {
    const t = (input ? input.value : text).trim();
    if (!t) { if (input) input.focus(); return; }
    if (sending) return;
    sending = true;
    paint();
    let snap;
    try { snap = snapshot(); } catch (e) { snap = { v: 1, route: location.hash, errors: ['снимок: ' + e.message] }; }
    const one = { id: uuid(), text: t, source: 'web', version: version(), screen: screenWords(), at: new Date().toISOString(),
      snapshot: snap, actions: ring.slice() };
    try {
      // Мимо ops: записка не должна попасть в своё же кольцо событий.
      const d = await api0('/api/ops', { ops: [{ op_id: uuid(), op: 'debug.add', note: one }] });
      const r = d.results[0];
      if (!r.ok) throw new Error(r.error);
      text = '';
      if (input) input.value = '';
      info = { ...(info || { on: true }), last: r.note.num, new: ((info && info.new) || 0) + (r.again ? 0 : 1) };
      dirty();
      toast0('🐞 №' + r.note.num + ' записал');
    } catch (e) {
      toast0('Не записал: ' + e.message + ' — текст остался в поле');
    } finally {
      sending = false;
      paint();
    }
  }
  function mic() {
    if (hearing) { stopListening(); return; }
    const base = (input ? input.value : text).trim();
    hearing = true;
    const ok = listen((said) => {
      text = (base ? base + ' ' : '') + said;
      if (input) input.value = text;
      paint();
    }, () => {
      hearing = false;
      const said = (input ? input.value : text).trim();
      paint();
      // «Стоп» — текст в поле, записывает человек — Enter или стрелкой (16.10.2026, запись отладки 12: «во всех микрофонах…
      // иногда хочу что-то подправить»; до того второе нажатие сразу записывало, как строка Claude с галочкой voiceAuto).
      if (said && input) { input.focus(); input.setSelectionRange(input.value.length, input.value.length); }
    });
    if (!ok) hearing = false;
    paint();
  }
  function build() {
    input = el('input', { id: 'dbg-text', type: 'text', autocomplete: 'off', enterkeyhint: 'send',
      placeholder: 'Что не так? — запишу с экраном', 'aria-label': 'Режим отладки: что не так — запишу вместе с тем, что на экране' });
    input.value = text;
    input.addEventListener('input', () => { text = input.value; paint(); });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); send(); }
      if (e.key === 'Escape') { input.value = text = ''; paint(); input.blur(); e.stopPropagation(); }
    });
    micBtn = el('button', { type: 'button', class: 'dbg-btn', onclick: mic });
    sendBtn = el('button', { type: 'button', class: 'dbg-btn dbg-send', title: 'Записать (Enter)', 'aria-label': 'Записать', onclick: send }, icon('up', 17));
    count = el('a', { class: 'dbg-n', href: '#/debug', title: 'Записки отладки — статус и ответ разбора' });
    bar = el('div', { class: 'dbg-bar', role: 'region', 'aria-label': 'Режим отладки' },
      el('span', { class: 'dbg-ico', title: 'Режим отладки: записка уйдёт со снимком экрана и последними действиями' }, icon('bug', 17)),
      input, micBtn, sendBtn, count);
  }
  function paint() {
    if (!bar) return;
    bar.classList.toggle('busy', sending);
    bar.classList.toggle('hearing', hearing);
    bar.classList.toggle('has-text', !!(input && input.value.trim()));
    input.disabled = sending;
    micBtn.replaceChildren(icon(hearing ? 'stop' : 'mic', 17));
    micBtn.title = hearing ? 'Готово — текст встанет в поле' : 'Сказать голосом: нажми, говори, нажми ещё раз — текст встанет в поле';
    micBtn.setAttribute('aria-label', hearing ? 'Закончить — текст встанет в поле' : 'Сказать голосом');
    micBtn.setAttribute('aria-pressed', hearing ? 'true' : 'false');
    sendBtn.disabled = sending;
    const n = info && info.new, last = info && info.last;
    count.replaceChildren(...[last ? el('span', {}, '№' + last) : el('span', {}, 'записки'), n ? el('b', {}, ' · новых ' + n) : null].filter(Boolean));
  }
  /** Строка — под шапкой Ковчега, над колонками (и над страницами-двойниками): узел один на всю жизнь вкладки — текст и
   *  фокус переживают перерисовку.
   *
   *  Узкий веб (≤ 900 px) с открытой карточкой (поезд 16.10.2026, проверка вагона 3): карточка там — лист поверх всего
   *  экрана (`section.card-pane { position: fixed; inset: 0 }` в style.css), и строка под шапкой оказывалась под ним —
   *  не видна и не нажимается. Закрыть карточку нельзя: снимок потерял бы открытое. Поэтому строка встаёт в сам лист —
   *  сразу под его шапкой «← назад» (`.card-top`), нет её — первой. Смена ширины перерисовывает страницу сама
   *  (слушатель NARROW в app.js зовёт `render` по имени — то есть эту обёртку), и строка переезжает обратно. */
  function mount() {
    if (!on()) { if (bar) bar.remove(); return; }
    if (!bar) build();
    paint();
    const pane = NARROW.matches && (S.cardId || S.draft) ? $app.querySelector('section.card-pane') : null;
    if (pane) {
      const head = pane.querySelector('.card-top');
      if (head) { if (head.nextSibling !== bar) head.after(bar); } else if (pane.firstChild !== bar) pane.prepend(bar);
      return;
    }
    const top = S.top && S.top.parentNode === $app ? S.top : null;
    if (top) { if (top.nextSibling !== bar) top.after(bar); } else if ($app.firstChild !== bar) $app.prepend(bar);
  }
  const render0 = render;
  render = function () { // eslint-disable-line no-global-assign
    const had = input && document.activeElement === input ? [input.selectionStart, input.selectionEnd] : null;
    render0();
    mount();
    if (had && input && input.isConnected) { input.focus(); safe(() => input.setSelectionRange(had[0], had[1])); }
  };

  // ── Маршрут #/debug, пункт в колонке, тумблер в «Настройках» ─────────
  const route0 = route;
  route = function () { // eslint-disable-line no-global-assign
    return /^#\/?debug(\/|$)/.test(location.hash) ? { kind: 'debug' } : route0();
  };
  const renderMain0 = renderMain;
  renderMain = function (r) { // eslint-disable-line no-global-assign
    return r && r.kind === 'debug' ? page() : renderMain0(r);
  };
  const renderSide0 = renderSide;
  renderSide = function (r) { // eslint-disable-line no-global-assign
    const aside = renderSide0(r);
    if (owner() && (on() || (info && info.last))) {
      const foot = aside.querySelector('.side-foot');
      const item = navItem('#/debug', icon('bug', 17), 'Отладка', r.kind === 'debug');
      const items = foot ? foot.querySelectorAll('.nav-item') : [];
      if (items.length) items[items.length - 1].before(item); else if (foot) foot.append(item); // перед «Настройками»
    }
    return aside;
  };
  async function setOn(v) {
    try {
      await op1({ op: 'user.settings', settings: { debug: v } });
      S.me.settings = { ...(S.me.settings || {}), debug: v };
      info = { ...(info || {}), on: v };
      crmDirty();
      toast0(v ? 'Режим отладки включён — строка «Что не так?» вверху' : 'Режим отладки выключен — записки целы');
      render();
    } catch (e) { fail(e); }
  }
  const renderSettings0 = renderSettings;
  renderSettings = function () { // eslint-disable-line no-global-assign
    const out = renderSettings0();
    if (!owner()) return out;
    const body = [out].flat(Infinity).find((x) => x && x.classList && x.classList.contains('body'));
    if (!body) return out;
    const box = el('input', { type: 'checkbox' });
    box.checked = on();
    box.addEventListener('change', () => setOn(box.checked));
    body.append(el('div', { class: 'group' }, el('h2', {}, icon('bug', 17), 'Режим отладки'),
      el('div', { class: 'hint-line' }, 'Вверху каждой страницы — строка «Что не так?»: пишешь или говоришь, что сделано не так, и записка уходит вместе с тем, что на экране, и последними действиями (что сказал Claude и что он поменял). Разбирает сессия Claude на компе; статус и ответ — на странице «Отладка».'),
      el('label', { class: 'set-check' }, box, 'Строка «Что не так?» вверху страниц', el('span', { class: 'faint' }, ' — и на телефоне, во вкладке Дел')),
      el('div', { class: 'hint-line' }, el('a', { href: '#/debug' }, 'Записки отладки →'))));
    return out;
  };

  // ── Страница #/debug ──────────────────────────────────────────────────
  const listPath = () => '/api/view/debug?limit=200' + (filter === 'all' ? '' : '&status=' + filter);
  const onePath = (id) => '/api/view/debug?id=' + encodeURIComponent(id);
  function dirty() { for (const [k, c] of crmCache) if (k.startsWith('/api/view/debug')) c.at = 0; }
  const when = (iso) => safe(() => { const m = msk(Date.parse(iso)); return (m.day === S.today ? 'сегодня' : D.ddmm(m.day)) + ' ' + m.hm; }, iso || '');
  const hms = (iso) => safe(() => new Date(Date.parse(iso) + MSK_MS).toISOString().slice(11, 19), String(iso || ''));
  const json1 = (v) => (typeof v === 'string' ? v : JSON.stringify(v));

  async function setStatus(x, status) {
    try {
      const d = await api0('/api/ops', { ops: [{ op_id: uuid(), op: 'debug.set', id: x.id, status }] });
      if (!d.results[0].ok) throw new Error(d.results[0].error);
      dirty();
      render();
    } catch (e) { toast0('Не вышло: ' + e.message); }
  }

  /** Снимок — читаемо: экран, режим, открытое полями, строки списка (★ — были в окне), остальное строкой. */
  function snapView(s) {
    if (!s || !Object.keys(s).length) return [el('div', { class: 'faint' }, 'Снимка нет.')];
    const out = [];
    // Список объектов («Новое» на экране, разговор, сделки) — строкой на запись, значения через « · », без id.
    const words = (v) => (Array.isArray(v) && v.length && v.every((x) => x && typeof x === 'object' && !Array.isArray(x))
      ? v.map((x) => Object.entries(x).filter(([k, y]) => k !== 'id' && y !== '' && y != null).map(([, y]) => (typeof y === 'object' ? json1(y) : String(y))).join(' · ')).join('\n')
      // Список строк («Внутри» карточки по месту, правило 27) — строкой на пункт, а не JSON.
      : Array.isArray(v) && v.length && v.every((x) => typeof x === 'string') ? v.join('\n')
      : typeof v === 'object' ? json1(v) : String(v));
    const kv = (k, v) => el('div', { class: 'dbg-kv' }, el('span', { class: 'k' }, k), el('span', { class: 'v' }, words(v)));
    if (s.service || s.service_name) out.push(kv('служба', [s.service_name, s.service, s.url].filter(Boolean).join(' · ')));
    out.push(kv('экран', [s.title, s.route, s.sub].filter(Boolean).join(' · ')));
    if (s.mode) for (const [k, v] of Object.entries(s.mode)) out.push(kv(k, v));
    if (s.card) {
      out.push(el('div', { class: 'dbg-h' }, 'Открыто: ' + [s.card.kind, s.card.num ? '#' + s.card.num : null, s.card.title].filter(Boolean).join(' ')));
      for (const [k, v] of Object.entries(s.card.fields || {})) out.push(kv(k, v));
    }
    if (s.selected) out.push(kv('выбрано', s.selected.map((x) => x.title || x.id || x).join('; ')));
    if (s.rows) {
      out.push(el('div', { class: 'dbg-h' }, `Строки списка: ${s.rows.length}` + (s.rows_total > s.rows.length ? ` из ${s.rows_total}` : '') + ' · ★ — были в окне'));
      out.push(el('div', { class: 'dbg-rows' }, s.rows.map((r) => el('div', { class: 'dbg-row' + (r.in_view ? ' seen' : '') },
        el('span', { class: 'star' }, r.in_view ? '★' : ''), r.num ? el('span', { class: 'num' }, '#' + r.num) : null, el('b', {}, r.title || r.id),
        el('span', { class: 'faint' }, ' ' + [r.depth ? '↳'.repeat(Math.min(r.depth, 4)) : null, r.in ? 'внутри ' + r.in : null,
          r.pos != null ? 'место ' + r.pos : null, r.inside ? 'внутри открыто ' + r.inside + (r.folded ? ' (свёрнуто)' : '') : null,
          r.group, r.grp, r.due, r.person, r.project, r.why, r.status, r.meta].filter(Boolean).join(' · '))))));
    }
    // «Подскажи» по фазе (шаг 2 петли): строкой — фаза, заход, свёрнутая строка или «раскрыто», числа; группы — по строке
    // (те же слова, что debug.snapshot_lines сервера и DelaDebug.snapshotLines телефона).
    if (s.asks) {
      const a = s.asks;
      out.push(el('div', { class: 'dbg-h' }, '«Подскажи»: ' + [`фаза ${a.phase}` + (a.next ? `, следующий заход ${a.next}` : ''),
        a.open ? 'раскрыто щелчком до смены фазы' : null, a.line, `всего ${a.total || 0}, сгорает ${a.urgent || 0}, к вечеру ${a.rest || 0}`
          + (a.plan ? `, кандидатов плана ${a.plan}` : '')].filter(Boolean).join(' · ')));
      for (const g of a.groups || []) out.push(kv(g.title || g.kind, `${g.n}` + (g.urgent ? ` (сгорает ${g.urgent})` : '')));
    }
    const SKIP = new Set(['v', 'title', 'route', 'sub', 'mode', 'card', 'selected', 'rows', 'rows_total', 'kind', 'asks', 'service', 'service_name', 'url']);
    for (const [k, v] of Object.entries(s)) if (!SKIP.has(k)) out.push(kv(k, v));
    return out;
  }
  function actionsView(a) {
    if (!a || !a.length) return [el('div', { class: 'faint' }, 'Действий нет.')];
    return [el('div', { class: 'dbg-acts' }, a.map((x) => el('div', { class: 'dbg-act k-' + (x.kind || '') },
      el('span', { class: 'at' }, hms(x.at)), el('span', { class: 'kind' }, KIND[x.kind] || x.kind || '?'),
      el('span', { class: 'v' }, x.text || ''), x.data ? el('div', { class: 'data faint' }, json1(x.data)) : null)))];
  }
  function detail(x) {
    const v = crmGet(onePath(x.id), 60000);
    if (!v) return loading();
    const n = v.note;
    const raw = rawOn.has(x.id);
    return el('div', { class: 'dbg-detail' },
      el('div', { class: 'dbg-tools' },
        el('button', { class: 'chip-btn' + (raw ? ' on' : ''), onclick: () => { if (raw) rawOn.delete(x.id); else rawOn.add(x.id); render(); } }, 'Как есть'),
        el('span', { class: 'faint' }, [n.version, n.screen, n.via].filter(Boolean).join(' · '))),
      raw ? el('pre', { class: 'dbg-raw' }, JSON.stringify({ snapshot: n.snapshot, actions: n.actions }, null, 1)) : [
        el('div', { class: 'dbg-h' }, 'Что было на экране'), ...snapView(n.snapshot),
        el('div', { class: 'dbg-h' }, 'Последние действия'), ...actionsView(n.actions)],
      n.shot ? el('img', { class: 'dbg-shot', src: n.shot, alt: 'Экран в миг записки' }) : null);
  }
  function item(x) {
    const open = openId === x.id;
    const where = [x.title !== x.service ? x.title : null, x.route].filter(Boolean).join(' · '); // «Кино · Кино» — один раз
    return el('div', { class: 'dbg-item st-' + x.status + (open ? ' open' : '') },
      el('div', { class: 'dbg-top' },
        el('b', { class: 'num' }, '№' + x.num),
        el('span', { class: 'faint' }, [when(x.said_at), x.source === 'phone' ? 'телефон' : x.service ? 'веб · ' + x.service : 'веб', where]
          .filter(Boolean).join(' · ')),
        x.has_shot ? el('span', { class: 'faint' }, ' · картинка') : null),
      el('div', { class: 'dbg-text' }, x.text),
      x.answer ? el('div', { class: 'dbg-answer' }, el('span', { class: 'faint' }, 'Ответ' + (x.status_by ? ' (' + x.status_by + (x.status_at ? ', ' + when(x.status_at) : '') + ')' : '') + ': '), x.answer) : null,
      el('div', { class: 'dbg-st' },
        el('div', { class: 'seg', role: 'radiogroup', 'aria-label': 'Статус записки' }, Object.entries(STATUS).map(([k, w]) =>
          el('button', { type: 'button', role: 'radio', class: x.status === k ? 'on' : null, 'aria-checked': x.status === k ? 'true' : 'false',
            onclick: () => { if (x.status !== k) setStatus(x, k); } }, w))),
        el('button', { class: 'chip-btn', 'aria-expanded': open ? 'true' : 'false', onclick: () => { openId = open ? null : x.id; render(); } },
          open ? 'Свернуть' : 'Снимок и действия')),
      open ? detail(x) : null);
  }
  function page() {
    const v = crmGet(listPath(), 15000);
    const c = (v && v.counts) || {};
    const sub = [on() ? 'строка вверху включена' : 'строка выключена',
      ...Object.entries(STATUS).filter(([k]) => c[k]).map(([k, w]) => `${w} ${c[k]}`)];
    const tb = el('div', { class: 'toolbar' },
      FILTERS.map(([k, w]) => el('button', { class: 'chip-btn' + (filter === k ? ' on' : ''), onclick: () => { filter = k; LS.set('dbgFilter', k); render(); } }, w)),
      owner() ? el('button', { class: 'chip-btn', onclick: () => setOn(!on()) }, on() ? 'Выключить строку' : 'Включить строку') : null);
    let body;
    if (!owner()) body = el('div', { class: 'empty' }, 'Отладка — только владельцу Дел, и сервер должен знать её (флаг debug).');
    else if (!v) body = loading();
    else if (!v.items.length) body = el('div', { class: 'empty' }, filter === 'open' ? 'Новых записок нет.' : 'Здесь пусто.');
    else body = el('div', { class: 'dbg-list' }, v.items.map(item));
    // Пустышка с классом строки Claude: listColumn не подставит её сюда — на странице записок она только мешала бы.
    return [head('Отладка', sub), el('div', { class: 'quick say', hidden: true }), el('div', { class: 'body dbg-page' },
      el('div', { class: 'hint-line' }, 'Записки о багах — с тем, что было на экране, и последними действиями. Разбирает сессия Claude: статус и ответ появляются здесь.'),
      tb, body)];
  }

  // Жук линией 1,75 — как значки общего набора Ковчега (своего «bug» в нём нет).
  ICONS.bug = 'M9 9h6a1 1 0 0 1 1 1v4a4 4 0 0 1-8 0v-4a1 1 0 0 1 1-1zM9.5 9a2.5 2.5 0 0 1 5 0M10.5 5.5L9.5 4M13.5 5.5l1-1.5'
    + 'M8 12H4M16 12h4M8 16l-3 1.5M16 16l3 1.5M8.5 10L6 8.5M15.5 10L18 8.5M12 11v7';
  return { on, note, snapshot, page, mount };
})();
