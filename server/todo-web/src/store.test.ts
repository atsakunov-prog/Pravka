// Страница без сети целиком (store.ts поверх очереди и догона): правки без сети — на экране сразу и в очереди; сеть
// появилась — ушли по разу, догон «после seq» принёс чужие правки, шапка погасла; потерянный ответ не задваивает.
// Сервер — подделка в памяти с теми же правилами (op_id один раз, синк по seq) — node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';

const srv = {
  net: true,
  loseReply: false,
  seq: 10,
  num: 100,
  tasks: new Map<string, any>([
    ['t1', { id: 't1', num: 1, title: 'Отчёт клиенту', status: 'open', priority: 4, seq: 5 }],
    ['t2', { id: 't2', num: 2, title: 'Зарядка', status: 'open', priority: 4, seq: 6 }],
    ['t3', { id: 't3', num: 3, title: 'Позвонить', status: 'open', priority: 4, seq: 7 }],
  ]),
  seen: new Map<string, any>(),
  posted: 0,
  creates: 0,
};
const json = (d: unknown) => new Response(JSON.stringify(d), { status: 200, headers: { 'content-type': 'application/json' } });
function apply(o: any): any {
  const row = (id: string, patch: Record<string, unknown>) => {
    const t = { ...srv.tasks.get(id), ...patch, seq: ++srv.seq };
    srv.tasks.set(id, t);
    return { op_id: o.op_id, ok: true, task: t };
  };
  if (o.op === 'task.create') {
    srv.creates++;
    return row(o.task.id, { ...o.task, num: ++srv.num, status: 'open', priority: o.task.priority ?? 4 });
  }
  if (!srv.tasks.has(o.id)) return { op_id: o.op_id, ok: false, error: 'нет такого дела или оно не видно' };
  if (o.op === 'task.set') return row(o.id, o.set);
  if (o.op === 'task.done') return row(o.id, { status: 'done', completed_at: '2026-10-19T10:00:00Z' });
  return { op_id: o.op_id, ok: false, error: 'не знаю ' + o.op };
}
(globalThis as any).fetch = async (url: string, opt: RequestInit = {}) => {
  if (!srv.net) throw new TypeError('Failed to fetch');
  const u = new URL(url, 'http://dela.test');
  if (u.pathname === '/api/me') return json({ ok: true, user: 'sasha', name: 'Саша', role: 'owner', settings: {} });
  if (u.pathname === '/api/sync') {
    const since = Number(u.searchParams.get('since'));
    return json({ ok: true, full: since <= 0, tasks: [...srv.tasks.values()].filter((t) => t.seq > since), labels: [], seq: srv.seq,
      today: '2026-10-19', features: ['todo'] });
  }
  if (u.pathname === '/api/ops') {
    const results = JSON.parse(String(opt.body)).ops.map((o: any) => {
      srv.posted++;
      if (!srv.seen.has(o.op_id)) srv.seen.set(o.op_id, apply(o));
      return srv.seen.get(o.op_id);
    });
    if (srv.loseReply) {
      srv.loseReply = false;
      throw new TypeError('Failed to fetch'); // правки легли, ответ потерялся
    }
    return json({ ok: true, results });
  }
  return new Response('{}', { status: 404 });
};

const { boot, ops, refresh, S } = await import('./store.ts');

test('без сети → правки → сеть: ушли по разу, догон принёс чужое, ничего не задвоилось', async () => {
  assert.equal(await boot(), '');
  assert.deepEqual([S.ready, S.tasks.size, S.seq], [true, 3, 10]);

  srv.net = false;
  const [made] = await ops([{ op: 'task.create', task: { id: 'n1', title: 'Купить хлеб' } }]);
  await ops([{ op: 'task.set', id: 't1', set: { priority: 1 } }]);
  await ops([{ op: 'task.done', id: 't2' }]);
  assert.equal(made.queued, true); // ответа нет — правка ждёт, исключения нет
  assert.deepEqual([S.queue, S.offline], [3, true]);
  assert.deepEqual([S.tasks.get('n1')?.title, S.tasks.get('t1')?.priority, S.tasks.get('t2')?.status], ['Купить хлеб', 1, 'done']);
  assert.equal(srv.posted, 0);

  // пока телефона не было в сети, дело поправили с компа
  srv.tasks.set('t3', { ...srv.tasks.get('t3'), title: 'Позвонить Ивану', seq: ++srv.seq });
  await refresh(); // без сети — всё на месте
  assert.deepEqual([S.queue, S.offline, S.tasks.get('t3')?.title], [3, true, 'Позвонить']);

  srv.net = true;
  await refresh();
  assert.deepEqual([S.queue, S.offline], [0, false]);
  assert.deepEqual([srv.creates, srv.posted], [1, 3]);
  assert.equal(S.tasks.get('t3')?.title, 'Позвонить Ивану'); // догон после seq
  const n1 = S.tasks.get('n1')!;
  assert.deepEqual([n1.num, (n1 as any)._pending], [101, undefined]); // строка сервера вместо своей
  assert.deepEqual([S.tasks.get('t1')?.priority, S.tasks.get('t2')?.status], [1, 'done']);
  assert.equal(S.seq, srv.seq);

  await refresh();
  assert.deepEqual([srv.creates, srv.posted], [1, 3]); // повторный проход ничего не шлёт
});

test('ответ потерялся — правка ушла снова тем же op_id, дело одно', async () => {
  srv.loseReply = true;
  const [r] = await ops([{ op: 'task.create', task: { id: 'n2', title: 'Отправить счёт' } }]);
  assert.equal(r.queued, true);
  assert.equal(srv.creates, 2); // сервер уже применил
  assert.equal(S.queue, 1);
  await refresh();
  assert.deepEqual([S.queue, srv.creates], [0, 2]);
  assert.equal([...S.tasks.values()].filter((t) => t.title === 'Отправить счёт').length, 1);
});

test('при сети отказ — как раньше: исключение тому, кто правил, и сервер — правда', async () => {
  await assert.rejects(ops([{ op: 'task.set', id: 'нет-такого', set: { title: 'x' } }], { quiet: true }), /нет такого дела/);
  assert.equal(S.queue, 0);
});
