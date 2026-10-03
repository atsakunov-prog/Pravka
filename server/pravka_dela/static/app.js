// Дела — веб. Одна страница поверх API службы (/api/*), без библиотек.
// Справочник (проекты, люди, сделки) берётся синком, списки — готовыми видами сервера.
'use strict';

const app = document.getElementById('app');
const S = {
  me: null,
  view: localStorage.getItem('dela.view') || 'morning',
  sphere: localStorage.getItem('dela.sphere') || '',
  dir: { projects: [], people: [], deals: [], users: [], labels: [] },
  newCount: 0,
};
const BALL = { mine: 'моё', waiting: 'жду', agenda: 'повестка' };
const MONEY = { paid: 'оплачено', potential: 'развитие' };
const TABS = [['morning', 'Утро'], ['new', 'Новое'], ['waiting', 'Жду'], ['week', 'Неделя'], ['projects', 'Проекты'], ['search', 'Поиск']];

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
const uuid = () => crypto.randomUUID ? crypto.randomUUID()
  : 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => (Math.random() * 16 | 0).toString(16));
async function op(item) {
  const d = await api('/api/ops', { ops: [{ op_id: uuid(), ...item }] });
  const r = d.results[0];
  if (!r.ok) throw new Error(r.error);
  return r;
}

// ── Помощники ───────────────────────────────────────────────────────────
function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k.nodeType ? k : String(k));
  return e;
}
const today = () => new Date(Date.now() + 3 * 3600e3).toISOString().slice(0, 10); // сутки — московские
const plural = (n, one, few, many) => {
  const a = n % 10, b = n % 100;
  return `${n} ${a === 1 && b !== 11 ? one : a >= 2 && a <= 4 && (b < 12 || b > 14) ? few : many}`;
};
const ddmm = (d) => d ? d.slice(8, 10) + '.' + d.slice(5, 7) : '';
function toast(text, undo) {
  document.querySelectorAll('.toast').forEach((t) => t.remove());
  const t = el('div', { class: 'toast' }, el('span', {}, text),
    undo ? el('button', { onclick: async () => { t.remove(); await undo(); refresh(); } }, 'Вернуть') : null);
  document.body.append(t);
  setTimeout(() => t.remove(), 5000);
}
function fail(e) { toast('Не вышло: ' + e.message); }
const person = (id) => S.dir.people.find((p) => p.id === id);
const project = (id) => S.dir.projects.find((p) => p.id === id);

// ── Вход ────────────────────────────────────────────────────────────────
function renderLogin(msg) {
  app.replaceChildren(el('div', { class: 'login' },
    el('h1', {}, 'Дела'),
    el('p', { class: 'muted' }, msg || 'Вход — по ссылке-приглашению. Попроси её у Саши: ссылка одноразовая, дальше страница помнит вход полгода.')));
  document.querySelector('nav.tabs')?.remove();
  document.querySelector('.fab')?.remove();
}

async function boot() {
  const m = location.hash.match(/invite=([\w-]+)/);
  if (m) {
    history.replaceState(null, '', '/');
    try { await api('/auth/redeem', { code: m[1] }); } catch (e) { renderLogin(e.message); return; }
  }
  try { S.me = await api('/api/me'); } catch (e) { return; }
  await loadDir();
  render();
}

async function loadDir() {
  const d = await api('/api/sync?since=0');
  S.dir = {
    projects: d.projects.filter((p) => !p.archived_at).sort((a, b) => a.name.localeCompare(b.name, 'ru')),
    people: d.people.filter((p) => !p.archived_at).sort((a, b) => a.name.localeCompare(b.name, 'ru')),
    deals: d.deals, users: d.users, labels: d.labels.map((l) => l.name),
  };
  S.newCount = d.suggestions.filter((s) => s.status === 'pending' && s.for_user === S.me.user).length;
}

