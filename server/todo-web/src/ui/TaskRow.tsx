// Строка задачи — анатомия Todoist: круглая галочка цвета приоритета, название, серое описание, строка меты
// (срок цветом близости, повтор, дедлайн, внутри, комментарии, метки, человек), справа — проект; при наведении — кнопки.
import { useRef, useState, type DragEvent, type PointerEvent as RPE } from 'react';
import { deadlineWords, dueWords, dueTone, nowHm } from '../dates.ts';
import { cls, dealShort, labelColor, personName, projectColor } from '../lib.ts';
import { completeTask, ops, S, setTask } from '../store.ts';
import type { Task } from '../types.ts';
import { childrenOf } from '../views.ts';
import { Icon } from './icons.tsx';
import { openPop } from './pop.tsx';
import { Scheduler } from './pickers.tsx';
import { openTask, UI } from './state.ts';
import { TaskMenu } from './TaskMenu.tsx';

export interface RowOpts {
  showProject?: boolean;
  depth?: number;
  hasKids?: boolean;
  collapsed?: boolean;
  onToggle?: () => void;
  drag?: {
    onDragStart: (e: DragEvent, t: Task) => void;
    onDragOver: (e: DragEvent, t: Task, el: HTMLElement) => void;
    onDrop: (e: DragEvent, t: Task) => void;
    onDragEnd: () => void;
    mark?: 'before' | 'after' | 'into' | null;
  };
  selected?: boolean;
  onSelect?: (t: Task, e: React.MouseEvent) => void;
  hideDate?: boolean;
}

export function Checkbox({ t, onDone }: { t: Task; onDone?: () => void }) {
  const p = t.priority ?? 4;
  const done = t.status !== 'open';
  return (
    <button className={cls('cb', p < 4 && 'p' + p, done && 'done')} aria-label={done ? 'Вернуть в работу' : 'Выполнить'}
      onClick={(e) => {
        e.stopPropagation();
        if (done) ops([{ op: 'task.reopen', id: t.id }]).catch(() => {});
        else {
          onDone?.();
          completeTask(t, e.shiftKey).catch(() => {});
        }
      }}>
      <i><Icon name="checkSm" size={14} sw={2} /></i>
    </button>
  );
}

