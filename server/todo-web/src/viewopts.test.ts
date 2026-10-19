// «Отображение» и Ctrl+K как у Todoist (опись поведения Todoist, раздел 11): node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { matchCommands } from './commands.ts';
import type { Task } from './types.ts';
import { filterTasks, viewLabel } from './viewopts.ts';

test('подпись кнопки «Отображение»: одна сортировка, одна группировка, больше — «Свой вид»', () => {
  assert.deepEqual(viewLabel({}), { text: 'Отображение', n: 0 });
  assert.deepEqual(viewLabel({ sort: 'manual', group: 'none', fdue: 'all' }), { text: 'Отображение', n: 0 }); // умолчания не считаются
  assert.deepEqual(viewLabel({ sort: 'priority' }), { text: 'Отсортировано по приоритету', n: 1 });
  assert.deepEqual(viewLabel({ sort: 'name' }), { text: 'Отсортировано по алфавиту', n: 1 });
  assert.deepEqual(viewLabel({ group: 'date' }), { text: 'Сгруппировано по сроку', n: 1 });
  assert.deepEqual(viewLabel({ group: 'label', sort: 'date' }), { text: 'Свой вид', n: 2 });
  assert.deepEqual(viewLabel({ fprio: '1' }), { text: 'Свой вид', n: 1 });
  assert.deepEqual(viewLabel({ layout: 'board', done: true, flabel: 'звонок' }), { text: 'Свой вид', n: 3 });
});

test('фильтр вида: срок, дедлайн, приоритет, метка', () => {
  const T = (id: string, p: Partial<Task>): Task => ({ id, title: id, due_date: null, deadline: null, priority: 4, labels: [], ...p } as Task);
  const MON = '2026-10-19';
  const ts = [
    T('сегодня', { due_date: MON, priority: 1, labels: ['звонок'] }),
    T('вчера', { due_date: '2026-10-18', deadline: '2026-10-22' }),
    T('сб', { due_date: '2026-10-24', priority: 2 }),
    T('пн-след', { due_date: '2026-10-26', deadline: '2026-11-30' }),
    T('без', {}),
  ];
  const ids = (o: object) => filterTasks(ts, o, MON).map((t) => t.id);
  assert.deepEqual(ids({}), ['сегодня', 'вчера', 'сб', 'пн-след', 'без']);
  assert.deepEqual(ids({ fdue: 'today' }), ['сегодня']);
  assert.deepEqual(ids({ fdue: 'over' }), ['вчера']);
  assert.deepEqual(ids({ fdue: 'week' }), ['сегодня', 'вчера', 'сб']); // до воскресенья, просроченное тоже
  assert.deepEqual(ids({ fdue: 'next7' }), ['сегодня', 'сб']);
  assert.deepEqual(ids({ fdue: 'none' }), ['без']);
  assert.deepEqual(ids({ fdl: 'has' }), ['вчера', 'пн-след']);
  assert.deepEqual(ids({ fdl: 'next7' }), ['вчера']);
  assert.deepEqual(ids({ fdl: 'none' }), ['сегодня', 'сб', 'без']);
  assert.deepEqual(ids({ fprio: '2' }), ['сб']);
  assert.deepEqual(ids({ fprio: '4' }), ['вчера', 'пн-след', 'без']);
  assert.deepEqual(ids({ flabel: 'звонок' }), ['сегодня']);
  assert.deepEqual(ids({ fdue: 'week', fprio: '1' }), ['сегодня']);
});

test('Ctrl+K: команды по началам слов, у переходов — клавиши', () => {
  const ids = (q: string) => matchCommands(q).map((c) => c.id);
  assert.ok(matchCommands('').length >= 10);
  assert.deepEqual(ids('сегод'), ['go:today']);
  assert.deepEqual(ids('доб пр'), ['add:project']);
  assert.deepEqual(ids('Входящие'), ['go:inbox']);
  assert.deepEqual(ids('панель'), ['view:side']);
  assert.deepEqual(ids('голос'), ['add:dictate']);
  assert.deepEqual(ids('ёлка'), []);
  assert.equal(matchCommands('сегодня')[0].keys, 'G T');
});
