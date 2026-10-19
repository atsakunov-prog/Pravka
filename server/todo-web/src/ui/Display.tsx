// «Отображение» (Display у Todoist): блоки Раскладка (список / доска, выполненные), Сортировать (группировка,
// сортировка), Фильтр (срок, дедлайн, приоритет, метка) — у каждого вида свои, личные (crm.users.settings.todo.views).
// Правила без React — viewopts.ts.
import { dueWords } from '../dates.ts';
import { saveTodo, S, toast, todoSettings } from '../store.ts';
import { T } from '../toastText.ts';
import type { Task } from '../types.ts';
import type { Chain } from '../order.ts';
import { sorted } from '../views.ts';
import { dealShort, projectName } from '../lib.ts';
import { filterTasks, type ViewOpts } from '../viewopts.ts';
import { Icon } from './icons.tsx';

export type { ViewOpts };

export function viewOpts(key: string): ViewOpts {
  const all = (todoSettings() as any).views || {};
  return all[key] || {};
}
export function saveViewOpts(key: string, v: ViewOpts) {
  const all = { ...((todoSettings() as any).views || {}), [key]: v };
  saveTodo({ views: all } as any).catch(() => {});
}

/** Выбранная сортировка — цепочкой Todoist (order.ts). «Вручную» — ручной порядок; «по дате добавления» — новые сверху
 *  (переключателя направления у нас нет, а свежее нужнее; разворачивается только первый признак). base — порядок вида
 *  по умолчанию, когда сортировка не выбрана (у фильтра — приоритет, срок…). */
export function sortTasks(ts: Task[], sort: ViewOpts['sort'], base: Chain = 'project'): Task[] {
  if (sort === 'date') return sorted(ts, 'date');
  if (sort === 'priority') return sorted(ts, 'priority');
  if (sort === 'name') return sorted(ts, 'name');
  if (sort === 'added') return sorted(ts, 'added', true);
  if (sort === 'deadline') return sorted(ts, 'deadline');
  if (sort === 'project') return sorted(ts, 'byProject');
  return sorted(ts, base);
}

/** Что показать: фильтр вида, потом его сортировка. */
export function shown(ts: Task[], o: ViewOpts, base: Chain = 'project'): Task[] {
  return sortTasks(filterTasks(ts, o, S.today), o.sort, base);
}

export function groupTasks(ts: Task[], group: ViewOpts['group']): { key: string; title: string; tasks: Task[] }[] {
  if (!group || group === 'none') return [{ key: 'all', title: '', tasks: ts }];
  const m = new Map<string, { key: string; title: string; tasks: Task[]; order: string }>();
  const put = (key: string, title: string, order: string, t: Task) => {
    if (!m.has(key)) m.set(key, { key, title, tasks: [], order });
    m.get(key)!.tasks.push(t);
  };
  for (const t of ts) {
    if (group === 'priority') put('p' + (t.priority ?? 4), (t.priority ?? 4) < 4 ? 'Приоритет ' + (t.priority ?? 4) : 'Без приоритета', String(t.priority ?? 4), t);
    else if (group === 'date') {
      if (!t.due_date) put('none', 'Без срока', '9999', t);
      else if (t.due_date < S.today) put('over', 'Просрочено', '0000', t);
      else put(t.due_date, dueWords(t.due_date, S.today), t.due_date, t);
    } else if (group === 'deadline') {
      if (!t.deadline) put('none', 'Без дедлайна', '9999', t);
      else if (t.deadline < S.today) put('over', 'Дедлайн прошёл', '0000', t);
      else put(t.deadline, dueWords(t.deadline, S.today), t.deadline, t);
    } else if (group === 'added') {
      const d = t.created_at.slice(0, 10);
      put(d, dueWords(d, S.today), String(99999999 - +d.replace(/-/g, '')), t); // новые сверху
    } else if (group === 'label') {
      if (!(t.labels || []).length) put('~', 'Без меток', '~', t);
      for (const l of t.labels || []) put('l' + l, l, l, t);
    } else if (group === 'project') {
      const k = t.deal_id || t.project_id || 'inbox';
      put(k, t.deal_id ? `${projectName(t.project_id)} / ${dealShort(t.deal_id)}` : projectName(t.project_id), projectName(t.project_id), t);
    }
  }
  return [...m.values()].sort((a, b) => a.order.localeCompare(b.order, 'ru'));
}

