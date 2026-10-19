// Правила планировщика как у Todoist (опись поведения Todoist, раздел 7): node --experimental-strip-types --test (npm test).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { deadlineOptions, dueOptions, nextDue, postponed, type Due } from './schedule.ts';

const MON = '2026-10-19', WED = '2026-10-21', THU = '2026-10-22', FRI = '2026-10-23', SUN = '2026-10-25';
const keys = (today: string, items: Due[], fresh = false) => dueOptions(today, items, fresh).map((o) => o.key + (o.date ? ' ' + o.date.slice(5) : ''));
const NONE: Due[] = [{ due_date: null }];

test('дело без срока: набор зависит от дня недели', () => {
  assert.deepEqual(keys(MON, NONE), ['today 10-19', 'tomorrow 10-20', 'weekend 10-24', 'nextweek 10-26']);
  assert.deepEqual(keys(THU, NONE), ['today 10-22', 'tomorrow 10-23', 'weekend 10-24', 'nextweek 10-26']);
  // пт–вс: вместо «На выходных» — «След. выходные», суббота следующей недели
  assert.deepEqual(keys(FRI, NONE), ['today 10-23', 'tomorrow 10-24', 'nextweekend 10-31', 'nextweek 10-26']);
  // вс: «След. недели» нет — «Завтра» и есть понедельник
  assert.deepEqual(keys(SUN, NONE), ['today 10-25', 'tomorrow 10-26', 'nextweekend 10-31']);
});

test('«Позднее на неделе»: +2 дня, если сегодня и послезавтра будни и нет сразу «Сегодня» и «Завтра»', () => {
  const onToday = (d: string) => [{ due_date: d }];
  assert.deepEqual(keys(MON, onToday(MON)), ['tomorrow 10-20', 'later 10-21', 'weekend 10-24', 'nextweek 10-26', 'none']);
  assert.deepEqual(keys(WED, onToday(WED)), ['tomorrow 10-22', 'later 10-23', 'weekend 10-24', 'nextweek 10-26', 'none']);
  assert.deepEqual(keys(THU, onToday(THU)), ['tomorrow 10-23', 'weekend 10-24', 'nextweek 10-26', 'none']); // послезавтра сб
  assert.deepEqual(keys(FRI, onToday(FRI)), ['tomorrow 10-24', 'nextweekend 10-31', 'nextweek 10-26', 'none']);
  assert.deepEqual(keys(SUN, onToday(SUN)), ['tomorrow 10-26', 'nextweekend 10-31', 'none']);
  // дело на завтра: «Завтра» нет, «Позднее» есть
  assert.deepEqual(keys(MON, [{ due_date: '2026-10-20' }]), ['today 10-19', 'later 10-21', 'weekend 10-24', 'nextweek 10-26', 'none']);
});

test('«Завтра» у нового дела — всегда; несколько дел — по «хоть одно»', () => {
  assert.deepEqual(keys(MON, [{ due_date: '2026-10-20' }], true), ['today 10-19', 'tomorrow 10-20', 'weekend 10-24', 'nextweek 10-26', 'none']);
  // одно на сегодня, другое на завтра: оба варианта нужны (каждый кому-то), «Позднее» прячется
  assert.deepEqual(keys(MON, [{ due_date: MON }, { due_date: '2026-10-20' }]), ['today 10-19', 'tomorrow 10-20', 'weekend 10-24', 'nextweek 10-26', 'none']);
  // «Без срока» — если хоть у одного срок есть
  assert.ok(keys(THU, [{ due_date: null }, { due_date: '2026-10-30' }]).includes('none'));
});

