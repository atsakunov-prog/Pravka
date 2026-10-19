// «Отображение» (Display у Todoist): блоки Раскладка (список / доска, выполненные), Сортировать (группировка,
// сортировка), Фильтр (срок, дедлайн, приоритет, метка) — у каждого вида свои, личные (crm.users.settings.todo.views).
// Правила без React — viewopts.ts.
import { dueWords } from '../dates.ts';
import { saveTodo, S, todoSettings } from '../store.ts';
import type { Task } from '../types.ts';
import { byDue, byPos } from '../views.ts';
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

export function sortTasks(ts: Task[], sort: ViewOpts['sort']): Task[] {
  const a = [...ts];
  if (sort === 'date') return a.sort(byDue);
  if (sort === 'priority') return a.sort((x, y) => (x.priority ?? 4) - (y.priority ?? 4) || byDue(x, y));
  if (sort === 'name') return a.sort((x, y) => x.title.localeCompare(y.title, 'ru'));
  if (sort === 'added') return a.sort((x, y) => y.created_at.localeCompare(x.created_at));
  if (sort === 'deadline') return a.sort((x, y) => (x.deadline || '9999').localeCompare(y.deadline || '9999') || byDue(x, y));
  if (sort === 'project') return a.sort((x, y) => projectName(x.project_id).localeCompare(projectName(y.project_id), 'ru') || byPos(x, y));
  return a.sort(byPos);
}

/** Что показать: фильтр вида, потом его сортировка. */
export function shown(ts: Task[], o: ViewOpts): Task[] {
  return sortTasks(filterTasks(ts, o, S.today), o.sort);
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
            {([['list', 'list', 'Список'], ['board', 'board', 'Доска']] as const).map(([v, ic, w]) => (
              <button key={v} className="chip" style={(opts.layout || 'list') === v ? { borderColor: 'var(--accent)', color: 'var(--accent-text)', flex: 1, justifyContent: 'center' } : { flex: 1, justifyContent: 'center' }}
                onClick={() => set({ layout: v })}><Icon name={ic} size={16} />{w}</button>
            ))}
          </div>
        </>
      ) : null}
      <label className="mi" style={{ justifyContent: 'space-between' }}>Выполненные задачи<input type="checkbox" checked={!!opts.done} onChange={(e) => set({ done: e.target.checked })} /></label>
      <div className="msep" />
      <div className="dm-h">Сортировать</div>
      {groups ? sel('Группировка', opts.group, 'none', [['none', 'Нет'], ['date', 'По сроку'], ['added', 'По дате добавления'], ['deadline', 'По дедлайну'],
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
      <button className="mi" onClick={() => saveViewOpts(vkey, {})}><Icon name="x" size={16} />Сбросить</button>
    </div>
  );
}
