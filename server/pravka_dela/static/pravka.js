// ── «Правка» (#/pravka) — двойник экрана «Правка» телефона (PravkaHistory.kt, план redesign-5 §4) ─────────────────
// История всех диктовок — телефона (и гарнитуры) и компьютера («Диктовка» на ПК), одна на сервере: архив, виды
// pravka.take и pravka.clean. Склейка тейка с его чисткой — на сервере (twins.pair, как core/Dictations.kt). Сверху —
// итог дня, фильтр «Все · Телефон · Компьютер» и поиск (по сказанному и записавшемуся, ё = е); список — днями, свежие
// сверху; выбранная — целиком справа: «Сказал», «Записалось», чем распознано, чем и за сколько почищено,
// «Скопировать». Узкое окно — столбиком: выбранная раскрывается под своей строкой. «Слушать», «Разобрать заново» и
// «В словарь» — у телефона: звук тейка живёт на нём.
TWINS.pages.pravka = (() => {
  const T = TWINS;
  const ui = { filter: LS.get('twPravkaFilter', 'all'), q: '', sel: null, days: 7 };
  const FILTERS = [['all', 'Все'], ['phone', 'Телефон'], ['pc', 'Компьютер']];
  const fold = (s) => String(s || '').toLowerCase().replace(/ё/g, 'е');

  function shown(items) {
    const q = fold(ui.q.trim());
    return items.filter((i) => (ui.filter === 'all' || (ui.filter === 'pc' ? i.origin === 'pc' : i.origin !== 'pc'))
      && (!q || fold(i.said).includes(q) || fold(i.wrote).includes(q)));
  }
  /** Итог дня тремя числами: диктовок, голоса, не распознано. */
  function stats(items) {
    const today = items.filter((i) => i.day === S.today);
    const ms = today.reduce((a, i) => a + (i.audio_ms || 0), 0);
    const miss = today.filter((i) => !i.ok).length;
    return { n: today.length, min: ms / 60000, miss };
  }
  const deviceIcon = (i) => (i.origin === 'pc' ? 'monitor' : i.origin === 'headset' ? 'headphones' : 'phone');
  /** Подпись строки: «Shokz · 15 с · Сонет $0,02», у компа — «в буфере», «отменено». */
  function meta(i) {
    const out = [i.origin === 'headset' ? i.device : i.origin === 'pc' ? 'компьютер' : 'телефон'];
    if (i.length) out.push(i.length);
    if (!i.ok) out.push(i.error || 'не распознано');
    else {
      if (i.clean_model) out.push([i.clean_model_label.split(' ')[0], i.clean_usd_label].filter(Boolean).join(' '));
      if (i.status === 'clipboard') out.push('в буфере');
      if (i.status === 'cancelled') out.push('отменено');
    }
    return out.join(' · ');
  }
  /** «Сонет 5.5 · $0,011 · 2,1 с» — или почему не чистилось. */
  function cleanLine(i) {
    if (!i.clean_model) return i.clean_error ? 'не вышло: ' + i.clean_error : i.origin === 'pc' && i.ok ? 'Сонет выключен — только словарь' : 'не чистилось';
    return [i.clean_model_label, i.clean_usd_label, i.clean_ms ? (i.clean_ms / 1000).toFixed(1).replace('.', ',') + ' с' : null,
      i.clean_error ? 'ошибка: ' + i.clean_error : null].filter(Boolean).join(' · ');
  }
  const copy = (text) => navigator.clipboard.writeText(text || '').then(() => toast('Скопировано'), fail);

  function detail(i) {
    const when = (i.day === S.today ? 'Сегодня' : D.ddmm(i.day)) + ' ' + i.time + (i.length ? ' · ' + i.length : '');
    return el('div', { class: 'tw-pr-detail' },
      el('div', { class: 'tw-row' }, el('span', { class: 'tw-eyebrow' }, when),
        el('span', { class: 'tw-pill' }, i.origin === 'pc' ? 'компьютер' : i.origin === 'headset' ? 'телефон · ' + i.device : 'телефон')),
      el('div', { class: 'tw-eyebrow tw-mt' }, 'Сказал'),
      el('p', { class: 'tw-said' }, i.said || (i.error ? '— ' + i.error : '—')),
      el('div', { class: 'tw-eyebrow tw-mt' }, 'Записалось'),
      el('p', { class: 'tw-wrote' }, i.wrote || (i.ok ? 'как сказано — без чистки' : '—')),
      el('div', { class: 'tw-sets' },
        el('div', { class: 'tw-set' }, el('span', {}, 'Распознал'), el('span', { class: 'v' }, i.engine_label)),
        el('div', { class: 'tw-set' }, el('span', {}, 'Почистил'), el('span', { class: 'v' }, cleanLine(i))),
        i.status_label ? el('div', { class: 'tw-set' }, el('span', {}, 'Куда ушло'), el('span', { class: 'v' }, i.status_label)) : null),
      el('div', { class: 'tw-row tw-acts' },
        el('button', { class: 'btn small', onclick: () => copy(i.text) }, icon('copy', 15), 'Скопировать'),
        i.wrote && i.said ? el('button', { class: 'btn small', onclick: () => copy(i.said) }, 'Скопировать сказанное') : null),
      el('div', { class: 'tw-ls tw-mt' }, '«Слушать», «Разобрать заново» и «В словарь» — на телефоне: звук тейка живёт там.'));
  }

  function draw() {
    const d = T.load('pravka', { days: ui.days });
    const err = T.error('pravka', { days: ui.days });
    const st = d ? stats(d.items || []) : null;
    const sub = st ? `сегодня ${plural(st.n, 'диктовка', 'диктовки', 'диктовок')} · ${T.num1(st.min)} мин голоса` + (st.miss ? ` · ${st.miss} не распознаны` : '') : 'история диктовок';
    const seg = el('div', { class: 'tw-seg', role: 'tablist' }, FILTERS.map(([k, w]) => el('button', { class: ui.filter === k ? 'on' : '', role: 'tab',
      'aria-selected': ui.filter === k ? 'true' : 'false', onclick: () => { ui.filter = k; LS.set('twPravkaFilter', k); render(); } }, w)));
    const head = T.pageHead('Правка', sub, seg);
    if (!d) return [head, el('div', { class: 'tw-empty' + (err ? ' err' : ' loading') }, err ? 'Не вышло: ' + err : 'Загружаю диктовки…')];
    const items = shown(d.items || []);
    const sel = items.find((i) => i.key === ui.sel) || (NARROW.matches ? null : items[0]) || null;
    const search = el('input', { class: 'tw-in tw-search', id: 'main-search', type: 'search', value: ui.q, placeholder: 'Найти в диктовках…', 'aria-label': 'Найти в диктовках',
      oninput: (e) => { ui.q = e.target.value; render(); } });
    const byDay = new Map();
    for (const i of items) { if (!byDay.has(i.day)) byDay.set(i.day, []); byDay.get(i.day).push(i); }
    const list = [];
    for (const [day, arr] of byDay) {
      const ms = arr.reduce((a, i) => a + (i.audio_ms || 0), 0);
      list.push(el('div', { class: 'tw-prd' }, el('b', {}, day === S.today ? 'Сегодня' : day === D.add(S.today, -1) ? 'Вчера' : D.long(day)),
        el('span', {}, plural(arr.length, 'диктовка', 'диктовки', 'диктовок') + ' · ' + T.num1(ms / 60000) + ' мин')));
      for (const i of arr) {
        const on = sel && sel.key === i.key;
        list.push(el('button', { class: 'tw-pr' + (on ? ' sel' : '') + (i.ok ? '' : ' miss'), type: 'button',
          onclick: () => { ui.sel = on && NARROW.matches ? null : i.key; render(); } },
        el('span', { class: 'tm' }, i.time),
        el('span', { class: 'tw-grow' }, el('span', { class: 'tw-lt' }, i.text || (i.error ? '— ' + i.error : '—')),
          el('span', { class: 'tw-ls' }, icon(deviceIcon(i), 13), meta(i)))));
        if (on && NARROW.matches) list.push(detail(i));
      }
    }
    const more = d.days < 180 ? el('button', { class: 'btn small tw-more', onclick: () => { ui.days = Math.min(180, ui.days + 14); render(); } },
      `Ещё 14 дней (сейчас — ${plural(d.days, 'день', 'дня', 'дней')})`) : null;
    const empty = el('div', { class: 'tw-empty' }, ui.q.trim() ? `Ничего не нашлось по «${ui.q.trim()}» за ${plural(d.days, 'день', 'дня', 'дней')}`
      : ui.filter === 'pc' ? 'С компьютера диктовок пока нет' : 'Диктовок пока нет');
    return [head, T.archiveNote(d),
      el('div', { class: 'tw-cols tw-pr-cols' },
        el('div', { class: 'tw-col' }, T.card('tw-pr-list', search, items.length ? list : empty, more)),
        el('div', { class: 'tw-col tw-pr-side' }, sel && !NARROW.matches ? T.card('tw-pr-card', detail(sel)) : null))];
  }

  return { title: 'Правка', render: draw };
})();