test('«Отложить» — только если все выбранные дела повторяющиеся', () => {
  const wk = { every: 'week' as const, n: 1, days: [1] };
  assert.deepEqual(keys(MON, [{ due_date: '2026-10-12', recur: wk }]).filter((k) => k.startsWith('postpone')), ['postpone 10-26']);
  assert.deepEqual(keys(MON, [{ due_date: MON, recur: wk }, { due_date: MON, recur: { every: 'day' } }]).filter((k) => k.startsWith('postpone')), ['postpone']);
  assert.equal(keys(MON, [{ due_date: MON, recur: wk }, { due_date: MON }]).some((k) => k.startsWith('postpone')), false);
  assert.equal(keys(MON, [{ due_date: MON, recur: wk }], true).some((k) => k.startsWith('postpone')), false); // новое дело
  for (const day of [THU, FRI, SUN]) assert.ok(keys(day, [{ due_date: day, recur: wk }]).some((k) => k.startsWith('postpone')), day);
});

test('дедлайн: от срока, если он есть; иначе Завтра, След. неделя, След. месяц', () => {
  assert.deepEqual(deadlineOptions(MON, '2026-10-23', null), [
    { label: '+3 дня', date: '2026-10-26' }, { label: '+1 неделя', date: '2026-10-30' }, { label: '+1 месяц', date: '2026-11-23' }]);
  assert.deepEqual(deadlineOptions(THU, null, '2026-11-01'), [
    { label: 'Завтра', date: '2026-10-23' }, { label: 'След. неделя', date: '2026-10-26' }, { label: 'След. месяц', date: '2026-11-22' },
    { label: 'Без дедлайна', date: null }]);
  assert.deepEqual(deadlineOptions(SUN, null, null).map((o) => o.date), ['2026-10-26', '2026-10-26', '2026-11-25']);
});

test('«Отложить» обычного дела: срок впереди — на день позже, прошёл — на завтра; время остаётся', () => {
  assert.deepEqual(postponed({ due_date: '2026-10-25', due_time: '15:00' }, MON), { due_date: '2026-10-26', due_time: '15:00' });
  assert.deepEqual(postponed({ due_date: MON, due_time: null }, MON), { due_date: '2026-10-20', due_time: null });
  assert.deepEqual(postponed({ due_date: '2026-10-15', due_time: '09:30' }, MON), { due_date: '2026-10-20', due_time: '09:30' });
  assert.deepEqual(postponed({ due_date: null }, FRI), { due_date: '2026-10-24', due_time: null });
});

test('«Отложить» повторяющегося: следующий раз по правилу, прошедшие пропустить', () => {
  const p = (recur: Due['recur'], due: string, today = MON) => postponed({ due_date: due, due_time: '10:00', recur }, today);
  assert.deepEqual(p({ every: 'week', n: 1, days: [1] }, '2026-10-12'), { due_date: '2026-10-26', due_time: '10:00' }); // 19.10 = сегодня — тоже мимо
  assert.equal(p({ every: 'day', n: 1 }, '2026-10-15').due_date, '2026-10-20'); // ежедневное — от сегодня
  assert.equal(p({ every: 'day', n: 3 }, MON).due_date, '2026-10-22');
  assert.equal(p({ every: 'week', n: 2, from: 'done' }, '2026-10-10').due_date, '2026-11-02'); // каждые! — от сегодня
  assert.equal(p({ every: 'month', n: 1, monthday: 31 }, '2026-09-30').due_date, '2026-10-31'); // 31-е помнится
  assert.equal(p({ every: 'year', n: 1 }, '2026-04-02').due_date, '2027-04-02');
  assert.equal(p({ every: 'week', n: 2, days: [5] }, FRI).due_date, '2026-11-06'); // раз в 2 недели по пт
  assert.equal(p({ every: 'workday', n: 1 }, '2026-10-16').due_date, '2026-10-20'); // пт → пн уже сегодня → вт
  assert.equal(p({ every: 'workday', n: 1 }, FRI, FRI).due_date, '2026-10-26'); // в пятницу — на понедельник
  assert.equal(nextDue({ every: 'month', n: 1, monthday: -1 }, '2026-10-31', MON), '2026-11-30');
});