// ── Каркас ──────────────────────────────────────────────────────────────
function render() {
  const sphere = el('div', { class: 'seg' }, [['work', 'Работа'], ['home', 'Дом'], ['', 'Всё']].map(([v, t]) =>
    el('button', { class: S.sphere === v ? 'on' : '', onclick: () => { S.sphere = v; localStorage.setItem('dela.sphere', v); render(); } }, t)));
  const head = el('header', { class: 'top' }, el('h1', {}, 'Дела'), sphere);
  const main = el('main', {}, el('p', { class: 'muted center' }, 'Загружаю…'));
  app.replaceChildren(head, main);
  document.querySelector('nav.tabs')?.remove();
  document.querySelector('.fab')?.remove();
  document.body.append(
    el('nav', { class: 'tabs' }, TABS.map(([v, t]) => el('button', {
      class: S.view === v ? 'on' : '', onclick: () => { S.view = v; localStorage.setItem('dela.view', v); render(); },
    }, t, v === 'new' && S.newCount ? el('span', { class: 'count' }, S.newCount) : null))),
    el('button', { class: 'fab', title: 'Новое дело', onclick: () => openTask(null) }, '+'),
  );
  draw(main).catch(fail);
}
const refresh = () => render();

async function draw(main) {
  const sp = S.sphere ? '?sphere=' + S.sphere : '';
  const v = S.view;
  const parts = [];
  if (v === 'morning') {
    const d = await api('/api/view/morning' + sp);
    parts.push(section('Сейчас', d.now, true), section('На сегодня и просроченное', d.today),
      section('Пора напомнить', d.nudge, true), section('Оплачено, без даты', d.paid_undated, true),
      section('Поставили другие', d.from_others, true));
    S.newCount = d.new_count;
    if (!d.now.length && !d.today.length && !d.nudge.length) parts.unshift(el('div', { class: 'card' }, el('div', { class: 'empty' }, 'На сегодня пусто.')));
  } else if (v === 'new') {
    parts.push(await drawNew());
  } else if (v === 'waiting') {
    const d = await api('/api/view/waiting' + sp);
    if (!d.people.length) parts.push(el('div', { class: 'card' }, el('div', { class: 'empty' }, 'Ни от кого ничего не ждём.')));
    d.people.forEach((p) => parts.push(section(p.person, p.items)));
  } else if (v === 'week') {
    const d = await api('/api/view/week' + sp);
    parts.push(section('Протухшее', d.stale), section('Жду без движения', d.waiting_stale, true));
    if (d.no_next_step.length) {
      parts.push(el('h2', {}, 'Проекты с деньгами без моего следующего шага'),
        el('div', { class: 'card' }, d.no_next_step.map((p) => projectRow(p))));
    }
  } else if (v === 'projects') {
    const list = S.dir.projects.filter((p) => !S.sphere || p.sphere === S.sphere);
    parts.push(el('h2', {}, 'Проекты'), el('div', { class: 'card' }, list.map((p) => projectRow(p))));
  } else if (v === 'search') {
    const box = el('input', { class: 'search', type: 'search', placeholder: 'Что найти…', autofocus: true });
    const out = el('div', {});
    let timer;
    box.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(async () => {
        if (box.value.trim().length < 2) { out.replaceChildren(); return; }
        const d = await api('/api/view/search?status=all&q=' + encodeURIComponent(box.value.trim()));
        out.replaceChildren(section('Найдено', d.items));
      }, 250);
    });
    parts.push(box, out);
  } else if (v.startsWith('project:')) {
    parts.push(await drawProject(v.slice(8)));
  } else if (v.startsWith('person:')) {
    parts.push(await drawPerson(v.slice(7)));
  }
  main.replaceChildren(...parts.filter(Boolean));
  const badge = document.querySelector('nav.tabs button:nth-child(2) .count');
  if (badge) badge.textContent = S.newCount; // счётчик «Нового» мог измениться
}

function section(title, items, hideEmpty) {
  if (!items || (!items.length && hideEmpty)) return null;
  return el('div', {}, el('h2', {}, `${title} · ${items.length}`),
    el('div', { class: 'card' }, items.length ? items.map(taskRow) : el('div', { class: 'empty' }, 'Пусто')));
}

