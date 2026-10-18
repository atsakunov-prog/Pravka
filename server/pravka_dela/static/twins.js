// ── Страницы-двойники экранов телефона «Правка 5.0»: «Сегодня», «Засечка», «Правка» (14.10.2026) ─────────────────
// Владелец: телефон и веб — «две копии одного и того же»; на компьютере — та же Засечка, тот же день, те же диктовки,
// одна база на сервере. Раскладка — разложенный телефон, «мини-компьютер» холста «Ковчег на телефоне», только шире;
// узкое окно (до 900 px) — столбиком, как сложенный телефон. Только владельцу (флаг /api/me look.pages): у команды
// маршрутов #/day, #/zasechka, #/pravka нет, и сервер ей эти виды не отдаёт.
//
// Здесь — правила телефона, перенесённые в веб один раз, и общий каркас трёх страниц:
// - цвет категории «чей это» — как core/CategoryTone.kt (палитра --sp0…7 общего вида, НЕ светофор): по верхнему
//   уровню имени, дорога и потери — пополам с фоном, «не размечено» — линией, прочие — по хешу имени (тому же, что
//   String.hashCode у Kotlin: тот же цвет, что на телефоне);
// - гора дня — как core/DayProfile.kt и ui/Mountain.kt: очки копятся по отрезкам (цена часа категории × длительность),
//   ноль пунктиром — только если день уходил ниже, полоса суток и метки часов;
// - лента с вечера к утру — как core/Lenta.kt и ui/Ribbon.kt: у отрезка «от–до», название, под ним «категория ·
//   откуда», длительность; внутри отрезка — еда, спорт, дела, деньги, сон строками со значком на месте времени.
// Страницы — свои файлы (day.js, zasechka.js, pravka.js) — регистрируются в TWINS.pages. Сервер отдаёт их хвостом
// app.js (api.TAILS): своего адреса у файлов нет — закрытый список путей сайта-посредника не трогаем. Всё внутри
// одного объекта: хвост — тот же скрипт, что app.js, и одно совпавшее имя уронило бы веб Дел целиком.
const TWINS = (() => {
  const pages = {};
  const st = { data: {}, at: {}, busy: {}, err: {}, skew: 0, open: {}, tick: null, last: 0 };

  // ── Цвет «чей это» — core/CategoryTone.kt ──
  const FIXED = { 'работа': 0, 'быт': 1, 'спорт': 2, 'семья': 3, 'отдых': 4, 'систематизация': 5, 'еда': 6, 'сон': 7 };
  const ROAD = new Set(['передвижение', 'дорога']);
  const top = (c) => String(c || '').split(':')[0].trim().toLowerCase();
  /** String.hashCode Kotlin/Java: тот же номер цвета, что на телефоне. */
  function javaHash(s) {
    let h = 0;
    for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
    return h;
  }
  function tone(category) {
    const t = top(category);
    if (t === 'не размечено') return { sp: 7, gap: true };
    if (!t) return { sp: 7 };
    if (ROAD.has(t)) return { sp: 7, half: true };
    if (t === 'потери') return { sp: 6, half: true };
    if (t in FIXED) return { sp: FIXED[t] };
    return { sp: ((javaHash(t) % 7) + 7) % 7 };
  }
  /** Класс квадратика и полосы: пополам с фоном — h6/h7, «не размечено» — линией (cg). */
  const toneClass = (category) => { const t = tone(category); return t.gap ? 'cg' : t.half ? 'h' + t.sp : 'c' + t.sp; };
  /** Класс отрезка горы: дорога и потери — полным цветом (линия в два пикселя пополам с фоном пропадала бы). */
  const mtnClass = (category, gap) => (gap || tone(category).gap ? 'g' : String(tone(category).sp));
  const isSleep = (c) => top(c) === 'сон';
  const isSport = (c) => top(c) === 'спорт';

  // ── Время: минуты суток, «сейчас» по часам сервера ──
  const hmMin = (s) => { const m = /^(\d{1,2}):(\d{2})/.exec(String(s || '')); return m ? +m[1] * 60 + +m[2] : null; };
  const hm = (min) => { const m = Math.max(0, Math.round(min)); return m >= 1440 ? '24:00' : String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0'); };
  const now = () => Date.now() + st.skew;
  /** Минута суток day «сейчас» (по Москве, как crm.today); не тот день — null. */
  function nowMin(day) {
    const m = msk(now());
    return m.day === day ? hmMin(m.hm) + new Date(now()).getUTCSeconds() / 60 : null;
  }
  /** Очки целым со знаком — как «очки +84» телефона. */
  const points = (v) => { const r = Math.round(v); return r > 0 ? '+' + r : r < 0 ? '−' + -r : '0'; };
  /** Рубли с пробелами: 1 250 (копейки отброшены — как плитка телефона). */
  const rub = (kop) => Math.round(Math.abs(kop) / 100).toLocaleString('ru-RU').replace(/ /g, ' ');
  const num1 = (v) => (Math.round(v * 10) / 10).toLocaleString('ru-RU');
  const timer = (ms) => { const m = Math.max(0, Math.floor(ms / 60000)); return Math.floor(m / 60) + ':' + String(m % 60).padStart(2, '0'); };

  // ── Лента: записи архива → отрезки суток ──
  /** Записи дня d из архива (и вчерашние — для головы ночного сна) — в минуты суток d. Идущая — до «сейчас». */
  function spans(entries, day, cats) {
    const worth = new Map((cats || []).map((c) => [String(c.name || '').trim().toLowerCase(), +c.value_per_hour || 0]));
    const nm = nowMin(day);
    const out = [];
    for (const e of entries || []) {
      const shift = e.day === day ? 0 : e.day < day ? -1440 : null;
      if (shift === null) continue;
      const from = hmMin(e.start_local);
      if (from === null) continue;
      let to;
      if (e.open) to = e.day === day && nm !== null ? Math.max(from, nm) : from + (+e.minutes || 0);
      else { to = hmMin(e.end_local); if (to === null) to = from + (+e.minutes || 0); else if (to < from || (to === from && +e.minutes > 0)) to += 1440; }
      out.push({
        id: e.id, from: from + shift, to: to + shift, title: e.title || '', category: e.category || '', client: e.client || '',
        source: e.source || '', raw: e.raw || '', comment: e.comment || '', task: e.task_id || '', open: !!e.open,
        gap: e.source === 'gap' || top(e.category) === 'не размечено', start_at: e.start_at, day: e.day,
        worth: worth.get(String(e.category || '').trim().toLowerCase()) || 0, start_local: e.start_local, end_local: e.end_local,
      });
    }
    return out.sort((a, b) => a.from - b.from || a.to - b.to);
  }

  /** Профиль горы — core/DayProfile.of: очки копятся по отрезкам суток, идущий — до «сейчас». */
  function profile(list, day) {
    const nm = nowMin(day);
    const cut = nm === null ? 1440 : Math.min(Math.max(nm, 0), 1440);
    let bal = 0, low = 0, high = 0, peak = null, peakMin = null, unmarked = 0;
    const segs = [];
    for (const s of list) {
      const a = Math.max(s.from, 0), b = Math.min(s.open ? cut : s.to, cut);
      if (b <= a) continue;
      const before = bal;
      bal += s.worth * (b - a) / 60;
      segs.push({ from: a, to: b, before, after: bal, category: s.category, gap: s.gap, open: s.open });
      low = Math.min(low, bal); high = Math.max(high, bal);
      if (bal > 0 && (peak === null || bal > peak)) { peak = bal; peakMin = b; }
      if (s.gap) unmarked += b - a;
    }
    return { segs, value: bal, low, high: Math.max(high, 1), peak, peakMin, nowMin: nm, unmarked: Math.round(unmarked), dipped: low < 0 };
  }
  /** Подпись под горой: «пик +97 в 16:08 · не размечено 2 ч 2 м»; пик, равный нынешнему, — не новость. */
  function caption(p) {
    return [p.peak !== null && Math.round(p.peak) !== Math.round(p.value) ? `пик ${points(p.peak)} в ${hm(p.peakMin)}` : null,
      p.unmarked > 0 ? 'не размечено ' + dur(p.unmarked) : null].filter(Boolean).join(' · ');
  }

  // ── Рисунок горы — ui/Mountain.kt: SVG, полоса суток, метки часов ──
  const NS = 'http://www.w3.org/2000/svg';
  const svgEl = (tag, attrs) => { const e = document.createElementNS(NS, tag); for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v); return e; };
  function mountain(p, { height = 64, strip = true, ticks = true } = {}) {
    const W = 1440, H = 100, PAD = 0.08, HEAD = 1.06;
    const hi = p.high * HEAD, span = hi - p.low > 0 ? hi - p.low : 1;
    const y = (v) => (PAD + (hi - v) / span * (1 - 2 * PAD)) * H;
    const svg = svgEl('svg', { class: 'tw-mtn', viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', role: 'img',
      'aria-label': `Профиль дня: сейчас ${points(p.value)}` + (p.peak !== null ? `, пик ${points(p.peak)}` : '') });
    svg.style.setProperty('height', height + 'px');
    const y0 = y(0);
    if (p.dipped) svg.append(svgEl('line', { class: 'z', x1: 0, y1: y0, x2: W, y2: y0 }));
    let prevEnd = null, prevVal = 0;
    for (const s of p.segs) {
      const c = mtnClass(s.category, s.gap);
      if (prevEnd !== null && s.from - prevEnd > 0.5) svg.append(svgEl('line', { class: 'hole', x1: prevEnd, y1: y(prevVal), x2: s.from, y2: y(prevVal) }));
      svg.append(svgEl('path', { class: 'f' + c, d: `M${s.from} ${y0}L${s.from} ${y(s.before)}L${s.to} ${y(s.after)}L${s.to} ${y0}Z` }));
      svg.append(svgEl('line', { class: 's' + c, x1: s.from, y1: y(s.before), x2: s.to, y2: y(s.after) }));
      prevEnd = s.to; prevVal = s.after;
    }
    if (p.nowMin !== null) svg.append(svgEl('line', { class: 'now', x1: p.nowMin, y1: H * 0.04, x2: p.nowMin, y2: H * 0.96 }));
    const box = el('div', { class: 'tw-mtn-box' }, svg);
    if (strip) {
      const bar = el('div', { class: 'tw-strip', 'aria-hidden': 'true' });
      for (const s of p.segs) {
        const i = el('i', { class: s.gap ? 'cg' : toneClass(s.category) });
        i.style.setProperty('left', (s.from / 14.4) + '%');
        i.style.setProperty('width', `calc(${(s.to - s.from) / 14.4}% - 1px)`);
        bar.append(i);
      }
      box.append(bar);
    }
    if (ticks) box.append(el('div', { class: 'tw-ticks', 'aria-hidden': 'true' }, ['0', '6', '12', '18', '24'].map((t) => el('span', {}, t))));
    return box;
  }

  // ── Лента строками — core/Lenta.kt ──
  /** Откуда запись словом — Lenta.sourceWord: голос, текст, руками, из дела, метка, автопилот, Garmin, авто. */
  function sourceWord(source, category, raw) {
    switch (source) {
      case 'voice': return 'голос';
      case 'text': return 'текст';
      case 'edit': return 'руками';
      case 'task': return 'из дела';
      case 'todoist': return 'из Todoist';
      case 'nfc': return 'метка';
      case 'autopilot': case 'calendar': return 'автопилот';
      case 'auto': return /garmin/i.test(raw || '') || isSleep(category) || isSport(category) ? 'Garmin' : 'авто';
      case 'gap': case '': return '';
      default: return 'голос';
    }
  }
  /**
   * Строки ленты дня — с вечера к утру: отрезок, под ним его события (свежие сверху). Событие лежит в том отрезке, в
   * чьё «от–до» попало (как DayAssembler: до первого — в первом, после последнего начала — в последнем). Голова
   * ночного сна (запись разрезана по полуночи) — «от» вчерашнее.
   */
  function rows(list, day, events) {
    const today = list.filter((s) => s.day === day && s.to > 0);
    const yest = list.filter((s) => s.day !== day);
    const segs = today.map((s) => {
      let fromFull = s.from;
      if (s.from <= 0) {
        const head = yest.find((h) => Math.abs(h.to) < 1 && top(h.category) === top(s.category) && h.from < 0);
        if (head) fromFull = head.from;
      }
      return { ...s, fromFull, events: [] };
    });
    const loose = [];
    for (const ev of [...(events || [])].sort((a, b) => a.at - b.at)) {
      let owner = null;
      for (let i = 0; i < segs.length; i++) {
        const r = segs[i], last = i === segs.length - 1;
        if ((i === 0 && ev.at < r.from) || (last && ev.at >= r.from) || (!last && ev.at >= r.from && ev.at < segs[i + 1].from)) { owner = r; break; }
      }
      (owner ? owner.events : loose).push(ev);
    }
    const out = [];
    for (const s of [...segs].reverse()) {
      out.push({ seg: s });
      for (const e of [...s.events].sort((a, b) => b.at - a.at)) out.push({ ev: e });
    }
    for (const e of loose.sort((a, b) => b.at - a.at)) out.push({ ev: e });
    return out;
  }
  const EV_ICON = { food: 'food', sport: 'activity', sleep: 'moon', dela: 'dela', money: 'wallet' };
  /** Строка ленты: отрезок («от–до», квадратик, название и «категория · откуда», длительность) или событие. */
  function ribbonRow(r, first, onSeg) {
    if (r.ev) {
      const e = r.ev;
      return el('div', { class: 'tw-lr tw-lx' + (e.pending ? ' pending' : '') },
        el('span', { class: 'tm' }, icon(EV_ICON[e.kind] || 'info', 14), hm(e.at)), el('span', { class: 'sq' }),
        el('span', { class: 'lx' }, e.text + (e.pending ? ' · записать?' : '')));
    }
    const s = r.seg;
    const current = s.open && nowMin(s.day) !== null;
    const sub = s.gap ? '' : [s.category, sourceWord(s.source, s.category, s.raw)].filter(Boolean).join(' · ');
    const minutes = Math.round(s.to - s.fromFull);
    const can = onSeg && !s.gap;
    return el(can ? 'button' : 'div', {
      class: 'tw-lr' + (current ? ' now' : '') + (first ? ' first' : '') + (s.gap ? ' gap' : ''), type: can ? 'button' : null,
      title: can ? 'Поправить' : null, onclick: can ? () => onSeg(s) : null },
    el('span', { class: 'tm' }, hm(s.fromFull < 0 ? s.fromFull + 1440 : s.fromFull) + '–' + (current ? 'сейчас' : hm(s.to))),
    el('span', { class: 'sq ' + toneClass(s.category) }),
    el('span', { class: 'lb' }, el('span', { class: 'lt' }, s.gap ? 'не размечено' : s.title || s.category || 'без названия'),
      sub ? el('span', { class: 'ls' }, sub) : null,
      s.comment ? el('span', { class: 'lc' }, s.comment) : null),
    el('span', { class: 'ld' }, dur(minutes) || '0 м'));
  }
  function ribbon(list, { onSeg = null, empty = 'Записей за этот день нет.' } = {}) {
    if (!list.length) return el('div', { class: 'tw-empty' }, empty);
    return el('div', { class: 'tw-ribbon' }, list.map((r, i) => ribbonRow(r, i === 0, onSeg)));
  }

  /**
   * События дня для ленты — словами холста, как buildModel телефона в виде v2: «Обед · 640 ккал, белок 42 г»,
   * «Аптека · −1 250 ₽», «Бег · 45 мин, 5,00 км, пульс 130, нагрузка 28», «Сон 7,6 ч · счёт 80, HRV 58», «Сделано: …»,
   * «+2 новых из встречи · разобрать». Еда без «да» — «записать?»; траты — только семьи и со временем.
   */
  function events(d, day, list) {
    const out = [];
    for (const m of d.meals || []) {
      if (m.kind === 'добавки' || !m.time_local) continue;
      const kind = (m.kind || 'еда').replace(/^./, (c) => c.toUpperCase());
      out.push(m.confirmed
        ? { at: hmMin(m.time_local), kind: 'food', text: `${kind} · ${Math.round(+m.kcal || 0).toLocaleString('ru-RU')} ккал, белок ${Math.round(+m.protein_g || 0)} г` }
        : { at: hmMin(m.time_local), kind: 'food', pending: true, text: `≈ ${Math.round(+m.kcal || 0).toLocaleString('ru-RU')} ккал · ${m.kind || 'еда'}` });
    }
    for (const x of d.money || []) {
      if (x.shelf !== 'family' || x.income || !(+x.amount_kop < 0) || !x.time_local) continue;
      const what = x.what || x.note || x.category_title || '';
      out.push({ at: hmMin(x.time_local), kind: 'money', text: (what ? what + ' · ' : '') + '−' + rub(x.amount_kop) + ' ₽' });
    }
    for (const w of d.workouts || []) {
      if (!w.start_local) continue;
      const nums = [Math.round(+w.minutes || 0) + ' мин'];
      if (+w.km >= 0.3) nums.push((+w.km).toFixed(2).replace('.', ',') + ' км');
      if (+w.hr > 0) nums.push('пульс ' + Math.round(+w.hr));
      if (+w.load > 0) nums.push('нагрузка ' + Math.round(+w.load));
      const name = (w.name || w.type || 'тренировка').replace(/^./, (c) => c.toUpperCase());
      out.push({ at: hmMin(w.start_local), kind: 'sport', text: name + ' · ' + nums.join(', ') });
    }
    const well = d.wellness;
    if (well && +well.sleep_h > 0) {
      // Сон — строкой внутри ночного отрезка: миг подъёма — конец последнего сна до полудня.
      const sleep = list.filter((s) => s.day === day && isSleep(s.category) && s.to <= 720 && s.to > 0).sort((a, b) => b.to - a.to)[0];
      const extra = [+well.sleep_score > 0 ? 'счёт ' + Math.round(well.sleep_score) : null, +well.hrv > 0 ? 'HRV ' + Math.round(well.hrv) : null].filter(Boolean).join(', ');
      out.push({ at: Math.max(0, (sleep ? sleep.to : 1) - 1), kind: 'sleep', text: `Сон ${num1(+well.sleep_h)} ч` + (extra ? ' · ' + extra : '') });
    }
    // Дела — из синка веба (его правила): сделанное сегодня и новое из встреч и Telegram пачками.
    for (const t of deeds()) {
      if (t.status !== 'done' || !isMine(t) || !t.completed_at) continue;
      const m = msk(Date.parse(t.completed_at));
      if (m.day === day) out.push({ at: hmMin(m.hm), kind: 'dela', text: 'Сделано: ' + t.title });
    }
    const batches = new Map();
    for (const s of S.sugs.values()) {
      if (s.kind !== 'create' || s.for_user !== S.me.user || !s.created_at) continue;
      const m = msk(Date.parse(s.created_at));
      if (m.day !== day) continue;
      const k = s.batch_ref || s.id;
      if (!batches.has(k)) batches.set(k, []);
      batches.get(k).push({ s, m });
    }
    for (const list2 of batches.values()) {
      const first = list2.sort((a, b) => a.s.created_at.localeCompare(b.s.created_at))[0];
      const from = { meeting: 'встречи', telegram: 'Telegram', userbot: 'Telegram', bot: 'бота' }[first.s.source] || 'почты';
      out.push({ at: hmMin(first.m.hm), kind: 'dela',
        text: '+' + plural(list2.length, 'новое', 'новых', 'новых') + ' из ' + from + ' · ' + (list2.length > 1 ? 'разобрать' : (first.s.payload && first.s.payload.title) || '') });
    }
    return out.filter((e) => e.at !== null);
  }

  // ── Итог дня по категориям — как «Итог дня» Засечки (ZasechkaDay.totals): верхний уровень, длинное сверху ──
  function totals(list, day) {
    const nm = nowMin(day);
    const cut = nm === null ? 1440 : nm;
    const groups = new Map();
    for (const s of list) {
      if (s.day !== day) continue;
      const a = Math.max(s.from, 0), b = Math.min(s.open ? cut : s.to, 1440);
      if (b <= a || !s.category) continue;
      const k = s.gap ? 'не размечено' : top(s.category);
      const g = groups.get(k) || { label: s.gap ? 'не размечено' : s.category.split(':')[0].trim(), byCat: new Map(), minutes: 0, points: 0, gap: s.gap };
      g.minutes += b - a;
      g.points += s.worth * (b - a) / 60;
      g.byCat.set(s.category, (g.byCat.get(s.category) || 0) + b - a);
      groups.set(k, g);
    }
    return [...groups.values()].map((g) => ({ ...g, category: [...g.byCat.entries()].sort((a, b) => b[1] - a[1])[0][0] }))
      .sort((a, b) => b.minutes - a.minutes || a.label.localeCompare(b.label, 'ru'));
  }

  // ── Данные: виды сервера, свой кэш, «сейчас» по часам сервера ──
  const qs = (p) => Object.entries(p || {}).filter(([, v]) => v !== undefined && v !== null && v !== '').map(([k, v]) => k + '=' + encodeURIComponent(v)).join('&');
  const key = (name, p) => name + '?' + qs(p);
  /** Вид страницы: из кэша; старше maxAge (или force) — запрос, по ответу — перерисовка. */
  function load(name, p, { maxAge = 25000, force = false } = {}) {
    const k = key(name, p);
    const fresh = st.data[k] && Date.now() - (st.at[k] || 0) < maxAge;
    if ((fresh && !force) || st.busy[k]) return st.data[k] || null;
    st.busy[k] = true;
    api('/api/view/' + name + (qs(p) ? '?' + qs(p) : '')).then((d) => {
      st.data[k] = d; st.err[k] = null;
      if (d.now) st.skew = Date.parse(d.now) - Date.now();
    }).catch((e) => { st.err[k] = e.message; }).finally(() => {
      st.busy[k] = false; st.at[k] = Date.now();
      if (route().kind === 'twin') softRender();
    });
    return st.data[k] || null;
  }
  const error = (name, p) => st.err[key(name, p)] || null;
  const drop = (name) => { for (const k of Object.keys(st.at)) if (k.startsWith(name + '?')) st.at[k] = 0; };

  // ── Каркас: шапка страницы, панели, строка «нет доступа к архиву» ──
  function pageHead(title, sub, extra) {
    return el('div', { class: 'tw-head' }, el('div', { class: 'tw-title' }, el('h1', {}, title),
      sub ? el('div', { class: 'tw-sub' }, sub) : null), extra ? el('div', { class: 'tw-hx' }, extra) : null);
  }
  const card = (cls, ...kids) => el('section', { class: 'tw-card' + (cls ? ' ' + cls : '') }, ...kids);
  function sec(ico, title, opts = {}) {
    return el('div', { class: 'tw-sec' }, ico ? icon(ico, 18) : null, el('span', { class: 'tw-st' }, title),
      opts.count ? el('span', { class: 'tw-count' + (opts.hot ? ' hot' : '') }, opts.count) : null,
      opts.small ? el('small', {}, opts.small) : null,
      opts.link ? el('a', { class: 'tw-r', href: opts.href || '#', onclick: opts.onLink || null }, opts.link) : null);
  }
  function archiveNote(d) {
    if (!d || !d.archive || d.archive.ok) return null;
    return el('div', { class: 'tw-callout warn' }, icon('alert', 16), el('span', {}, d.archive.error || 'нет доступа к архиву'));
  }
  /** Свежесть телефона: лента в вебе свежа настолько, насколько свеж архив. */
  function freshNote(d) {
    const at = d && d.fresh && d.fresh.phone;
    if (!at) return null;
    const ago = Math.round((now() - Date.parse(at)) / 60000);
    if (ago < 30) return null;
    const m = msk(Date.parse(at));
    return el('div', { class: 'tw-fresh' }, 'телефон присылал ленту ' + (m.day === S.today ? 'в ' + m.hm : D.ddmm(m.day) + ' ' + m.hm) + ' — свежее ещё у него');
  }
  /** Сутки «вторник, 14 октября». */
  const dayLong = (day) => { const s = D.long(day); return s[0].toLowerCase() + s.slice(1); };
  /** Навигация по дням: ‹ сутки › и «к сегодня». */
  function dayNav(page, day) {
    const set = (d) => { st.open[page + ':day'] = d === S.today ? null : d; render(); };
    return el('div', { class: 'tw-daynav' },
      el('button', { class: 'ib tw-prev', title: 'Предыдущие сутки', 'aria-label': 'Предыдущие сутки', onclick: () => set(D.add(day, -1)) }, icon('chevron', 18)),
      day !== S.today ? el('button', { class: 'chip-btn', onclick: () => set(S.today) }, 'к сегодня') : null,
      el('button', { class: 'ib tw-next', title: 'Следующие сутки', 'aria-label': 'Следующие сутки', disabled: day >= S.today, onclick: () => set(D.add(day, 1)) }, icon('chevron', 18)));
  }
  const dayOf = (page) => st.open[page + ':day'] || S.today;

  // ── Команды Засечки телефону: веб день в архив не пишет — ставит команду, телефон берёт её синком ──
  async function command(kind, args) {
    try {
      const [r] = await ops([{ op: 'zasechka.cmd', cmd: { kind, args, at: new Date(now()).toISOString() } }]);
      // Сколько ждать — честно (записка отладки 25, 18.10.2026): телефон берёт команды раз в ~5 минут и сразу, как
      // открыли Правку.
      toast('Ушло телефону — ' + CMD_WORD[kind] + ': возьмёт за 5 минут или сразу, как откроешь Правку');
      drop('zasechka'); drop('day');
      render();
      return r;
    } catch (e) { fail(e); return null; }
  }
  async function cancel(id) {
    try { await ops([{ op: 'zasechka.cancel', id }]); drop('zasechka'); drop('day'); render(); } catch (e) { fail(e); }
  }
  const CMD_WORD = { start: 'начать', stop: 'стоп', edit: 'поправить' };
  /** Строки команд: что ждёт телефона, что он взял, сделал или не стал — за последний час; снять — пока ждёт. */
  function commandLines(cmds) {
    const hour = now() - 3600e3;
    const list = (cmds || []).filter((c) => ['wait', 'taken'].includes(c.status)
      || (['done', 'refused'].includes(c.status) && Date.parse(c.done_at || c.created_at) > hour)
      || Date.parse(c.created_at) > now() - 600e3).slice(0, 4);
    if (!list.length) return null;
    return el('div', { class: 'tw-cmds' }, list.map((c) => {
      const what = CMD_WORD[c.kind] + (c.args && c.args.title ? ` «${c.args.title}»` : c.args && c.args.set && c.args.set.title ? ` → «${c.args.set.title}»` : '');
      const at = msk(Date.parse(c.said_at)).hm;
      const word = { wait: 'ждёт телефона · до 5 мин', taken: 'телефон взял', done: 'сделано телефоном', refused: 'телефон не стал',
        expired: 'не взята — устарела', cancelled: 'снята' }[c.status] || c.status;
      return el('div', { class: 'tw-cmd ' + c.status },
        el('span', { class: 'st' }, word), el('span', { class: 'wh' }, `${what} · ${at}`),
        c.note && c.status !== 'wait' ? el('span', { class: 'nt' }, c.note) : null,
        c.status === 'wait' ? el('button', { class: 'chip-btn', onclick: () => cancel(c.id) }, 'Снять') : null);
    }));
  }
  const pending = (cmds) => (cmds || []).some((c) => c.status === 'wait' || c.status === 'taken');

  /** Идущая запись и последняя закрытая — для «идёт сейчас». */
  function current(list, day) {
    const today = list.filter((s) => s.day === day);
    const open = today.find((s) => s.open) || null;
    const last = open ? null : today.filter((s) => !s.gap && !s.open).sort((a, b) => b.to - a.to)[0] || null;
    return { open, last };
  }

  // Часы тикают сами: таймер «идёт» — без запросов; данные — раз в минуту, а пока команда ждёт телефона — раз в 5 с.
  function ensureTick() {
    if (st.tick) return;
    st.tick = setInterval(() => {
      if (route().kind !== 'twin' || document.hidden) return;
      for (const t of document.querySelectorAll('[data-tw-start]')) t.textContent = timer(now() - Date.parse(t.dataset.twStart));
      const busy = Object.values(st.data).some((d) => d && pending(d.commands));
      if (Date.now() - st.last > (busy ? 5000 : 60000)) {
        st.last = Date.now();
        for (const k of Object.keys(st.at)) if (!k.startsWith('pogoda')) st.at[k] = 0;
        softRender();
      }
    }, 5000);
  }

  /** Страница целиком: шапка Ковчега, страница, прокрутка колонок — где была. */
  function renderPage(r) {
    const page = pages[r.page];
    if (!page) return false;
    S.order = []; S.sugOrder = [];
    const keep = new Map([...document.querySelectorAll('[data-tw-scroll]')].map((x) => [x.dataset.twScroll, x.scrollTop]));
    // Поле в фокусе (поиск «Правки») переживает перерисовку: тот же id — фокус и курсор на месте.
    const ae = document.activeElement;
    const focus = ae && ae.id && ae.closest && ae.closest('.tw-page') ? { id: ae.id, a: ae.selectionStart, b: ae.selectionEnd } : null;
    if (!S.top) S.top = topBar();
    const fresh = S.toTop;
    S.toTop = false;
    const root = el('main', { class: 'tw-page tw-' + r.page, 'data-tw-scroll': 'page-' + r.page }, page.render(r));
    $app.replaceChildren(S.top, root);
    paintLook();
    if (!fresh) {
      for (const x of [root, ...root.querySelectorAll('[data-tw-scroll]')]) if (keep.has(x.dataset.twScroll)) x.scrollTop = keep.get(x.dataset.twScroll);
    }
    if (focus) {
      const x = document.getElementById(focus.id);
      if (x) { x.focus(); try { x.setSelectionRange(focus.a, focus.b); } catch (e) { /* поле без курсора */ } }
    }
    ensureTick();
    return true;
  }

  return { pages, st, tone, toneClass, mtnClass, spans, profile, caption, mountain, rows, ribbon, ribbonRow, events, totals,
    sourceWord, javaHash, hm, hmMin, now, nowMin, points, rub, num1, timer, load, error, drop, pageHead, card, sec,
    archiveNote, freshNote, dayLong, dayNav, dayOf, command, cancel, commandLines, pending, current, renderPage };
})();

/** Маршрут страницы-двойника: только владельцу и только объявленной сервером (look.pages). Для route() в app.js. */
function twinsRoute(kind) {
  const pages = (S.me && S.me.look && S.me.look.pages) || [];
  return TWINS.pages[kind] && pages.includes(kind) ? { kind: 'twin', page: kind } : null;
}
/** Отрисовка страницы-двойника вместо колонок Дел. Для render() в app.js. */
function twinsRender(r) { return TWINS.renderPage(r); }
