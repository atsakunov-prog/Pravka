// «Телефон» — настройки Правки на телефоне, видимые и правимые с компьютера (14.10.2026; описание —
// docs/phone-settings-server.md, контракт — server/contract/dela-phone.json, сервер — pravka_dela/phone.py).
//
// Владелец: «настройки телефона править с ПК», «две копии одного и того же». Схема, а не форма на каждую настройку:
// телефон сам присылает группы, подписи, типы, допустимые значения и почему что-то нельзя трогать издалека
// (phone.snapshot), страница рисует это как есть — новая настройка в телефоне появляется здесь без правки веба.
// Правка — операция phone.set: она ждёт телефона, телефон берёт её синком Дел (обычно за пять минут) и ставит тем
// же путём, что тумблер на экране, а в следующем снимке отвечает — «взято», «не встало» или «на телефоне поменяли
// позже» (последний побеждает). Видны только свои телефоны.
//
// Свой файл, а не app.js: страницу правят отдельно от остального веба. Отдаётся хвостом app.js (api.TAILS, как
// страницы-двойники): своего адреса у файла нет, и список путей сайта-посредника не меняется. Помощники (el, icon, api,
// ops, head, crmGet, choicePop, toast…) — общие app.js; свои имена — внутри PHONE, наружу — только renderPhone.
'use strict';

