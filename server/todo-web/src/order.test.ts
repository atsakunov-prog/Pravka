// Порядок задач — цепочки Todoist (опись поведения Todoist §12.3): node --experimental-strip-types --test (npm test).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { orderBy, plainTitle, type Chain, type OrderCtx } from './order.ts';
import type { Task } from './types.ts';

let n = 0;
const T = (title: string, p: Partial<Task> = {}): Task => ({
  id: 'id' + (++n), num: n, title, status: 'open', priority: 4, due_date: null, due_time: null, deadline: null, pos: null,
  project_id: null, section_id: null, deal_id: null, why_id: null, created_at: '2026-10-01T10:00:00Z', ...p,
} as Task);
// Проекты: Входящие (null) — 0, «Альфа» — 1, «Бета» — 2; разделы Беты: s1 — 1, s2 — 2.
const ctx: OrderCtx = {
  project: (id) => (id ? { alpha: 1, beta: 2 }[id] ?? 9 : 0),
  section: (t) => (t.section_id ? { s1: 1, s2: 2 }[t.section_id] ?? 9 : -1),
  parent: () => undefined,
};
const ord = (chain: Chain, ts: Task[], desc = false) => [...ts].sort(orderBy(chain, ctx, desc)).map((t) => t.title);
const D = '2026-10-19';

test('день («Сегодня»): со временем выше и по времени, потом приоритет, дедлайн, добавлено, проект', () => {
  const ts = [
    T('без времени p1', { due_date: D, priority: 1 }),
    T('в 15', { due_date: D, due_time: '15:00' }),
    T('в 9 p4', { due_date: D, due_time: '09:00' }),
    T('без времени p4 Бета', { due_date: D, project_id: 'beta' }),
    T('без времени p4 Входящие', { due_date: D }),
    T('без времени p4 дедлайн', { due_date: D, deadline: '2026-10-25' }),
    T('сделано', { due_date: D, due_time: '08:00', status: 'done' }),
  ];
  assert.deepEqual(ord('day', ts), ['в 9 p4', 'в 15', 'без времени p1', 'без времени p4 дедлайн', 'без времени p4 Входящие', 'без времени p4 Бета', 'сделано']);
});

test('просрочено: срок или дедлайн (что раньше), приоритет, дедлайн, добавлено', () => {
  const ts = [
    T('17-го p4', { due_date: '2026-10-17' }),
    T('18-го p1', { due_date: '2026-10-18', priority: 1 }),
    T('18-го, дедлайн 10-го', { due_date: '2026-10-18', deadline: '2026-10-10' }),
    T('17-го p2', { due_date: '2026-10-17', priority: 2 }),
    T('18-го p4 раньше добавлено', { due_date: '2026-10-18', created_at: '2026-09-01T00:00:00Z' }),
  ];
  assert.deepEqual(ord('overdue', ts), ['18-го, дедлайн 10-го', '17-го p2', '17-го p4', '18-го p1', '18-го p4 раньше добавлено']);
});

test('фильтр и метка: приоритет, срок (без срока в конце), дедлайн, проект, раздел', () => {
  const ts = [
    T('p4 завтра', { due_date: '2026-10-20' }),
    T('p1 без срока', { priority: 1 }),
    T('p1 сегодня', { priority: 1, due_date: D }),
    T('p4 завтра дедлайн', { due_date: '2026-10-20', deadline: '2026-10-21' }),
    T('p4 завтра Бета s2', { due_date: '2026-10-20', project_id: 'beta', section_id: 's2' }),
    T('p4 завтра Бета s1', { due_date: '2026-10-20', project_id: 'beta', section_id: 's1' }),
  ];
  assert.deepEqual(ord('filter', ts), ['p1 сегодня', 'p1 без срока', 'p4 завтра дедлайн', 'p4 завтра', 'p4 завтра Бета s1', 'p4 завтра Бета s2']);
});

test('проект: ручной порядок, без места — по добавлению', () => {
  const ts = [T('третье', { pos: 3 }), T('без места новое', { created_at: '2026-10-05T00:00:00Z' }), T('первое', { pos: 1 }),
    T('без места старое', { created_at: '2026-10-02T00:00:00Z' })];
  assert.deepEqual(ord('project', ts), ['первое', 'третье', 'без места старое', 'без места новое']);
});

test('поиск: открытые выше, приоритет, проект, раздел', () => {
  const ts = [T('Бета p1', { project_id: 'beta', priority: 1 }), T('Альфа p4', { project_id: 'alpha' }), T('Входящие p1', { priority: 1 }),
    T('сделано p1', { priority: 1, status: 'done' })];
  assert.deepEqual(ord('search', ts), ['Входящие p1', 'Бета p1', 'Альфа p4', 'сделано p1']);
});

test('выбранная сортировка: срок, дедлайн, приоритет, алфавит, добавлено, проект', () => {
  const a = T('Ёлка', { due_date: '2026-10-21', priority: 2, deadline: '2026-10-30', project_id: 'beta', created_at: '2026-10-03T00:00:00Z' });
  const b = T('**Арбуз**', { due_date: '2026-10-20', priority: 3, project_id: 'alpha', created_at: '2026-10-02T00:00:00Z' });
  const c = T('Банан', { priority: 1, deadline: '2026-10-25', created_at: '2026-10-04T00:00:00Z' });
  const ts = [a, b, c];
  assert.deepEqual(ord('date', ts), ['**Арбуз**', 'Ёлка', 'Банан']); // без срока — в конце
  assert.deepEqual(ord('date', ts, true), ['Ёлка', '**Арбуз**', 'Банан']); // и при обратном направлении
  assert.deepEqual(ord('deadline', ts), ['Банан', 'Ёлка', '**Арбуз**']); // без дедлайна — в конце
  assert.deepEqual(ord('priority', ts), ['Банан', 'Ёлка', '**Арбуз**']);
  assert.deepEqual(ord('name', ts), ['**Арбуз**', 'Банан', 'Ёлка']); // разметка не мешает, ё — по-русски
  assert.deepEqual(ord('added', ts, true), ['Банан', 'Ёлка', '**Арбуз**']); // новые сверху
  assert.deepEqual(ord('byProject', ts), ['Банан', '**Арбуз**', 'Ёлка']); // Входящие, Альфа, Бета
});

test('дерево: дело внутри — сразу под родителем, по своему месту', () => {
  const p = T('родитель', { pos: 1 });
  const k2 = T('внутри 2', { pos: 2, why_id: p.id }), k1 = T('внутри 1', { pos: 1, why_id: p.id });
  const q = T('сосед', { pos: 2 });
  const c2: OrderCtx = { ...ctx, parent: (t) => (t.why_id === p.id ? p : undefined) };
  const out = [q, k2, p, k1].sort(orderBy('byProject', c2)).map((t) => t.title);
  assert.deepEqual(out, ['родитель', 'внутри 1', 'внутри 2', 'сосед']);
  assert.equal(plainTitle('[Отчёт](https://x.y) для _Беты_'), 'Отчёт для Беты');
});
