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
// (window.opener), только по своей схеме (как сами Дела: https; у копии на http — http) и только со страницы службы:
// адрес ровно `<служба>.<родитель>`, где родитель — домен куки темы (look.domain из /api/me) или родитель адреса, по
// которому открыты сами Дела (прямой адрес роутера), а служба — из списка look.debug_from (сервер: настройка на компе
// DELA_DEBUG_FROM, иначе заводские имена служб debug.KOVCHEG_FROM; без входа — список с прошлого входа в этом браузере,
// а нет и его — записка ждёт входа и проверяется перед записью); точный адрес из того же списка — тоже. Под тем же
// доменом живут и файлы (облако и книги по WebDAV, медиатека, архив): открытый оттуда .html иначе положил бы в
// «Отладку» что угодно, а записки читают и выполняют сессии Claude на компе. Самих доменов в коде нет — репозиторий
// публичный. Форма — только записка: текст, снимок, действия, размеры; картинки со страницы службы не бывает.
//
// Входа нет или Дела не ответили (сеть, перезапуск) — записка ждёт в этом браузере (localStorage Дел, до 5 записок,
// неделю) и ложится при следующем входе владельца на любой странице Дел (тост «🐞 №N записал (со страницы …)»); странице
// службы окно говорит, почему ждёт (`why`: login | down), и закрывается само через 7 с — войти в нём всё равно нельзя
// (вход — по ссылке). Не владельцу записку не отдаём никогда: сервер отказал бы, а снимок — экран владельца.
//
// И кука kv-debug: страница службы узнаёт, показывать ли строку, только из неё (спросить Дела ей нельзя). Её ставит веб
// Дел на родительский домен, как kv-theme (look.domain; на прямом адресе роутера — родитель адреса): при входе, на
// каждом синке и перерисовке и в самом окне-приёмнике — «1» на неделю (продлевается раз за открытие страницы), когда
// режим отладки владельца включён; пусто — когда выключен, человек не владелец или вход потерян. Выключил режим с
// телефона или командой — строка служб уйдёт при следующем открытии Дел или сама через неделю. Это не защита (записку
// всё равно пишет сервер только владельцу), а чтобы строка появлялась и пропадала вместе со строкой Дел.
'use strict';

