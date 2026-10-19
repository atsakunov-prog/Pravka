// След правки на экране до ответа сервера: поля и новые строки, повтор поверх синка безвреден, повтор дела — новым сроком
// как у сервера — node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyEffects, effectsOf, mskToday, type Maps } from './local.ts';

const maps = (): Maps => ({ tasks: new Map(), sections: new Map(), projects: new Map(), comments: new Map() });
const ctx = { user: 'sasha', today: '2026-10-19', now: '2026-10-19T09:00:00.000Z' };

test('новое дело, правка, выполнение — сразу на экране; повтор следа безвреден', () => {
  const m = maps();
  const create = effectsOf({ op: 'task.create', task: { id: 'n1', title: 'Купить хлеб', due_date: '2026-10-19' } }, m, ctx);
  applyEffects(m, create.fx);
  assert.equal(m.tasks.get('n1').title, 'Купить хлеб');
  assert.equal(m.tasks.get('n1')._pending, true);
  const set = effectsOf({ op: 'task.set', id: 'n1', set: { priority: 1 } }, m, ctx);
  applyEffects(m, set.fx);
  const done = effectsOf({ op: 'task.done', id: 'n1' }, m, ctx);
  applyEffects(m, done.fx);
  assert.deepEqual([m.tasks.get('n1').priority, m.tasks.get('n1').status], [1, 'done']);
  // Синк прислал строку сервера (без правок, что ещё не ушли) — след наложился снова, строка сервера не затёрта «новой»
  m.tasks.set('n1', { id: 'n1', num: 42, title: 'Купить хлеб', priority: 4, status: 'open' });
  for (const e of [create, set, done]) applyEffects(m, e.fx);
  assert.deepEqual([m.tasks.get('n1').num, m.tasks.get('n1').priority, m.tasks.get('n1').status], [42, 1, 'done']);
});

test('выполнение повтора без сети — новый срок, как посчитает сервер; конец повтора — дело закрыто', () => {
  const m = maps();
  m.tasks.set('r', { id: 'r', title: 'Зарядка', status: 'open', due_date: '2026-10-19', recur: { every: 'day', n: 1 }, done_count: 3,
    focus_on: '2026-10-19', remind_at: '2026-10-19T05:00:00.000Z' });
  const e = effectsOf({ op: 'task.done', id: 'r' }, m, ctx);
  assert.deepEqual(e.extra, { recurred: '2026-10-20' });
  applyEffects(m, e.fx);
  const t = m.tasks.get('r');
  assert.deepEqual([t.status, t.due_date, t.done_count, t.focus_on, t.remind_at], ['open', '2026-10-20', 4, null, '2026-10-20T05:00:00.000Z']);
  applyEffects(m, e.fx); // второй раз поверх синка — тот же срок, не следующий
  assert.equal(m.tasks.get('r').due_date, '2026-10-20');
  m.tasks.set('u', { id: 'u', title: 'До 19-го', status: 'open', due_date: '2026-10-19', recur: { every: 'day', until: '2026-10-19' } });
  const end = effectsOf({ op: 'task.done', id: 'u' }, m, ctx);
  applyEffects(m, end.fx);
  assert.equal(m.tasks.get('u').status, 'done');
  const forever = effectsOf({ op: 'task.done', id: 'r', forever: true }, m, ctx);
  applyEffects(m, forever.fx);
  assert.equal(m.tasks.get('r').status, 'done');
});

test('разделы, проекты, комментарии — свои uuid, видны сразу', () => {
  const m = maps();
  applyEffects(m, effectsOf({ op: 'project.create', data: { id: 'p', name: 'Дача', kind: 'personal' } }, m, ctx).fx);
  applyEffects(m, effectsOf({ op: 'section.create', data: { id: 's', project_id: 'p', name: 'Весна' } }, m, ctx).fx);
  applyEffects(m, effectsOf({ op: 'section.set', id: 's', set: { name: 'Осень' } }, m, ctx).fx);
  applyEffects(m, effectsOf({ op: 'comment.add', comment: { id: 'c', task_id: 't', text: 'созвонились' } }, m, ctx).fx);
  assert.deepEqual([m.projects.get('p').name, m.sections.get('s').name, m.comments.get('c').body, m.comments.get('c').author_id],
    ['Дача', 'Осень', 'созвонились', 'sasha']);
  assert.deepEqual(effectsOf({ op: 'task.set', id: 'нет', set: { title: 'x' } }, m, ctx).fx, []); // нет строки — нечего менять
  assert.deepEqual(effectsOf({ op: 'capture.say', text: 'купить' }, m, ctx).fx, []);
});

test('сегодня по Москве с часов устройства', () => {
  assert.equal(mskToday(Date.parse('2026-10-19T20:59:00Z')), '2026-10-19');
  assert.equal(mskToday(Date.parse('2026-10-19T21:01:00Z')), '2026-10-20');
});
