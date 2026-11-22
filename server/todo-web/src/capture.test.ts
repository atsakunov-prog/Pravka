// Строка быстрого ввода поверх quick.ts (capture.ts): «~направление» и «напомни …». node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';

const { S } = await import('./store.ts');
const { parseQuick } = await import('./quick.ts');
const { withDir, withRemind } = await import('./capture.ts');

const ctx = { today: '2026-11-23', now: '08:00', projects: [], sections: [], labels: [], people: [], places: [] } as never;
const parse = (s: string) => withRemind(withDir(parseQuick(s, ctx), s), s);

test('«напомни» со сроком — напоминание ботом и чистое название', () => {
  const p = parse('напомни завтра в 9 позвонить врачу');
  assert.equal(p.title, 'Позвонить врачу');
  assert.equal(p.due_date, '2026-11-24');
  assert.deepEqual(p.remind_when, { date: '2026-11-24', time: '09:00' });
  const q = parse('Напомнить мне в пятницу купить подарок');
  assert.equal(q.title, 'Купить подарок');
  assert.deepEqual(q.remind_when, { date: '2026-11-27', time: '09:00' }); // без времени — в 9 утра того дня
});

test('«напомни» без срока — обычная задача; не в начале — не трогаем; явное «!» главнее', () => {
  const p = parse('напомни купить молоко');
  assert.equal(p.title, 'Купить молоко');
  assert.equal(p.remind_when, undefined);
  assert.equal(parse('купить напоминалку завтра').title, 'купить напоминалку');
  const q = parse('напомни позвонить завтра !в 18:00');
  assert.equal(q.remind_when?.time, '18:00');
});

test('«~направление»: слово уходит из названия, без Свода — остаётся как есть', () => {
  S.dirs = [];
  assert.equal(parse('Купить магний ~здор').title.includes('~здор'), true);
  S.dirs = [{ key: 'health', name: 'Здоровье', short: 'Здоровье', pos: 0 }];
  const p = parse('Купить магний ~здор') as { title: string; dir?: string };
  assert.equal(p.title, 'Купить магний');
  assert.equal(p.dir, 'health');
  assert.equal((parse('Купить ~что-то') as { dir?: string }).dir, undefined);
});