function taskRow(t) {
  const meta = [];
  const where = (t.project_name || 'Входящие') + (t.deal_name ? ' / ' + t.deal_name : '');
  meta.push(el('span', {}, where));
  const who = t.person_short || t.person_name;
  if (t.ball !== 'mine') meta.push(el('span', {}, BALL[t.ball] + (who ? ' ' + who : '') + (t.waiting_since ? ' с ' + ddmm(t.waiting_since) : '')));
  else if (who) meta.push(el('span', {}, 'с ' + who));
  if (t.due_date) meta.push(el('span', { class: t.overdue ? 'late' : '' }, 'срок ' + ddmm(t.due_date)));
  if (t.estimate_min) meta.push(el('span', {}, t.estimate_min + ' мин'));
  if (MONEY[t.money_eff]) meta.push(el('span', { class: 'badge ' + t.money_eff }, MONEY[t.money_eff]));
  if (t.focus_on === today()) meta.push(el('span', { class: 'badge' }, 'сейчас'));
  const done = t.status !== 'open';
  return el('div', { class: 'row', onclick: () => openTask(t.num) },
    el('button', {
      class: 'tick' + (done ? ' done' : ''), title: done ? 'Вернуть' : 'Сделано',
      onclick: async (e) => {
        e.stopPropagation();
        try {
          await op({ op: done ? 'task.reopen' : 'task.done', id: t.id });
          toast(done ? 'Вернул в работу' : 'Сделано: ' + t.title, () => op({ op: done ? 'task.done' : 'task.reopen', id: t.id }));
          refresh();
        } catch (err) { fail(err); }
      },
    }),
    el('div', { class: 'body' }, el('div', { class: 'title' }, el('span', { class: 'num' }, '#' + t.num), t.title),
      el('div', { class: 'meta' }, meta)));
}

function projectRow(p) {
  return el('div', { class: 'row', onclick: () => { S.view = 'project:' + p.id; render(); } },
    el('div', { class: 'body proj' }, el('span', {}, p.name),
      MONEY[p.money_default] ? el('span', { class: 'badge ' + p.money_default }, MONEY[p.money_default]) : null));
}

async function drawProject(id) {
  const d = await api('/api/view/project?project_id=' + id);
  const box = el('div', {}, el('h2', {}, d.project.name));
  if (d.deals.length) {
    box.append(el('div', { class: 'card' }, d.deals.map((x) => el('div', { class: 'sug' },
      el('div', {}, x.name, ' ', el('span', { class: 'badge' }, x.stage)),
      x.next_step ? el('div', { class: 'hint' }, 'Следующий шаг (из Notion): ' + x.next_step) : null))));
  }
  box.append(section('Открытые', d.open), section('Закрытые недавно', d.done, true));
  if (d.minutes && d.minutes.length) {
    const total = d.minutes.reduce((a, r) => a + (r.minutes || 0), 0);
    box.append(el('p', { class: 'muted' }, `Время из ленты: ${Math.floor(total / 60)} ч ${total % 60} мин за ${d.minutes.length} дн.`));
  }
  return box;
}

async function drawPerson(id) {
  const d = await api('/api/view/person?person_id=' + id);
  return el('div', {}, el('h2', {}, d.person ? d.person.name : 'Человек'),
    section('Повестка с ним', d.agenda), section('Жду от него', d.waiting, true),
    section('Его просьбы ко мне', d.asked, true), section('Моё о нём', d.mine_about, true));
}

// ── «Новое» ─────────────────────────────────────────────────────────────
async function drawNew() {
  const d = await api('/api/view/new');
  S.newCount = d.count;
  if (!d.batches.length) return el('div', { class: 'card' }, el('div', { class: 'empty' }, 'Новых предложений нет.'));
  const box = el('div', {});
  for (const b of d.batches) {
    const ids = b.items.map((s) => s.id);
    box.append(el('h2', {}, b.title || 'Без пачки'), el('div', { class: 'card' },
      ids.length > 1 ? el('div', { class: 'batch-head' }, el('b', {}, plural(ids.length, 'предложение', 'предложения', 'предложений')),
        el('button', { class: 'btn small ok', onclick: () => decideMany(ids, 'accept') }, 'Принять всё'),
        el('button', { class: 'btn small bad', onclick: () => decideMany(ids, 'reject') }, 'Отклонить всё')) : null,
      b.items.map(sugRow)));
  }
  return box;
}

function sugRow(s) {
  const p = s.payload || {};
  const hint = [p.project_name, p.person_name, BALL[p.ball], p.due_date && 'срок ' + ddmm(p.due_date)].filter(Boolean).join(' · ');
  return el('div', { class: 'sug' },
    el('div', {}, s.kind === 'create' ? p.title : `${s.kind}: ${p.title || ''}`),
    hint ? el('div', { class: 'hint' }, hint) : null,
    el('div', { class: 'btns' },
      el('button', { class: 'btn small ok', onclick: () => decideMany([s.id], 'accept') }, 'Принять'),
      el('button', { class: 'btn small', onclick: () => openTask(null, s) }, 'Поправить'),
      el('button', {
        class: 'btn small bad', onclick: () => {
          const reason = prompt('Почему не дело? (можно пусто)');
          if (reason !== null) decideMany([s.id], 'reject', reason || null);
        },
      }, 'Отклонить')));
}

