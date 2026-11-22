// Дерево жизни в /t (krug.ts): главное направление дела — у верхнего звена цепочки «Зачем» (через проект), слово «~здор»
// узнаёт направление, раскладка по направлениям и «Без места». node --experimental-strip-types --test.
import assert from 'node:assert/strict';
import { test } from 'node:test';

(globalThis as any).document = { documentElement: { dataset: { theme: 'dark' } } };

const { S } = await import('./store.ts');
const { dirByWord, dirsOf, dirTasks, mainDir, unplaced, dirColor } = await import('./krug.ts');

const base = { status: 'open', kind: 'task', owner_id: 'sasha', labels: [], created_at: '2026-11-01T00:00:00Z', updated_at: '2026-11-01T00:00:00Z' };
function setup() {
  S.me = { user: 'sasha', name: 'Владелец', role: 'owner', settings: {} };
  S.dirs = [
    { key: 'health', name: 'Здоровье', short: 'Здоровье', pos: 0 },
    { key: 'work', name: 'Дело', short: 'Дело', pos: 3 },
    { key: 'parenting', name: 'Родительство', short: 'Родительство', pos: 6, aliases: ['дети'] },
    { key: 'home', name: 'Комфортный быт', short: 'Быт', pos: 8 },
  ];
  S.tasks.clear();
  S.krugProj.clear();
  const put = (t: any) => S.tasks.set(t.id, { ...base, project_id: null, why_id: null, directions: [], ...t });
  put({ id: 'g1', title: 'Дети знают математику', goal: true, directions: ['parenting'] });
  put({ id: 'g2', title: 'Младший считает до 20', goal: true, why_id: 'g1' });
  put({ id: 'a', title: 'Столбики младшему', why_id: 'g2', why_goal: true });
  put({ id: 'b', title: 'Купить магний', directions: ['health'] });
  put({ id: 'c', title: 'Сверка с банком', project_id: 'p1' });
  put({ id: 'd', title: 'Повесить полку' });
  put({ id: 'e', title: 'Шаг внутри', why_id: 'a' });
  put({ id: 'f', title: 'Магний для сна и для пары', directions: ['home', 'health'] });
  put({ id: 'x', title: 'Чужое', owner_id: 'natasha', directions: ['work'] });
  S.krugProj.set('p1', { directions: ['work'], why_id: null });
  S.version++;
}

test('направление: у верхнего звена цепочки «Зачем», через проект; свои — метками', () => {
  setup();
  assert.equal(mainDir(S.tasks.get('a')!), 'parenting'); // через две цели к корню
  assert.equal(mainDir(S.tasks.get('e')!), 'parenting'); // дело внутри дела под целью
  assert.equal(mainDir(S.tasks.get('b')!), 'health');
  assert.equal(mainDir(S.tasks.get('c')!), 'work'); // проект
  assert.equal(mainDir(S.tasks.get('d')!), null);
  assert.deepEqual(dirsOf(S.tasks.get('f')!), ['home', 'health']);
  assert.deepEqual(dirsOf(S.tasks.get('x')!), []); // чужое дело направлений не показывает
});

test('раскладка: по главному направлению и «Без места»', () => {
  setup();
  assert.deepEqual(dirTasks('parenting').map((t) => t.id).sort(), ['a', 'e']);
  assert.deepEqual(dirTasks('health').map((t) => t.id).sort(), ['b']);
  assert.deepEqual(unplaced().map((t) => t.id), ['d']); // цели и чужое не в счёт
});

test('«~слово» в строке: начало имени, короткое имя, ключ, псевдоним', () => {
  setup();
  assert.equal(dirByWord('здор'), 'health');
  assert.equal(dirByWord('Быт'), 'home');
  assert.equal(dirByWord('комфортныйбыт'), 'home');
  assert.equal(dirByWord('parenting'), 'parenting');
  assert.equal(dirByWord('дети'), 'parenting');
  assert.equal(dirByWord('з'), null); // одна буква — не направление
  assert.equal(dirByWord('театр'), null);
});

test('цвет: свой у известного ключа, у незнакомого — оттенок по месту на круге', () => {
  setup();
  assert.equal(dirColor('health'), '#5fe39a');
  S.dirs.push({ key: 'new', name: 'Новое', pos: 9 });
  assert.match(dirColor('new'), /^hsl\(/);
  assert.equal(dirColor(null), 'var(--fg3)');
});
