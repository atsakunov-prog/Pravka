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
