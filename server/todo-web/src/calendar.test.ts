// Календарь как у Todoist: дни режимов, дела по дням, будущие повторы, блоки по времени — node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { calDays, calItems, dayBlocks, HOUR, nowTop, shiftAnchor } from './calendar.ts';
import type { Task } from './types.ts';

const WED = '2026-10-21';
let n = 0;
const T = (p: Partial<Task>): Task => ({ id: 'id' + (++n), title: 'дело ' + n, status: 'open', due_date: null, due_time: null, estimate_min: null, recur: null, ...p } as Task);

test('дни режимов и стрелки', () => {
  assert.deepEqual(calDays('week', WED), ['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25']);
  assert.deepEqual(calDays('3d', WED), ['2026-10-21', '2026-10-22', '2026-10-23']);
  const m = calDays('month', WED);
  assert.equal(m[0], '2026-09-28'); // октябрь 2026 начинается в чт — неделя с пн 28.09
  assert.equal(m[m.length - 1], '2026-11-01');
  assert.equal(m.length % 7, 0);
  assert.equal(shiftAnchor('week', WED, 1), '2026-10-28');
  assert.equal(shiftAnchor('3d', WED, -1), '2026-10-18');
  assert.equal(shiftAnchor('month', WED, 1), '2026-11-01');
});

test('что в какие дни: весь день, со временем, будущие повторы, сделанное по выбору', () => {
  const days = calDays('week', WED);
  const allDay = T({ due_date: '2026-10-20' });
  const timed = T({ due_date: '2026-10-21', due_time: '15:30:00', estimate_min: 90 });
  const daily = T({ due_date: '2026-10-22', recur: { every: 'day', n: 1 } });
  const done = T({ due_date: '2026-10-19', status: 'done' });
  const outside = T({ due_date: '2026-11-05' });
  const items = calItems([allDay, timed, daily, done, outside], days);
  assert.deepEqual(items.map((x) => [x.task.id, x.day, x.time, x.mins]), [[allDay.id, '2026-10-20', null, 30], [timed.id, '2026-10-21', '15:30', 90], [daily.id, '2026-10-22', null, 30]]);
  const rep = calItems([daily], days, { repeats: true });
  assert.deepEqual(rep.map((x) => [x.day, x.ghost]), [['2026-10-22', false], ['2026-10-23', true], ['2026-10-24', true], ['2026-10-25', true]]);
  const until = calItems([T({ due_date: '2026-10-19', recur: { every: 'day', n: 2, until: '2026-10-22' } })], days, { repeats: true });
  assert.deepEqual(until.map((x) => x.day), ['2026-10-19', '2026-10-21']); // конец повтора соблюдён
  assert.equal(calItems([done], days, { done: true }).length, 1);
});

test('блоки по времени: час 68 пкс, длительность, пересечения — дорожками', () => {
  const it = (time: string, mins: number) => ({ task: T({}), day: WED, time, mins, ghost: false });
  const b = dayBlocks([it('09:00', 60), it('09:30', 30), it('11:00', 0), it('10:00', 30)]);
  const at = (t: string) => b.find((x) => x.item.time === t)!;
  assert.equal(HOUR, 68);
  assert.deepEqual([at('09:00').top, at('09:00').height], [9 * 68, 68]);
  assert.deepEqual([at('09:30').lane, at('09:30').lanes, at('09:00').lanes], [1, 2, 2]); // 9:00–10:00 и 9:30 — рядом
  assert.deepEqual([at('10:00').lane, at('10:00').lanes], [0, 1]); // 10:00 уже отдельно
  assert.equal(at('11:00').height, 17); // меньше 15 мин не бывает
  assert.equal(dayBlocks([{ task: T({}), day: WED, time: null, mins: 30, ghost: false }]).length, 0); // без времени — в «Весь день»
  assert.equal(nowTop('12:30'), 12.5 * 68);
});
