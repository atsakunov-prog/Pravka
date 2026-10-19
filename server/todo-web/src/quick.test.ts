// Разбор быстрого ввода: node --experimental-strip-types --test src/*.test.ts (npm test). Имена — выдуманные.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseQuick, parseWhen, type Ctx } from './quick.ts';

// Понедельник, 19 октября 2026.
const ctx: Ctx = {
  today: '2026-10-19',
  now: '12:00',
  projects: [
    { id: 'p1', name: 'Альфа Групп' },
    { id: 'p2', name: 'Бета' },
    { id: 'd1', name: 'Альфа: фонды', deal: true, project_id: 'p1' },
  ],
  sections: [{ id: 's1', name: 'Договоры', project_id: 'p2' }],
  labels: ['звонок', 'дорога'],
  people: [{ id: 'h1', name: 'Иван Петров', short: 'Иван' }, { id: 'h2', name: 'Ольга Смирнова', short: 'Ольга' }],
};
const q = (s: string) => parseQuick(s, ctx);

test('срок словами и время', () => {
  assert.deepEqual([q('Позвонить завтра в 15').due_date, q('Позвонить завтра в 15').due_time], ['2026-10-20', '15:00']);
  assert.equal(q('Позвонить завтра в 15').title, 'Позвонить');
  assert.equal(q('Отчёт в пятницу').due_date, '2026-10-23');
  assert.equal(q('Отчёт к среде').due_date, '2026-10-21');
  assert.equal(q('Отчёт до пт').due_date, '2026-10-23');
  assert.equal(q('Сдать в понедельник').due_date, '2026-10-19'); // сегодня понедельник — сегодня
  assert.equal(q('Созвон в следующий вторник').due_date, '2026-10-27');
  assert.equal(q('План на следующей неделе').due_date, '2026-10-26');
  assert.equal(q('Купить дрова на выходных').due_date, '2026-10-24');
  assert.equal(q('Проверить через 3 дня').due_date, '2026-10-22');
  assert.equal(q('Проверить через неделю').due_date, '2026-10-26');
  assert.equal(q('Продлить 25 окт').due_date, '2026-10-25');
  assert.equal(q('Продлить 5 января').due_date, '2027-01-05'); // прошлое без года — следующий год
  assert.equal(q('Продлить 25.10').due_date, '2026-10-25');
  assert.equal(q('Продлить 25.10.2027').due_date, '2027-10-25');
  assert.equal(q('Встреча послезавтра в 9 утра').due_time, '09:00');
  assert.equal(q('Встреча завтра в 7 вечера').due_time, '19:00');
  assert.equal(q('Встреча завтра вечером').due_time, '19:00');
  assert.equal(q('Встреча в 11').due_date, '2026-10-20'); // 11:00 уже прошло — завтра
  assert.equal(q('Встреча в 15:30').due_date, '2026-10-19');
  assert.equal(q('Встреча в 15:30').title, 'Встреча');
  assert.equal(q('Tomorrow at 3pm call').due_time, '15:00');
  assert.equal(q('Купить в 3 банка').due_date, undefined); // «в 3 банка» — не время
  assert.equal(q('Купить в 3 банка').title, 'Купить в 3 банка');
});

