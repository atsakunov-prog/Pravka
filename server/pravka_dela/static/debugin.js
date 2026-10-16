// Записки отладки со страниц соседних сервисов Ковчега (16.10.2026, правило 28 — «Со всех сервисов Ковчега»).
//
// Владелец: «И в вебе сделай то же самое тогда. Во всех сервисах». Строку «Что не так?» на страницах служб рисует их
// общий слой (он не в этом репозитории), но сохранить записку сам он не может: у служб CSP connect-src 'self', к Делам
// их страница не сходит. Поэтому по Enter страница службы открывает маленькое окно веба Дел на #/debug-in и передаёт
// записку postMessage; здесь она проверяется и ложится тем же debug.add — вход в Дела у владельца уже есть. Окно
// говорит «🐞 №N записал», отвечает странице службы (там — тост) и закрывается само через 1,5 с. Нового пути HTTP нет:
// хвост app.js (api.TAILS), маршрут — после # в адресе; закрытый список путей сайта-посредника не меняется.
//
// Разговор окон: окно → странице службы {kv: 'debug-ready'}; страница → окну {kv: 'debug-note', v: 1, note}; окно →
// странице {kv: 'debug-done', id, ok, num | wait | error}. Записку берём только от той страницы, что открыла окно
// (window.opener), только по https (как сами Дела; у копии на http — по http) и только с родительского домена Дел:
// того же, что у куки темы (look.domain из /api/me), или родителя адреса, по которому открыты сами Дела (прямой адрес
// роутера), или с адреса соседнего сервиса из настроек на компе (look.nav). Самих доменов в коде нет — репозиторий
// публичный. Форма — только записка: текст, снимок, действия, размеры; картинки со страницы службы не бывает.
//
// Входа нет — записка ждёт в этом браузере (localStorage Дел, до 5 записок, неделю) и ложится при следующем входе
// владельца на любой странице Дел (тост «🐞 №N записал (со страницы …)»). Не владельцу её не отдаём никогда: сервер
// отказал бы, а снимок — экран владельца.
//
// И кука kv-debug: страница службы узнаёт, показывать ли строку, только из неё (спросить Дела ей нельзя). Её ставит веб
// Дел на родительский домен, как kv-theme (look.domain; на прямом адресе роутера — родитель адреса): при входе и на
// каждом синке и перерисовке — «1», когда режим отладки владельца включён, пусто — когда выключен, человек не
// владелец или вход потерян. Это не защита (записку всё равно пишет сервер только владельцу), а чтобы строка
// появлялась и пропадала вместе со строкой Дел.
'use strict';