const DEBUG_IN = (() => {
  const IN = /^#\/?debug-in(\/|$)/.test(location.hash); // это окно — приёмник: решается один раз, при загрузке
  const KEY = 'debugIn';                                  // LS: записки, что ждут входа
  const LOOK = 'debugFrom';                               // LS: откуда берём — с прошлого входа (для окна без входа)
  const TEXT_MAX = 4000, SNAP_MAX = 300000, ACTS_MAX = 60, ACT_MAX = 4000, KEEP = 5, KEEP_DAYS = 7, WAIT_ME = 20000;
  const CLOSE_DONE = 1500, CLOSE_LATER = 7000, WAIT_NOTE = 60000, COOKIE_AGE = 7 * 86400;
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const SVC = /^[a-z][a-z0-9_-]{0,23}$/;
  let me = '';          // '' — ещё не знаем; 'in' — вход есть (boot дошёл до синка); 'out' — входа нет; 'down' — Дела молчат
  let inbox = null;     // записка, пришедшая до того, как стало ясно со входом: {data, origin, win}
  let reply = null;     // кому ответить: {win, origin, id}
  let flushing = false;
  let renewed = false;  // кука «1» уже продлена в этом открытии страницы
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
    if (KV.cookie('kv-debug') === want && (!want || renewed)) return;
    if (want) renewed = true;
    const d = cookieDomain();
    document.cookie = 'kv-debug=' + want + '; path=/; max-age=' + (want ? COOKIE_AGE : 0) + '; SameSite=Lax'
      + (d ? '; domain=' + d : '') + (location.protocol === 'https:' ? '; Secure' : '');
  }
  /** Кука для строки служб и, заодно, список «откуда берём» с этого входа — окну без входа (S.me нет) он нужен тоже. */
  function syncCookie() {
    try { setCookie(!!S.me && DEBUG.on()); } catch (e) { /* без куки строка служб просто не видна */ }
    if (owner() && S.me.look) LS.set(LOOK, { domain: S.me.look.domain || '', debug_from: S.me.look.debug_from || [] });
  }

  // ── Откуда и что принимаем ───────────────────────────────────────────
  /** Страница службы Ковчега: точный адрес из look.debug_from или `<служба>.<родитель>` ровно одним уровнем, где служба —
   *  имя из того же списка (в именах нет точек: a.b.<родитель> и сам родитель не проходят), а родитель — look.domain или
   *  родитель адреса Дел. Схема — своя. Ни look.nav (там бывают внешние ссылки и медиатека), ни «всё под доменом». */
  const lookNow = () => (S.me && S.me.look) || LS.get(LOOK, null) || {};
  /** Знаем ли, откуда берём: вход есть или список остался с прошлого входа. Нет — проверка при записи (flush). */
  const knowFrom = () => Array.isArray(lookNow().debug_from);
  function allowed(origin) {
    let u;
    try { u = new URL(origin); } catch (e) { return false; }
    if (u.origin !== origin || u.protocol !== location.protocol) return false;
    const look = lookNow();
    const from = (Array.isArray(look.debug_from) ? look.debug_from : []).filter((x) => typeof x === 'string' && x);
    if (from.includes(origin)) return true;
    const host = u.hostname, roots = [look.domain, parentOf(location.hostname)].filter(Boolean);
    return roots.some((d) => host.endsWith('.' + d) && from.includes(host.slice(0, -d.length - 1)));
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
    let d;
    try { d = await api('/api/ops', { ops: [{ op_id: uuid(), op: 'debug.add', note }] }); } catch (e) {
      // 401 — api() уже снял S.me: нужен вход. Остальное (сеть, 502 на перезапуске, 404 и 413 посредника) — Дела не ответили.
      e.why = S.me ? 'down' : 'login';
      throw e;
    }
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
        // Окно без входа и без списка служб записку только сохранило — откуда она, проверяем здесь, со свежим списком.
        const from = str((x.note.snapshot || {}).from, 300);
        if (!allowed(from)) {
          drop(x.note.id);
          toast('Записка с ' + (from || 'неизвестного адреса') + ' не легла: это не страница сервиса Ковчега');
          continue;
        }
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
  /** Окно закрывается само: записал — через 1,5 с; ждёт входа, Дела молчат, отказ — через 7 с (тост на странице службы
   *  уже всё сказал, а войти в этом окне нельзя — вход по ссылке). */
  function closeSoon(ms) { setTimeout(() => { try { window.close(); } catch (e) { /* не наше окно — пусть висит */ } }, ms); }
  function fail(error) {
    Object.assign(st, { step: 'error', error });
    paint();
    answer({ ok: false, error });
    closeSoon(CLOSE_LATER);
  }
  function waiting(why, error) {
    Object.assign(st, { step: why, error: error || st.error });
    paint();
    answer({ ok: false, wait: true, why });
    closeSoon(CLOSE_LATER);
  }
  async function take() {
    if (!inbox || !me) return;
    const { data, origin, win } = inbox;
    inbox = null;
    const note = cleanNote(data, origin);
    if (!note) {
      // Не записка — молчим, как на чужое. Записка не той формы со страницы службы — отказ словами, а не «жду» вечно.
      const id = data && data.note && typeof data.note.id === 'string' && UUID.test(data.note.id) ? data.note.id : '';
      if (!id || !allowed(origin)) return;
      reply = { win, origin, id };
      fail('записка не той формы (без текста?) — Дела её не приняли');
      return;
    }
    reply = { win, origin, id: note.id };
    st.note = note;
    st.from = origin;
    // Без входа и без списка с прошлого входа сказать нельзя — записка ждёт и проверяется при записи (flush).
    if ((me === 'in' || knowFrom()) && !allowed(origin)) { fail('записки принимаю только со страниц сервисов Ковчега'); return; }
    keep(note); // до сети: закроют окно на полпути — записка ляжет при следующем открытии Дел
    if (me !== 'in') { waiting(me === 'out' ? 'login' : 'down'); return; }
    st.step = 'save';
    paint();
    try {
      st.num = await save(note);
      drop(note.id);
      st.step = 'done';
      paint();
      answer({ ok: true, num: st.num });
      closeSoon(CLOSE_DONE);
    } catch (e) {
      if (e.final) { drop(note.id); fail(e.message); } else waiting(e.why || 'down', e.message);
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
      login: 'Нужен вход в Дела. Записка ждёт в этом браузере: открой Дела по своей ссылке — она ляжет сама.',
      down: 'Дела сейчас не ответили' + (st.error ? ' (' + st.error + ')' : '')
        + '. Записка ждёт в этом браузере и ляжет при следующем открытии Дел.',
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
    // Куку — и здесь: окно открывают со страниц служб, и режим, выключенный с телефона, гаснет у них уже отсюда.
    if (IN) { known(S.me ? 'in' : 'out'); syncCookie(); return; }
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
    setTimeout(() => { if (!me) { st.error = 'нет ответа за 20 с'; known('down'); } }, WAIT_ME);
    // Записка так и не пришла (страницу службы закрыли, прислала не то) — окно не висит вечно.
    setTimeout(() => { if (st.step === 'wait' || st.step === 'alone') window.close(); }, WAIT_NOTE);
  }
  return { IN, allowed, cleanNote, parentOf, pending, flush, knowFrom };
})();
