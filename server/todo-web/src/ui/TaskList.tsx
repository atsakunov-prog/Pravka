// Список задач: подзадачи под родителем (дела внутри дела), свернуть, перетащить (порядок, внутрь, в другой раздел),
// «Добавить задачу» внизу — редактором в строке.
import { useState, type DragEvent } from 'react';
import { before, ops, S, toast } from '../store.ts';
import { placeName } from '../lib.ts';
import { T } from '../toastText.ts';
import type { Id, Task } from '../types.ts';
import { byPos, parentOf } from '../views.ts';
import { Editor } from './Editor.tsx';
import { Icon } from './icons.tsx';
import type { QuickDefaults } from './state.ts';
import { TaskRow, type RowOpts } from './TaskRow.tsx';
import { UI } from './state.ts';
import { emit } from '../store.ts';

export type Mark = 'before' | 'after' | 'into';
export interface DropCtx {
  /** Порядок и вложенность меняются только в проекте и во «Входящих»; в «Сегодня» и фильтрах — своё (onDrop). */
  nest?: boolean;
  onDrop?: (dragged: Task, target: Task, mark: Mark) => void;
  /** Куда ложится брошенное в этот список (раздел, проект). */
  into?: Partial<Task>;
}

let dragId: Id | null = null;
export const draggingId = () => dragId;
export const setDragId = (id: Id | null) => {
  dragId = id;
};

/** Порядок по месту: перенумеровать соседей 1…n, отправить только изменившиеся. */
export async function reorder(siblings: Task[], moved: Task, target: Task | null, mark: Mark, extra: Record<string, unknown> = {}, said?: string) {
  const list = siblings.filter((x) => x.id !== moved.id);
  let i = target ? list.findIndex((x) => x.id === target.id) : list.length;
  if (i < 0) i = list.length;
  if (mark === 'after') i++;
  list.splice(i, 0, moved);
  const items = [];
  for (let k = 0; k < list.length; k++) {
    const x = list[k];
    const set: Record<string, unknown> = x.id === moved.id ? { ...extra } : {};
    if (x.pos !== k + 1) set.pos = k + 1;
    if (Object.keys(set).length) items.push({ op: 'task.set', id: x.id, set });
  }
  if (!items.length) return;
  // Отмена — прежние места и поля (как перетащил, так и вернул)
  const back = items.map((o) => ({ op: 'task.set', id: o.id, set: before(S.tasks.get(o.id) || moved, o.set) }));
  const ok = await ops(items).then(() => true, () => false);
  if (ok && said) toast(said, () => ops(back).catch(() => {}));
}

export function defaultDrop(all: Task[]) {
  return async (d: Task, target: Task, mark: Mark) => {
    if (mark === 'into') {
      if (target.id === d.id) return;
      const kids = all.filter((x) => parentOf(x) === target.id).sort(byPos);
      await reorder(kids, d, null, 'after', { why_id: target.id, project_id: target.project_id, deal_id: target.deal_id, section_id: target.section_id ?? null },
        T.moved('«' + target.title + '»'));
      return;
    }
    const parent = parentOf(target);
    const sibs = all.filter((x) => parentOf(x) === parent && (x.section_id ?? null) === (target.section_id ?? null)).sort(byPos);
    const extra: Record<string, unknown> = {};
    if (parentOf(d) !== parent) extra.why_id = parent;
    if ((d.section_id ?? null) !== (target.section_id ?? null) || d.project_id !== target.project_id || d.deal_id !== target.deal_id) {
      Object.assign(extra, { project_id: target.project_id, deal_id: target.deal_id, section_id: target.section_id ?? null });
    }
    await reorder(sibs, d, target, mark, extra, 'project_id' in extra ? T.moved(placeName(target)) : T.reordered);
  };
}

export function TaskList({ tasks, rowOpts = {}, add, drop, nest = true, empty, addLabel = 'Добавить задачу' }: {
  tasks: Task[]; rowOpts?: RowOpts; add?: QuickDefaults | false; drop?: DropCtx; nest?: boolean; empty?: React.ReactNode; addLabel?: string;
}) {
  const [collapsed, setCollapsed] = useState<Set<Id>>(new Set());
  const [adding, setAdding] = useState(false);
  const [mark, setMark] = useState<{ id: Id; m: Mark } | null>(null);
  const ids = new Set(tasks.map((t) => t.id));
  // Дерево: подзадача — под родителем, если родитель в этом же списке.
  const kids = new Map<Id, Task[]>();
  const roots: Task[] = [];
  for (const t of tasks) {
    const p = nest ? parentOf(t) : null;
    if (p && ids.has(p)) {
      if (!kids.has(p)) kids.set(p, []);
      kids.get(p)!.push(t);
    } else roots.push(t);
  }
  const onDrop = drop?.onDrop || defaultDrop([...S.tasks.values()]);
  const dragOpts = drop ? {
    onDragStart: (e: DragEvent, t: Task) => {
      dragId = t.id;
      e.dataTransfer.setData('text/x-task', t.id);
      e.dataTransfer.effectAllowed = 'move';
    },
    onDragOver: (e: DragEvent, t: Task, el: HTMLElement) => {
      if (!dragId || dragId === t.id) return;
      e.preventDefault();
      const r = el.getBoundingClientRect();
      const y = e.clientY - r.top, x = e.clientX - r.left;
      const m: Mark = drop.nest !== false && nest && y > r.height / 2 && x > 80 ? 'into' : y < r.height / 2 ? 'before' : 'after';
      if (!mark || mark.id !== t.id || mark.m !== m) setMark({ id: t.id, m });
    },
    onDrop: (e: DragEvent, t: Task) => {
      e.preventDefault();
      const d = dragId ? S.tasks.get(dragId) : null;
      const m = mark?.m || 'after';
      setMark(null);
      dragId = null;
      if (d && d.id !== t.id) onDrop(d, t, m);
    },
    onDragEnd: () => {
      dragId = null;
      setMark(null);
    },
  } : undefined;

  const sel = (t: Task) => {
    if (UI.sel.has(t.id)) UI.sel.delete(t.id);
    else UI.sel.add(t.id);
    emit();
  };
  const render = (t: Task, depth: number): React.ReactNode[] => {
    const ch = (kids.get(t.id) || []).sort(byPos);
    const isC = collapsed.has(t.id);
    const out: React.ReactNode[] = [
      <TaskRow key={t.id} t={t} opts={{
        ...rowOpts, depth, hasKids: ch.length > 0, collapsed: isC, selected: UI.sel.has(t.id), onSelect: sel,
        onToggle: () => {
          const n = new Set(collapsed);
          if (isC) n.delete(t.id);
          else n.add(t.id);
          setCollapsed(n);
        },
        drag: dragOpts ? { ...dragOpts, mark: mark && mark.id === t.id ? mark.m : null } : undefined,
      }} />,
    ];
    if (!isC) for (const c of ch) out.push(...render(c, depth + 1));
    return out;
  };
  return (
    <div className="tlist">
      {roots.map((t) => render(t, 0))}
      {!tasks.length && empty && !adding ? empty : null}
      {add !== false && add ? (adding
        ? <Editor inline keepOpen defaults={add} onClose={() => setAdding(false)} />
        : (
          <button className="add-task" onClick={() => setAdding(true)}>
            <span className="plus"><Icon name="plus" size={16} /></span>{addLabel}
          </button>
        )) : null}
    </div>
  );
}
