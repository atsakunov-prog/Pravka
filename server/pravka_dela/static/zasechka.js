// ── «Засечка» (#/zasechka) — двойник экрана Засечки телефона (план redesign-5 §4, холст «Засечка») ───────────────
// Разложенный телефон: слева «Сейчас» крупно с таймером, «Начать» (что и категория), «Стоп», «Поправить»; «Дальше» —
// дела с ▶ (как ZasechkaTasks.shortlist: идущее, «Сейчас», срок сегодня, начатое за неделю); итог дня по категориям.
// Справа — весь день: гора, полоса суток и лента с вечера к утру; отрезок — щелчком «Поправить». Узкое окно —
// столбиком: «Сейчас», «Дальше», весь день, итог.
//
// Запись с компьютера — командой телефону (zasechka.py, docs/zasechka.md «Команды из веба»): правда ленты — телефон,
// архив — её копия, и веб день в архив не пишет (телефон перезатёр бы его своей отправкой). Команда ждёт телефона —
// так и написано; телефон взял, применил тем же путём, что свои кнопки, отправил день — лента в вебе обновится сама.
TWINS.pages.zasechka = (() => {
  const T = TWINS;
  const ui = { title: '', category: '', task: null, edit: null, editTitle: '', editCat: '', editStart: '', editEnd: '', more: false };
  const RECENT_MS = 7 * 86400e3;

  /** Категории ленты (справочник телефона) для выбора — без «не размечено»: это дыра, а не категория. */
  const cats = (d) => (d.categories || []).filter((c) => !T.tone(c.name).gap).map((c) => c.name);
  function catBtn(d, cur, set, label = 'Категория') {
    return choiceBtn(cur || label, (a) => choicePop(a, { title: 'Категория ленты', current: cur || null,
      items: cats(d).map((n) => ({ v: n, title: n, lead: () => el('span', { class: 'sq ' + T.toneClass(n) }) })), onPick: (v) => { set(v || ''); render(); } }),
    { empty: !cur, cls: 'tw-cat' });
  }

  /** «Начать»: чем занят и категория — команда телефону с мигом нажатия (время дела — с него, а не с мига, когда телефон возьмёт). */
  function startForm(d) {
    const input = el('input', { class: 'tw-in', id: 'tw-start', type: 'text', placeholder: 'Чем занят?', value: ui.title, maxlength: 120, 'aria-label': 'Чем занят',
      oninput: (e) => { ui.title = e.target.value; ui.task = null; },
      onkeydown: (e) => { if (e.key === 'Enter') { e.preventDefault(); go1(); } } });
    const go1 = async () => {
      const title = ui.title.trim();
      if (!title) { input.focus(); return; }
      const args = { title, category: ui.category };
      if (ui.task) Object.assign(args, ui.task);
      const ok = await T.command('start', args);
      if (ok) { ui.title = ''; ui.task = null; render(); }
    };
    return el('div', { class: 'tw-form' }, input, catBtn(d, ui.category, (v) => { ui.category = v; }),
      el('button', { class: 'btn main small', onclick: go1 }, icon('play', 15), 'Начать'));
  }

  /** «Сейчас» крупно: что идёт, с какого часа, таймер; «Стоп» и «Поправить» — командами телефону. */
  function nowCard(d, list, day) {
    const today = day === S.today;
    const { open, last } = T.current(list, day);
    const e = today ? open : null;
    const cmds = d.commands || [];
    const stopWait = e && cmds.some((c) => c.kind === 'stop' && ['wait', 'taken'].includes(c.status) && +c.args.entry_id === +e.id);
    const target = e || last;
    const kids = [el('div', { class: 'tw-row' }, el('span', { class: 'tw-eyebrow' }, 'Сейчас'),
      e ? el('span', { class: 'tw-ls tw-r0' }, 'записано ' + ({ голос: 'голосом', текст: 'текстом', руками: 'руками', 'из дела': 'из дела',
        Garmin: 'часами', авто: 'само', автопилот: 'автопилотом', метка: 'меткой' }[T.sourceWord(e.source, e.category, e.raw)] || 'на телефоне')) : null)];
    if (e) {
      kids.push(el('div', { class: 'tw-row tw-nowrow' },
        el('div', { class: 'tw-grow' }, el('div', { class: 'tw-nt big' }, e.title || e.category || 'без названия'),
          el('div', { class: 'tw-ns' }, el('span', { class: 'sq ' + T.toneClass(e.category) }), [e.category, 'с ' + T.hm(e.from)].filter(Boolean).join(' · '))),
        el('div', { class: 'tw-timer big', 'data-tw-start': e.start_at }, T.timer(T.now() - Date.parse(e.start_at)))));
    } else {
      kids.push(el('div', { class: 'tw-nt' }, today ? 'Ничего не идёт' : 'Итог ' + D.ddmm(day)),
        today && last ? el('div', { class: 'tw-ns' }, `последнее — «${last.title || last.category}» до ${T.hm(last.to)}`) : null);
    }
    if (today) {
      kids.push(el('div', { class: 'tw-row tw-acts' },
        e ? el('button', { class: 'btn small', disabled: stopWait, title: 'Остановить — команда телефону', onclick: () => T.command('stop', { entry_id: e.id }) },
          icon('stop', 15), stopWait ? 'Стоп ждёт телефона' : 'Стоп') : null,
        target ? el('button', { class: 'btn small', onclick: () => openEdit(target) }, icon('edit', 15), 'Поправить') : null));
      kids.push(el('div', { class: 'tw-eyebrow tw-mt' }, e ? 'Начать другое' : 'Начать'), startForm(d));
    }
    kids.push(T.commandLines(cmds));
    return T.card('tw-zs-now o1', ...kids);
  }

  // ── «Поправить»: отрезок ленты — название, категория, начало, конец (у идущего — без конца: его ставит «Стоп») ──
  function openEdit(s) {
    ui.edit = { id: s.id, day: s.day, was: { title: s.title, category: s.category, start: s.start_local, end: s.open ? '' : s.end_local || '' }, open: s.open };
    ui.editTitle = s.title; ui.editCat = s.category; ui.editStart = s.start_local || ''; ui.editEnd = s.open ? '' : s.end_local || '';
    render();
    setTimeout(() => document.querySelector('.tw-edit .tw-in')?.focus(), 0);
  }
  function editCard(d) {
    const x = ui.edit;
    if (!x) return null;
    const send = async () => {
      const set = {};
      if (ui.editTitle.trim() && ui.editTitle.trim() !== x.was.title) set.title = ui.editTitle.trim();
      if (ui.editCat !== x.was.category) set.category = ui.editCat;
      if (ui.editStart && ui.editStart !== x.was.start) set.start = ui.editStart;
      if (!x.open && ui.editEnd && ui.editEnd !== x.was.end) set.end = ui.editEnd;
      if (!Object.keys(set).length) { toast('Ничего не поменялось'); return; }
      for (const k of ['start', 'end']) {
        if (!set[k]) continue;
        const m = /^(\d{1,2})[:.](\d{2})$/.exec(set[k]);
        if (!m || +m[1] > 24 || +m[2] > 59) { toast('Время — «ЧЧ:ММ», например 09:30'); return; }
        set[k] = m[1].padStart(2, '0') + ':' + m[2];
      }
      const ok = await T.command('edit', { entry_id: x.id, day: x.day, was: x.was, set });
      if (ok) { ui.edit = null; render(); }
    };
    // Время — строкой «ЧЧ:ММ», а не type=time: тот в браузере с английской системой показывает «12:00 AM».
    const time = (val, set, label) => el('label', { class: 'tw-time' }, el('span', {}, label),
      el('input', { type: 'text', inputmode: 'numeric', placeholder: 'ЧЧ:ММ', maxlength: 5, value: val,
        oninput: (e) => set(e.target.value.trim()) }));
    return T.card('tw-edit o0',
      T.sec('edit', 'Поправить', { small: `${x.was.start}–${x.open ? 'сейчас' : x.was.end} · уйдёт телефону, он поправит у себя` }),
      el('input', { class: 'tw-in', id: 'tw-edit-title', type: 'text', value: ui.editTitle, maxlength: 120, 'aria-label': 'Название', oninput: (e) => { ui.editTitle = e.target.value; },
        onkeydown: (e) => { if (e.key === 'Enter') { e.preventDefault(); send(); } if (e.key === 'Escape') { ui.edit = null; render(); } } }),
      el('div', { class: 'tw-form' }, catBtn(d, ui.editCat, (v) => { ui.editCat = v; }),
        time(ui.editStart, (v) => { ui.editStart = v; }, 'с'), x.open ? el('span', { class: 'tw-ls' }, 'идёт') : time(ui.editEnd, (v) => { ui.editEnd = v; }, 'до')),
      el('div', { class: 'tw-row tw-acts' }, el('button', { class: 'btn main small', onclick: send }, 'Поправить'),
        el('button', { class: 'btn small', onclick: () => { ui.edit = null; render(); } }, 'Отмена')));
  }

  /** «Дальше» — дела, за которые взяться (ZasechkaTasks.shortlist): идущее, «Сейчас», срок сегодня, начатое за неделю. */
  function nextCard(d, list) {
    const stats = d.tasks || {};
    const mine = deeds().filter((t) => isOpen(t) && isMine(t));
    const byId = new Map(mine.map((t) => [t.id, t]));
    const out = new Map();
    const add = (t) => { if (t && !out.has(t.id)) out.set(t.id, t); };
    const running = (list.find((s) => s.open) || {}).task || '';
    add(byId.get(running));
    nowTasks().sort(byTime).forEach(add);
    todayTasks().sort((a, b) => ['owed', 'self', 'check'].indexOf(grpOf(a)) - ['owed', 'self', 'check'].indexOf(grpOf(b)) || byTime(a, b)).forEach(add);
    Object.entries(stats).filter(([, x]) => x.last && T.now() - Date.parse(x.last) < RECENT_MS)
      .sort((a, b) => b[1].last.localeCompare(a[1].last)).forEach(([id]) => add(byId.get(id)));
    const all = [...out.values()];
    const shown = ui.more ? all : all.slice(0, 6);
    const play = async (t) => {
      const cat = (stats[t.id] || {}).category || '';
      const p = project(t.project_id);
      const task = { task_id: t.id, ...(t.project_id ? { project_id: t.project_id } : {}) };
      if (!cat) {
        // Своей категории у дела ещё нет (его не писали в ленту): телефон спросил бы Сонета, веб — владельца.
        ui.title = t.title; ui.task = task; ui.category = '';
        toast('Выбери категорию — и «Начать»');
        render();
        return;
      }
      await T.command('start', { title: t.title, category: cat, client: p && p.kind === 'client' ? p.name : '', ...task });
    };
    const rows = shown.map((t) => {
      const st = stats[t.id];
      const isRun = t.id === running;
      const sub = [isRun ? 'идёт' : null, st && st.min ? 'в ленте ' + dur(st.min) : null, (project(t.project_id) || {}).name || null].filter(Boolean).join(' · ');
      return el('div', { class: 'tw-li' + (isRun ? ' run' : '') },
        el('div', { class: 'tw-grow' }, el('a', { class: 'tw-lt', href: '#/now', onclick: () => { S.cardId = t.id; } }, t.title),
          sub ? el('div', { class: 'tw-ls' }, sub) : null),
        isRun ? el('span', { class: 'tw-tag' }, 'идёт') : el('button', { class: 'ib tw-play', title: 'Взяться — начать в ленте', 'aria-label': 'Начать: ' + t.title, onclick: () => play(t) }, icon('play', 17)));
    });
    return T.card('o2', T.sec('dela', 'Дальше', { small: 'взяться за дело', link: 'Дела', href: '#/now' }),
      rows.length ? el('div', { class: 'tw-list' }, rows) : el('div', { class: 'tw-empty' }, 'Взяться не за что: «Сейчас» и срок сегодня пусты.'),
      all.length > 6 ? el('button', { class: 'link-btn', onclick: () => { ui.more = !ui.more; render(); } }, ui.more ? 'свернуть' : 'ещё ' + (all.length - 6)) : null);
  }

  /** Итог дня по категориям: верхний уровень имени, длинное сверху, полоса от самого длинного. */
  function totalsCard(list, day) {
    const rows = T.totals(list, day);
    if (!rows.length) return null;
    const max = Math.max(...rows.map((r) => r.minutes));
    const nm = T.nowMin(day);
    return T.card('tw-tot o4', T.sec('chart', 'Итог дня', { small: nm !== null ? 'до ' + T.hm(nm) : 'сутки' }),
      el('div', { class: 'tw-crs' }, rows.map((r) => {
        const bar = el('i', { class: r.gap ? 'cg' : T.toneClass(r.category) });
        bar.style.setProperty('width', Math.max(2, Math.round(r.minutes / max * 100)) + '%');
        return el('div', { class: 'tw-cr' }, el('span', { class: 'sq ' + (r.gap ? 'cg' : T.toneClass(r.category)) }),
          el('span', { class: 'n' }, r.label), el('span', { class: 'tw-crbar' }, bar), el('span', { class: 'v' }, dur(Math.round(r.minutes))),
          el('span', { class: 'p' }, T.points(r.points)));
      })));
  }

  function dayCard(list, prof, day, d) {
    const rows = T.rows(list, day, T.events(d, day, list));
    return T.card('tw-allday o3',
      T.sec(null, 'Весь день', { small: 'с вечера к утру · очки ' + T.points(prof.value) + (T.caption(prof) ? ' · ' + T.caption(prof) : '') }),
      T.mountain(prof, { height: 72 }),
      el('div', { class: 'tw-scrollbox', 'data-tw-scroll': 'zs-lenta' }, T.ribbon(rows, { onSeg: day >= D.add(S.today, -1) ? openEdit : null })));
  }

  function draw() {
    const day = T.dayOf('zasechka');
    const p = day === S.today ? {} : { day };
    const d = T.load('zasechka', p);
    if (!d) {
      const err = T.error('zasechka', p);
      return [T.pageHead('Засечка', T.dayLong(day), T.dayNav('zasechka', day)),
        el('div', { class: 'tw-empty' + (err ? ' err' : ' loading') }, err ? 'Не вышло: ' + err : 'Загружаю ленту…')];
    }
    const list = T.spans(d.entries, day, d.categories);
    const prof = T.profile(list, day);
    const sub = [T.dayLong(day), 'очки ' + T.points(prof.value), prof.unmarked ? 'не размечено ' + dur(prof.unmarked) : null].filter(Boolean).join(' · ');
    return [T.pageHead('Засечка', sub, T.dayNav('zasechka', day)), T.archiveNote(d), T.freshNote(d),
      el('div', { class: 'tw-cols tw-zs' },
        el('div', { class: 'tw-col' }, editCard(d), nowCard(d, list, day), nextCard(d, list), totalsCard(list, day)),
        el('div', { class: 'tw-col' }, dayCard(list, prof, day, d)))];
  }

  return { title: 'Засечка', render: draw, openEdit };
})();