async function decideMany(ids, decision, reason) {
  try {
    const d = await api('/api/ops', { ops: ids.map((id) => ({ op_id: uuid(), op: 'suggestion.decide', id, decision, reason })) });
    const bad = d.results.filter((r) => !r.ok);
    toast(bad.length ? 'Не все: ' + bad[0].error : (decision === 'accept' ? 'Принято: ' : 'Отклонено: ') + ids.length);
    refresh();
  } catch (e) { fail(e); }
}

// ── Карточка дела ───────────────────────────────────────────────────────
async function openTask(num, sug) {
  let t = null, card = null;
  if (num != null) {
    try { card = await api('/api/task/' + num); t = card.task; } catch (e) { fail(e); return; }
  }
  const src = t || (sug ? { ...sug.payload } : { ball: 'mine' });
  if (sug && src.project_name && !src.project_id) {
    const n = src.project_name.toLowerCase();
    const hit = S.dir.projects.find((p) => p.name.toLowerCase() === n || (p.aliases || []).some((a) => a.toLowerCase() === n));
    if (hit) src.project_id = hit.id;
  }
  const f = {};
  const input = (key, attrs) => (f[key] = el('input', { value: src[key] ?? '', ...attrs }));
  const select = (key, options, value) => (f[key] = el('select', {}, options.map(([v, t]) =>
    el('option', { value: v, selected: String(value ?? '') === String(v) }, t))));

  const projOpts = [['', 'Входящие'], ...S.dir.projects.map((p) => [p.id, p.name])];
  const peopleOpts = [['', '—'], ...S.dir.people.map((p) => [p.id, p.short ? `${p.short} (${p.name})` : p.name])];
  const dealOpts = (pid) => [['', '—'], ...S.dir.deals.filter((d) => d.project_id === pid).map((d) => [d.id, d.name])];
  const deal = el('label', { class: 'field' }, el('span', {}, 'Сделка'), select('deal_id', dealOpts(src.project_id), src.deal_id));
  const proj = select('project_id', projOpts, src.project_id);
  proj.addEventListener('change', () => {
    const s = el('select', {}, dealOpts(proj.value).map(([v, t]) => el('option', { value: v }, t)));
    f.deal_id.replaceWith(s); f.deal_id = s;
  });

  const nowBox = el('input', { type: 'checkbox', checked: src.focus_on === today() });
  const wantBox = el('input', { type: 'checkbox', checked: !!src.want });
  const notes = (f.notes = el('textarea', {}, src.notes || ''));
  const sheet = el('div', { class: 'sheet' },
    el('h3', {}, t ? `Дело #${t.num}` : sug ? 'Принять с поправкой' : 'Новое дело'),
    el('label', { class: 'field' }, el('span', {}, 'Что сделать («Кто: действие»)'), input('title', { required: true })),
    el('div', { class: 'grid2' },
      el('label', { class: 'field' }, el('span', {}, 'Проект'), proj),
      deal,
      el('label', { class: 'field' }, el('span', {}, 'Мяч'), select('ball', Object.entries(BALL), src.ball || 'mine')),
      el('label', { class: 'field' }, el('span', {}, 'Человек'), select('person_id', peopleOpts, src.person_id)),
      el('label', { class: 'field' }, el('span', {}, 'Срок'), input('due_date', { type: 'date' })),
      el('label', { class: 'field' }, el('span', {}, 'Напомнить ему'), input('nudge_on', { type: 'date' })),
      el('label', { class: 'field' }, el('span', {}, 'Минут'), input('estimate_min', { type: 'number', min: 1 })),
      el('label', { class: 'field' }, el('span', {}, 'Деньги'),
        select('money', [['', 'как у проекта'], ['paid', 'оплачено'], ['potential', 'развитие'], ['none', 'без денег']], src.money))),
    el('label', { class: 'check' }, nowBox, 'Сейчас — на сегодня'),
    el('label', { class: 'check' }, wantBox, 'Хочу сам'),
    el('label', { class: 'field' }, el('span', {}, 'Заметки'), notes),
  );

  const collect = () => {
    const out = {};
    for (const k of ['title', 'notes', 'project_id', 'deal_id', 'ball', 'person_id', 'due_date', 'nudge_on', 'money']) {
      out[k] = f[k].value.trim() === '' ? null : f[k].value.trim();
    }
    out.estimate_min = f.estimate_min.value ? parseInt(f.estimate_min.value, 10) : null;
    out.want = wantBox.checked;
    out.focus_on = nowBox.checked ? today() : null;
    if (out.title == null) throw new Error('нужно название');
    return out;
  };
  const close = () => bg.remove();
  const btns = el('div', { class: 'btns' });
  if (t) {
    btns.append(
      el('button', { class: 'btn main', onclick: async () => {
        try {
          const now = collect();
          const set = {}, was = {};
          for (const [k, v] of Object.entries(now)) {
            if ((t[k] ?? null) !== v) { set[k] = v; was[k] = t[k] ?? null; }
          }
          if (Object.keys(set).length) {
            const r = await op({ op: 'task.set', id: t.id, set, was });
            if (r.conflicts && r.conflicts.length) toast('Сохранил. Это поле успели поменять и другие: ' + r.conflicts.join(', '));
          }
          close(); refresh();
        } catch (e) { fail(e); }
      } }, 'Сохранить'),
      t.status === 'open'
        ? el('button', { class: 'btn ok', onclick: async () => { try { await op({ op: 'task.done', id: t.id }); close(); refresh(); } catch (e) { fail(e); } } }, 'Сделано')
        : el('button', { class: 'btn', onclick: async () => { try { await op({ op: 'task.reopen', id: t.id }); close(); refresh(); } catch (e) { fail(e); } } }, 'Вернуть в работу'),
      t.status === 'open' ? el('button', { class: 'btn bad', onclick: async () => {
        if (!confirm('Отменить дело? Оно останется в журнале.')) return;
        try { await op({ op: 'task.cancel', id: t.id }); close(); refresh(); } catch (e) { fail(e); }
      } }, 'Отменить дело') : null,
      el('button', { class: 'btn', onclick: close }, 'Закрыть'));
  } else {
    btns.append(
      el('button', { class: 'btn main', onclick: async () => {
        try {
          const data = collect();
          if (sug) {
            const set = {};
            for (const [k, v] of Object.entries(data)) if (v !== null && v !== false) set[k] = v;
            const r = await op({ op: 'suggestion.decide', id: sug.id, decision: 'accept', set });
            toast('Принято: #' + r.task.num);
          } else {
            const clean = Object.fromEntries(Object.entries(data).filter(([, v]) => v !== null && v !== false));
            const r = await op({ op: 'task.create', task: { ...clean, source: 'web' } });
            toast('Записал: #' + r.task.num);
          }
          close(); refresh();
        } catch (e) { fail(e); }
      } }, sug ? 'Принять' : 'Записать'),
      el('button', { class: 'btn', onclick: close }, 'Закрыть'));
  }
  sheet.append(btns);

  if (t) {
    if (t.person_id) sheet.append(el('p', {}, el('button', { class: 'btn small', onclick: () => { close(); S.view = 'person:' + t.person_id; render(); } }, 'Всё с ' + (t.person_short || t.person_name))));
    const list = el('div', { class: 'comments' }, card.comments.map((c) =>
      el('div', { class: 'c' }, el('div', { class: 'who' }, `${c.author_id} · ${c.created_at.slice(0, 16).replace('T', ' ')}`), c.text)));
    const add = el('input', { placeholder: 'Комментарий…' });
    sheet.append(el('h2', {}, 'Комментарии'), list, el('div', { class: 'btns' },
      el('label', { class: 'field grow' }, add),
      el('button', { class: 'btn', onclick: async () => {
        if (!add.value.trim()) return;
        try { await op({ op: 'comment.add', comment: { task_id: t.id, text: add.value.trim() } }); close(); openTask(t.num); } catch (e) { fail(e); }
      } }, 'Добавить')));
    sheet.append(el('details', {}, el('summary', {}, 'Журнал'), card.history.map((h) =>
      el('div', { class: 'hist' }, `${h.at.slice(0, 16).replace('T', ' ')} · ${h.actor} (${h.via}) · ${h.op === 'update' ? Object.keys(h.after || {}).join(', ') : h.op}`))));
  }
  const bg = el('div', { class: 'sheet-bg', onclick: (e) => { if (e.target === bg) close(); } }, sheet);
  document.body.append(bg);
  if (!t) f.title.focus();
}

boot();
