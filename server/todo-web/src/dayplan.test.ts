// Лента дня (dayplan.ts) на настоящем дне владельца 24.11.2026 (выдуманные названия): дорога на дачу и обратно,
// велик к встрече, встречи календаря, вопросы к встрече, «в машине заодно», окна и ёмкость. node --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';

import type { CalEvent } from './calev.ts';
import { buildPlan, durWords, gapSlot, spanWords } from './dayplan.ts';
import type { Task } from './types.ts';

const D = '2026-11-24';
let n = 0;
const T = (title: string, p: Partial<Task> = {}): Task => ({ id: 'id' + (++n), title, status: 'open', ball: 'mine', due_date: D, due_time: null,
  estimate_min: null, labels: [], ...p } as Task);
const ev = (id: string, start: string, end: string, p: Partial<CalEvent> = {}): CalEvent => ({ id, title: id, all_day: false, day: D, last_day: D,
  start, end, end_day: D, mins: null, location: '', link: '', url: '', task_id: null, busy: true, ...p });

function day() {
  const print = T('Копирка: отправить на печать', { due_time: '08:15:00', estimate_min: 10 });
  const drive = T('Дача: выехать', { due_time: '09:00:00', estimate_min: 75, labels: ['машина'] });
  const back = T('Дача: обратно', { due_time: '11:15:00', estimate_min: 75, labels: ['машина'] });
  const bike = T('На велике в Сити', { due_time: '12:55:00', estimate_min: 35 });
  const vim = T('Встреча с фондом', { due_time: '13:30:00', estimate_min: 60 });
  const vimQ = T('Спросить про решение', { due_date: D, why_id: vim.id, estimate_min: 10 });
  const brio = T('Созвон с брокером', { due_time: '17:00:00', estimate_min: 30 });
  const q1 = T('Спросить по сделке', { why_id: brio.id, ball: 'agenda' });
  const q2 = T('Спросить по договору', { why_id: brio.id, ball: 'agenda' });
  const call = T('Назначить встречу', { estimate_min: 10, labels: ['машина'] });
  const think = T('Подумать про финансирование', { estimate_min: 40, labels: ['машина'] });
  const check = T('Иван: задал ли вопрос', { ball: 'waiting', estimate_min: 15, labels: ['машина'] });
  const later = T('Без срока, в машине', { due_date: null, labels: ['машина'] });
  const rundown = T('Рандаун по деньгам', { estimate_min: 90 });
  const read = T('Прочитать отчёт', { estimate_min: 30 });
  const wait = T('Пётр: прислать модель', { ball: 'waiting' });
  const dayTasks = [print, drive, back, bike, vim, vimQ, brio, q1, q2, call, think, check, rundown, read, wait];
  const open = [...dayTasks, later];
  const events = [ev('Тренировка', '13:00', '14:00'), ev('Лекция', '19:00', '21:00'), ev('Отпуск коллеги', '', '', { all_day: true, start: null, end: null }),
    ev('Свободно', '15:00', '15:30', { busy: false })];
  return { print, drive, back, bike, vim, vimQ, brio, q1, q2, call, think, check, later, rundown, read, wait, dayTasks, open, events };
}

test('лента: по порядку, «весь день» сверху, накладки — у чего с чем', () => {
  const d = day();
  const p = buildPlan(D, d.events, d.dayTasks, d.open);
  const names = p.items.map((x) => x.ev ? x.ev.title : x.task!.title);
  assert.deepEqual(names, ['Отпуск коллеги', 'Копирка: отправить на печать', 'Дача: выехать', 'Дача: обратно', 'На велике в Сити', 'Тренировка',
    'Встреча с фондом', 'Свободно', 'Созвон с брокером', 'Лекция']);
  const at = (t: string) => p.items.find((x) => (x.ev ? x.ev.title : x.task!.title) === t)!;
  assert.deepEqual(at('Тренировка').clash, ['На велике в Сити', 'Встреча с фондом']);
  assert.deepEqual(at('Встреча с фондом').clash, ['Тренировка']);
  assert.deepEqual(at('Дача: выехать').clash, []);
  assert.deepEqual(at('Свободно').clash, []); // «свободен» в календаре не мешает
  assert.equal(spanWords(at('Дача: выехать').from, at('Дача: выехать').to), '09:00–10:15');
});

test('под блоком: вопросы к встрече и «в машине заодно» — один раз, у первого блока с меткой', () => {
  const d = day();
  const p = buildPlan(D, d.events, d.dayTasks, d.open);
  const at = (t: string) => p.items.find((x) => x.task?.title === t)!;
  assert.deepEqual(at('Созвон с брокером').inside.map((t) => t.title), ['Спросить по сделке', 'Спросить по договору']);
  assert.deepEqual(at('Встреча с фондом').inside.map((t) => t.title), ['Спросить про решение']);
  assert.deepEqual(at('Дача: выехать').along.map((g) => [g.label, g.tasks.map((t) => t.title)]),
    [['машина', ['Назначить встречу', 'Подумать про финансирование', 'Иван: задал ли вопрос', 'Без срока, в машине']]]);
  assert.deepEqual(at('Дача: обратно').along, []); // уже взяты в первую дорогу
  // Без часа остаются только то, что не внутри и не «заодно».
  assert.deepEqual(p.untimed.map((t) => t.title), ['Рандаун по деньгам', 'Прочитать отчёт', 'Пётр: прислать модель']);
});

test('окна и ёмкость: с 8:00 до 22:00 между занятыми, «сейчас» отрезает прошлое', () => {
  const d = day();
  const p = buildPlan(D, d.events, d.dayTasks, d.open);
  assert.deepEqual(p.gaps.map((g) => spanWords(g.from, g.to)), ['08:25–09:00', '10:15–11:15', '12:30–12:55', '14:30–17:00', '17:30–19:00', '21:00–22:00']);
  assert.equal(p.free, 35 + 60 + 25 + 150 + 90 + 60);
  assert.equal(p.need, 90 + 30 + 5); // ждать — пять минут проверить
  const later = buildPlan(D, d.events, d.dayTasks, d.open, 15 * 60 + 10);
  assert.deepEqual(later.gaps.map((g) => spanWords(g.from, g.to)), ['15:10–17:00', '17:30–19:00', '21:00–22:00']);
  assert.equal(gapSlot({ from: 15 * 60 + 10, to: 17 * 60 }), '15:15');
  assert.equal(durWords(150), '2 ч 30 мин');
  assert.equal(durWords(45), '45 мин');
});
