// Горячие клавиши как у Todoist — и в русской раскладке. У Todoist почти у каждой клавиши русский двойник (q/й,
// t/е, g t = п е): клавиатура работает без переключения языка. Здесь двойники — одной таблицей: русская буква →
// латинская на той же клавише (ЙЦУКЕН → QWERTY), дальше всё решается по латинской.

const RU = 'йцукенгшщзхъфывапролджэячсмитьбюё';
const EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";

/** Клавиша события → латинское имя: «Е» и «e» → «t»; цифры, Enter, стрелки — как есть, нижним регистром. */
export function keyName(key: string): string {
  const k = key.toLowerCase();
  const i = k.length === 1 ? RU.indexOf(k) : -1;
  return i >= 0 ? EN[i] : k;
}

export type TaskAct = 'open' | 'done' | 'done-forever' | 'prio1' | 'prio2' | 'prio3' | 'prio4' | 'date' | 'date-clear' | 'deadline'
  | 'deadline-clear' | 'prio' | 'labels' | 'move' | 'select' | 'menu' | 'in-project';

/** Действие над задачей в фокусе. Shift+E у нас — «выполнить навсегда» (у повторяющегося), у Todoist — раскрыть. */
export function taskAct(k: string, shift: boolean): TaskAct | null {
  if (k === 'enter') return 'open';
  if (k === 'e') return shift ? 'done-forever' : 'done';
  if (/^[1-4]$/.test(k)) return ('prio' + k) as TaskAct;
  if (k === 't') return shift ? 'date-clear' : 'date';
  if (k === 'd') return shift ? 'deadline-clear' : 'deadline';
  if (k === 'y') return 'prio';
  if (k === 'l') return 'labels';
  if (k === 'v') return 'move';
  if (k === 'x') return 'select';
  if (k === '.') return 'menu';
  if (k === 'g' && shift) return 'in-project';
  return null;
}

export type GlobalAct = 'quick' | 'dictate' | 'search' | 'side' | 'go' | 'open' | 'undo' | 'next' | 'prev' | 'status';

/** Действие без задачи (или если у задачи в фокусе такой клавиши нет). */
export function globalAct(k: string, shift: boolean): GlobalAct | null {
  if (k === 'q') return shift ? 'dictate' : 'quick';
  if (k === 'a') return 'quick';
  if (k === '/' || k === 'f') return 'search';
  if (k === 'm') return 'side';
  if (k === 'g') return 'go';
  if (k === 'o') return 'open'; // O и вторая клавиша — окна (O P — «Продуктивность»)
  if (k === 'z') return 'undo';
  if (k === 'i') return 'status'; // статус проекта (Insights)
  if (k === 'arrowdown' || k === 'j') return 'next';
  if (k === 'arrowup' || k === 'k') return 'prev';
  return null;
}

/** Вторая клавиша после G: G T — «Сегодня», G I — «Входящие», G U — «Предстоящее», G V — «Фильтры и метки»,
 *  G C — «Выполнено» (у нас ещё G D), G N и G H — «Сейчас». */
/** Окна после O, как у Todoist: O P — «Продуктивность». */
export const OPEN: Record<string, string> = { p: 'prod' };

export const GO: Record<string, string> = {
  i: 'inbox', t: 'today', u: 'upcoming', v: 'filters', c: 'done', d: 'done', n: 'now', h: 'now',
};
