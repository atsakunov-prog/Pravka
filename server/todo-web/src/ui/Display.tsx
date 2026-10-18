// «Вид» (Display у Todoist): раскладка список/доска, группировка, сортировка, выполненные — у каждого вида свои,
// личные (crm.users.settings.todo.views).
import { dueWords } from '../dates.ts';
import { saveTodo, S, todoSettings } from '../store.ts';
import type { Task } from '../types.ts';
import { byDue, byPos } from '../views.ts';
import { dealShort, projectName } from '../lib.ts';
import { Icon } from './icons.tsx';

export interface ViewOpts {
  layout?: 'list' | 'board';
  group?: 'none' | 'date' | 'priority' | 'label' | 'project';
  sort?: 'manual' | 'date' | 'priority' | 'name' | 'added';
  done?: boolean;
}

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
  return a.sort(byPos);
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
      <select value={val || def} onChange={(e) => on(e.target.value as T)} style={{ border: '1px solid var(--border)', borderRadius: 6, height: 28, background: 'var(--raised)' }}>
        {items.map(([v, w]) => <option key={v} value={v}>{w}</option>)}
      </select>
    </label>
  );
  return (
    <div style={{ width: 300 }}>
      {board ? (
        <>
          <div className="mh">Раскладка</div>
          <div style={{ display: 'flex', gap: 6, padding: '4px 8px 8px' }}>
            {([['list', 'list', 'Список'], ['board', 'board', 'Доска']] as const).map(([v, ic, w]) => (
              <button key={v} className="chip" style={(opts.layout || 'list') === v ? { borderColor: 'var(--accent)', color: 'var(--accent-text)', flex: 1, justifyContent: 'center' } : { flex: 1, justifyContent: 'center' }}
                onClick={() => set({ layout: v })}><Icon name={ic} size={16} />{w}</button>
            ))}
          </div>
        </>
      ) : null}
      {groups ? sel('Группировка', opts.group, 'none', [['none', 'Нет'], ['date', 'По сроку'], ['priority', 'По приоритету'], ['label', 'По меткам'], ['project', 'По проекту']], (v) => set({ group: v })) : null}
      {sel('Сортировка', opts.sort, 'manual', [['manual', 'Вручную'], ['date', 'По сроку'], ['priority', 'По приоритету'], ['name', 'По названию'], ['added', 'По дате добавления']], (v) => set({ sort: v }))}
      <label className="mi" style={{ justifyContent: 'space-between' }}>Выполненные задачи<input type="checkbox" checked={!!opts.done} onChange={(e) => set({ done: e.target.checked })} /></label>
      <div className="msep" />
      <button className="mi" onClick={() => saveViewOpts(vkey, {})}><Icon name="x" size={16} />Сбросить</button>
    </div>
  );
}
