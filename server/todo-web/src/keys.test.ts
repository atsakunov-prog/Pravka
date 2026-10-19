// Горячие клавиши в обеих раскладках — пары из таблицы клавиш Todoist (19.10): node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { GO, globalAct, keyName, OPEN, taskAct } from './keys.ts';

test('русская буква — латинская на той же клавише', () => {
  const pairs: [string, string][] = [['й', 'q'], ['е', 't'], ['у', 'e'], ['н', 'y'], ['д', 'l'], ['м', 'v'], ['ч', 'x'], ['о', 'j'],
    ['л', 'k'], ['п', 'g'], ['ш', 'i'], ['г', 'u'], ['ь', 'm'], ['ю', '.'], ['я', 'z'], ['в', 'd'], ['с', 'c'], ['а', 'f'], ['ф', 'a'], ['ё', '`']];
  for (const [ru, en] of pairs) {
    assert.equal(keyName(ru), en, ru);
    assert.equal(keyName(ru.toUpperCase()), en, ru + ' с Shift');
    assert.equal(keyName(en.toUpperCase()), en, en + ' с Shift');
  }
  for (const k of ['1', '4', '/', 'Enter', 'Escape', 'ArrowDown']) assert.equal(keyName(k), k.toLowerCase());
});

test('задача в фокусе: Todoist-пары дают одно действие', () => {
  // [латинская, русская, Shift, действие] — из таблицы клавиш Todoist
  const rows: [string, string, boolean, string][] = [
    ['e', 'у', false, 'done'], ['t', 'е', false, 'date'], ['t', 'е', true, 'date-clear'], ['d', 'в', false, 'deadline'],
    ['d', 'в', true, 'deadline-clear'], ['y', 'н', false, 'prio'], ['l', 'д', false, 'labels'], ['v', 'м', false, 'move'],
    ['x', 'ч', false, 'select'], ['.', 'ю', false, 'menu'], ['g', 'п', true, 'in-project'],
  ];
  for (const [en, ru, shift, act] of rows) {
    assert.equal(taskAct(keyName(en), shift), act, en);
    assert.equal(taskAct(keyName(ru), shift), act, ru);
  }
  assert.equal(taskAct('enter', false), 'open');
  assert.equal(taskAct('3', false), 'prio3');
  assert.equal(taskAct(keyName('у'), true), 'done-forever'); // у нас Shift+E — выполнить навсегда
  assert.equal(taskAct(keyName('й'), false), null); // Q — не про задачу, уходит в общие
});

test('общие клавиши и переходы G', () => {
  const rows: [string, string, boolean, string][] = [
    ['q', 'й', false, 'quick'], ['q', 'й', true, 'dictate'], ['f', 'а', false, 'search'], ['m', 'ь', false, 'side'],
    ['z', 'я', false, 'undo'], ['j', 'о', false, 'next'], ['k', 'л', false, 'prev'], ['g', 'п', false, 'go'],
  ];
  for (const [en, ru, shift, act] of rows) {
    assert.equal(globalAct(keyName(en), shift), act, en);
    assert.equal(globalAct(keyName(ru), shift), act, ru);
  }
  assert.equal(globalAct('arrowdown', false), 'next');
  assert.equal(globalAct('/', false), 'search');
  // G T = П Е — «Сегодня», G I = П Ш — «Входящие», G U = П Г — «Предстоящее», G V = П М, G C = П С
  const go: [string, string, string][] = [['t', 'е', 'today'], ['i', 'ш', 'inbox'], ['u', 'г', 'upcoming'], ['v', 'м', 'filters'], ['c', 'с', 'done']];
  for (const [en, ru, view] of go) {
    assert.equal(GO[keyName(en)], view, 'g ' + en);
    assert.equal(GO[keyName(ru)], view, 'п ' + ru);
  }
  // O P = Щ З — «Продуктивность»
  assert.equal(globalAct(keyName('щ'), false), 'open');
  assert.equal(OPEN[keyName('з')], 'prod');
});
