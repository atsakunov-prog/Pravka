// Настройки быстрого ввода как у Todoist (опись §12.4): node --experimental-strip-types --test (npm test).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { features, visibleChips, type Feature } from './quickprefs.ts';

test('список чипов: «Проект» скрыть нельзя, незнакомое выброшено, недостающее — в конец показанным', () => {
  assert.deepEqual(features().map((f) => f.name), ['project', 'due', 'priority', 'deadline', 'labels', 'person', 'why', 'now']);
  const f = features({ features: [{ name: 'priority', shown: true }, { name: 'due', shown: false }, { name: 'project', shown: false }, { name: 'attachment', shown: true }] });
  assert.deepEqual(f.slice(0, 3), [{ name: 'priority', shown: true }, { name: 'due', shown: false }, { name: 'project', shown: true }]);
  assert.equal(f.length, 8);
  assert.deepEqual(features({ features: [{ name: 'labels', shown: true }] })[0], { name: 'project', shown: true }); // нет проекта — первым
});

test('что видно: пустая строка — только «Срок», с текстом — показанные, скрытый — лишь со значением', () => {
  const none = () => false;
  assert.deepEqual(visibleChips(undefined, false, none), ['due']);
  assert.deepEqual(visibleChips(undefined, true, none), ['due', 'priority', 'deadline', 'labels', 'person', 'why', 'now']);
  const p = { features: [{ name: 'labels', shown: true }, { name: 'due', shown: true }, { name: 'priority', shown: false }] };
  assert.deepEqual(visibleChips(p, true, none).slice(0, 2), ['labels', 'due']); // порядок — из настроек
  assert.equal(visibleChips(p, true, none).includes('priority'), false);
  const hasPrio = (x: Feature) => x === 'priority';
  assert.equal(visibleChips(p, true, hasPrio).includes('priority'), true); // «p1» в строке — чип виден, чтобы снять
  assert.deepEqual(visibleChips({ features: [{ name: 'due', shown: false }] }, false, none), []);
});