test('повторы', () => {
  const a = q('Полить цветы каждые 3 дня');
  assert.deepEqual(a.recur, { every: 'day', n: 3 });
  assert.equal(a.due_rule, 'каждые 3 дня');
  assert.equal(a.due_date, '2026-10-19');
  assert.equal(a.title, 'Полить цветы');
  assert.deepEqual(q('Отчёт каждый пн и чт в 9').recur, { every: 'week', n: 1, days: [1, 4] });
  assert.equal(q('Отчёт каждый пн и чт в 9').due_time, '09:00');
  assert.equal(q('Пробежка каждую среду').due_date, '2026-10-21');
  assert.deepEqual(q('Зарядка каждое утро').recur, { every: 'day', n: 1 });
  assert.equal(q('Зарядка каждое утро').due_time, '09:00');
  assert.deepEqual(q('Стрижка каждые! 3 недели').recur, { every: 'week', n: 3, from: 'done' });
  assert.deepEqual(q('Счета каждое 15 число').recur, { every: 'month', n: 1, monthday: 15 });
  assert.equal(q('Счета каждое 15 число').due_date, '2026-10-19'.slice(0, 8) + '15' > '2026-10-19' ? '2026-10-15' : '2026-11-15');
  assert.deepEqual(q('Отчёт каждый последний день').recur, { every: 'month', n: 1, monthday: -1 });
  assert.equal(q('Отчёт каждый последний день').due_date, '2026-10-31');
  assert.deepEqual(q('Сверка каждый рабочий день').recur, { every: 'workday', n: 1 });
  assert.deepEqual(q('Уборка каждую вторую неделю').recur, { every: 'week', n: 2 });
  assert.deepEqual(q('Налоги ежеквартально').recur, { every: 'month', n: 3 });
  assert.deepEqual(q('Курс каждый день до 30 окт').recur, { every: 'day', n: 1, until: '2026-10-30' });
  assert.deepEqual(q('Gym every mon, wed').recur, { every: 'week', n: 1, days: [1, 3] });
});

test('проект, раздел, метки, человек, приоритет, дедлайн, описание', () => {
  const a = q('Тизер #Альфа Групп @звонок p1 +Иван {25 окт} // две версии');
  assert.equal(a.project_id, 'p1');
  assert.deepEqual(a.labels, ['звонок']);
  assert.equal(a.priority, 1);
  assert.equal(a.person_id, 'h1');
  assert.equal(a.deadline, '2026-10-25');
  assert.equal(a.notes, 'две версии');
  assert.equal(a.title, 'Тизер');
  const d = q('Список фондов #Альфа: фонды завтра');
  assert.deepEqual([d.deal_id, d.project_id, d.due_date, d.title], ['d1', 'p1', '2026-10-20', 'Список фондов']);
  const s = q('Акт /Договоры');
  assert.deepEqual([s.section_id, s.project_id, s.title], ['s1', 'p2', 'Акт']);
  assert.equal(q('Позвонить +Ольге').person_id, undefined); // падеж имени — не узнаём, остаётся в названии
  assert.deepEqual(q('Купить %новая-метка').labels, ['новая-метка']);
  assert.equal(q('Сделать !сейчас').now, true);
  assert.equal(q('Сделать !!2').priority, 2);
  assert.equal(q('Отчёт по p1 задачам').priority, 1); // как у Todoist: p1 в тексте — приоритет
  // срок обходит токен проекта
  const w = q('Созвон завтра #Бета в 15');
  assert.deepEqual([w.project_id, w.due_date, w.due_time, w.title], ['p2', '2026-10-20', '15:00', 'Созвон']);
});

test('поле срока планировщика', () => {
  assert.equal(parseWhen('завтра', ctx)?.due_date, '2026-10-20');
  assert.equal(parseWhen('без срока', ctx)?.clear, true);
  assert.equal(parseWhen('каждый пн', ctx)?.recur?.every, 'week');
  assert.equal(parseWhen('купить хлеб', ctx), null);
});

test('фразы владельца 19.10: завтра, послезавтра, через неделю, в пн, в понедельник', () => {
  const d = (s: string) => q('Отчёт ' + s).due_date;
  assert.equal(d('завтра'), '2026-10-20');
  assert.equal(d('послезавтра'), '2026-10-21');
  assert.equal(d('через неделю'), '2026-10-26');
  assert.equal(d('в пн'), '2026-10-19'); // в понедельник сказано «в пн» — сегодня, как у Todoist
  assert.equal(d('в понедельник'), '2026-10-19');
  assert.equal(d('во вторник'), '2026-10-20');
  assert.equal(d('через 2 недели'), '2026-11-02');
});

