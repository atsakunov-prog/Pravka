// Очередь правок без сети, как у Todoist: без сети копится, с сетью уходит по порядку и не задваивается (op_id), отказ —
// не молча, плохая правка не держит остальные — node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CHUNK, flushOnce, MAX_TRIES, memStore, unreachable, type Queued, type Send } from './outbox.ts';
import type { Op } from './local.ts';

/** Сервер как у Дел: каждую правку применяет один раз (crm.ops_seen), повтор op_id отдаёт прежний ответ. */
function server() {
  const seen = new Map<string, any>();
  const applied: Op[] = [];
  const s = {
    net: true, // есть ли связь
    loseReply: false, // правки применены, а ответ потерялся (обрыв связи)
    status: 0, // ответить ошибкой всей пачке
    refuse: new Set<string>(), // какие правки сервер отклоняет (по op)
    seen,
    applied,
    calls: 0,
    send: (async (ops: Op[]) => {
      s.calls++;
      if (!s.net) throw Object.assign(new Error('нет связи с сервером'), { status: 0 });
      if (s.status) throw Object.assign(new Error('ошибка ' + s.status), { status: s.status });
      const results = ops.map((o) => {
        if (seen.has(o.op_id)) return seen.get(o.op_id);
        const r = s.refuse.has(o.op) ? { op_id: o.op_id, ok: false, error: 'нет такого дела' } : { op_id: o.op_id, ok: true };
        seen.set(o.op_id, r);
        if (r.ok) applied.push(o);
        return r;
      });
      if (s.loseReply) throw Object.assign(new Error('нет связи с сервером'), { status: 0 });
      return { results };
    }) as Send,
  };
  return s;
}

let k = 0;
const q = (op: Op, user = 'sasha'): Queued => ({ user, op_id: `00000000-0000-4000-8000-${String(++k).padStart(12, '0')}`, op, at: '2026-10-19T10:00:00Z', tries: 0 });

test('без сети — ждут; сеть появилась — ушли по порядку, по разу', async () => {
  const box = memStore();
  const srv = server();
  srv.net = false;
  await box.add([q({ op: 'task.create', task: { id: 'a', title: 'Купить хлеб' } }), q({ op: 'task.set', id: 'a', set: { priority: 1 } }), q({ op: 'task.done', id: 'b' })]);
  const off = await flushOnce(box, 'sasha', srv.send);
  assert.equal(off.offline, true);
  assert.equal(off.left, 3);
  assert.equal(srv.applied.length, 0);
  srv.net = true;
  const on = await flushOnce(box, 'sasha', srv.send);
  assert.deepEqual([on.offline, on.left, on.results.size, on.rejected.length], [false, 0, 3, 0]);
  assert.deepEqual(srv.applied.map((o) => o.op), ['task.create', 'task.set', 'task.done']); // порядок — как правили
  const again = await flushOnce(box, 'sasha', srv.send);
  assert.equal(again.results.size, 0);
  assert.equal(srv.applied.length, 3); // ничего не задвоилось
});

test('ответ потерялся — повтор с теми же op_id, сервер не применяет второй раз', async () => {
  const box = memStore();
  const srv = server();
  await box.add([q({ op: 'task.create', task: { id: 'c', title: 'Позвонить' } })]);
  srv.loseReply = true;
  const lost = await flushOnce(box, 'sasha', srv.send);
  assert.deepEqual([lost.offline, lost.left, srv.applied.length], [true, 1, 1]); // применена, но мы этого не знаем
  srv.loseReply = false;
  const back = await flushOnce(box, 'sasha', srv.send);
  assert.deepEqual([back.left, back.results.size, srv.applied.length], [0, 1, 1]); // ушла, а дело одно
});

test('отказ по правке — не молча: в rejected и из очереди вон, остальные встали', async () => {
  const box = memStore();
  const srv = server();
  srv.refuse.add('task.reopen');
  await box.add([q({ op: 'task.set', id: 'a', set: { title: 'x' } }), q({ op: 'task.reopen', id: 'gone' }), q({ op: 'task.done', id: 'a' })]);
  const f = await flushOnce(box, 'sasha', srv.send);
  assert.equal(f.left, 0);
  assert.deepEqual(f.rejected.map((x) => [x.q.op.op, x.r.error]), [['task.reopen', 'нет такого дела']]);
  assert.deepEqual(srv.applied.map((o) => o.op), ['task.set', 'task.done']);
});

test('пачку целиком отклонили (4xx) — по одной: плохая в отказ, хорошие ушли', async () => {
  const box = memStore();
  let calls = 0;
  const send: Send = async (ops) => {
    calls++;
    if (ops.some((o) => o.op === 'huge')) throw Object.assign(new Error('пачка больше 8 МБ — дели на части'), { status: 413 });
    return { results: ops.map((o) => ({ op_id: o.op_id, ok: true })) };
  };
  await box.add([q({ op: 'task.set', id: 'a', set: {} }), q({ op: 'huge' }), q({ op: 'task.set', id: 'b', set: {} })]);
  const f = await flushOnce(box, 'sasha', send);
  assert.equal(f.left, 0);
  assert.deepEqual(f.rejected.map((x) => [x.q.op.op, x.r.error]), [['huge', 'пачка больше 8 МБ — дели на части']]);
  assert.equal([...f.results.values()].filter((x) => x.r.ok).length, 2);
  assert.ok(calls >= 3);
});

test('сервер падает (5xx) — ждём; пять раз подряд на одной правке — отказ ей', async () => {
  const box = memStore();
  const srv = server();
  srv.status = 500;
  await box.add([q({ op: 'task.set', id: 'a', set: {} })]);
  for (let i = 1; i < MAX_TRIES; i++) {
    const f = await flushOnce(box, 'sasha', srv.send);
    assert.deepEqual([f.left, f.rejected.length], [1, 0]);
  }
  const last = await flushOnce(box, 'sasha', srv.send);
  assert.equal(last.left, 0);
  assert.match(String(last.rejected[0].r.error), /сервер падает на этой правке/);
});

test('посредник не достучался до дома (502–504) — это «нет сети», правка ждёт без счёта', async () => {
  assert.deepEqual([0, 502, 503, 504, 500, 400].map(unreachable), [true, true, true, true, false, false]);
  const box = memStore();
  const srv = server();
  srv.status = 502;
  await box.add([q({ op: 'task.set', id: 'a', set: {} })]);
  for (let i = 0; i < MAX_TRIES + 2; i++) assert.equal((await flushOnce(box, 'sasha', srv.send)).offline, true);
  assert.equal((await box.all('sasha'))[0].tries, 0);
});

test('большая очередь — пачками, чужие правки (другой вход) не уходят', async () => {
  const box = memStore();
  const srv = server();
  await box.add(Array.from({ length: CHUNK * 2 + 5 }, (_, i) => q({ op: 'task.set', id: 'a', set: { n: i } })));
  await box.add([q({ op: 'task.set', id: 'z', set: {} }, 'natasha')]);
  const f = await flushOnce(box, 'sasha', srv.send);
  assert.deepEqual([f.left, srv.calls, srv.applied.length], [0, 3, CHUNK * 2 + 5]);
  assert.equal((await box.all('natasha')).length, 1);
});
