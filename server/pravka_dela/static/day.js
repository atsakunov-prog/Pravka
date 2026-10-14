// ── «Сегодня» (#/day) — двойник экрана «Сегодня» телефона (TodayV2.kt, план redesign-5 §4) ────────────────────────
// Разложенный телефон: слева плитки (Еда, Спорт, Деньги, Шаббат), «идёт сейчас» с горой дня и лента с вечера к утру;
// справа погода (строка, раскрытие на сутки и неделю), «Сейчас», дайджест, «Новое» с «да/нет», «Сегодня» (срок
// сегодня и встречи дня, завтрашнее — строкой). Узкое окно — столбиком в порядке сложенного телефона: идёт сейчас,
// погода, «Сейчас», дайджест, «Новое», «Сегодня», плитки, лента. Дела — из синка веба теми же правилами, что страницы
// Дел (nowTasks, todayTasks, «Подскажи»); день — из архива видом day; погода — своим видом (сеть не держит день).
TWINS.pages.day = (() => {
  const T = TWINS;
  const ui = { wx: false, dg: false };
  const navUrl = (ico) => { const x = ((S.me.look || {}).nav || []).find((n) => n.icon === ico); return x ? x.url : null; };
  const SKY = { clear: 'sun', night: 'moon', partly: 'cloud', cloud: 'cloud', fog: 'cloud', rain: 'cloud', snow: 'cloud', storm: 'alert' };

  function tile(ico, title, value, unit, sub, url, opts = {}) {
    const kids = [el('div', { class: 'tw-th' }, icon(ico, 16), el('h3', {}, title)),
      el('div', { class: 'tw-kpi' + (opts.empty ? ' dim' : '') }, value, unit ? el('small', {}, unit) : null),
      opts.bar !== undefined ? el('div', { class: 'tw-bar' }, (() => { const i = el('i', {}); i.style.setProperty('width', Math.min(100, Math.round(opts.bar * 100)) + '%'); return i; })()) : null,
      sub ? el('div', { class: 'tw-ts' }, sub) : null];
    return url ? el('a', { class: 'tw-tile', href: url, target: url.startsWith('http') ? '_blank' : null, rel: 'noopener' }, kids)
      : el('div', { class: 'tw-tile' }, kids);
  }

  /** Плитки — числа те же, что у телефона: еда к цели Свода, тренировки и сон, траты семьи за день и месяц, Шаббат. */
  function tiles(t) {
    if (!t) return null;
    const sh = t.shabbat;
    const fri = sh && sh.today ? 'сегодня' : 'пт';
    return el('div', { class: 'tw-tiles o7' },
      tile('food', 'Еда', t.kcal.toLocaleString('ru-RU'), t.kcal_goal ? 'из ' + t.kcal_goal.toLocaleString('ru-RU') : 'ккал',
        t.kcal ? `ккал · белок ${t.protein} г` : 'сегодня ещё не ел', navUrl('food'), { bar: t.kcal_goal ? t.kcal / t.kcal_goal : undefined, empty: !t.kcal }),
      tile('activity', 'Спорт', t.sport_min ? String(t.sport_min) : '—', t.sport_min ? 'мин ' + t.sport_name : null,
        [t.sleep_h ? 'сон ' + T.num1(t.sleep_h) + ' ч' : null, t.tsb !== null && t.tsb !== undefined ? 'форма ' + (t.tsb >= 0 ? '+' : '−') + T.num1(Math.abs(t.tsb)) : null]
          .filter(Boolean).join(' · ') || 'тренировок сегодня нет', navUrl('activity'), { empty: !t.sport_min }),
      tile('wallet', 'Деньги', T.rub(t.money_day_kop), '₽ сегодня', t.money_month_kop ? `${t.month} ${T.rub(t.money_month_kop)} ₽` : '',
        navUrl('wallet'), { empty: !t.money_day_kop }),
      tile('candles', 'Шаббат', sh ? (sh.now ? sh.exit : sh.candles) : '—', sh ? (sh.now ? 'исход' : fri + ', свечи') : null,
        sh ? (sh.now ? 'Шаббат шалом' : 'исход сб ' + sh.exit) : 'время — на странице Шаббата', navUrl('candles'), { empty: !sh }));
  }

  /** «Идёт сейчас» крупно, таймер и «Стоп» (командой телефону), под ним гора дня. Ничего не идёт — что было последним. */
  function nowCard(d, list, prof, day) {
    const today = day === S.today;
    const { open, last } = T.current(list, day);
    const e = today ? open : null;
    const stopWait = (d.commands || []).some((c) => c.kind === 'stop' && ['wait', 'taken'].includes(c.status) && e && +c.args.entry_id === +e.id);
    const eyebrow = !today ? 'Засечка · ' + D.long(day).toLowerCase() : e ? 'Засечка · идёт' : 'Засечка · ничего не идёт';
    const title = e ? e.title || e.category || 'без названия' : today ? 'Скажи, чем занят' : 'Итог дня';
    const sub = e ? [e.category, 'с ' + T.hm(e.from)].filter(Boolean).join(' · ')
      : today && last ? `последнее — «${last.title || last.category}» до ${T.hm(last.to)}` : null;
    return T.card('tw-nowc o1',
      el('div', { class: 'tw-row' }, el('span', { class: 'tw-eyebrow' }, eyebrow), el('a', { class: 'tw-r', href: '#/zasechka' }, 'Засечка')),
      el('div', { class: 'tw-row tw-nowrow' },
        el('div', { class: 'tw-grow' }, el('div', { class: 'tw-nt' }, title),
          sub ? el('div', { class: 'tw-ns' }, e && e.category ? el('span', { class: 'sq ' + T.toneClass(e.category) }) : null, sub) : null),
        e && e.start_at ? el('div', { class: 'tw-timer', 'data-tw-start': e.start_at }, T.timer(T.now() - Date.parse(e.start_at))) : null,
        e ? el('button', { class: 'btn small', disabled: stopWait, title: stopWait ? 'Стоп уже ждёт телефона' : 'Остановить — команда телефону',
          onclick: () => T.command('stop', { entry_id: e.id }) }, icon('stop', 15), stopWait ? 'ждёт' : 'Стоп') : null),
      T.mountain(prof, { height: 58 }),
      el('div', { class: 'tw-val' }, el('b', { class: prof.value < 0 ? 'warn' : '' }, T.points(prof.value)),
        el('span', {}, T.caption(prof) ? 'очки · ' + T.caption(prof) : 'очки')),
      T.commandLines(d.commands));
  }

  function lentaCard(rows) {
    const box = el('div', { class: 'tw-scrollbox fade', 'data-tw-scroll': 'day-lenta' },
      T.ribbon(rows, { onSeg: () => go('#/zasechka') }));
    return T.card('tw-lenta o8', T.sec(null, 'Лента', { small: 'с вечера к утру', link: 'Засечка', href: '#/zasechka' }), box);
  }

  /** Погода над «Сейчас»: сейчас и три части суток от текущего часа; раскрытие — сутки по часам вбок и неделя. */
  function weatherCard(wx) {
    const w = wx && wx.weather;
    if (!w) return null;
    if (!w.ok) return T.card('tw-wxc o2', el('div', { class: 'tw-wx-off' }, icon('cloud', 18), 'Погода: ' + (w.error || 'прогноза нет')));
    const line = el('button', { class: 'tw-wx', type: 'button', 'aria-expanded': ui.wx ? 'true' : 'false', onclick: () => { ui.wx = !ui.wx; render(); } },
      icon(SKY[w.now.sky] || 'cloud', 22), el('b', {}, w.now.temp),
      el('span', { class: 'tw-wxp' }, (w.parts || []).map((p) => el('span', { class: 'tw-wxs' }, el('small', {}, p.label), p.temp, p.feels ? el('i', {}, 'ощущ. ' + p.feels) : null))),
      el('span', { class: 'tw-r' }, ui.wx ? 'свернуть' : 'раскрыть'));
    const kids = [line];
    if (ui.wx) {
      kids.push(el('div', { class: 'tw-hrs', 'data-tw-scroll': 'wx-hours' }, (w.hours || []).map((h) => el('div', { class: 'tw-hr' },
        el('small', {}, h.t), icon(SKY[h.sky] || 'cloud', 16), el('b', {}, h.temp), el('small', { class: h.rain ? 'rain' : '' }, h.rain || ' ')))));
      kids.push(el('div', { class: 'tw-wkr head' }, el('span', {}), el('span', {}, 'утро'), el('span', {}, 'день'), el('span', {}, 'вечер'), el('span', {}, 'осадки')));
      for (const x of w.week || []) {
        kids.push(el('div', { class: 'tw-wkr' }, el('b', {}, x.d), el('span', {}, x.m), el('span', {}, x.a), el('span', {}, x.e),
          el('span', { class: x.wet ? 'wet' : 'faint' }, x.rain)));
      }
      kids.push(el('div', { class: 'tw-wx-note' }, [w.place, w.stale ? 'прогноз не обновился — показан прежний' : null].filter(Boolean).join(' · ')));
    }
    return T.card('tw-wxc o2', ...kids);
  }

  /** Строка дела: квадрат-галка (сделано), название — карточка в Делах, ниже срок и кто. */
  function taskLine(t, extra) {
    const due = dueLabel(t);
    const who = personName(person(t.person_id)) || (project(t.project_id) || {}).name || '';
    const rest = [t.estimate_min && !due ? dur(t.estimate_min) : null, t.ball === 'waiting' ? 'жду' : null, who || null].filter(Boolean).join(' · ');
    const chk = el('button', { class: 'tw-chk' + (isOpen(t) ? '' : ' on'), title: isOpen(t) ? 'Сделано' : 'Вернуть', 'aria-label': 'сделано: ' + t.title,
      onclick: (ev) => { ev.stopPropagation(); toggleDone([t]); } });
    return el('div', { class: 'tw-li', 'data-id': t.id }, chk,
      el('div', { class: 'tw-grow' }, el('a', { class: 'tw-lt', href: '#/now', onclick: () => { S.cardId = t.id; } }, t.title),
        due || rest ? el('div', { class: 'tw-ls' }, due ? el('span', { class: due.cls === 'late' ? 'warn' : 'acc' }, due.text) : null,
          due && rest ? ' · ' : null, rest) : null),
      extra || null);
  }

  function nowTasksCard() {
    const list = nowTasks().sort(byTime);
    return T.card('o3', T.sec('bolt', 'Сейчас', { count: list.length || null, hot: true, small: 'отмечено тобой', link: 'Дела', href: '#/now' }),
      list.length ? el('div', { class: 'tw-list' }, list.map((t) => taskLine(t)))
        : el('div', { class: 'tw-empty' }, 'Ничего не отмечено — «Сейчас» ставится в Делах.'));
  }

  /** Последний дайджест: растворяется книзу, нажатие — целиком; «Ждут тебя» первым (как DigestNote телефона). */
  function digestCard(g) {
    if (!g) return null;
    const s = g.summary || {};
    // «Кто (чат)» — как digest.where_of сервера: чат не повторяем, если он и есть человек.
    const where = (w) => {
      const who = w.who || '', chat = w.chat || '';
      if (!chat || who.toLowerCase().includes(chat.toLowerCase()) || (who && chat.toLowerCase().includes(who.toLowerCase()))) return who || chat;
      return who ? `${who} (${chat})` : chat;
    };
    const blocks = [];
    if ((s.waiting || []).length) blocks.push(el('span', { class: 'tw-eyebrow' }, 'Ждут тебя'), ...s.waiting.map((w) => el('p', {}, el('b', {}, where(w)), w.what ? ' ' + w.what : '')));
    if ((s.work || []).length) blocks.push(el('span', { class: 'tw-eyebrow' }, 'По работе'), ...s.work.map((w) => el('p', {}, w.topic ? el('b', {}, w.topic) : null, w.topic && w.text ? ' ' : '', w.text || '')));
    if ((s.family || []).length) blocks.push(el('span', { class: 'tw-eyebrow' }, 'Дом'), ...s.family.map((x) => el('p', {}, x)));
    if (s.other) blocks.push(el('p', {}, el('b', {}, 'Прочее:'), ' ' + s.other));
    if (!blocks.length) blocks.push(el('p', {}, g.text || 'Ничего, что ждёт тебя.'));
    const counts = [g.chats ? plural(g.chats, 'чат', 'чата', 'чатов') : null, g.msgs ? plural(g.msgs, 'сообщение', 'сообщения', 'сообщений') : null].filter(Boolean).join(' · ');
    const body = el('div', { class: 'tw-dgb' + (ui.dg ? ' open' : ''), onclick: () => { ui.dg = !ui.dg; render(); } }, blocks);
    const kids = [T.sec('digest', 'Дайджест', { small: [g.label, counts].filter(Boolean).join(' · '), link: ui.dg ? 'свернуть' : 'раскрыть',
      onLink: (e) => { e.preventDefault(); ui.dg = !ui.dg; render(); } }), body];
    if (ui.dg) {
      const n = g.dela || {};
      const line = n && Object.keys(n).length ? 'В Дела: ' + ([n.created ? 'поставлено ' + n.created : null, n.asks ? 'вопросов ' + n.asks : null,
        n.closed ? 'закрыто само ' + n.closed : null, n.updated ? 'уточнено само ' + n.updated : null].filter(Boolean).join(', ') || 'новых дел нет') + '.' : '';
      kids.push(el('div', { class: 'tw-row tw-acts' }, line ? el('span', { class: 'tw-ls' }, line) : null,
        el('a', { class: 'btn small', href: '#/new' }, icon('inbox', 15), 'Открыть «Новое»'),
        // Вся сводка, куда разнесена в CRM, и все прошлые с поиском (digest.js, 14.10.2026).
        el('a', { class: 'btn small', href: '#/digest' }, icon('list', 15), 'Все сводки'),
        el('button', { class: 'btn small', onclick: () => navigator.clipboard.writeText(g.text || '').then(() => toast('Сводка скопирована'), fail) }, icon('copy', 15), 'Скопировать')));
    }
    return T.card('tw-dg o4', ...kids);
  }

  /** «Новое» — вопросы автоматики теми же строками, что «Подскажи» в Делах: свои кнопки, П-номера, «да» и «нет». */
  function newCard() {
    const asks = sortedAsks(pendingSugs());
    if (!asks.length) return null;
    return T.card('tw-new o5', T.sec('inbox', 'Новое', { count: asks.length, hot: true, small: 'ждёт твоего да',
      link: asks.length > 4 ? 'все ' + asks.length : 'в Делах', href: '#/new' }),
    el('div', { class: 'group asks tw-asks' }, asks.slice(0, 4).map(askItem)));
  }

  /** «Сегодня» — дела со сроком сегодня (кроме взятых в «Сейчас») и встречи дня по времени; завтрашнее — строкой. */
  function todayCard(d, day) {
    const taken = new Set(nowTasks().map((t) => t.id));
    const tasks = day === S.today ? todayTasks().filter((t) => !taken.has(t.id)) : deeds().filter((t) => isOpen(t) && isMine(t) && t.due_date === day);
    const rowsT = tasks.map((t) => ({ at: t.due_time ? t.due_time.slice(0, 5) : '', t }));
    const rowsM = (d.meetings || []).map((m) => ({ at: msk(Date.parse(m.started_at)).hm, m }));
    const all = [...rowsT, ...rowsM].sort((a, b) => (a.at || '99').localeCompare(b.at || '99'));
    const nm = T.nowMin(day);
    const lines = all.map((x) => x.t
      ? taskLine(x.t, x.at ? el('span', { class: 'tw-when' }, x.at) : null)
      : el('a', { class: 'tw-li tw-meet' + (nm !== null && T.hmMin(x.at) < nm ? ' past' : ''), href: '#/m/' + x.m.id },
        el('span', { class: 'tw-when' }, x.at), icon('users', 16), el('span', { class: 'tw-grow tw-lt' }, x.m.title || 'Встреча'),
        x.m.duration_min ? el('span', { class: 'tw-ls' }, dur(x.m.duration_min)) : null));
    const tm = D.add(day, 1);
    const tomorrow = deeds().filter((t) => isOpen(t) && isMine(t) && t.due_date === tm)
      .sort((a, b) => (a.due_time || '99').localeCompare(b.due_time || '99')).slice(0, 5)
      .map((t) => t.title + (t.due_time ? ' ' + t.due_time.slice(0, 5) : ''));
    return T.card('o6', T.sec('sun', 'Сегодня', { count: all.length || null, small: 'срок и встречи на сегодня', link: 'Дела', href: '#/today' }),
      lines.length ? el('div', { class: 'tw-list' }, lines) : el('div', { class: 'tw-empty' }, 'Сроков и встреч на сегодня нет.'),
      tomorrow.length ? el('div', { class: 'tw-tomorrow' }, 'завтра: ' + tomorrow.join(', ')) : null);
  }

  function draw() {
    const day = T.dayOf('day');
    const p = day === S.today ? {} : { day };
    const d = T.load('day', p);
    const wx = day === S.today ? T.load('pogoda', {}, { maxAge: 10 * 60e3 }) : null;
    if (!d) {
      const err = T.error('day', p);
      return [T.pageHead('Сегодня', T.dayLong(day), T.dayNav('day', day)),
        el('div', { class: 'tw-empty' + (err ? ' err' : ' loading') }, err ? 'Не вышло: ' + err : 'Загружаю день…')];
    }
    const list = T.spans(d.entries, day, d.categories);
    const prof = T.profile(list, day);
    const rows = T.rows(list, day, T.events(d, day, list));
    const sub = [T.dayLong(day), day === S.today ? msk(T.now()).hm : null, 'очки ' + T.points(prof.value)].filter(Boolean).join(' · ');
    // Справа в шапке — значок трат Claude (spend.js, 14.10.2026): $ за 7 дней и метка непрочитанных советов недели.
    const spend = typeof spendBadge === 'function' ? spendBadge() : null;
    return [T.pageHead('Сегодня', sub, [T.dayNav('day', day), spend].filter(Boolean)), T.archiveNote(d), T.freshNote(d),
      el('div', { class: 'tw-cols tw-day' },
        el('div', { class: 'tw-col' }, tiles(d.tiles), nowCard(d, list, prof, day), lentaCard(rows)),
        el('div', { class: 'tw-col' }, weatherCard(wx), nowTasksCard(), digestCard(d.digest), newCard(), todayCard(d, day)))];
  }

  return { title: 'Сегодня', render: draw };
})();