export function DisplayMenu({ vkey, opts, board = true, groups = true }: { vkey: string; opts: ViewOpts; board?: boolean; groups?: boolean }) {
  const set = (p: Partial<ViewOpts>) => saveViewOpts(vkey, { ...opts, ...p });
  const sel = <T extends string>(label: string, val: T | undefined, def: T, items: [T, string][], on: (v: T) => void) => (
    <label className="mi" style={{ justifyContent: 'space-between' }}>
      {label}
      <select value={val || def} onChange={(e) => on(e.target.value as T)} style={{ border: '1px solid var(--border)', borderRadius: 6, height: 28, background: 'var(--raised)', maxWidth: 150 }}>
        {items.map(([v, w]) => <option key={v} value={v}>{w}</option>)}
      </select>
    </label>
  );
  return (
    <div style={{ width: 302 }}>
      {board ? (
        <>
          <div className="dm-h">Раскладка</div>
          <div style={{ display: 'flex', gap: 6, padding: '4px 8px 8px' }}>
            {([['list', 'list', 'Список'], ['board', 'board', 'Доска'], ['calendar', 'calendar', 'Календарь']] as const).map(([v, ic, w]) => (
              <button key={v} className="chip" style={(opts.layout || 'list') === v ? { borderColor: 'var(--accent)', color: 'var(--accent-text)', flex: 1, justifyContent: 'center' } : { flex: 1, justifyContent: 'center' }}
                onClick={() => set({ layout: v })}><Icon name={ic} size={16} />{w}</button>
            ))}
          </div>
        </>
      ) : null}
      {opts.layout === 'calendar' ? (
        <>
          {sel('Режим', opts.calMode, 'week', [['3d', '3 дня'], ['week', 'Неделя'], ['month', 'Месяц']], (v) => set({ calMode: v }))}
          <label className="mi" style={{ justifyContent: 'space-between' }}>Будущие повторы<input type="checkbox" checked={!!opts.calRepeats} onChange={(e) => set({ calRepeats: e.target.checked })} /></label>
          {sel('Раскраска', opts.calColor, 'priority', [['priority', 'По приоритету'], ['project', 'По проекту']], (v) => set({ calColor: v }))}
        </>
      ) : null}
      <label className="mi" style={{ justifyContent: 'space-between' }}>Выполненные задачи<input type="checkbox" checked={!!opts.done} onChange={(e) => set({ done: e.target.checked })} /></label>
      <div className="msep" />
      <div className="dm-h">Сортировать</div>
      {groups && opts.layout !== 'calendar' ? sel('Группировка', opts.group, 'none', [['none', 'Нет'], ['date', 'По сроку'], ['added', 'По дате добавления'], ['deadline', 'По дедлайну'],
        ['priority', 'По приоритету'], ['label', 'По метке'], ['project', 'По проекту']], (v) => set({ group: v })) : null}
      {sel('Сортировка', opts.sort, 'manual', [['manual', 'Вручную'], ['date', 'По сроку'], ['added', 'По дате добавления'], ['deadline', 'По дедлайну'],
        ['priority', 'По приоритету'], ['name', 'По алфавиту'], ['project', 'По проекту']], (v) => set({ sort: v }))}
      <div className="msep" />
      <div className="dm-h">Фильтр</div>
      {sel('Срок', opts.fdue, 'all', [['all', 'Все'], ['today', 'Сегодня'], ['over', 'Просрочено'], ['week', 'На этой неделе'], ['next7', 'Ближайшие 7 дней'], ['none', 'Без срока']],
        (v) => set({ fdue: v }))}
      {sel('Дедлайн', opts.fdl, 'all', [['all', 'Все'], ['has', 'С дедлайном'], ['next7', 'В ближайшие 7 дней'], ['none', 'Без дедлайна']], (v) => set({ fdl: v }))}
      {sel('Приоритет', opts.fprio, 'all', [['all', 'Все'], ['1', 'Приоритет 1'], ['2', 'Приоритет 2'], ['3', 'Приоритет 3'], ['4', 'Приоритет 4']], (v) => set({ fprio: v }))}
      {sel('Метка', opts.flabel || '', '', [['', 'Все'], ...S.labels.map((l) => [l.name, l.name] as [string, string])], (v) => set({ flabel: v || undefined }))}
      <div className="msep" />
      <button className="mi" onClick={() => { saveViewOpts(vkey, {}); toast(T.viewReset, () => saveViewOpts(vkey, opts)); }}><Icon name="x" size={16} />Сбросить</button>
    </div>
  );
}
