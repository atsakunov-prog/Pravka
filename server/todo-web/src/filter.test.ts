// Язык фильтров: npm test. Имена — выдуманные.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compile, runFilter, splitLists, type FilterCtx } from './filter.ts';
import type { Task } from './types.ts';

const T = (title: string, x: Partial<Task> = {}): Task => ({ id: title, num: 1, title, notes: null, project_id: null, deal_id: null,
  owner_id: 'sasha', ball: 'mine', person_id: null, requested_by: null, due_date: null, due_time: null, estimate_min: null,
  focus_on: null, labels: [], status: 'open', source: 'web', source_ref: null, created_by: 'sasha', created_at: '2026-10-01T00:00:00Z',
  updated_at: '', completed_at: null, remind_at: null, remind_place: null, pos: null, priority: 4, ...x });

const tasks = [
  T('тизер', { due_date: '2026-10-19', priority: 1, project_id: 'p1', labels: ['звонок'] }),
  T('акт', { due_date: '2026-10-10', project_id: 'p2', ball: 'waiting', person_id: 'h1' }),
  T('почта', { labels: ['компьютер'] }),
  T('отчёт', { due_date: '2026-10-22', deal_id: 'd1', project_id: 'p1', recur: { every: 'week' } }),
  T('магний', { focus_on: '2026-10-19', deadline: '2026-10-19' }),
];
const ctx: FilterCtx = {
  today: '2026-10-19', projects: [], sections: [], labels: [], people: [], me: 'sasha',
  projectOf: (name, deep) => (name.toLowerCase().startsWith('альфа') ? (t) => t.project_id === 'p1' && (deep || !t.deal_id) : null),
  sectionOf: () => null,
  personOf: (n) => (n.toLowerCase() === 'иван' ? (t) => t.person_id === 'h1' : null),
};
const run = (q: string) => tasks.filter(compile(q, ctx)).map((t) => t.title);

test('слова и операторы', () => {
  assert.deepEqual(run('today'), ['тизер', 'магний']); // дедлайн сегодня — тоже «сегодня», как у Todoist
  assert.deepEqual(run('просрочено'), ['акт']);
  assert.deepEqual(run('(сегодня | просрочено) & p1'), ['тизер']);
  assert.deepEqual(run('без даты & !@компьютер'), ['магний']);
  assert.deepEqual(run('@звон*'), ['тизер']);
  assert.deepEqual(run('#Альфа'), ['тизер']);
  assert.deepEqual(run('##Альфа'), ['тизер', 'отчёт']);
  assert.deepEqual(run('7 дней'), ['тизер', 'отчёт']);
  assert.deepEqual(run('повторяющиеся'), ['отчёт']);
  assert.deepEqual(run('жду & +Иван'), ['акт']);
  assert.deepEqual(run('сейчас'), ['магний']);
  assert.deepEqual(run('срок до: 20 окт'), ['тизер', 'акт']);
  assert.deepEqual(run('поиск: тиз'), ['тизер']);
});

test('списки через запятую и ошибки', () => {
  assert.deepEqual(splitLists('сегодня, (p1 | p2), без даты'), ['сегодня', '(p1 | p2)', 'без даты']);
  assert.deepEqual(runFilter('просрочено, p1', tasks, ctx).map((b) => b.tasks.map((t) => t.title)), [['акт'], ['тизер']]);
  assert.throws(() => compile('(сегодня', ctx), /скобка/);
  assert.throws(() => compile('#Нет такого', ctx), /нет проекта/);
  assert.throws(() => compile('абракадабра', ctx), /не понял/);
});
