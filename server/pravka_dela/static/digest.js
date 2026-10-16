// «Дайджест» (#/digest — список и поиск, #/digest/<ref> — одна сводка) — 14.10.2026, dela_0018.
//
// Владелец: «дайджесты Телеграма надо разносить по карточкам CRM, а также складывать на сервере». Сводки служба
// дайджеста кладёт в Дела (digest.put), сервер разносит их пункты в хронологию людей и клиентов (digest_crm.py) — запись
// «Telegram, <окно>: …» в карточке ведёт сюда, на саму сводку. Здесь: сводки за дни или поиск по тексту (вид digest:
// days, q — словоформы и подстрока), одна сводка целиком — разделами, как карточка дайджеста на «Сегодня» (day.js) и
// телефона, с «В Дела» и «В карточках CRM» — куда разнесены её пункты (люди, клиенты, сделки — ссылками).
// Только владельцу: дайджест — его личная переписка (правило 21), сервер другим вид не отдаёт, а пункта в колонке и
// маршрута без флага digest и не владельцу нет.
//
// Свой файл, хвостом app.js (api.TAILS), как spend.js: своего адреса нет, список путей сайта-посредника не меняется.
'use strict';

const DIGEST = (() => {
  const DAYS = [[2, '2 дня'], [7, 'неделя'], [14, '2 недели']];
  let days = 7;
  let q = '';
  let timer = null;

  const on = () => !!(S.me && S.me.role === 'owner' && (S.me.features || []).includes('digest'));
  const enc = encodeURIComponent;

  /** «Кто (чат)» — как digest.where_of сервера: чат не повторяем, если он и есть человек. */
  function where(w) {
    const who = w.who || '', chat = w.chat || '';
    if (!chat || who.toLowerCase().includes(chat.toLowerCase()) || (who && chat.toLowerCase().includes(who.toLowerCase()))) return who || chat;
    return who ? `${who} (${chat})` : chat;
  }

  /** Разделы сводки из пунктов: «Ждут тебя» первым, потом «По работе», «Команда», «Дом», «Прочее» — как DigestNote
   *  телефона и Telegram. С 16.10.2026 (этап 6 аудита): у «Ждут тебя» — с какого дня ждёт (since), «Команда» — что команда
   *  пообещала и к какому сроку. «Только тебе» здесь нет — своим блоком ниже (onlyYou), как отдельным сообщением в Telegram. */
  function blocks(g) {
    const s = g.summary || {};
    const out = [];
    if ((s.waiting || []).length) out.push(el('span', { class: 'tw-eyebrow' }, 'Ждут тебя'), ...s.waiting.map((w) => el('p', {}, el('b', {}, where(w)), w.what ? ' ' + w.what : '',
      w.since ? el('span', { class: 'faint' }, ' — ждёт с ' + w.since) : null)));
    if ((s.work || []).length) out.push(el('span', { class: 'tw-eyebrow' }, 'По работе'), ...s.work.map((w) => el('p', {}, w.topic ? el('b', {}, w.topic) : null, w.topic && w.text ? ' ' : '', w.text || '')));
    if ((s.team || []).length) out.push(el('span', { class: 'tw-eyebrow' }, 'Команда'), ...s.team.map((t) => el('p', {}, t.who ? el('b', {}, t.who) : null, t.who && t.what ? ' — ' : '', t.what || '',
      t.due ? el('span', { class: 'faint' }, ', ' + t.due) : null)));
    if ((s.family || []).length) out.push(el('span', { class: 'tw-eyebrow' }, 'Дом'), ...s.family.map((x) => el('p', {}, x)));
    if (s.other) out.push(el('span', { class: 'tw-eyebrow' }, 'Прочее'), el('p', {}, s.other));
    if (!out.length) out.push(el('p', {}, g.text || 'Ничего, что ждёт тебя.'));
    return out;
  }

  /** «В Дела: …» — теми же словами, что под сводкой в Telegram (digest.dela_line). */
  function delaLine(n) {
    if (!n || !Object.keys(n).length) return '';
    const bits = [n.created ? 'поставлено ' + n.created : null, n.asks ? 'вопросов ' + n.asks : null, n.close ? 'закрыть ' + n.close : null,
      n.update ? 'уточнить ' + n.update : null, n.closed ? 'закрыто само ' + n.closed : null, n.updated ? 'уточнено само ' + n.updated : null].filter(Boolean);
    return 'В Дела: ' + (bits.join(', ') || 'новых дел нет') + '.';
  }

  // «Только тебе» (этап 6 аудита, 16.10.2026): выводы о людях и сделках — только владельцу, своим блоком, не в «Скопировать».
  // Неделя в тени: у пункта «Пригодилось», «Мимо» и «Неверно» — insight.rate по строке crm.insights (сервер кладёт туда
  // пункты сводки); через 4 недели мерило (вид lens) скажет, какой вид выключить строкой Свода meetings.lens.
  const KIND = { cooling: 'остывает', unhappy: 'недоволен', pressure: 'давит', signal: 'сигнал продажи', objection: 'возражение',
    conflict: 'несостыковка', reason: 'повод написать' };
  const RATE = [['useful', 'Пригодилось'], ['dismissed', 'Мимо'], ['wrong', 'Неверно']];

  async function rate(ins, to) {
    try { await op1({ op: 'insight.rate', id: ins.id, rate: ins.state === to ? 'new' : to }); crmDirty(); render(); } catch (e) { fail(e); }
  }

  function mineRow(o, ins) {
    const head = [o.who, o.chat && o.chat !== o.who ? '(' + o.chat + ')' : null, o.at].filter(Boolean).join(' ');
    const extra = [o.deal ? 'сделка: ' + o.deal : null, o.service ? 'услуга: ' + o.service : null].filter(Boolean).join(' · ');
    return el('div', { class: 'ins-row' + (ins && ins.state === 'new' ? ' new' : '') },
      el('div', { class: 'tl-when' }, KIND[o.kind] || o.kind || '—'),
      el('div', { class: 'tl-what' },
        el('div', { class: 'tl-head' }, head ? el('b', {}, head) : null, o.quote ? ' «' + o.quote + '»' : ''),
        o.why ? el('div', {}, o.why) : null,
        o.do ? el('div', {}, el('span', { class: 'faint' }, 'Сделать: '), o.do) : null,
        extra ? el('div', { class: 'faint' }, extra) : null,
        ins ? el('div', { class: 'tl-meta' }, RATE.map(([k, word]) => el('button', { class: 'link-btn' + (ins.state === k ? ' on' : ''),
          title: ins.state === k ? 'снять оценку' : null, onclick: () => rate(ins, k) }, (ins.state === k ? '✓ ' : ' ') + word))) : null));
  }

  function onlyYou(g, list) {
    const items = (g.summary || {}).only_you || [];
    if (!items.length) return null;
    const by = new Map((list || []).map((x) => [x.item_key, x]));
    return el('section', { class: 'group panel' }, el('h2', {}, icon('lock', 17), 'Только тебе', el('span', { class: 'n' }, items.length),
      el('span', { class: 'gsub' }, 'выводы о людях и сделках — не для пересылки; оцени: пригодилось или мимо')),
    el('div', { class: 'only-you' }, el('div', { class: 'rows tl-rows' }, items.map((o) => mineRow(o, by.get(o.key))))));
  }

  const counts = (g) => [g.chats ? plural(g.chats, 'чат', 'чата', 'чатов') : null, g.msgs ? plural(g.msgs, 'сообщение', 'сообщения', 'сообщений') : null]
    .filter(Boolean).join(' · ');

  /** Куда сводка разнесена: строка на запись — люди и клиент ссылками на их карточки. */
  function crmBox(list, label) {
    if (!list || !list.length) {
      return el('div', { class: 'faint pad' }, 'По карточкам CRM не разнесена: в ней нет узнанных рабочих людей и клиентов (семья и личные чаты туда не идут).');
    }
    const lead = 'Telegram, ' + label + ': '; // начало записи хронологии (digest_crm._line) — здесь окно и так в шапке
    return el('div', { class: 'rows' }, list.map((x) => {
      // Имена людей сервер отдаёт по алфавиту — ссылкой на карточку того, чьё это имя, а не по порядку person_ids.
      const ppl = (x.people_ids || x.person_ids || []).map((id, k) => el('a', { href: '#/h/' + id }, (x.people || [])[k] || 'человек'));
      const links = [...ppl, x.project_id ? el('a', { href: '#/p/' + x.project_id }, x.project_name || 'клиент') : null,
        x.deal_id ? el('a', { href: '#/d/' + x.deal_id }, x.deal_name || 'сделка') : null].filter(Boolean);
      const sum = x.summary || '';
      const body = sum.startsWith(lead) ? sum.slice(lead.length) : sum;
      return el('div', { class: 'crow' }, el('div', { class: 'main' },
        el('div', { class: 'title' }, links.flatMap((a, i) => (i ? [', ', a] : [a]))),
        el('div', { class: 'next' }, body)));
    }));
  }

  function one(ref) {
    const path = '/api/view/digest?ref=' + enc(ref);
    const v = crmGet(path, 60000);
    const err = crmCache.get(path)?.error;
    const all = el('a', { class: 'btn small', href: '#/digest' }, icon('list', 15), 'Все сводки');
    if (!v) return [head('Дайджест', null, all), el('div', { class: 'body' }, err ? el('div', { class: 'empty' }, 'Не открылась: ' + err) : loading())];
    const g = v.digest;
    const line = delaLine(g.dela);
    const copy = el('button', { class: 'btn small', onclick: () => navigator.clipboard.writeText(g.text || '').then(() => toast('Сводка скопирована'), fail) },
      icon('copy', 15), 'Скопировать');
    return [head('Дайджест · ' + g.label, [counts(g)].filter(Boolean), [copy, all]),
      el('div', { class: 'body' },
        el('section', { class: 'group panel' }, el('h2', {}, icon('digest', 17), 'Сводка', el('span', { class: 'gsub' }, g.label)),
          el('div', { class: 'tw-dgb open dg-one' }, blocks(g)),
          line ? el('div', { class: 'faint pad' }, line, g.dela && g.dela.batch
            ? [' ', el('a', { href: '#/new/' + enc(g.dela.batch) }, 'это «Новое» →')] : null) : null),
        onlyYou(g, v.insights),
        el('section', { class: 'group panel' }, el('h2', {}, icon('users', 17), 'В карточках CRM',
          el('span', { class: 'n' }, (v.crm || []).length || ''), el('span', { class: 'gsub' }, 'видишь только ты')), crmBox(v.crm, g.label)))];
  }

  /** Строка списка: окно, числа, начало сводки — или строки, где нашлось искомое. */
  function row(g) {
    const s = g.summary || {};
    const first = [...(s.waiting || []).map((w) => where(w) + (w.what ? ': ' + w.what : '')), ...(s.work || []).map((w) => (w.topic ? w.topic + ': ' : '') + (w.text || ''))]
      .join(' · ') || g.text || '';
    const end = new Date(g.ended_at);
    const day = msk(end.getTime());
    return el('div', { class: 'crow mrow', onclick: () => go('#/digest/' + enc(g.ref)) },
      el('div', { class: 'mdate' }, el('b', {}, D.ddmm(day.day)), el('span', {}, WD_SHORT[D.wd(day.day)] + ' ' + day.hm)),
      el('div', { class: 'main' },
        el('div', { class: 'title' }, g.label),
        el('div', { class: 'chips' }, [counts(g) ? el('span', {}, counts(g)) : null,
          (s.waiting || []).length ? el('span', { class: 'tag' }, 'ждут ответа: ' + s.waiting.length) : null,
          (s.work || []).length ? el('span', {}, 'по работе: ' + s.work.length) : null,
          (s.only_you || []).length ? el('span', {}, 'только тебе: ' + s.only_you.length) : null].filter(Boolean)),
        g.snippet ? el('div', { class: 'mhits' }, el('div', {}, g.snippet)) : el('div', { class: 'next' }, short(first, 220))));
  }

  function list() {
    const path = q ? `/api/view/digest?q=${enc(q)}&limit=50` : `/api/view/digest?days=${days}&limit=50`;
    const v = crmGet(path, 60000);
    const search = el('input', { id: 'main-search', class: 'inline-search', type: 'search', placeholder: 'Найти в сводках: счёт, Соколов, NDA…', value: q });
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { q = search.value.trim(); render(); }, 400); });
    const seg = el('div', { class: 'seg', role: 'radiogroup', 'aria-label': 'За сколько дней' }, DAYS.map(([d, label]) =>
      el('button', { type: 'button', class: !q && d === days ? 'on' : null, onclick: () => { days = d; q = ''; render(); } }, label)));
    const tb = el('div', { class: 'toolbar mt-top' }, search, seg);
    const sub = ['сводки Telegram — только тебе', 'пункты о рабочих людях и клиентах — и в их карточках'];
    if (!v) return [head('Дайджест', sub), el('div', { class: 'body' }, tb, loading())];
    const items = v.items || [];
    const body = items.length ? el('div', { class: 'group' }, items.map(row))
      : el('div', { class: 'empty' }, q ? 'Не нашлось ни в одной сводке.' : `За ${DAYS.find((x) => x[0] === days)[1]} сводок нет.` +
        (v.last ? ' Последняя — ' + v.last.label + '.' : ''));
    return [head('Дайджест', sub), el('div', { class: 'body' }, tb, body)];
  }

  function page(r) {
    if (!on()) {
      return [head('Дайджест'), el('div', { class: 'body' }, el('div', { class: 'empty' },
        S.me && S.me.role !== 'owner' ? 'Дайджест видит только владелец Дел.' : 'Сервер Дел ещё без дайджеста — его обновят на компе (install-dela.ps1).'))];
    }
    return r.kind === 'digest' && r.id ? one(r.id) : list();
  }

  return { on, page };
})();

/** Страница «Дайджест» (#/digest, #/digest/<ref>) — её зовёт renderMain из app.js. */
function renderDigest(r) { return DIGEST.page(r); }
/** Пункт «Дайджест» в колонке разделов — только владельцу с флагом digest. */
function digestOn() { return DIGEST.on(); }