const DEBUG_IN = (() => {
  const IN = /^#\/?debug-in(\/|$)/.test(location.hash); // это окно — приёмник: решается один раз, при загрузке
  const KEY = 'debugIn';                                  // LS: записки, что ждут входа
  const TEXT_MAX = 4000, SNAP_MAX = 300000, ACTS_MAX = 60, ACT_MAX = 4000, KEEP = 5, KEEP_DAYS = 7, WAIT_ME = 20000;
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const SVC = /^[a-z][a-z0-9_-]{0,23}$/;
  let me = '';          // '' — ещё не знаем; 'in' — вход есть (boot дошёл до синка); 'out' — входа нет; 'down' — Дела молчат
  let inbox = null;     // записка, пришедшая до того, как стало ясно со входом: {data, origin, win}
  let reply = null;     // кому ответить: {win, origin, id}
  let flushing = false;
  const st = { step: window.opener ? 'wait' : 'alone', note: null, from: '', num: null, error: '' };

  const owner = () => !!(S.me && S.me.role === 'owner' && (S.me.features || []).includes('debug'));
  const str = (v, n) => (typeof v === 'string' ? v.slice(0, n) : '');
  /** Родитель адреса: dela.<домен> → <домен>; IP, localhost и двухсложное — нет родителя. */
  function parentOf(host) {
    const p = String(host || '').split('.');
    return p.length >= 3 && !/^[\d.]+$/.test(host) ? p.slice(1).join('.') : '';
  }

  // ── Кука kv-debug для страниц служб ──────────────────────────────────
  function cookieDomain() {
    const d = KV.domain();
    return d || parentOf(location.hostname);
  }
  function setCookie(on) {
    const want = on ? '1' : '';
    if (KV.cookie('kv-debug') === want) return;
    const d = cookieDomain();
    document.cookie = 'kv-debug=' + want + '; path=/; max-age=' + (want ? 31536000 : 0) + '; SameSite=Lax'
      + (d ? '; domain=' + d : '') + (location.protocol === 'https:' ? '; Secure' : '');
  }
  const syncCookie = () => { try { setCookie(!!S.me && DEBUG.on()); } catch (e) { /* без куки строка служб просто не видна */ } };

  // ── Откуда и что принимаем ───────────────────────────────────────────
  function allowed(origin) {
    let u;
    try { u = new URL(origin); } catch (e) { return false; }
    if (u.origin !== origin || u.protocol !== location.protocol) return false;
    const look = (S.me && S.me.look) || {};
    const roots = [look.domain, parentOf(location.hostname)].filter(Boolean);
    if (roots.some((d) => u.hostname === d || u.hostname.endsWith('.' + d))) return true;
    return (look.nav || []).some((x) => { try { return new URL(x.url, location.href).origin === origin; } catch (e) { return false; } });
  }
  /** Записка со страницы службы — только нужные поля и в своих размерах; кривая — null. */
  function cleanNote(d, origin) {
    try {
      if (!d || typeof d !== 'object' || d.kv !== 'debug-note' || !d.note || typeof d.note !== 'object') return null;
      const n = d.note;
      const text = typeof n.text === 'string' ? n.text.trim().slice(0, TEXT_MAX) : '';
      if (!text || !UUID.test(String(n.id || ''))) return null;
      let snap = n.snapshot && typeof n.snapshot === 'object' && !Array.isArray(n.snapshot) ? n.snapshot : {};
      if (JSON.stringify(snap).length > SNAP_MAX) {
        snap = { v: 1, route: str(snap.route, 300), title: str(snap.title, 200), service: snap.service, service_name: snap.service_name,
          cut: `снимок больше ${SNAP_MAX / 1000} тыс. знаков — остался заголовок` };
      }
      // Откуда — словами страницы (служба) и проверенным адресом (from): по ним debug_logs.py на компе находит журнал.
      snap = { ...snap, kind: 'kovcheg', service: SVC.test(String(snap.service || '')) ? snap.service : undefined,
        service_name: str(snap.service_name, 40) || undefined, from: origin };
      const acts = [];
      for (const x of (Array.isArray(n.actions) ? n.actions : []).slice(-ACTS_MAX)) {
        if (!x || typeof x !== 'object' || Array.isArray(x)) continue;
        let j = '';
        try { j = JSON.stringify(x); } catch (e) { continue; }
        acts.push(j.length > ACT_MAX ? { at: str(x.at, 40), kind: str(x.kind, 20), text: str(x.text, 500), cut: true } : JSON.parse(j));
      }
      return { id: String(n.id).toLowerCase(), text, source: 'web', version: str(n.version, 80), screen: str(n.screen, 80),
        at: str(n.at, 40), snapshot: JSON.parse(JSON.stringify(snap)), actions: acts };
    } catch (e) { return null; }
  }
  const fromWords = (note) => { const s = (note && note.snapshot) || {}; return s.service_name || s.service || str(s.from, 80); };

  // ── Записки, что ждут входа ──────────────────────────────────────────
  const pending = () => (LS.get(KEY, []) || []).filter((x) => x && x.note && x.note.id && Date.now() - (x.t || 0) < KEEP_DAYS * 864e5);
  function keep(note) { LS.set(KEY, [...pending().filter((x) => x.note.id !== note.id), { t: Date.now(), note }].slice(-KEEP)); }
  function drop(id) { LS.set(KEY, pending().filter((x) => x.note.id !== id)); }
  async function save(note) {
    const d = await api('/api/ops', { ops: [{ op_id: uuid(), op: 'debug.add', note }] });
    const r = d.results[0];
    if (!r.ok) { const e = new Error(r.error); e.final = true; throw e; }
    return r.note.num;
  }
  /** Обычная страница Дел владельца: дождавшиеся входа записки — в базу (сервер узнает повтор по id). */
  async function flush() {
    if (IN || flushing || !owner()) return;
    const list = pending();
    if (!list.length) return;
    flushing = true;
    try {
      for (const x of list) {
        try {
          const num = await save(x.note);
          drop(x.note.id);
          toast('🐞 №' + num + ' записал (со страницы ' + fromWords(x.note) + ')');
        } catch (e) {
          if (!e.final) break; // сети нет — в следующий синк
          drop(x.note.id);
          toast('Записка со страницы ' + fromWords(x.note) + ' не легла: ' + e.message);
        }
      }
    } finally { flushing = false; }
  }

  // ── Окно-приёмник ────────────────────────────────────────────────────
  function answer(o) {
    if (!reply) return;
    try { reply.win.postMessage({ kv: 'debug-done', id: reply.id, ...o }, reply.origin); } catch (e) { /* страницы службы уже нет */ }
  }
  async function take() {
    if (!inbox || !me) return;
    const { data, origin, win } = inbox;
    inbox = null;
    const note = cleanNote(data, origin);
    if (!note) return; // не записка — молчим, как на чужое
    reply = { win, origin, id: note.id };
    st.note = note;
    st.from = origin;
    if (!allowed(origin)) {
      Object.assign(st, { step: 'error', error: 'записки принимаю только со страниц сервисов Ковчега' });
      paint();
      answer({ ok: false, error: st.error });
      return;
    }
    keep(note); // до сети: закроют окно на полпути — записка ляжет при следующем открытии Дел
    if (me !== 'in') {
      Object.assign(st, { step: 'login' });
      paint();
      answer({ ok: false, wait: true });
      return;
    }
    st.step = 'save';
    paint();
    try {
      st.num = await save(note);
      drop(note.id);
      st.step = 'done';
      paint();
      answer({ ok: true, num: st.num });
      setTimeout(() => window.close(), 1500);
    } catch (e) {
      if (e.final) drop(note.id);
      Object.assign(st, { step: e.final ? 'error' : 'login', error: e.message });
      paint();
      answer(e.final ? { ok: false, error: e.message } : { ok: false, wait: true });
    }
  }
  function known(how) {
    if (me) return;
    me = how;
    take();
  }
  function paint() {
    if (!IN) return;
    document.title = 'Отладка · Дела';
    const from = st.note ? fromWords(st.note) : '';
    const word = {
      alone: 'Это окно принимает записки отладки со страниц сервисов Ковчега — само по себе оно ничего не делает.',
      wait: 'Жду записку от страницы…',
      save: 'Записываю…',
      done: '🐞 №' + st.num + ' записал',
      login: 'Нужен вход в Дела. Записка ждёт в этом браузере: войди по своей ссылке — она ляжет сама.'
        + (st.error ? ' (' + st.error + ')' : ''),
      error: 'Не записал: ' + st.error,
    }[st.step];
    S.top = null;
    $app.replaceChildren(el('div', { class: 'dbg-in st-' + st.step },
      el('div', { class: 'dbg-in-h' }, icon('bug', 18), el('b', {}, 'Записка отладки'), from ? el('span', { class: 'faint' }, 'со страницы ' + from) : null),
      st.note ? el('div', { class: 'dbg-in-text' }, st.note.text) : null,
      el('div', { class: 'dbg-in-st', role: 'status' }, word),
      st.step === 'done' || st.step === 'save' ? null : el('div', { class: 'dbg-in-tools' },
        el('button', { type: 'button', class: 'chip-btn', onclick: () => window.close() }, 'Закрыть'),
        el('a', { href: '/#/debug', target: '_blank', rel: 'noopener' }, 'Все записки →'))));
  }

  // ── Обёртки app.js (по имени — их зовут boot, api и обёртки других хвостов) ──
  const sync0 = sync;
  sync = async function (...a) { // eslint-disable-line no-global-assign
    // Окну-приёмнику дела не нужны: вход проверен (/api/me), полный синк — лишние секунды и сотни КБ.
    if (IN) { known(S.me ? 'in' : 'out'); return; }
    const r = await sync0.apply(this, a);
    syncCookie();
    flush();
    return r;
  };
  const render0 = render;
  render = function () { // eslint-disable-line no-global-assign
    if (IN) { paint(); return; }
    render0();
    syncCookie();
  };
  const renderLogin0 = renderLogin;
  renderLogin = function (msg) { // eslint-disable-line no-global-assign
    try { setCookie(false); } catch (e) { /* не беда */ }
    if (IN) { known('out'); paint(); return; }
    renderLogin0(msg);
  };

  if (IN) {
    window.addEventListener('message', (e) => {
      if (!window.opener || e.source !== window.opener || !e.data || e.data.kv !== 'debug-note') return;
      if (st.note && e.data.note && st.note.id === String(e.data.note.id).toLowerCase()) return; // повтор после перезагрузки
      inbox = { data: e.data, origin: e.origin, win: e.source };
      take();
    });
    paint();
    // Данных в «готов» нет — поэтому адресат любой: адрес страницы службы окну не виден, пока она не напишет сама.
    if (window.opener) { try { window.opener.postMessage({ kv: 'debug-ready' }, '*'); } catch (e) { /* нет страницы */ } }
    setTimeout(() => { if (!me) { st.error = 'Дела не ответили'; known('down'); } }, WAIT_ME);
  }
  return { IN, allowed, cleanNote, parentOf, pending, flush };
})();