// Ожидания — из разбора кода Todoist 19.10 (опись todoist-behavior.md, раздел 3). Где модель повтора фразу не держит —
// фраза остаётся в названии целиком, без срока: не узнать лучше, чем узнать неправильно.
test('восемь ошибок 19.10: теперь верно или остаётся в названии', () => {
  const none = (s: string) => {
    const p = q(s);
    assert.deepEqual([p.due_date, p.recur, p.title], [undefined, undefined, s], s);
  };
  none('Отчёт каждый 3 понедельник'); // у Todoist — 3-й пн месяца; было «каждое 3-е число»
  none('Отчёт каждый последний пн'); // последний пн месяца; было «разово сегодня»
  const a = q('Взносы каждое 2 апреля'); // раз в год с ближайшего 2 апреля: сервер шагает год от даты срока
  assert.deepEqual([a.recur, a.due_date, a.title], [{ every: 'year', n: 1 }, '2027-04-02', 'Взносы']);
  const b = q('Созвон по будням в 10');
  assert.deepEqual([b.recur, b.due_time, b.due_rule, b.title], [{ every: 'workday', n: 1 }, '10:00', 'по будням в 10', 'Созвон']);
  const c = q('Уборка каждые 2 недели по пт');
  assert.deepEqual([c.recur, c.due_date, c.title], [{ every: 'week', n: 2, days: [5] }, '2026-10-23', 'Уборка']);
  const d = q('Купить 3 дня до 8 марта'); // разовая дата со сдвигом
  assert.deepEqual([d.due_date, d.recur, d.title], ['2027-03-05', null, 'Купить']);
  const e = q('Курс каждый день в течение 3 недель');
  assert.deepEqual([e.recur, e.due_date, e.title], [{ every: 'day', n: 1, until: '2026-11-09' }, '2026-10-19', 'Курс']);
  const f = q('Курс каждый день с 5 по 10 ноября');
  assert.deepEqual([f.recur, f.due_date, f.title], [{ every: 'day', n: 1, until: '2026-11-10' }, '2026-11-05', 'Курс']);
});

test('переписывания перед разбором', () => {
  const d = (s: string) => q('Отчёт ' + s).due_date;
  const r = (s: string) => q('Отчёт ' + s).recur;
  // числа словами, «несколько», «пару»
  assert.equal(d('через три дня'), '2026-10-22');
  assert.equal(d('через две недели'), '2026-11-02');
  assert.equal(d('через несколько дней'), '2026-10-22');
  assert.equal(d('через пару дней'), '2026-10-21');
  assert.equal(q('Встреча в два часа').title, 'Встреча в два часа'); // 2:00 или 14:00 — не гадаем
  // будни и дни недели
  assert.deepEqual(r('пн-пт'), { every: 'workday', n: 1 });
  assert.deepEqual(r('с понедельника по пятницу'), { every: 'workday', n: 1 });
  assert.deepEqual(r('в будние дни'), { every: 'workday', n: 1 });
  assert.deepEqual([r('в 10 по будням'), q('Отчёт в 10 по будням').due_time], [{ every: 'workday', n: 1 }, '10:00']);
  assert.deepEqual(r('по понедельникам и четвергам'), { every: 'week', n: 1, days: [1, 4] });
  assert.deepEqual(r('по выходным'), { every: 'week', n: 1, days: [6, 7] });
  assert.deepEqual(r('каждые 2 пятницы'), { every: 'week', n: 2, days: [5] });
  // частота
  assert.deepEqual(r('раз в месяц'), { every: 'month', n: 1 });
  assert.deepEqual(r('раз в две недели'), { every: 'week', n: 2 });
  assert.deepEqual(r('дважды в месяц'), { every: 'day', n: 15 });
  assert.deepEqual(r('после 3 недель'), { every: 'week', n: 3, from: 'done' });
  assert.equal(q('Стрижка после 3 недель').due_rule, 'после 3 недель');
  // начало, середина, конец; когда-нибудь; следующий месяц; число месяца
  assert.equal(d('в начале ноября'), '2026-11-01');
  assert.equal(d('середина ноября'), '2026-11-15');
  assert.equal(d('к середине ноября'), '2026-11-15');
  assert.equal(d('в конце ноября'), '2026-11-30');
  assert.equal(d('в конце февраля'), '2027-02-28');
  assert.equal(d('в конце месяца'), '2026-10-31');
  assert.equal(d('когда-нибудь'), '2026-12-19'); // +2 месяца, как у Todoist
  assert.equal(d('в следующем месяце'), '2026-11-19'); // +1 месяц
  assert.equal(d('25 число'), '2026-10-25');
  assert.equal(d('до 5 числа'), '2026-11-05');
  // праздники
  assert.equal(d('в день победы'), '2027-05-09');
  assert.equal(d('к новому году'), '2027-01-01');
  assert.equal(d('на рождество'), '2027-01-07');
  assert.equal(d('в канун нового года'), '2026-12-31');
  assert.equal(d('ко дню знаний'), '2027-09-01');
  assert.equal(q('Поздравить с новым годом').title, 'Поздравить с новым годом'); // поздравление — не срок
  // часы — от «сейчас» (12:00)
  assert.deepEqual([q('Перезвонить через час').due_date, q('Перезвонить через час').due_time], ['2026-10-19', '13:00']);
  assert.equal(q('Перезвонить через полчаса').due_time, '12:30');
  // подсветка и название — по исходным словам
  const p = q('Созвон по будням в 10 #Бета');
  assert.deepEqual(p.tokens.filter((t) => t.kind === 'date').map((t) => 'Созвон по будням в 10 #Бета'.slice(t.start, t.end)), ['по будням в 10']);
  assert.equal(parseWhen('по будням', ctx)?.recur?.every, 'workday');
  assert.equal(parseWhen('в день победы', ctx)?.due_date, '2027-05-09');
});

