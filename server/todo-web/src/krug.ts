// Дерево жизни в /t (23.11.2026, слово владельца: «Todoist, но с нашими фичами — в частности, распределение дел по
// дереву жизни»): девять направлений Свода (krug.directions) со своим цветом, направление каждого дела, что где лежит
// и что ещё без места. Правило направления — как tasks.krug_dirs на сервере и dirsOf прежнего веба: главное — у самого
// верхнего звена цепочки «Зачем» (через проект, если «Зачем» у звена нет), свои направления дела, кроме главного, —
// метки. Считается в браузере, а не берётся из v_tasks.dirs: после своей правки (перетащил дело на направление) поле
// сервера устарело бы до следующего синка.

import { S } from './store.ts';
import type { Direction, Id, Task } from './types.ts';
import { isListed, mine } from './views.ts';

/** Цвет направления ночью — свечение по смыслу: здоровье зелёное, деньги фиолетовые (как плитка «Денег»), пара
 *  розовая… Ключи — из Свода (krug.directions), незнакомый ключ — оттенок по месту на круге (40° на направление). */
const DIR_COLOR: Record<string, string> = {
  health: '#5fe39a',
  mind: '#63d2e8',
  create: '#d8e36a',
  work: '#7fb2ff',
  money: '#b89cff',
  circle: '#ffd84a',
  parenting: '#ff9d66',
  couple: '#ff6fb4',
  home: '#e8c48f',
};
/** Днём (светлая тема) те же оттенки темнее — свечения нет, читается цвет. */
const DIR_COLOR_DAY: Record<string, string> = {
  health: '#1f8a52',
  mind: '#0e7490',
  create: '#7a8a12',
  work: '#2a5d9e',
  money: '#6d4fc4',
  circle: '#9a7400',
  parenting: '#b4521f',
  couple: '#b8326f',
  home: '#8a6a3c',
};

const isDay = () => document.documentElement.dataset.theme === 'light';

export function dirColor(key: string | null | undefined): string {
  if (!key) return 'var(--fg3)';
  const own = (isDay() ? DIR_COLOR_DAY : DIR_COLOR)[key];
  if (own) return own;
  const d = S.dirs.find((x) => x.key === key);
  const hue = (150 + 40 * (+(d?.pos ?? 0) || 0)) % 360;
  return isDay() ? `hsl(${hue} 55% 34%)` : `hsl(${hue} 85% 70%)`;
}

export const krugOn = () => S.dirs.length > 0;
export const dirBy = (k: string | null | undefined): Direction | undefined => (k ? S.dirs.find((d) => d.key === k) : undefined);
/** Короткое имя («Быт», а не «Комфортный быт») — для чипа строки и боковой панели. */
export const dirShort = (k: string | null | undefined): string => {
  const d = dirBy(k);
  return d ? String(d.short || d.name || d.title || k) : k || '';
};
/** Направление по слову строки («~здор», «~Быт», «~parenting»): начало короткого или полного имени, ключ, псевдоним. */
export function dirByWord(word: string): string | null {
  const w = word.toLowerCase().replace(/ё/g, 'е');
  if (w.length < 2) return null;
  const low = (x: unknown) => String(x || '').toLowerCase().replace(/ё/g, 'е');
  for (const d of S.dirs) {
    const names = [d.short, d.name, d.title, d.key, ...(((d as { aliases?: string[] }).aliases) || [])].map(low).filter(Boolean);
    if (names.some((n) => n === w || n.replace(/\s+/g, '') === w || n.startsWith(w))) return d.key;
  }
  return null;
}
export const dirFull = (k: string | null | undefined): string => {
  const d = dirBy(k);
  return d ? String(d.name || d.title || d.short || k) : k || '';
};
/** Зоны круга — три ряда «Круга жизни»: Я, Мир, Семья (как в брифе 7.2). */
export function zones(): { zone: string; dirs: Direction[] }[] {
  const out: { zone: string; dirs: Direction[] }[] = [];
  for (const d of S.dirs) {
    const z = String((d as { zone?: string }).zone || '');
    const last = out[out.length - 1];
    if (last && last.zone === z) last.dirs.push(d);
    else out.push({ zone: z, dirs: [d] });
  }
  return out;
}

interface Link { directions: string[]; why_id: Id | null; goal?: boolean; project?: boolean }

/** Цепочка вверх от дела: само дело, его «Зачем» и выше; у звена без «Зачем» — проект дела и цель проекта. */
function upOf(t: Task): Link[] {
  const out: Link[] = [];
  const seen = new Set<string>();
  let x: (Link & { id?: Id; project_id?: Id | null }) | null = { directions: t.directions || [], why_id: t.why_id ?? null, goal: !!t.goal, id: t.id, project_id: t.project_id };
  for (let n = 0; x && n < 40; n++) {
    out.push(x);
    if (x.why_id) {
      if (seen.has(x.why_id)) break;
      seen.add(x.why_id);
      const w = S.tasks.get(x.why_id);
      x = w ? { directions: w.directions || [], why_id: w.why_id ?? null, goal: !!w.goal, id: w.id, project_id: w.project_id } : null;
      continue;
    }
    if (x.project || x.goal) break;
    const p: { directions: string[]; why_id: Id | null } | null | undefined = x.project_id ? S.krugProj.get(x.project_id) : null;
    x = p ? { directions: p.directions || [], why_id: p.why_id || null, project: true } : null;
  }
  return out;
}

/** Направления дела: [главное, метки…]. Пусто — дело без места в дереве. Чужие дела направлений не показывают. */
export function dirsOf(t: Task): string[] {
  if (!krugOn() || !mine(t)) return [];
  const up = upOf(t);
  let main: string | null = null;
  for (let i = up.length - 1; i >= 0 && !main; i--) if ((up[i].directions || []).length) main = up[i].directions[0];
  if (!main) return [];
  return [main, ...(t.directions || []).filter((k) => k !== main)].slice(0, 3);
}
export const mainDir = (t: Task): string | null => dirsOf(t)[0] || null;

// Раскладка по направлениям — раз на изменение памяти: строка задачи спрашивает «где я», вид — «что в направлении».
let at = -1;
let byDir = new Map<string, Task[]>();
let none: Task[] = [];
function index() {
  if (at === S.version) return;
  at = S.version;
  byDir = new Map();
  none = [];
  for (const t of S.tasks.values()) {
    if (!isListed(t) || !mine(t)) continue;
    const k = mainDir(t);
    if (!k) { none.push(t); continue; }
    const arr = byDir.get(k);
    if (arr) arr.push(t);
    else byDir.set(k, [t]);
  }
}
/** Открытые свои дела направления (по главному). */
export function dirTasks(key: string): Task[] {
  index();
  return byDir.get(key) || [];
}
/** Дела без места: ни «Зачем», ни направления (сами, через проект или цель). Их раз в полчаса разбирает разборщик
 *  сервера (krug_auto.py) — уверен — ставит сам, нет — вопрос в «Новое». */
export function unplaced(): Task[] {
  index();
  return none;
}
