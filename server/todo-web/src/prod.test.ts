// «Продуктивность»: цели, серии по дням и неделям, выходные и отпуск — node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { prodSummary, type ProdDay } from './prod.ts';

const MON = '2026-10-19';
const D = (day: string, n: number, pid = 'p1'): ProdDay => ({ day, by_project: { [pid]: n } });

test('серия по дням: цель 5, сб и вс не рвут, сегодня ещё не вечер', () => {
  // чт 15, пт 16 — по 5; сб, вс — пусто (выходные); пн-сегодня — 2 из 5
  const s = prodSummary([D('2026-10-15', 5), D('2026-10-16', 6), D(MON, 2)], MON);
  assert.deepEqual(s.dayStreak, { current: 2, best: 2 });
  assert.deepEqual(s.today, { done: 2, goal: 5 });
  // ср 14 — 1 из 5 — рвёт
  assert.deepEqual(prodSummary([D('2026-10-13', 5), D('2026-10-14', 1), D('2026-10-15', 5)], '2026-10-15').dayStreak, { current: 1, best: 1 });
  // без выходных суббота рвёт
  assert.deepEqual(prodSummary([D('2026-10-16', 5), D(MON, 5)], MON, { days_off: [] }).dayStreak, { current: 1, best: 1 });
  // сделал цель в выходной — засчитывается
  assert.deepEqual(prodSummary([D('2026-10-16', 5), D('2026-10-17', 5), D(MON, 5)], MON).dayStreak, { current: 3, best: 3 });
});

test('отпуск: пропуски после последнего выполненного дня серию не рвут', () => {
  const days = [D('2026-10-12', 5), D('2026-10-13', 5)]; // пн, вт прошлой недели, дальше пусто
  assert.equal(prodSummary(days, MON).dayStreak.current, 0);
  assert.deepEqual(prodSummary(days, MON, { vacation: true }).dayStreak, { current: 2, best: 2 });
});

test('серия по неделям, своя цель, неделя ещё идёт', () => {
  const days = [D('2026-10-05', 10), D('2026-10-07', 10), D('2026-10-12', 12), D(MON, 3)];
  const s = prodSummary(days, MON, { weekly: 20 });
  assert.deepEqual(s.week, { done: 3, goal: 20 });
  assert.deepEqual(s.weekStreak, { current: 0, best: 1 }); // 05.10 — 20 есть; 12.10 — 12 из 20 рвёт; эта неделя ещё идёт
  assert.deepEqual(prodSummary(days, MON, { weekly: 12 }).weekStreak, { current: 2, best: 2 });
});

test('столбики кусками проектов, всего и с какого дня', () => {
  const s = prodSummary([{ day: '2026-10-10', by_project: { p1: 1 } }, { day: MON, by_project: { p1: 1, '': 3 } }], MON);
  assert.equal(s.last7.length, 7);
  assert.deepEqual(s.last7[6], { day: MON, parts: [['', 3], ['p1', 1]] });
  assert.deepEqual(s.last4w.map((w) => w.week), ['2026-09-28', '2026-10-05', '2026-10-12', MON]);
  assert.deepEqual(s.last4w[1].parts, [['p1', 1]]);
  assert.deepEqual([s.total, s.since], [5, '2026-10-10']);
  assert.deepEqual(prodSummary([], MON).dayStreak, { current: 0, best: 0 });
});