export function Meta({ t, showProject, hideDate }: { t: Task; showProject?: boolean; hideDate?: boolean }) {
  const today = S.today;
  const kids = childrenOf(t.id);
  const kidsDone = kids.filter((k) => k.status === 'done').length;
  const comments = [...S.comments.values()].filter((c) => c.task_id === t.id && !c.deleted_at).length;
  const proj = t.project_id ? S.projects.get(t.project_id) : null;
  const items = [];
  if (t.due_date && hideDate && t.due_time) {
    items.push(<span key="d" className="m"><Icon name="alarm" size={12} />{t.due_time.slice(0, 5)}{t.recur ? <Icon name="repeat" size={12} /> : null}</span>);
  } else if (t.due_date && hideDate && t.recur) {
    items.push(<span key="d" className="m"><Icon name="repeat" size={12} /></span>);
  }
  if (t.due_date && !hideDate) {
    items.push(
      <span key="d" className={'m ' + dueTone(t.due_date, today, t.due_time, nowHm())}>
        <Icon name="calendar" size={12} />{dueWords(t.due_date, today)}{t.due_time ? ' ' + t.due_time.slice(0, 5) : ''}
        {t.recur ? <Icon name="repeat" size={12} /> : null}
      </span>,
    );
  }
  if (t.deadline) {
    const near = (Date.parse(t.deadline) - Date.parse(today)) / 864e5 < 7;
    items.push(<span key="dl" className={'m' + (t.deadline < today ? ' d-over' : near ? ' d-tom' : '')}><Icon name="deadline" size={12} />{deadlineWords(t.deadline, today)}</span>);
  }
  if (kids.length) items.push(<span key="k" className="m"><Icon name="sub" size={12} />{kidsDone}/{kids.length}</span>);
  if (comments) items.push(<span key="c" className="m"><Icon name="comment" size={12} />{comments}</span>);
  if (t.remind_at || t.remind_place) items.push(<span key="r" className="m" title={t.remind_place || ''}><Icon name="alarm" size={12} /></span>);
  if (t.person_id) {
    const who = personName(t.person_id);
    const w = t.ball === 'waiting' ? 'жду: ' : t.ball === 'agenda' ? 'обсудить: ' : '';
    items.push(<span key="p" className="m"><Icon name="user" size={12} />{w}{who}</span>);
  }
  for (const l of t.labels || []) items.push(<span key={'l' + l} className="m lbl-chip" style={{ color: labelColor(l) }}><Icon name="tag" size={12} />{l}</span>);
  if (t.why_id && t.why_goal && t.why_title) items.push(<span key="w" className="m" title="Зачем"><Icon name="target" size={12} />{t.why_title}</span>);
  if (showProject) {
    const where = t.deal_id ? `${proj?.name || ''} / ${dealShort(t.deal_id)}` : proj ? proj.name + (t.section_name ? ' / ' + t.section_name : '') : 'Входящие';
    items.push(
      <span key="pr" className="m where">
        {where}
        {proj ? <span className="hash" style={{ color: projectColor(proj), fontSize: 13 }}>#</span> : <Icon name="inbox" size={12} />}
      </span>,
    );
  }
  if (!items.length) return null;
  return <div className="tmeta">{items}</div>;
}

export function TaskRow({ t, opts = {} }: { t: Task; opts?: RowOpts }) {
  const ref = useRef<HTMLDivElement>(null);
  const [leaving, setLeaving] = useState(false);
  const [dx, setDx] = useState(0);
  const sw = useRef<{ x: number; y: number; id: number; on: boolean } | null>(null);
  const d = opts.drag;
  // Свайп на телефоне: вправо — выполнить, влево — срок (как у Todoist на Android).
  const down = (e: RPE) => {
    if (e.pointerType !== 'touch') return;
    sw.current = { x: e.clientX, y: e.clientY, id: e.pointerId, on: false };
  };
  const move = (e: RPE) => {
    const s = sw.current;
    if (!s) return;
    const mx = e.clientX - s.x, my = e.clientY - s.y;
    if (!s.on) {
      if (Math.abs(my) > 10) { sw.current = null; return; }
      if (Math.abs(mx) > 12) s.on = true;
    }
    if (s.on) setDx(mx);
  };
  const up = () => {
    const s = sw.current;
    sw.current = null;
    if (!s?.on) return;
    const w = ref.current?.offsetWidth || 300;
    if (dx > w * 0.3) {
      setLeaving(true);
      completeTask(t).catch(() => {});
    } else if (dx < -w * 0.3 && ref.current) {
      openPop(ref.current, (close) => <Scheduler value={t as any} close={close} onPick={(v) => setTask(t, v as any, 'Срок поменял')} />, { cls: 'sched' });
    }
    setDx(0);
  };
  return (
    <div ref={ref} data-id={t.id}
      className={cls('task', opts.selected && 'sel', UI.focus === t.id && 'focus', leaving && 'done-anim', t.status !== 'open' && 'closed',
        d?.mark === 'before' && 'drop-before', d?.mark === 'after' && 'drop-after', d?.mark === 'into' && 'drop-into')}
      style={{ marginLeft: (opts.depth || 0) * 28, ...(dx ? { transform: `translateX(${dx}px)`, transition: 'none' } : {}) }}
      draggable={!!d && t.status === 'open'}
      onDragStart={d ? (e) => d.onDragStart(e, t) : undefined}
      onDragOver={d ? (e) => d.onDragOver(e, t, ref.current!) : undefined}
      onDrop={d ? (e) => d.onDrop(e, t) : undefined}
      onDragEnd={d ? d.onDragEnd : undefined}
      onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={() => { sw.current = null; setDx(0); }}
      onClick={(e) => {
        if (opts.onSelect && (e.metaKey || e.ctrlKey || e.shiftKey)) {
          opts.onSelect(t, e);
          return;
        }
        openTask(t.id);
      }}
      onContextMenu={(e) => {
        e.preventDefault();
        openPop(new DOMRect(e.clientX, e.clientY, 0, 0), (close) => <TaskMenu t={t} close={close} />);
      }}>
      {d ? <span className="grip d-only" onClick={(e) => e.stopPropagation()}><Icon name="grip" size={16} /></span> : null}
      {opts.hasKids ? (
        <button className="tw" onClick={(e) => { e.stopPropagation(); opts.onToggle?.(); }} aria-label="Свернуть">
          <Icon name={opts.collapsed ? 'chevR' : 'chevD'} size={16} />
        </button>
      ) : null}
      <Checkbox t={t} onDone={() => setLeaving(true)} />
      <div className="tbody">
        <div className="ttl">{t.title}</div>
        {t.notes ? <div className="tdesc">{t.notes.split('\n')[0]}</div> : null}
        <Meta t={t} showProject={opts.showProject} hideDate={opts.hideDate} />
      </div>
      {t.status === 'open' ? (
        <div className="tacts d-only" onClick={(e) => e.stopPropagation()}>
          <button className="icon-btn sm" title="Изменить" onClick={() => openTask(t.id, true)}><Icon name="pencil" size={18} /></button>
          <button className="icon-btn sm" title="Срок" onClick={(e) => openPop(e.currentTarget, (close) => <Scheduler value={t as any} close={close} onPick={(v) => setTask(t, v as any, 'Срок поменял')} />, { cls: 'sched' })}><Icon name="calendar" size={18} /></button>
          <button className="icon-btn sm" title="Комментарий" onClick={() => openTask(t.id, false, true)}><Icon name="comment" size={18} /></button>
          <button className="icon-btn sm" title="Ещё" onClick={(e) => openPop(e.currentTarget, (close) => <TaskMenu t={t} close={close} />)}><Icon name="more" size={18} /></button>
        </div>
      ) : null}
    </div>
  );
}
