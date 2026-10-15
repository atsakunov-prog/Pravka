// «Claude: траты» (#/spend) и значок трат на «Сегодня» (#/day) — 14.10.2026, двойник экрана телефона
// (ClaudeSpendScreen.kt). Контракт — server/contract/dela-spend.json, сервер — pravka_dela/spend.py, аудит —
// docs/claude-spend.md.
//
// Владелец: «значок денег Claude (все запросы в Claude) на главный экран справа вверху… систематизировать лучше по
// задачам… раз в неделю сам Claude пускай предлагает варианты экономии (красная точка над значком)» и вдогонку: «сбор в
// одном месте, плюс там отдельно должны быть серверные траты». Страница читает вид spend — одну книгу трат телефона и
// служб компа: общий итог, блоки «Телефон» и «Сервер» (службы и задачи полосами, дороже — выше), модели, кэш и пачки,
// по дням, откуда числа и что не учитывается; советы недели — сверху, и, раз они на экране, красная точка гаснет
// (spend.seen). Только владельцу: сервер другим вид не отдаёт, а значка без флага spend и не владельцу нет.
//
// Свой файл, хвостом app.js (api.TAILS), как phone.js: своего адреса нет, список путей сайта-посредника не меняется.
'use strict';

const SPEND = (() => {
  const PERIODS = [['today', 'Сегодня'], ['week', '7 дней'], ['month', '30 дней']];
  const TASKS_MAX = 10;
  let period = 'week';
  let seenSent = null;

  const on = () => !!(S.me && S.me.role === 'owner' && (S.me.features || []).includes('spend'));

  /** $ по-русски, как SpendBook.money телефона: «$4,20», от $100 — без центов, меньше цента — «$0,004». */
  function money(v) {
    v = +v || 0;
    if (v >= 100) return '$' + Math.round(v);
    if (v >= 0.01 || v <= 0) return '$' + Math.max(0, v).toFixed(2).replace('.', ',');
    return '$' + v.toFixed(3).replace('.', ',');
  }
  const model = (m) => {
    if (!m || m === 'legacy') return 'модель не записана';
    const [name, ...rest] = m.replace(/^claude-/, '').split('-');
    const ver = rest.filter((x) => /^\d{1,2}$/.test(x)).join('.');
    return name.charAt(0).toUpperCase() + name.slice(1) + (ver ? ' ' + ver : '');
  };

  /** Значок для шапки «Сегодня»: доллар, $ за 7 дней мелко и красная метка непрочитанных советов. */
  function badge() {
    const b = S.spend;
    const dot = !!(b && b.tips && b.tips.unseen && b.tips.id !== seenSent);
    const hint = b ? `Claude: ${money(b.week)} за 7 дней (телефон ${money(b.week_phone)}, сервер ${money(b.week_server)})` +
      (dot ? ' — есть советы недели' : '') : 'Траты Claude';
    return el('a', { class: 'sp-badge', href: '#/spend', title: hint, 'aria-label': hint },
      icon('usd', 18), b ? el('span', { class: 'sp-bsum' }, money(b.week)) : null, dot ? el('span', { class: 'sp-dot' }) : null);
  }

  function bar(label, v, top, sub) {
    const pct = top > 0 ? Math.max(2, Math.round(100 * v / top)) : 0;
    return el('div', { class: 'sp-bar' },
      el('span', { class: 'sp-bl', title: sub || label }, label, sub ? el('small', {}, sub) : null),
      el('span', { class: 'sp-bt' }, el('i', { style: `width:${pct}%` })),
      el('span', { class: 'sp-bv' }, money(v)));
  }

  function group(ico, title, small, ...kids) {
    return el('section', { class: 'group panel sp-card' },
      el('h2', {}, icon(ico, 17), title, small ? el('span', { class: 'gsub' }, small) : null),
      el('div', { class: 'sp-in' }, ...kids));
  }

  function totalCard(v, p) {
    const blk = (k) => ((p.blocks || []).find((b) => b.block === k) || {}).usd || 0;
    const kpi = (val, label) => el('div', { class: 'sp-kpi' }, el('b', {}, val), el('span', {}, label));
    return group('usd', 'Claude: всего', PERIODS.find(([k]) => k === period)[1].toLowerCase(),
      el('div', { class: 'sp-kpis' }, kpi(money(p.usd), `всего · ${p.calls || 0} выз.`), kpi(money(blk('phone')), 'телефон'),
        kpi(money(blk('server')), 'сервер')),
      period === 'week' ? el('div', { class: 'sp-note' }, `Прошлые 7 дней — ${money(v.prev_week)}`) : null,
      p.inexact_usd >= 0.01 ? el('div', { class: 'sp-note' }, `Из них ${money(p.inexact_usd)} — без токенов (книга службы знает только $)`) : null);
  }

  function tipsCard(t) {
    if (!t || !(t.tips || []).length) return null;
    const week = t.week ? t.week.slice(8, 10) + '.' + t.week.slice(5, 7) : '';
    return group('bolt', 'Советы Claude на неделю', week ? 'неделя с ' + week : null,
      t.note ? el('div', { class: 'sp-claude' }, t.note) : null,
      el('div', { class: 'sp-tips' }, t.tips.map((x) => el('div', { class: 'sp-tip' },
        el('div', { class: 'sp-tt' }, x.where ? el('span', { class: 'sp-where' }, x.where === 'phone' ? 'телефон' : 'сервер') : null,
          el('b', {}, x.title), x.save_usd_month > 0 ? el('span', { class: 'sp-save' }, '−' + money(x.save_usd_month) + '/мес') : null),
        el('div', { class: 'sp-ts' }, [x.why, x.how ? 'как: ' + x.how : null, x.risk ? 'риск: ' + x.risk : null].filter(Boolean).join(' · '))))));
  }

  function blockCard(b, total) {
    const share = total > 0 ? ' · ' + Math.round(100 * b.usd / total) + '%' : '';
    const phone = b.block === 'phone';
    if (!(b.services || []).length) {
      return group(phone ? 'phone' : 'server', b.title, money(b.usd) + share,
        el('div', { class: 'sp-note' }, phone ? 'Телефон за этот период Claude не звал (или ещё не присылал).' : 'Службы компа за этот период Claude не звали.'));
    }
    const topS = Math.max(...b.services.map((s) => s.usd), 1e-9);
    const topT = Math.max(...b.tasks.map((s) => s.usd), 1e-9);
    const tasks = b.tasks.slice(0, TASKS_MAX);
    const rest = b.tasks.slice(TASKS_MAX);
    return group(phone ? 'phone' : 'server', b.title, money(b.usd) + share,
      phone ? null : el('div', { class: 'sp-bars' }, b.services.map((s) => bar(s.title, s.usd, topS, (s.models || []).map(model).join(', ')))),
      phone ? null : el('div', { class: 'sp-label' }, 'По задачам'),
      el('div', { class: 'sp-bars' }, tasks.map((t) => bar(phone ? t.title : `${t.service_title} · ${t.title}`, t.usd, topT,
        t.how || (t.models || []).map(model).join(', ')))),
      rest.length ? el('div', { class: 'sp-note' }, `и ещё ${rest.length} — на ${money(rest.reduce((a, t) => a + t.usd, 0))}`) : null);
  }

  function modelsCard(p) {
    if (!(p.models || []).length) return null;
    const top = Math.max(...p.models.map((m) => m.usd), 1e-9);
    return group('chart', 'По моделям', null, el('div', { class: 'sp-bars' }, p.models.map((m) => bar(model(m.model), m.usd, top))));
  }

  function savingsCard(p) {
    const neg = p.cache_saved < 0;
    return group('refresh', 'Кэш и пачки', 'кэш: те же токены по цене входа минус как вышло; пачки — половина цены',
      el('div', { class: 'sp-kpis' },
        el('div', { class: 'sp-kpi' + (neg ? ' warn' : '') }, el('b', {}, (neg ? '−' : '') + money(Math.abs(p.cache_saved))),
          el('span', {}, `кэш · прочитано ${Math.round((p.cache_read || 0) / 1000)} тыс.` + (neg ? ' — пишется, а не читается' : ''))),
        el('div', { class: 'sp-kpi' }, el('b', {}, money(p.batch_saved)), el('span', {}, `пачки · ${p.batch_calls || 0} из ${p.calls || 0}`))));
  }

  function trendCard(v) {
    const days = v.trend || [];
    if (!days.length) return null;
    const top = Math.max(...days.map((d) => d.usd), 1e-9);
    const sum = days.reduce((a, d) => a + d.usd, 0);
    return group('calendar', 'По дням', days.length + ' дней',
      el('div', { class: 'sp-cols' }, days.map((d) => el('div', { class: 'sp-col' + (d.day === v.today ? ' on' : ''), title: `${d.day.slice(8)}.${d.day.slice(5, 7)}: ${money(d.usd)}` },
        el('i', { style: `height:${Math.round(100 * d.usd / top)}%` }), el('span', {}, +d.day.slice(8))))),
      el('div', { class: 'sp-note' }, `За ${days.length} дней ${money(sum)} · в среднем ${money(sum / days.length)} в день`));
  }

  function sourcesCard(v) {
    return group('info', 'Откуда числа', 'Дела пишут свои вызовы сами, телефон присылает итоги дней, службы компа — сборщик «Дома»',
      el('div', { class: 'sp-src' }, (v.sources || []).map((s) => el('div', {}, el('b', {}, s.src), ' — ', (s.titles || []).join(', '),
        s.day ? el('span', { class: 'faint' }, ` · по ${s.day.slice(8)}.${s.day.slice(5, 7)}`) : null))),
      (v.gaps || []).length ? el('div', { class: 'sp-label' }, 'Не учитывается') : null,
      el('ul', { class: 'sp-gaps' }, (v.gaps || []).map((g) => el('li', {}, g))));
  }

  /** Защита от петли (аудит №12): что на сервере стоит — полосой сверху с «Продолжать» (claude.guard resume: час
   *  пороги втрое). Ничего не стоит — полосы нет. */
  function guardStrip(g) {
    if (!g || !(g.stopped || []).length) return null;
    const hm = (iso) => (iso ? new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : '');
    return el('section', { class: 'group panel sp-card sp-guard' },
      el('h2', {}, icon('alert', 17), 'Claude на сервере остановлен защитой от петли'),
      el('div', { class: 'sp-in' }, g.stopped.map((x) => el('div', { class: 'sp-note' },
        `⛔ ${x.title}: ${x.why}` + (x.at ? ` · с ${hm(x.at)}` : ''), ' ',
        el('button', { class: 'btn small', onclick: async () => {
          try { await ops([{ op: 'claude.guard', action: 'resume', task: x.task }]); crmDirty(); toast('Продолжаю — час пороги втрое'); render(); } catch (e) { fail(e); }
        } }, 'Продолжать')))));
  }

  function page() {
    if (!on()) {
      return [head('Claude: траты'), el('div', { class: 'body' }, el('div', { class: 'empty' },
        S.me && S.me.role !== 'owner' ? 'Траты Claude видит только владелец Дел.' : 'Сервер Дел ещё без книги трат — его обновят на компе (install-dela.ps1).'))];
    }
    const v = crmGet('/api/view/spend', 60000);
    if (!v) return [head('Claude: траты'), el('div', { class: 'body' }, loading())];
    const t = v.tips;
    // Советы на экране — значит, открыты: метка над значком гаснет (один раз на советы).
    if (t && t.id && !t.seen_at && seenSent !== t.id) {
      seenSent = t.id;
      ops([{ op: 'spend.seen', id: t.id }]).then(() => { if (S.spend && S.spend.tips) S.spend.tips.unseen = false; })
        .catch(() => { seenSent = null; });
    }
    const p = (v.periods || {})[period] || {};
    const seg = el('div', { class: 'seg', role: 'radiogroup', 'aria-label': 'Период' }, PERIODS.map(([k, label]) =>
      el('button', { type: 'button', class: k === period ? 'on' : null, onclick: () => { period = k; render(); } }, label)));
    return [head('Claude: траты', ['все запросы: телефон и службы компа'], seg),
      el('div', { class: 'body sp-body' },
        el('div', { class: 'sp-grid' },
          el('div', { class: 'sp-colx' }, guardStrip(v.guard), totalCard(v, p), tipsCard(t), ...(p.blocks || []).map((b) => blockCard(b, p.usd))),
          el('div', { class: 'sp-colx' }, savingsCard(p), modelsCard(p), trendCard(v), sourcesCard(v))))];
  }

  return { on, page, badge, money };
})();

/** Страница «Claude: траты» (маршрут #/spend) — её зовёт renderMain из app.js. */
function renderSpend() { return SPEND.page(); }
/** Значок трат для шапок (сейчас — «Сегодня», #/day); без флага spend или не владельцу — ничего. */
function spendBadge() { return SPEND.on() ? SPEND.badge() : null; }