const PHONE = (() => {
  const WORD = { pending: 'ждёт телефона', applied: 'взято телефоном', rejected: 'не встало', conflict: 'на телефоне поменяли позже',
    superseded: 'заменена новой', cancelled: 'отменена' };
  const RECENT_MS = 3 * 86400e3;   // «взято в …» у настройки — трое суток, дальше это история в журнале
  const SEG_MAX = 6;               // до шести вариантов — сегментом, больше — списком с поиском

  const on = () => ((S.me && S.me.features) || []).includes('phone');
  let last = null;   // последний ответ вида phone: при переходе между группами страница не мигает «Загружаю…»

  /** #/phone/<телефон>/<группа>: route() Дел отдаёт только первые две части адреса — остальное разбираем сами. */
  function addr() {
    const [, dev, grp] = location.hash.replace(/^#\/?/, '').split('/');
    return { dev: dev ? decodeURIComponent(dev) : '', grp: grp ? decodeURIComponent(grp) : '' };
  }
  const hash = (dev, grp) => '#/phone' + (dev ? '/' + encodeURIComponent(dev) + (grp ? '/' + encodeURIComponent(grp) : '') : '');

  /** «в 14:20», «вчера 09:05», «12.10 18:40» — по Москве, как всё время в Ковчеге. */
  function when(iso) {
    if (!iso) return '';
    const { day, hm } = msk(Date.parse(iso));
    const n = D.diff(S.today, day);
    return n === 0 ? 'в ' + hm : n === 1 ? 'вчера ' + hm : D.ddmm(day) + ' ' + hm;
  }

  /** Значение словами: вариант — подписью, да/нет — словом, время — «23:30», число — с единицей. */
  function label(it, v) {
    if (v == null || v === '') return it && it.type === 'text' ? 'пусто' : '—';
    if (it && it.options) {
      const t = Object.fromEntries(it.options.map((o) => [o.v, o.t]));
      if (Array.isArray(v)) return v.map((x) => t[x] || x).join(', ') || 'ничего';
      if (v in t) return t[v];
    }
    if (typeof v === 'boolean') return v ? 'да' : 'нет';
    if (it && it.type === 'time') return hm(v);
    if (Array.isArray(v)) return v.join(', ');
    return it && it.unit ? `${v} ${it.unit}` : String(v);
  }
  const hm = (m) => String(Math.floor((m % 1440) / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');

  /** Отзывался ли телефон: свежо — серым, давно (больше часа) — цветом «на заметку»: правки ждут. */
  function seen(d) {
    if (!d.seen_at) return el('span', { class: 'late' }, 'ещё не отзывался');
    const min = (Date.now() - Date.parse(d.seen_at)) / 60000;
    if (min < 2) return 'отзывался только что';
    return min > 60 ? el('span', { class: 'ph-old' }, 'давно не отзывался: ' + when(d.seen_at)) : 'отзывался ' + when(d.seen_at);
  }

  async function set(dev, it, value) {
    try {
      const [r] = await ops([{ op: 'phone.set', device: dev, key: it.key, value }]);
      toast(r.same ? `«${it.title}»: так уже стоит на телефоне` : `«${it.title}»: ${label(it, value)} — ждёт телефона`);
    } catch (e) { fail(e); }
    crmDirty();
    render();
  }

  async function cancel(e) {
    try { await ops([{ op: 'phone.cancel', id: e.id }]); toast(`«${e.title}»: правка отменена`); } catch (x) { fail(x); }
    crmDirty();
    render();
  }

  async function hide(d) {
    if (!confirm(`Убрать «${d.name || d.device}» из списка? Он вернётся сам, если снова пришлёт настройки.`)) return;
    try { await ops([{ op: 'phone.hide', device: d.device }]); go('#/phone'); } catch (e) { fail(e); }
    crmDirty();
  }

  // ── Управление: по типу из схемы телефона ────────────────────────────────
  function control(dev, it, cur) {
    const put = (v) => set(dev, it, v);
    if (it.type === 'bool') {
      return el('button', { type: 'button', class: 'ph-sw' + (cur ? ' on' : ''), role: 'switch', 'aria-checked': cur ? 'true' : 'false',
        'aria-label': it.title, title: cur ? 'включено' : 'выключено', onclick: () => put(!cur) });
    }
    if (it.type === 'choice') {
      if (it.options.length <= SEG_MAX) {
        return el('div', { class: 'seg', role: 'radiogroup', 'aria-label': it.title }, it.options.map((o) =>
          el('button', { type: 'button', role: 'radio', class: o.v === cur ? 'on' : null, 'aria-checked': o.v === cur ? 'true' : 'false',
            onclick: () => { if (o.v !== cur) put(o.v); } }, o.t)));
      }
      return choiceBtn(label(it, cur), (a) => choicePop(a, { title: it.title, current: cur,
        items: it.options.map((o) => ({ v: o.v, title: o.t })), onPick: (v) => { if (v != null && v !== cur) put(v); } }));
    }
    if (it.type === 'set') {
      const have = new Set(cur || []);
      return el('div', { class: 'ph-chips' }, it.options.map((o) => el('button', { type: 'button', class: 'chip-btn' + (have.has(o.v) ? ' on' : ''),
        'aria-pressed': have.has(o.v) ? 'true' : 'false',
        onclick: () => { const next = new Set(have); if (next.has(o.v)) next.delete(o.v); else next.add(o.v); put(it.options.map((x) => x.v).filter((x) => next.has(x))); } }, o.t)));
    }
    if (it.type === 'int' || it.type === 'time' || it.type === 'text') {
      const time = it.type === 'time', text = it.type === 'text';
      const input = el('input', { class: text ? 'ph-text' : 'ph-num', type: time ? 'time' : text ? 'text' : 'number', 'aria-label': it.title,
        value: cur == null ? '' : time ? hm(cur) : String(cur), autocomplete: 'off',
        ...(time ? { step: (it.step || 1) * 60 } : text ? { maxlength: it.max || 200 } : { min: it.min, max: it.max, step: it.step || 1, inputmode: 'numeric' }) });
      const read = () => {
        if (text) return input.value.trim().replace(/\s+/g, ' ');
        if (time) {
          const m = /^(\d{1,2}):(\d{2})$/.exec(input.value);
          if (!m) return null;
          let v = +m[1] * 60 + +m[2];
          if (it.min != null && v < it.min) v += 1440;   // время сна после полуночи — минутами сверх суток (01:00 = 1500)
          return v;
        }
        return input.value === '' ? null : Number(input.value);
      };
      const save = () => {
        const v = read();
        if (v == null || (!text && !Number.isInteger(v))) { input.value = cur == null ? '' : time ? hm(cur) : String(cur); toast(`«${it.title}»: нужно ${time ? 'время' : 'целое число'}`); return; }
        if (v === cur) return;
        put(v);
      };
      input.addEventListener('change', save);
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); input.blur(); } if (e.key === 'Escape') { input.value = cur == null ? '' : time ? hm(cur) : String(cur); input.blur(); } });
      if (!text && !time && it.min != null && it.max != null) input.title = `от ${it.min} до ${it.max}` + (it.step > 1 ? `, шаг ${it.step}` : '');
      return el('span', { class: 'ph-inp' }, input, it.unit && !time ? el('span', { class: 'ph-unit' }, it.unit) : null);
    }
    return el('span', { class: 'ph-val' }, label(it, cur));
  }

  /** Что с настройкой сейчас: ждёт телефона (и что стоит там), взято, не встало, меняли на телефоне. */
  function stateLine(it, phoneVal, pend, last, web) {
    if (pend) {
      return el('div', { class: 'ph-st wait' }, el('span', {}, `${WORD.pending} · с компьютера ${when(pend.at)} · сейчас на телефоне: ${label(it, phoneVal)}`),
        el('button', { class: 'link-btn', onclick: () => cancel(pend) }, 'Отменить'));
    }
    // Спор и отказ объясняют, откуда взялось значение, — они важнее строки «поменяли на телефоне», что их вызвала.
    if (web && web !== last && ['rejected', 'conflict'].includes(web.status) && Date.now() - Date.parse(web.at) <= RECENT_MS) last = web;
    if (!last || Date.now() - Date.parse(last.at) > RECENT_MS) return null;
    if (last.source === 'phone') return el('div', { class: 'ph-st' }, 'поменяли на телефоне ' + when(last.at));
    if (last.status === 'applied') return el('div', { class: 'ph-st ok' }, `поменяно с компьютера ${when(last.at)} · ${WORD.applied} ${when(last.taken_at)}`);
    if (last.status === 'rejected' || last.status === 'conflict') {
      return el('div', { class: 'ph-st bad' }, `правка с компьютера ${when(last.at)} (${label(it, last.value)}): ${WORD[last.status]}${last.note ? ' — ' + last.note : ''}`);
    }
    return null;
  }

  function row(dev, items, vals, pending, lastBy, webBy, note) {
    const head = items[0];
    const editable = items.some((it) => it.edit);
    const ctl = items.map((it) => {
      const pend = pending.find((e) => e.key === it.key);
      const cur = pend ? pend.value : vals[it.key];
      return it.edit ? control(dev, it, cur) : el('span', { class: 'ph-val' }, label(it, vals[it.key]));
    });
    const states = items.map((it) => stateLine(it, vals[it.key], pending.find((e) => e.key === it.key), lastBy.get(it.key), webBy.get(it.key))).filter(Boolean);
    // Причина у строки — если она своя: у группы «только в руках» (ключи, разрешения) она одна и стоит над группой.
    const why = !editable && head.why && head.why !== note ? el('div', { class: 'ph-why' }, 'только на телефоне: ' + head.why) : null;
    return el('div', { class: 'ph-row' + (editable ? '' : ' ro'), 'data-key': head.key },
      el('div', { class: 'ph-main' }, el('div', { class: 'ph-t' }, head.title), head.hint ? el('div', { class: 'ph-h' }, head.hint) : null, why, states),
      el('div', { class: 'ph-ctl' }, ctl));
  }

  /** Открытая группа: разделы (sec) — панелями, настройки одной строки (row: модель и усилие дороги) — вместе. */
  function groupPane(dev, g, d, pending, lastBy, webBy) {
    const vals = d.values || {};
    const secs = [];
    for (const it of g.items) {
      const name = it.sec || '';
      let s = secs.find((x) => x.name === name);
      if (!s) secs.push(s = { name, rows: [] });
      const last = s.rows[s.rows.length - 1];
      if (it.row && last && last[0].row === it.row) last.push(it); else s.rows.push([it]);
    }
    const pendN = pending.filter((e) => g.items.some((it) => it.key === e.key)).length;
    // Шапка группы — над её панелями (как заголовок открытой группы на развороте телефона); разделы — панелями.
    const top = el('div', { class: 'ph-ghead' }, icon(g.icon || 'settings', 18), el('div', { class: 'ph-gm' },
      el('div', { class: 'ph-gtitle' }, g.title, pendN ? el('span', { class: 'ph-wait' }, ' · ждут телефона: ' + pendN) : null),
      g.hint ? el('div', { class: 'ph-gs' }, g.hint) : null));
    return [top, g.note ? el('div', { class: 'ph-callout ph-note' }, icon('phone', 16), el('div', {}, 'Только на телефоне: ' + g.note + '.')) : null,
      ...secs.map((s) => el('div', { class: 'group panel' }, s.name ? el('h2', {}, s.name) : null,
        el('div', { class: 'rows' }, s.rows.map((items) => row(dev, items, vals, pending, lastBy, webBy, g.note)))))];
  }

  function groupList(dev, groups, d, pending, pick) {
    const status = d.status || {};
    return el('nav', { class: 'ph-groups', 'aria-label': 'Группы настроек' }, groups.map((g) => {
      const n = pending.filter((e) => g.items.some((it) => it.key === e.key)).length;
      const ro = g.items.every((it) => !it.edit);
      return el('button', { type: 'button', class: 'ph-g' + (pick && pick.key === g.key ? ' on' : ''), 'aria-current': pick && pick.key === g.key ? 'true' : null,
        onclick: () => go(hash(dev, g.key)) },
      icon(g.icon || 'settings', 17),
      el('span', { class: 'ph-gm' }, el('span', { class: 'ph-gt' }, g.title),
        el('span', { class: 'ph-gs' }, [status[g.key], ro ? 'только показ' : null].filter(Boolean).join(' · ') || g.hint)),
      n ? el('span', { class: 'ph-gn', title: 'правок ждёт телефона' }, n) : null);
    }));
  }

  function journal(d, edits, items) {
    if (!edits.length) return null;
    return el('div', { class: 'group panel' }, el('h2', {}, icon('clock', 17), 'Журнал', el('span', { class: 'gsub' }, 'правки с компьютера и что меняли на телефоне')),
      el('div', { class: 'rows' }, edits.slice(0, 40).map((e) => {
        const it = items.get(e.key);
        const st = e.source === 'phone' ? 'на телефоне' : WORD[e.status] + (e.status === 'applied' && e.taken_at ? ' ' + when(e.taken_at) : '');
        const cls = e.source === 'phone' ? '' : e.status === 'applied' ? 'ok' : e.status === 'pending' ? 'wait' : ['rejected', 'conflict'].includes(e.status) ? 'bad' : '';
        return el('div', { class: 'ph-log' },
          el('span', { class: 'when' }, when(e.at)),
          el('span', { class: 'what' }, el('b', {}, e.title || e.key), ': ', label(it, e.value),
            e.was != null ? el('span', { class: 'faint' }, ` (было ${label(it, e.was)})`) : null,
            e.source === 'web' ? el('span', { class: 'faint' }, ' · с компьютера') : null),
          el('span', { class: 'ph-st ' + cls }, st),
          e.note && e.source === 'web' && ['rejected', 'conflict'].includes(e.status) ? el('span', { class: 'note' }, e.note) : null);
      })));
  }

  function render() {
    const a = addr();
    if (!on()) {
      return [head('Телефон', ['настройки Правки на телефоне']), el('div', { class: 'body' },
        el('div', { class: 'empty' }, 'Сервер Дел ещё без настроек телефона — его обновят на компе (install-dela.ps1).'))];
    }
    // Пока грузится вид того же телефона по другому адресу (был без device, стал с ним) — рисуем прежний, без мигания.
    const got = crmGet('/api/view/phone' + (a.dev ? '?device=' + encodeURIComponent(a.dev) : ''), 15000);
    const v = got || (last && (!a.dev || (last.device && last.device.device === a.dev)) ? last : null);
    if (!v) return [head('Телефон'), el('div', { class: 'body' }, loading())];
    if (got) last = got;
    const d = v.device;
    if (!d) {
      return [head('Телефон', ['настройки Правки на телефоне']), el('div', { class: 'body' }, el('div', { class: 'empty' },
        'Телефонов пока нет. Правка присылает свои настройки, когда подключена к Делам (на телефоне: Настройки → Подключения → Дела), — первый снимок придёт через несколько минут.'))];
    }
    const dev = d.device;
    const groups = ((d.schema || {}).groups || []).filter((g) => (g.items || []).length);
    const pick = groups.find((g) => g.key === a.grp) || (NARROW.matches ? null : groups[0]);
    const pending = v.pending || [];
    const items = new Map(groups.flatMap((g) => g.items.map((it) => [it.key, it])));
    const lastBy = new Map();
    const webBy = new Map();
    for (const e of v.edits || []) {
      if (e.status === 'pending' || e.status === 'superseded' || e.status === 'cancelled') continue;
      if (!lastBy.has(e.key)) lastBy.set(e.key, e);
      if (e.source === 'web' && !webBy.has(e.key)) webBy.set(e.key, e);
    }

    const devs = v.devices || [];
    const switcher = devs.length > 1 ? el('div', { class: 'seg ph-dev', role: 'radiogroup', 'aria-label': 'Телефон' }, devs.map((x) =>
      el('button', { type: 'button', class: x.device === dev ? 'on' : null, title: x.device, onclick: () => go(hash(x.device, a.grp)) }, x.name || x.device))) : null;
    const stale = d.seen_at && Date.now() - Date.parse(d.seen_at) > 7 * 86400e3;
    const sub = [d.name || dev, d.app ? 'Правка ' + d.app : null, seen(d), pending.length ? el('span', { class: 'ph-old' }, 'ждут телефона: ' + pending.length) : null];
    const top = head('Телефон', sub, [switcher, stale ? el('button', { class: 'chip-btn', onclick: () => hide(d) }, 'Убрать из списка') : null].filter(Boolean));

    const callout = el('div', { class: 'ph-callout' }, icon('server', 17), el('div', {},
      'Настройки живут в телефоне, здесь — их копия. Правка отсюда ждёт телефона: он заберёт её при следующем синке Дел (обычно за пять минут) и поставит так же, как тумблер на экране. Что можно поменять только в руках — серым, с причиной; ключи и токены с телефона не уходят вовсе.'));
    const back = NARROW.matches && pick ? el('button', { class: 'link-btn ph-back', onclick: () => go(hash(dev)) }, icon('arrowLeft', 15), 'Все группы') : null;
    const cols = NARROW.matches
      ? (pick ? [back, groupPane(dev, pick, d, pending, lastBy, webBy)] : [groupList(dev, groups, d, pending, null)])
      : [el('div', { class: 'ph-cols' }, groupList(dev, groups, d, pending, pick), el('div', { class: 'ph-pane' }, pick ? groupPane(dev, pick, d, pending, lastBy, webBy) : null))];
    // На узком экране группа — своим экраном: пояснение и журнал — только у списка групп, чтобы настройки были сразу.
    const inGroup = NARROW.matches && pick;
    return [top, el('div', { class: 'body ph-body' }, inGroup ? null : callout, cols, inGroup ? null : journal(d, v.edits || [], items))];
  }

  return { on, render };
})();

/** Страница «Телефон» (маршрут #/phone) — её зовёт renderMain из app.js. */
function renderPhone() { return PHONE.render(); }