test('сознательно не узнаём: N-й и последний день недели, разовый промежуток', () => {
  for (const s of ['Отчёт каждый третий понедельник', 'Отчёт каждый 3-й пн месяца', 'Отчёт в последнюю пятницу',
    'Отчёт каждый последний пн в 10', 'Отпуск с 5 по 10 ноября']) {
    const p = q(s);
    assert.deepEqual([p.due_date, p.due_time, p.title], [undefined, undefined, s], s);
  }
  assert.equal(parseWhen('каждый последний пн', ctx), null);
  // а время рядом с обычным днём недели — по-прежнему время
  assert.deepEqual([q('Созвон в 10 пн').due_date, q('Созвон в 10 пн').due_time], ['2026-10-19', '10:00']);
});

// Слово владельца 19.10: «Следующий X оставляем как в /t: день следующей календарной недели. На выходных в воскресенье —
// как у Todoist, ближайшая суббота».
test('«следующий X» — день следующей календарной недели (не как у Todoist)', () => {
  const thu = { ...ctx, today: '2026-10-22' }, sun = { ...ctx, today: '2026-10-25' };
  assert.equal(parseQuick('Созвон в следующий вторник', thu).due_date, '2026-10-27'); // Todoist: 03.11
  assert.equal(parseQuick('Созвон в следующий понедельник', sun).due_date, '2026-10-26'); // Todoist: 02.11
});

test('«на выходных» — ближайшая суббота: в субботу сегодня, в воскресенье следующая (как у Todoist)', () => {
  const sat = { ...ctx, today: '2026-10-24' }, sun = { ...ctx, today: '2026-10-25' };
  for (const s of ['Дрова на выходных', 'Дрова на этих выходных', 'Дрова выходные']) {
    assert.equal(parseQuick(s, sun).due_date, '2026-10-31', s);
    assert.equal(parseQuick(s, sat).due_date, '2026-10-24', s);
    assert.equal(q(s).due_date, '2026-10-24', s); // в понедельник — эта суббота
  }
});

test('напоминание: !время и !место', () => {
  const c = { ...ctx, places: ['дом', 'Летово'] };
  const a = parseQuick('Отправить счёт завтра !15:00', c);
  assert.deepEqual([a.due_date, a.remind_when, a.title], ['2026-10-20', { date: null, time: '15:00' }, 'Отправить счёт']);
  const b = parseQuick('Позвонить !завтра в 9', c);
  assert.deepEqual(b.remind_when, { date: '2026-10-20', time: '09:00' });
  assert.equal(b.due_date, undefined); // напоминание — не срок
  const h = parseQuick('Повесить полку !дома', c);
  assert.deepEqual([h.remind_place, h.title], ['дом', 'Повесить полку']);
  assert.equal(parseQuick('Сделать !сейчас', c).now, true);
});
