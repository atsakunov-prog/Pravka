// Тосты как у Todoist: склонения одной функцией (4 формы) и тексты — node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { plural, pluralForm } from './dates.ts';
import { dayWords, T } from './toastText.ts';

test('форма множественного числа: 1 / 2–4 / 5–20 / дробное', () => {
  const rows: [number, number][] = [[1, 0], [21, 0], [101, 0], [2, 1], [4, 1], [22, 1], [104, 1], [0, 2], [5, 2], [11, 2], [12, 2], [14, 2],
    [20, 2], [25, 2], [111, 2], [112, 2], [1.5, 3]];
  for (const [n, f] of rows) assert.equal(pluralForm(n), f, String(n));
  assert.equal(plural(3, 'задача', 'задачи', 'задач'), '3 задачи');
  assert.equal(plural(11, 'задача', 'задачи', 'задач'), '11 задач');
});

test('тексты тостов Todoist', () => {
  const MON = '2026-10-19';
  assert.equal(T.done(), 'Задача выполнена');
  assert.equal(T.done(1), 'Задача выполнена');
  assert.equal(T.done(3), '3 задачи выполнено');
  assert.equal(T.done(5), '5 задач выполнено');
  assert.equal(T.done(21), '21 задача выполнена');
  assert.equal(T.next('2026-10-20', MON), 'Следующий раз: завтра');
  assert.equal(T.next('2026-10-26', MON), 'Следующий раз: 26 окт');
  assert.equal(T.reset(1), 'Выполнена и снова добавлена 1 подзадача');
  assert.equal(T.reset(3), 'Выполнено и снова добавлено 3 подзадачи');
  assert.equal(T.reset(5), 'Выполнено и снова добавлено 5 подзадач');
  assert.equal(T.reset(2, 5), 'Выполнено и снова добавлено 2 из 5 подзадач');
  assert.equal(T.rescheduled('2026-10-19', MON), 'Перенесено на сегодня');
  assert.equal(T.rescheduled(null, MON), 'Перенесено');
  assert.equal(T.due('2026-11-05', MON), 'Срок изменен на 5 ноя');
  assert.equal(T.due(null, MON), 'Срок снят');
  assert.equal(T.moved('Альфа Групп'), 'Задача перенесена в Альфа Групп');
  assert.equal(T.moved('Бета', 2), 'Задачи перенесены в Бета');
  assert.equal(T.added('Входящие'), 'Задача добавлена во Входящие');
  assert.equal(T.moved('Входящие'), 'Задача перенесена во Входящие');
  assert.equal(T.added('Важное'), 'Задача добавлена в Важное');
  assert.equal(T.added('Бета', 2), '2 задачи добавлено');
  assert.equal(T.added('Бета', 7), '7 задач добавлено');
  assert.equal(T.deleted(4), '4 задачи удалено');
  assert.equal(T.reordered, 'Порядок изменен');
  assert.equal(T.sectionMoved('Бета'), 'Раздел перенесен в Бета');
  assert.equal(T.viewReset, 'Восстановлены параметры отображения по умолчанию');
  assert.equal(dayWords('2027-01-05', MON), '5 янв 2027');
});
