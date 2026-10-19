// Несколько выбранных задач (Ctrl/Shift+клик) — панель снизу, как у Todoist: срок, переместить, метки, приоритет,
// «Сейчас», выполнить, удалить. И окно нового проекта.
import { T } from '../toastText.ts';
import { placeName } from '../lib.ts';
import { useState } from 'react';
import { plural } from '../dates.ts';
import { PALETTE } from '../lib.ts';
import { emit, op1, ops, S, syncSoon, toast, useStore } from '../store.ts';
import type { Task } from '../types.ts';
import { Icon, PrioFlag } from './icons.tsx';
import { openPop } from './pop.tsx';
import { LabelPicker, PriorityMenu, ProjectPicker, Scheduler } from './pickers.tsx';
import { UI } from './state.ts';

/** Выполнить выбранные: «N задач выполнено»; «Отменить» у обычного — снова открыто, у повтора — прежний срок и счётчик,
 *  а сброшенное повтором внутри — снова сделанным (restore, без дубля в книге выполнений). */
function doneAll(ts: Task[]) {
  ops(ts.map((t) => ({ op: 'task.done', id: t.id }))).then((res) => {
    const back = ts.flatMap((t, i) => (res[i]?.recurred
      ? [{ op: 'task.set', id: t.id, set: { due_date: t.due_date, focus_on: t.focus_on ?? null, done_count: t.done_count ?? 0, ...(t.remind_at ? { remind_at: t.remind_at } : {}) } },
        ...((res[i].reset || []) as string[]).map((id) => ({ op: 'task.set', id, set: { status: 'done' }, restore: true }))]
      : [{ op: 'task.reopen', id: t.id }]));
    toast(T.done(ts.length), () => ops(back).catch(() => {}));
    syncSoon().catch(() => {}); // сброшенное повтором внутри — открыть на экране
  }).catch(() => {});
}

export function BulkBar() {
  useStore();
  const ts = [...UI.sel].map((id) => S.tasks.get(id)).filter(Boolean) as Task[];
  if (!ts.length) return null;
  const clear = () => {
    UI.sel.clear();
    emit();
  };
  const each = (set: (t: Task) => Record<string, unknown>, said: string) => {
    const back = ts.map((t) => ({ op: 'task.set', id: t.id, set: Object.fromEntries(Object.keys(set(t)).map((k) => [k, (t as any)[k] ?? null])) }));
    ops(ts.map((t) => ({ op: 'task.set', id: t.id, set: set(t) }))).then(() => toast(said, () => ops(back).catch(() => {}))).catch(() => {});
  };
  return (
    <div style={{ position: 'fixed', left: '50%', transform: 'translateX(-50%)', bottom: 76, zIndex: 45, display: 'flex', alignItems: 'center', gap: 4,
      background: 'var(--raised)', border: '1px solid var(--border)', borderRadius: 12, boxShadow: 'var(--sh2)', padding: '6px 8px', maxWidth: 'calc(100vw - 16px)', flexWrap: 'wrap' }}>
      <span style={{ fontWeight: 600, padding: '0 8px' }}>{plural(ts.length, 'задача', 'задачи', 'задач')}</span>
      <button className="tb-btn" onClick={(e) => openPop(e.currentTarget, (close) => <Scheduler tasks={ts} value={{ due_date: null, due_time: null, recur: null, due_rule: null }} close={close}
        onPick={(v) => each(() => ({ due_date: v.due_date, ...(v.due_date ? {} : { due_time: null }) }), T.due(v.due_date, S.today))} />, { cls: 'sched' })}><Icon name="calendar" size={18} />Срок</button>
      <button className="tb-btn" onClick={(e) => openPop(e.currentTarget, (close) => <ProjectPicker value={{ project_id: null, deal_id: null, section_id: null }} close={close}
        onPick={(p) => each(() => ({ project_id: p.project_id, deal_id: p.deal_id, section_id: p.section_id }), T.moved(placeName(p), ts.length))} />)}><Icon name="door" size={18} />Переместить</button>
      <button className="tb-btn" onClick={(e) => openPop(e.currentTarget, () => <LabelPicker value={[]} onPick={(l) => each((t) => ({ labels: [...new Set([...(t.labels || []), ...l])] }), 'Метки добавил')} />)}><Icon name="tag" size={18} />Метки</button>
      <button className="tb-btn" onClick={(e) => openPop(e.currentTarget, (close) => <PriorityMenu value={4} close={close} onPick={(p) => each(() => ({ priority: p }), T.priority(p))} />)}><PrioFlag p={2} size={18} />Приоритет</button>
      <button className="tb-btn" onClick={() => each(() => ({ focus_on: S.today }), 'В «Сейчас»')}><Icon name="bolt" size={18} />Сейчас</button>
      <button className="tb-btn" onClick={() => { doneAll(ts); clear(); }}><Icon name="done" size={18} />Выполнить</button>
      <button className="tb-btn" style={{ color: 'var(--p1)' }} onClick={() => { const ids = ts.map((t) => t.id); ops(ids.map((id) => ({ op: 'task.cancel', id }))).then(() => toast(T.deleted(ids.length), () => ops(ids.map((id) => ({ op: 'task.reopen', id }))).catch(() => {}))).catch(() => {}); clear(); }}><Icon name="trash" size={18} /></button>
      <button className="icon-btn" title="Снять выбор (Esc)" onClick={clear}><Icon name="x" size={18} /></button>
    </div>
  );
}

export function ProjectDialog({ close }: { close: () => void }) {
  const [name, setName] = useState('');
  const [kind, setKind] = useState('internal');
  const [color, setColor] = useState('blue');
  const save = () => {
    if (!name.trim()) return;
    const id = crypto.randomUUID();
    op1({ op: 'project.create', data: { id, name: name.trim(), kind, sphere: kind === 'personal' ? 'home' : 'work', color } })
      .then(() => { close(); location.hash = '#/project/' + id; }).catch(() => {});
  };
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
      <div className="dialog">
        <h3>Новый проект</h3>
        <div className="form">
          <label>Название<input autoFocus value={name} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') save(); }} /></label>
          <label>Что это
            <select value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="internal">Свой проект (работа)</option>
              <option value="client">Клиент — появится и в CRM</option>
              <option value="personal">Личное, дом</option>
            </select>
          </label>
          <label>Цвет
            <select value={color} onChange={(e) => setColor(e.target.value)}>
              {Object.entries(PALETTE).map(([k, [, w]]) => <option key={k} value={k}>{w}</option>)}
            </select>
          </label>
        </div>
        <div className="foot"><button className="btn sec" onClick={close}>Отмена</button><button className="btn pri" disabled={!name.trim()} onClick={save}>Добавить</button></div>
      </div>
    </div>
  );
}
