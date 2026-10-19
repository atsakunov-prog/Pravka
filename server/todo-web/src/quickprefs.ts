// Настройки быстрого ввода — как у Todoist (из кода, 19.10, опись §12.4): какие чипы и в каком порядке, подписи у чипов,
// описание всегда открыто. Личное, в crm.users.settings.todo.quick ({labels_shown, description_shown, features}).

export type Feature = 'project' | 'due' | 'priority' | 'deadline' | 'labels' | 'person' | 'why' | 'now';

export const FEATURE_NAMES: Record<Feature, string> = {
  project: 'Проект', due: 'Срок', priority: 'Приоритет', deadline: 'Дедлайн', labels: 'Метки', person: 'Человек', why: 'Зачем', now: 'Сейчас',
};
const ALL = Object.keys(FEATURE_NAMES) as Feature[];

export interface QuickPrefs {
  labels_shown?: boolean; // чип с подписью или только значок (у пустого)
  description_shown?: boolean; // поле описания видно сразу, а не по ↓
  features?: { name: string; shown: boolean }[];
}

/** Чипы по порядку: незнакомые выброшены, недостающие — в конец показанными. «Проект» скрыть нельзя: нет его в списке —
 *  встаёт первым и показанным. */
export function features(p?: QuickPrefs): { name: Feature; shown: boolean }[] {
  const seen = new Set<string>();
  const out: { name: Feature; shown: boolean }[] = [];
  for (const f of p?.features || []) {
    if (!ALL.includes(f.name as Feature) || seen.has(f.name)) continue;
    seen.add(f.name);
    out.push({ name: f.name as Feature, shown: f.name === 'project' || !!f.shown });
  }
  if (!seen.has('project')) {
    out.unshift({ name: 'project', shown: true });
    seen.add('project');
  }
  for (const n of ALL) if (!seen.has(n)) out.push({ name: n, shown: true });
  return out;
}

/** Какие чипы видны сейчас (проект у нас внизу, не чипом): пока строка пуста — только «Срок», остальное появляется, когда
 *  начинаешь печатать (как у Todoist); скрытый — лишь когда у него есть значение, иначе его не снять. */
export function visibleChips(p: QuickPrefs | undefined, typing: boolean, has: (f: Feature) => boolean): Feature[] {
  return features(p).filter((f) => f.name !== 'project' && (has(f.name) || (f.shown && (typing || f.name === 'due')))).map((f) => f.name);
}
