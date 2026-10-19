// «Статус проекта»: правило статуса, прогресс, неделя — node --experimental-strip-types --test (npm test).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { projStatus } from './projstatus.ts';
import type { Task } from './types.ts';

const MON = '2026-10-19';
let n = 0;
const T = (p: Partial<Task>): Task => ({ id: 'id' + (++n), title: 'дело ' + n, status: 'open', due_date: null, deadline: null, completed_at: null, kind: 'task', ...p } as Task);
const done = (iso: string) => T({ status: 'done', completed_at: iso });

test('статус: критично / под угрозой / отлично / по плану', () => {
  const over = (k: number) => Array.from({ length: k }, () => T({ due_date: '2026-10-15' }));
  assert.equal(projStatus(over(3), MON).level, 'critical');
  assert.equal(projStatus([T({ deadline: '2026-10-18' })], MON).level, 'critical'); // дедлайн прошёл
  assert.equal(projStatus(over(2), MON).level, 'at_risk');
  assert.equal(projStatus([T({ deadline: '2026-10-20' })], MON).level, 'at_risk'); // дедлайн завтра
  assert.equal(projStatus([T({ due_date: '2026-10-25' }), done('2026-10-19T08:00:00Z')], MON).level, 'great');
  assert.equal(projStatus([T({ due_date: '2026-10-25' }), done('2026-10-12T08:00:00Z')], MON).level, 'on_track'); // сделано не на этой неделе
  const st = projStatus([...over(2), T({ deadline: '2026-10-20' }), T({})], MON);
  assert.equal(st.spoilers.length, 3); // что портит: просроченные и горящий дедлайн
});

test('прогресс и неделя по дням (по Москве), отменённое не считается', () => {
  const st = projStatus([
    done('2026-10-19T06:00:00Z'), done('2026-10-19T20:30:00Z'), // пн; 23:30 по Москве — тоже пн
    done('2026-10-20T21:30:00Z'), // 00:30 ср по Москве
    done('2026-10-10T10:00:00Z'), T({}), T({ status: 'cancelled' }),
  ], MON);
  assert.deepEqual(st.progress, { done: 4, total: 5, pct: 80 });
  assert.deepEqual(st.week.map((w) => w.n), [2, 0, 1, 0, 0, 0, 0]);
  assert.equal(st.weekDone, 3);
});
