// Команды для Ctrl+K — как у Todoist: поиск и все команды одним списком, группами, у каждой подписана клавиша
// (клавиши — keys.ts; русская раскладка работает так же).

export interface Cmd {
  id: string;
  group: 'Переходы' | 'Добавить' | 'Вид' | 'Настройки';
  label: string;
  keys?: string;
  also?: string; // другие слова для поиска
}

export const COMMANDS: Cmd[] = [
  { id: 'go:now', group: 'Переходы', label: 'Сейчас', keys: 'G N' },
  { id: 'go:inbox', group: 'Переходы', label: 'Входящие', keys: 'G I' },
  { id: 'go:today', group: 'Переходы', label: 'Сегодня', keys: 'G T' },
  { id: 'go:upcoming', group: 'Переходы', label: 'Предстоящее', keys: 'G U' },
  { id: 'go:filters', group: 'Переходы', label: 'Фильтры и метки', keys: 'G V' },
  { id: 'go:done', group: 'Переходы', label: 'Выполнено', keys: 'G C', also: 'сделано готово' },
  { id: 'go:tree', group: 'Переходы', label: 'Дерево жизни', also: 'цели' },
  { id: 'add:task', group: 'Добавить', label: 'Добавить задачу', keys: 'Q', also: 'новая' },
  { id: 'add:dictate', group: 'Добавить', label: 'Надиктовать задачи', keys: 'Shift Q', also: 'голос' },
  { id: 'add:project', group: 'Добавить', label: 'Добавить проект', also: 'новый' },
  { id: 'add:filter', group: 'Добавить', label: 'Добавить фильтр', also: 'новый' },
  { id: 'view:side', group: 'Вид', label: 'Боковая панель — показать или скрыть', keys: 'M', also: 'меню' },
  { id: 'set:quick', group: 'Настройки', label: 'Быстрое добавление', also: 'настройки чипы ввод' },
];

const low = (s: string) => s.toLowerCase().replace(/ё/g, 'е');

/** Команды под запрос: каждое слово запроса — начало какого-то слова команды («доб пр» → «Добавить проект»). Пусто — все. */
export function matchCommands(q: string): Cmd[] {
  const parts = low(q).split(/\s+/).filter(Boolean);
  if (!parts.length) return COMMANDS;
  return COMMANDS.filter((c) => {
    const words = low(c.label + ' ' + (c.also || '')).split(/[\s—,]+/);
    return parts.every((p) => words.some((w) => w.startsWith(p)));
  });
}
