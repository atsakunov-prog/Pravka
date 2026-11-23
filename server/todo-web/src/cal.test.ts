// Календарь Google рядом с делами (calev.ts): что в какой день, кусок на сетке, подписи времени, ближайшая встреча.
// node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { eventsOn, nextEvent, phase, spanOn, timeWords, twin, type CalEvent as Ev } from './calev.ts';

const ev = (id: string, p: Partial<Ev>): Ev => ({
  id, title: id, all_day: false, day: '2026-11-23', last_day: '2026-11-23', start: null, end: null, end_day: null, mins: null,
  location: '', link: '', url: '', task_id: null, busy: true, ...p,
});
const timed = (id: string, day: string, start: string, end: string, endDay = day, lastDay = endDay) =>
  ev(id, { day, last_day: lastDay, start, end, end_day: endDay });

test('день: «весь день» сверху, потом по началу; близнец открытого дела не рисуется', () => {
  const tasks = new Map([['t1', { status: 'open' }], ['t2', { status: 'done' }]]);
  const list = [
    timed('b', '2026-11-23', '15:00', '15:45'),
    timed('a', '2026-11-23', '09:30', '10:00'),
    ev('all', { all_day: true, day: '2026-11-22', last_day: '2026-11-24' }),
    { ...timed('twin', '2026-11-23', '12:00', '12:30'), task_id: 't1' },
    { ...timed('was', '2026-11-23', '13:00', '13:30'), task_id: 't2' }, // дело закрыто — событие видно
    timed('tomorrow', '2026-11-24', '09:00', '10:00'),
  ];
  assert.deepEqual(eventsOn('2026-11-23', list, tasks).map((e) => e.id), ['all', 'a', 'was', 'b']);
  assert.ok(twin(list[3], tasks) && !twin(list[4], tasks));
});

test('кусок на сетке: до полуночи — тот же день, через ночь — два куска, середина — «весь день»', () => {
  const late = timed('late', '2026-11-23', '23:00', '00:00', '2026-11-24', '2026-11-23');
  assert.deepEqual(spanOn(late, '2026-11-23'), { from: 1380, to: 1440 });
  assert.equal(spanOn(late, '2026-11-24'), null);
  assert.equal(timeWords(late, '2026-11-23'), '23:00–00:00');
  const night = timed('night', '2026-11-23', '22:00', '02:00', '2026-11-24');
  assert.deepEqual(spanOn(night, '2026-11-23'), { from: 1320, to: 1440 });
  assert.deepEqual(spanOn(night, '2026-11-24'), { from: 0, to: 120 });
  assert.equal(timeWords(night, '2026-11-23'), 'с 22:00');
  assert.equal(timeWords(night, '2026-11-24'), 'до 02:00');
  const trip = timed('trip', '2026-11-23', '10:00', '18:00', '2026-11-26');
  assert.equal(spanOn(trip, '2026-11-24'), null);
  assert.equal(timeWords(trip, '2026-11-25'), 'весь день');
  assert.equal(timeWords(timed('x', '2026-11-23', '15:00', '15:45'), '2026-11-23'), '15:00–15:45');
  assert.equal(timeWords(ev('a', { all_day: true }), '2026-11-23'), 'весь день');
});

test('сейчас: прошло, идёт, впереди; ближайшая встреча — только занятая и в пределах трёх часов', () => {
  const list = [timed('m', '2026-11-23', '10:00', '11:00'), { ...timed('free', '2026-11-23', '12:00', '13:00'), busy: false },
    timed('call', '2026-11-23', '14:00', '14:30'), timed('eve', '2026-11-23', '19:00', '20:00')];
  assert.equal(phase(list[0], '2026-11-23', '2026-11-23', '11:00'), 'past');
  assert.equal(phase(list[0], '2026-11-23', '2026-11-23', '10:15'), 'live');
  assert.equal(phase(list[2], '2026-11-23', '2026-11-23', '10:15'), 'next');
  assert.deepEqual(nextEvent('2026-11-23', '10:20', 180, list)?.e.id, 'm');
  const n = nextEvent('2026-11-23', '11:30', 180, list);
  assert.equal(n?.e.id, 'call');
  assert.equal(n?.inMin, 150);
  assert.equal(nextEvent('2026-11-23', '15:00', 180, list), null); // до вечерней — четыре часа
});
