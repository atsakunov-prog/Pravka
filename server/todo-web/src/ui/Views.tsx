// Виды: Сейчас, Входящие, Сегодня, Предстоящее, проект (разделы, сделки, доска), сделка, метка, фильтр, человек,
// дерево целей, выполненные.
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { addDays, dayHead, MONTHS_NOM, mondayOf, plural, shortDate, WD, WD_SHORT, weekday } from '../dates.ts';
import { compile, FilterError, runFilter } from '../filter.ts';
import { crmUrl, dealShort, go, projectColor, STAGE } from '../lib.ts';
import { op1, ops, S, saveNowOrder, saveTodo, setTask, todoSettings, toast, useStore } from '../store.ts';
import type { Id, Task } from '../types.ts';
import {
  byPos, completedRecent, dayList, search as searchAll, dealTasks, dirName, goalChildren, goalDir, goalTasks, goals, inbox, isListed, labelTasks,
  listed, liveDeals, nowList, overdue, personTasks, projectTasks, sectionsOf, upcomingDays, whyPath,
} from '../views.ts';
import { filterCtx } from './ctx.ts';
import { DisplayMenu, groupTasks, shown, viewOpts } from './Display.tsx';
import { viewLabel } from '../viewopts.ts';
import { Icon, TodayIcon } from './icons.tsx';
import { openPop } from './pop.tsx';
import { ColorPicker, Scheduler } from './pickers.tsx';
import { openDialog, openDictate, openQuick } from './state.ts';
import { TaskList, reorder, draggingId, setDragId } from './TaskList.tsx';
import { Checkbox, Meta } from './TaskRow.tsx';
import { openTask } from './state.ts';

// ── Каркас вида: шапка, заголовок, кнопки справа ──────────────────────────

export function Page({ title, icon, sub, actions, children, crumbs, wide }: {
  title: ReactNode; icon?: ReactNode; sub?: ReactNode; actions?: ReactNode; children: ReactNode; crumbs?: ReactNode; wide?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [stuck, setStuck] = useState(false);
  useEffect(() => {
    const main = ref.current?.closest('.main');
    if (!main) return;
    const on = () => setStuck(main.scrollTop > 40);
    main.addEventListener('scroll', on);
    return () => main.removeEventListener('scroll', on);
  }, []);
  return (
    <div ref={ref}>
      <div className={'topbar' + (stuck ? ' stuck' : '')}>
        <MobileMenuButton />
        <div className="crumbs">{stuck ? <span className="tb-title">{title}</span> : crumbs}</div>
        <button className="icon-btn m-only" onClick={() => openDictate()} aria-label="Надиктовать задачи"><Icon name="mic" size={22} /></button>
        {actions}
      </div>
      <div className={'view' + (wide ? ' wide' : '')}>
        <h1 className="view-h">{icon}{title}</h1>
        {sub ? <div className="view-sub">{sub}</div> : null}
        {children}
      </div>
    </div>
  );
}

import { toggleSide } from './state.ts';
function MobileMenuButton() {
  return <button className="icon-btn m-only" onClick={toggleSide} aria-label="Меню"><Icon name="menu" size={22} /></button>;
}

function DisplayBtn({ vkey, board, groups }: { vkey: string; board?: boolean; groups?: boolean }) {
  // Подпись — как у Todoist: «Отсортировано по…», «Сгруппировано по…», «Свой вид»; рядом — сколько настроек изменено.
  const { text, n } = viewLabel(viewOpts(vkey));
  return (
    <button className="tb-btn" title={text} onClick={(e) => openPop(e.currentTarget, () => <DisplayMenu vkey={vkey} opts={viewOpts(vkey)} board={board} groups={groups} />)}>
      <Icon name="sliders" size={20} /><span className="d-only">{text}</span>
      {n ? <span className="vb-n">{n}</span> : null}
    </button>
  );
}

const Empty = ({ icon = 'done', big, text }: { icon?: string; big: string; text?: string }) => (
  <div className="empty"><Icon name={icon} size={56} sw={1} /><div className="big">{big}</div>{text ? <div>{text}</div> : null}</div>
);

/** Список со «Видом»: сортировка, группировка, выполненные внизу. */
function Displayed({ tasks, vkey, add, showProject, nest = true, manualDrop }: {
  tasks: Task[]; vkey: string; add?: any; showProject?: boolean; nest?: boolean; manualDrop?: boolean;
}) {
  const o = viewOpts(vkey);
  const sorted = shown(tasks, o);
  const groups = groupTasks(sorted, o.group);
  const manual = (!o.sort || o.sort === 'manual') && (!o.group || o.group === 'none');
  return (
    <>
      {groups.map((g) => (
        <div key={g.key} className="sect">
          {g.title ? <div className="sect-h">{g.title}<span className="n">{g.tasks.length}</span></div> : null}
          <TaskList tasks={g.tasks} rowOpts={{ showProject }} nest={nest && manual} add={groups.length === 1 ? add : false} drop={manual && manualDrop !== false ? {} : undefined} />
        </div>
      ))}
      {o.done ? <DoneList filter={(t) => tasks.length === 0 || true ? isDoneHere(t, vkey) : false} /> : null}
    </>
  );
}
function isDoneHere(t: Task, vkey: string): boolean {
  const [kind, id] = vkey.split(':');
  if (kind === 'project') return t.project_id === id && !t.deal_id;
  if (kind === 'deal') return t.deal_id === id;
  if (kind === 'inbox') return !t.project_id;
  if (kind === 'label') return (t.labels || []).includes(id);
  return false;
}
function DoneList({ filter }: { filter: (t: Task) => boolean }) {
  const done = completedRecent(30).filter(filter);
  if (!done.length) return null;
  return (
    <div className="sect">
      <div className="sect-h">Выполнено<span className="n">{done.length}</span></div>
      <TaskList tasks={done} nest={false} />
    </div>
  );
}

// ── Сейчас ───────────────────────────────────────────────────────────────

export function NowView() {
  useStore();
  const list = nowList();
  return (
    <Page title="Сейчас" icon={<Icon name="bolt" size={26} style={{ color: 'var(--accent)' }} />} crumbs="Сейчас"
      sub={<span>{list.length ? plural(list.length, 'задача', 'задачи', 'задач') + ' — то, что ты сам отметил на сегодня' : 'Отмеченное тобой на сегодня'}</span>}>
      <TaskList tasks={list} rowOpts={{ showProject: true }} nest={false} add={{ focus_on: S.today }}
        drop={{ nest: false, onDrop: (d, target, m) => {
          const ids = list.map((x) => x.id).filter((x) => x !== d.id);
          let i = ids.indexOf(target.id);
          if (m === 'after') i++;
          ids.splice(i, 0, d.id);
          if (d.focus_on !== S.today) setTask(d, { focus_on: S.today }).catch(() => {});
          saveNowOrder(ids).catch(() => {});
        } }}
        empty={<Empty icon="bolt" big="Сейчас пусто" text="Отметь молнией то, чем займёшься сегодня: в меню задачи, в окне задачи или «!сейчас» в строке." />} />
    </Page>
  );
}

// ── Входящие ─────────────────────────────────────────────────────────────

export function InboxView() {
  useStore();
  const list = inbox();
  return (
    <Page title="Входящие" crumbs="Входящие" actions={<DisplayBtn vkey="inbox" board={false} />}>
      <Displayed tasks={list} vkey="inbox" add={{}} />
      {!list.length ? <Empty icon="inbox" big="Во «Входящих» пусто" text="Сюда падает всё без проекта." /> : null}
    </Page>
  );
}

// ── Сегодня ──────────────────────────────────────────────────────────────

export function TodayView() {
  useStore();
  const over = overdue();
  const today = dayList(S.today);
  const [openOver, setOpenOver] = useState(true);
  const n = over.length + today.length;
  const reschedule = (el: HTMLElement) => openPop(el, (close) => (
    <Scheduler tasks={over} value={{ due_date: null, due_time: null, recur: null, due_rule: null }} close={close} onPick={(v) => {
      // «Без срока» снимает и время, а у повторяющегося сервер снимает и повтор — отмена возвращает всё
      const set = v.due_date ? { due_date: v.due_date } : { due_date: null, due_time: null };
      const items = over.map((t) => ({ op: 'task.set', id: t.id, set }));
      const back = over.map((t) => ({ op: 'task.set', id: t.id, set: v.due_date ? { due_date: t.due_date }
        : { due_date: t.due_date, due_time: t.due_time ?? null, ...(t.recur ? { recur: t.recur, due_rule: t.due_rule } : {}) } }));
      ops(items).then(() => toast(`${v.due_date ? 'Перенёс' : 'Снял срок:'} ${plural(over.length, 'задачу', 'задачи', 'задач')}`, () => ops(back).catch(() => {}))).catch(() => {});
    }} />
  ), { cls: 'sched' });
  const dropToday = { nest: false, onDrop: (d: Task) => { if (d.due_date !== S.today) setTask(d, { due_date: S.today }, 'Срок — сегодня').catch(() => {}); } };
  return (
    <Page title="Сегодня" crumbs="Сегодня" sub={n ? <span><Icon name="done" size={14} /> {plural(n, 'задача', 'задачи', 'задач')}</span> : null}>
      {over.length ? (
        <div className="sect over">
          <div className="sect-h">
            <button className="tw" onClick={() => setOpenOver(!openOver)}><Icon name={openOver ? 'chevD' : 'chevR'} size={16} /></button>
            Просрочено<span className="n">{over.length}</span>
            <span className="right"><button className="act" onClick={(e) => reschedule(e.currentTarget)}>Перенести</button></span>
          </div>
          {openOver ? <TaskList tasks={over} rowOpts={{ showProject: true }} nest={false} /> : null}
        </div>
      ) : null}
      <div className="sect">
        <div className="sect-h"
          onDragOver={(e) => { if (draggingId()) e.preventDefault(); }}
          onDrop={(e) => { e.preventDefault(); const d = draggingId() && S.tasks.get(draggingId()!); if (d) dropToday.onDrop(d); }}>
          {dayHead(S.today, S.today).join(' · ')}<span className="n">{today.length || ''}</span>
        </div>
        <TaskList tasks={today} rowOpts={{ showProject: true, hideDate: false }} nest={false} add={{ due_date: S.today }} drop={dropToday} />
      </div>
      {!n ? <Empty big="Всё сделано!" text="На сегодня задач нет — хорошего дня." /> : null}
    </Page>
  );
}

// ── Предстоящее ──────────────────────────────────────────────────────────

export function UpcomingView() {
  useStore();
  const [start, setStart] = useState(mondayOf(S.today));
  const [days, setDays] = useState(21);
  const from = start < S.today ? S.today : start;
  const list = upcomingDays(from, days);
  const over = start <= S.today ? overdue() : [];
  const busy = new Set(listed().filter((t) => t.due_date).map((t) => t.due_date!));
  const scrollTo = (d: string) => {
    if (d < S.today) return;
    if (d >= addDays(from, days)) setDays(Math.max(days, 7 + Math.round((Date.parse(d) - Date.parse(from)) / 864e5)));
    requestAnimationFrame(() => document.getElementById('day-' + d)?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  };
  const week = Array.from({ length: 7 }, (_, i) => addDays(start, i));
  const [dropDay, setDropDay] = useState<string | null>(null);
  const moveTo = (day: string) => {
    const d = draggingId() && S.tasks.get(draggingId()!);
    setDropDay(null);
    if (d && d.due_date !== day) setTask(d, { due_date: day }, 'Срок — ' + shortDate(day, S.today)).catch(() => {});
  };
  return (
    <Page title="Предстоящее" crumbs="Предстоящее">
      <div className="up-head">
        <div className="up-nav">
          <span className="month">{MONTHS_NOM[+start.slice(5, 7) - 1]} {start.slice(0, 4)}</span>
          <button className="icon-btn" onClick={() => setStart(addDays(start, -7))} disabled={start <= mondayOf(S.today)}><Icon name="chevL" size={20} /></button>
          <button className="icon-btn" onClick={() => setStart(addDays(start, 7))}><Icon name="chevR" size={20} /></button>
          <button className="btn sec" onClick={() => { setStart(mondayOf(S.today)); scrollTo(S.today); }}>Сегодня</button>
        </div>
        <div className="week">
          {week.map((d) => (
            <button key={d} className={[d === S.today ? 'today' : '', d < S.today ? 'past' : ''].join(' ')} onClick={() => scrollTo(d)}>
              <span className="wd">{WD_SHORT[weekday(d)]}</span><span className="n">{+d.slice(8, 10)}</span>
              {busy.has(d) ? <span className="busy" /> : null}
            </button>
          ))}
        </div>
      </div>
      {over.length ? (
        <div className="sect over">
          <div className="sect-h">Просрочено<span className="n">{over.length}</span></div>
          <TaskList tasks={over} rowOpts={{ showProject: true }} nest={false} />
        </div>
      ) : null}
      {list.map(({ day, tasks }) => (
        <div key={day} id={'day-' + day}>
          <div className={'day-h' + (dropDay === day ? ' drop' : '')}
            onDragOver={(e) => { if (draggingId()) { e.preventDefault(); setDropDay(day); } }} onDragLeave={() => setDropDay(null)}
            onDrop={(e) => { e.preventDefault(); moveTo(day); }}>
            {dayHead(day, S.today).map((x, i) => <span key={i} className={i ? 'rest' : ''}>{i ? <span className="dot">· </span> : null}{x}</span>)}
          </div>
          <TaskList tasks={tasks} rowOpts={{ showProject: true, hideDate: true }} nest={false} add={{ due_date: day }}
            drop={{ nest: false, onDrop: (d) => { if (d.due_date !== day) setTask(d, { due_date: day }, 'Срок — ' + shortDate(day, S.today)).catch(() => {}); } }} />
        </div>
      ))}
      <button className="btn sec" style={{ margin: '16px 0' }} onClick={() => setDays(days + 21)}>Показать ещё три недели</button>
    </Page>
  );
}

// ── Проект ───────────────────────────────────────────────────────────────

function SectionHead({ s, count, collapsed, toggle }: { s: { id: string; name: string; project_id: string }; count: number; collapsed: boolean; toggle: () => void }) {
  const [edit, setEdit] = useState(false);
  const [name, setName] = useState(s.name);
  return (
    <div className="sect-h"
      onDragOver={(e) => { if (draggingId()) e.preventDefault(); }}
      onDrop={(e) => {
        e.preventDefault();
        const d = draggingId() && S.tasks.get(draggingId()!);
        if (d && d.section_id !== s.id) setTask(d, { section_id: s.id, project_id: s.project_id, deal_id: null }, 'Переложил в «' + s.name + '»').catch(() => {});
      }}>
      <button className="tw" onClick={toggle}><Icon name={collapsed ? 'chevR' : 'chevD'} size={16} /></button>
      {edit ? (
        <input autoFocus value={name} onChange={(e) => setName(e.target.value)} style={{ font: 'inherit', border: '1px solid var(--border)', borderRadius: 6, padding: '2px 6px', flex: 1 }}
          onBlur={() => { setEdit(false); if (name.trim() && name !== s.name) op1({ op: 'section.set', id: s.id, set: { name: name.trim() } }).catch(() => {}); }}
          onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); if (e.key === 'Escape') { setName(s.name); setEdit(false); } }} />
      ) : <span onDoubleClick={() => setEdit(true)}>{s.name}</span>}
      <span className="n">{count || ''}</span>
      <span className="right">
        <button className="icon-btn sm more" onClick={(e) => openPop(e.currentTarget, (close) => (
          <div>
            <button className="mi" onClick={() => { close(); setEdit(true); }}><Icon name="pencil" size={18} />Переименовать</button>
            <button className="mi" onClick={() => { close(); op1({ op: 'section.set', id: s.id, set: { archived: true } }).then(() => toast('Раздел в архиве, задачи остались в проекте', () => op1({ op: 'section.set', id: s.id, set: { archived: false } }).catch(() => {}))).catch(() => {}); }}>
              <Icon name="archive" size={18} />В архив
            </button>
          </div>
        ))}><Icon name="more" size={16} /></button>
      </span>
    </div>
  );
}

function AddSection({ pid, pos }: { pid: Id; pos: number }) {
  const [on, setOn] = useState(false);
  const [name, setName] = useState('');
  if (!on) return <div className="add-sect" onClick={() => setOn(true)}><span>Добавить раздел</span></div>;
  const save = () => {
    if (name.trim()) op1({ op: 'section.create', data: { project_id: pid, name: name.trim(), pos } }).catch(() => {});
    setOn(false);
    setName('');
  };
  return (
    <div style={{ margin: '12px 0', display: 'flex', gap: 8 }}>
      <input autoFocus placeholder="Название раздела" value={name} onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') save(); if (e.key === 'Escape') setOn(false); }}
        style={{ flex: 1, border: '1px solid var(--border)', borderRadius: 6, height: 32, padding: '0 8px', fontWeight: 600, background: 'var(--raised)' }} />
      <button className="btn pri" onClick={save} disabled={!name.trim()}>Добавить раздел</button>
      <button className="btn sec" onClick={() => setOn(false)}>Отмена</button>
    </div>
  );
}

export function ProjectView({ id }: { id: Id }) {
  useStore();
  const p = S.projects.get(id);
  const [coll, setColl] = useState<Set<string>>(new Set());
  if (!p) return <Page title="Проект не найден"><Empty icon="hash" big="Такого проекта нет или он не виден" /></Page>;
  const vkey = 'project:' + id;
  const o = viewOpts(vkey);
  const own = projectTasks(id);
  const secs = sectionsOf(id);
  const deals = liveDeals(id);
  const loose = own.filter((t) => !t.section_id || !secs.some((s) => s.id === t.section_id));
  const toggle = (k: string) => { const n = new Set(coll); if (n.has(k)) n.delete(k); else n.add(k); setColl(n); };
  const kind = p.kind === 'client' ? 'Клиент' : p.kind === 'internal' ? 'Свой проект' : 'Личное';
  const header = (
    <span style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <span>{kind}</span>
      {p.kind === 'client' ? <><span className="dot">·</span><a href={crmUrl('p', id)} target="_blank" rel="noreferrer" style={{ color: 'var(--accent-text)' }}>Карточка клиента в CRM ↗</a></> : null}
      {deals.length ? <span className="dot">·</span> : null}
      {deals.map((d) => <a key={d.id} className="chip" href={'#/deal/' + d.id}><Icon name="deal" size={14} />{dealShort(d.id)}<span style={{ color: 'var(--fg3)' }}>{STAGE[d.stage] || ''}</span></a>)}
    </span>
  );
  const actions = (
    <>
      <DisplayBtn vkey={vkey} />
      <button className="tb-btn" onClick={(e) => openPop(e.currentTarget, (close) => (
        <div>
          <button className="mi" onClick={() => { close(); openQuick({ project_id: id }); }}><Icon name="plus" size={18} />Добавить задачу</button>
          <button className="mi" onClick={() => { close(); setTimeout(() => (document.querySelector('.add-sect') as HTMLElement)?.click(), 50); }}><Icon name="list" size={18} />Добавить раздел</button>
          <button className="mi" onClick={(ev) => { const a = (ev.currentTarget as HTMLElement).getBoundingClientRect(); close(); setTimeout(() => openPop(a, (c2) => <ColorPicker value={p.color || null} close={c2} onPick={(c) => op1({ op: 'project.set', id, set: { color: c } }).catch(() => {})} />), 0); }}>
            <span style={{ width: 18, height: 18, display: 'grid', placeItems: 'center' }}><span style={{ width: 12, height: 12, borderRadius: 6, background: projectColor(p) }} /></span>Цвет
          </button>
          <FavItem k={'p:' + id} close={close} />
          {p.kind === 'client' ? <a className="mi" href={crmUrl('p', id)} target="_blank" rel="noreferrer" onClick={close}><Icon name="deal" size={18} />Карточка в CRM</a> : null}
        </div>
      ))}><Icon name="more" size={20} /></button>
    </>
  );
  const title = <><span className="hash" style={{ color: projectColor(p), fontSize: 24 }}>#</span>{p.name}</>;
  if (o.layout === 'board') {
    return (
      <Page title={title} crumbs="Мои проекты /" sub={header} actions={actions} wide>
        <div className="board">
          <BoardCol title="Без раздела" tasks={shown(loose, o)} add={{ project_id: id }} onDropTask={(d) => setTask(d, { section_id: null, project_id: id, deal_id: null }).catch(() => {})} />
          {secs.map((s) => (
            <BoardCol key={s.id} title={s.name} tasks={shown(own.filter((t) => t.section_id === s.id), o)} add={{ project_id: id, section_id: s.id }}
              onDropTask={(d) => setTask(d, { section_id: s.id, project_id: id, deal_id: null }).catch(() => {})} />
          ))}
          {deals.map((d) => (
            <BoardCol key={d.id} title={dealShort(d.id)} icon="deal" tasks={shown(dealTasks(d.id), o)} add={{ project_id: id, deal_id: d.id }}
              onDropTask={(x) => setTask(x, { deal_id: d.id, project_id: id, section_id: null }).catch(() => {})} />
          ))}
          <div className="col"><AddSection pid={id} pos={secs.length + 1} /></div>
        </div>
      </Page>
    );
  }
  const grouped = o.group && o.group !== 'none';
  return (
    <Page title={title} crumbs="Мои проекты /" sub={header} actions={actions}>
      {grouped ? <Displayed tasks={[...own, ...deals.flatMap((d) => dealTasks(d.id))]} vkey={vkey} add={{ project_id: id }} /> : (
        <>
          <div className="sect">
            <TaskList tasks={shown(loose, o)} add={{ project_id: id }} drop={{}} nest={!o.sort || o.sort === 'manual'} />
          </div>
          {secs.map((s, i) => {
            const ts = shown(own.filter((t) => t.section_id === s.id), o);
            return (
              <div key={s.id}>
                {i === 0 ? <AddSection pid={id} pos={0} /> : null}
                <div className="sect">
                  <SectionHead s={s} count={ts.length} collapsed={coll.has(s.id)} toggle={() => toggle(s.id)} />
                  {coll.has(s.id) ? null : <TaskList tasks={ts} add={{ project_id: id, section_id: s.id }} drop={{}} nest={!o.sort || o.sort === 'manual'} />}
                </div>
                <AddSection pid={id} pos={(s.pos ?? i + 1) + 0.5} />
              </div>
            );
          })}
          {!secs.length ? <AddSection pid={id} pos={1} /> : null}
          {deals.map((d) => {
            const ts = shown(dealTasks(d.id), o);
            return (
              <div key={d.id} className="sect">
                <div className="sect-h">
                  <button className="tw" onClick={() => toggle(d.id)}><Icon name={coll.has(d.id) ? 'chevR' : 'chevD'} size={16} /></button>
                  <Icon name="deal" size={16} style={{ color: 'var(--fg2)' }} />
                  <a href={'#/deal/' + d.id}>{dealShort(d.id)}</a>
                  <span className="n">{STAGE[d.stage] || d.stage}{ts.length ? ' · ' + ts.length : ''}</span>
                </div>
                {coll.has(d.id) ? null : <TaskList tasks={ts} add={{ project_id: id, deal_id: d.id }} drop={{}} />}
              </div>
            );
          })}
          {o.done ? <DoneList filter={(t) => t.project_id === id} /> : null}
        </>
      )}
    </Page>
  );
}

function BoardCol({ title, tasks, add, icon, onDropTask }: { title: string; tasks: Task[]; add: any; icon?: string; onDropTask: (t: Task) => void }) {
  const [over, setOver] = useState(false);
  return (
    <div className="col" onDragOver={(e) => { if (draggingId()) { e.preventDefault(); setOver(true); } }} onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); const d = draggingId() && S.tasks.get(draggingId()!); if (d) onDropTask(d); }}
      style={over ? { background: 'var(--hover)', borderRadius: 10 } : undefined}>
      <div className="col-h">{icon ? <Icon name={icon} size={16} /> : null}{title}<span className="n">{tasks.length || ''}</span></div>
      {tasks.map((t) => (
        <div key={t.id} className="card" data-id={t.id} draggable onDragStart={(e) => { e.dataTransfer.setData('text/x-task', t.id); setDragId(t.id); }} onDragEnd={() => setDragId(null)}
          onClick={() => openTask(t.id)}>
          <Checkbox t={t} />
          <div className="tbody"><div className="ttl">{t.title}</div>{t.notes ? <div className="tdesc">{t.notes}</div> : null}<Meta t={t} /></div>
        </div>
      ))}
      <TaskList tasks={[]} add={add} />
    </div>
  );
}

export function FavItem({ k, close }: { k: string; close: () => void }) {
  const fav = todoSettings().fav || [];
  const on = fav.includes(k);
  return (
    <button className="mi" onClick={() => { close(); saveTodo({ fav: on ? fav.filter((x) => x !== k) : [...fav, k] }).catch(() => {}); }}>
      <Icon name="star" size={18} />{on ? 'Убрать из избранного' : 'В избранное'}
    </button>
  );
}

// ── Сделка ───────────────────────────────────────────────────────────────

export function DealView({ id }: { id: Id }) {
  useStore();
  const d = S.deals.get(id);
  if (!d) return <Page title="Сделка не найдена"><Empty icon="deal" big="Такой сделки нет или она не видна" /></Page>;
  const p = S.projects.get(d.project_id);
  const vkey = 'deal:' + id;
  return (
    <Page title={<><Icon name="deal" size={24} style={{ color: projectColor(p) }} />{dealShort(id)}</>}
      crumbs={p ? <a href={'#/project/' + p.id}>{p.name}</a> : null}
      sub={<span style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <span>Сделка · {STAGE[d.stage] || d.stage}</span>
        {d.next_step ? <><span className="dot">·</span><span>дальше: {d.next_step}</span></> : null}
        <span className="dot">·</span><a href={crmUrl('d', id)} target="_blank" rel="noreferrer" style={{ color: 'var(--accent-text)' }}>Сделка в CRM ↗</a>
      </span>}
      actions={<><DisplayBtn vkey={vkey} board={false} /><button className="tb-btn" onClick={(e) => openPop(e.currentTarget, (close) => <div><FavItem k={'d:' + id} close={close} /></div>)}><Icon name="more" size={20} /></button></>}>
      <Displayed tasks={dealTasks(id)} vkey={vkey} add={{ project_id: d.project_id, deal_id: id }} />
    </Page>
  );
}

// ── Метка, человек ───────────────────────────────────────────────────────

export function LabelView({ name }: { name: string }) {
  useStore();
  const vkey = 'label:' + name;
  return (
    <Page title={<><Icon name="tag" size={24} />{name}</>} crumbs="Фильтры и метки /" actions={<><DisplayBtn vkey={vkey} board={false} /><button className="tb-btn" onClick={(e) => openPop(e.currentTarget, (close) => <div><FavItem k={'l:' + name} close={close} /></div>)}><Icon name="more" size={20} /></button></>}>
      <Displayed tasks={labelTasks(name)} vkey={vkey} add={{ labels: [name] }} showProject manualDrop={false} />
    </Page>
  );
}

export function PersonView({ id }: { id: Id }) {
  useStore();
  const p = S.people.get(id);
  if (!p) return <Page title="Человек не найден"><Empty icon="user" big="Такого человека нет" /></Page>;
  const ts = personTasks(id);
  const groups: [string, Task[]][] = [
    ['Жду от него', ts.filter((t) => t.ball === 'waiting')],
    ['Обсудить', ts.filter((t) => t.ball === 'agenda')],
    ['Делаю я', ts.filter((t) => t.ball === 'mine')],
  ];
  return (
    <Page title={<><Icon name="user" size={24} />{p.name}</>} crumbs="Люди /"
      sub={<a href={crmUrl('h', id)} target="_blank" rel="noreferrer" style={{ color: 'var(--accent-text)' }}>Карточка в CRM ↗</a>}>
      {groups.map(([title, list]) => list.length ? (
        <div key={title} className="sect"><div className="sect-h">{title}<span className="n">{list.length}</span></div>
          <TaskList tasks={list} rowOpts={{ showProject: true }} nest={false} /></div>
      ) : null)}
      <TaskList tasks={[]} add={{ person_id: id }} />
      {!ts.length ? <Empty icon="user" big="С этим человеком открытых задач нет" /> : null}
    </Page>
  );
}

// ── Фильтр ───────────────────────────────────────────────────────────────

export function FilterView({ id }: { id: string }) {
  useStore();
  const f = (todoSettings().filters || []).find((x) => x.id === id);
  if (!f) return <Page title="Фильтр не найден"><Empty icon="filters" big="Такого фильтра нет" /></Page>;
  let blocks: { title: string; tasks: Task[] }[] = [];
  let err = '';
  try {
    blocks = runFilter(f.query, listed(), filterCtx());
  } catch (e) {
    err = e instanceof FilterError ? e.message : String(e);
  }
  const vkey = 'filter:' + id;
  const o = viewOpts(vkey);
  return (
    <Page title={<><Icon name="filters" size={24} style={{ color: f.color ? undefined : 'var(--fg2)' }} />{f.name}</>} crumbs="Фильтры и метки /"
      sub={<code style={{ fontSize: 12, color: 'var(--fg2)' }}>{f.query}</code>}
      actions={<>
        <DisplayBtn vkey={vkey} board={false} />
        <button className="tb-btn" onClick={(e) => openPop(e.currentTarget, (close) => (
          <div>
            <button className="mi" onClick={() => { close(); openDialog('filter', f); }}><Icon name="pencil" size={18} />Изменить</button>
            <FavItem k={'f:' + id} close={close} />
            <button className="mi danger" onClick={() => { close(); saveTodo({ filters: (todoSettings().filters || []).filter((x) => x.id !== id) }).then(() => go('filters')).catch(() => {}); }}><Icon name="trash" size={18} />Удалить фильтр</button>
          </div>
        ))}><Icon name="more" size={20} /></button>
      </>}>
      {err ? <div className="form"><div className="err">Не понял запрос: {err}</div></div> : null}
      {blocks.map((b, i) => {
        const ts = shown(b.tasks, { ...o, sort: o.sort === 'manual' || !o.sort ? 'date' : o.sort });
        return (
          <div key={i} className="sect">
            {blocks.length > 1 ? <div className="sect-h">{b.title}<span className="n">{ts.length}</span></div> : null}
            {groupTasks(ts, o.group).map((g) => (
              <div key={g.key}>
                {g.title ? <div className="sect-h" style={{ fontWeight: 600 }}>{g.title}<span className="n">{g.tasks.length}</span></div> : null}
                <TaskList tasks={g.tasks} rowOpts={{ showProject: true }} nest={false} />
              </div>
            ))}
          </div>
        );
      })}
    </Page>
  );
}

export function FiltersPage() {
  useStore();
  const fs = todoSettings().filters || [];
  const labels = S.labels.map((l) => l.name);
  const used = new Map<string, number>();
  for (const t of listed()) for (const l of t.labels || []) used.set(l, (used.get(l) || 0) + 1);
  return (
    <Page title="Фильтры и метки" crumbs="Фильтры и метки">
      <div className="sect">
        <div className="sect-h">Мои фильтры<span className="right"><button className="icon-btn sm" title="Добавить фильтр" onClick={() => openDialog('filter')}><Icon name="plus" size={18} /></button></span></div>
        {fs.map((f) => (
          <a key={f.id} className="fl-row" href={'#/filter/' + f.id}><Icon name="filters" size={18} style={{ color: 'var(--fg2)' }} />{f.name}<span className="q">{f.query}</span></a>
        ))}
        {!fs.length ? <div className="fl-row" style={{ color: 'var(--fg2)' }}>Фильтров пока нет — например, «(сегодня | просрочено) & p1» или «жду, повестка».</div> : null}
      </div>
      <div className="sect">
        <div className="sect-h">Метки</div>
        {[...new Set([...labels, ...used.keys()])].sort((a, b) => a.localeCompare(b, 'ru')).map((l) => (
          <a key={l} className="fl-row" href={'#/label/' + encodeURIComponent(l)}><Icon name="tag" size={18} style={{ color: 'var(--fg2)' }} />{l}<span className="q">{used.get(l) || ''}</span></a>
        ))}
      </div>
    </Page>
  );
}

export function FilterDialog({ f, close }: { f?: { id: string; name: string; query: string }; close: () => void }) {
  const [name, setName] = useState(f?.name || '');
  const [query, setQuery] = useState(f?.query || '');
  let err = '', n = 0;
  if (query.trim()) {
    try {
      const pred = compile(query.split(',')[0], filterCtx());
      n = listed().filter(pred).length;
    } catch (e) {
      err = e instanceof Error ? e.message : String(e);
    }
  }
  const save = () => {
    const all = todoSettings().filters || [];
    const id = f?.id || Math.random().toString(36).slice(2, 10);
    const next = f ? all.map((x) => (x.id === f.id ? { ...x, name, query } : x)) : [...all, { id, name, query }];
    saveTodo({ filters: next }).then(() => { close(); go('filter/' + id); }).catch(() => {});
  };
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
      <div className="dialog">
        <h3>{f ? 'Изменить фильтр' : 'Новый фильтр'}</h3>
        <div className="form">
          <label>Название<input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Утро" /></label>
          <label>Запрос<input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="(сегодня | просрочено) & !жду, жду" /></label>
          {err ? <div className="err">{err}</div> : query.trim() ? <div className="ok">Понял: {plural(n, 'задача', 'задачи', 'задач')} в первом блоке</div> : null}
          <div style={{ fontSize: 12, color: 'var(--fg2)', lineHeight: '18px' }}>
            Слова: сегодня, завтра, просрочено, без даты, 7 дней, p1…p4, @метка, #проект, ##проект (со сделками), +человек, сейчас, жду, повестка, повторяющиеся, срок до: пт, поиск: слово. Знаки: &amp; и, | или, ! не, ( ), запятая — отдельный блок.
          </div>
        </div>
        <div className="foot"><button className="btn sec" onClick={close}>Отмена</button><button className="btn pri" disabled={!name.trim() || !query.trim() || !!err} onClick={save}>Сохранить</button></div>
      </div>
    </div>
  );
}

// ── Дерево целей ─────────────────────────────────────────────────────────

function GoalRow({ g, depth }: { g: Task; depth: number }) {
  const kids = goalChildren(g.id);
  const tasks = goalTasks(g.id);
  const [open, setOpen] = useState(depth < 1);
  return (
    <>
      <div className="goal" style={{ paddingLeft: depth * 24 }} onClick={() => go('goal/' + g.id)}>
        {kids.length ? <button className="icon-btn sm" onClick={(e) => { e.stopPropagation(); setOpen(!open); }}><Icon name={open ? 'chevD' : 'chevR'} size={16} /></button> : <span style={{ width: 24 }} />}
        <Icon name="target" size={18} style={{ color: 'var(--accent)' }} />
        <span>{g.title}</span>
        {g.rhythm ? <span className="rh">↻ {g.rhythm}</span> : null}
        <span className="n">{tasks.length ? plural(tasks.length, 'задача', 'задачи', 'задач') : ''}</span>
      </div>
      {open ? kids.map((k) => <GoalRow key={k.id} g={k} depth={depth + 1} />) : null}
    </>
  );
}

export function TreeView({ dir }: { dir?: string }) {
  useStore();
  const dirs = S.dirs.length ? S.dirs : [...new Set(goals().map((g) => goalDir(g)).filter(Boolean))].map((k) => ({ key: k! }));
  const shown = dir ? dirs.filter((d) => d.key === dir) : dirs;
  return (
    <Page title={<><Icon name="tree" size={24} style={{ color: 'var(--accent)' }} />{dir ? dirName(dir) : 'Дерево жизни'}</>} crumbs={dir ? <a href="#/tree">Дерево жизни</a> : 'Дерево жизни'}
      sub={<span>Цели по направлениям; под целью — задачи, которые к ней ведут. Цель видишь только ты.</span>}>
      {shown.map((d) => {
        const top = goalChildren(null, d.key);
        return (
          <div key={d.key} className="sect">
            <div className="sect-h"><a href={'#/dir/' + d.key}>{dirName(d.key)}</a><span className="n">{top.length || ''}</span></div>
            {top.map((g) => <GoalRow key={g.id} g={g} depth={0} />)}
            {!top.length ? <div style={{ color: 'var(--fg3)', padding: '8px 0' }}>Целей нет</div> : null}
          </div>
        );
      })}
    </Page>
  );
}

export function GoalView({ id }: { id: Id }) {
  useStore();
  const g = S.tasks.get(id);
  if (!g) return <Page title="Цель не найдена"><Empty icon="target" big="Такой цели нет" /></Page>;
  const path = whyPath(g);
  const dir = goalDir(g);
  const kids = goalChildren(id);
  const tasks = goalTasks(id);
  // Задачи, вложенные в задачи этой цели, — под ними (дела внутри дела).
  const deep = [...S.tasks.values()].filter((t) => isListed(t) && t.why_id && !t.why_goal && tasks.some((x) => x.id === t.why_id));
  return (
    <Page title={<><Icon name="target" size={24} style={{ color: 'var(--accent)' }} />{g.title}</>}
      crumbs={<span><a href="#/tree">Дерево жизни</a>{dir ? <> / <a href={'#/dir/' + dir}>{dirName(dir)}</a></> : null}{path.map((x) => <span key={x.id}> / <a href={'#/goal/' + x.id}>{x.title}</a></span>)}</span>}
      sub={<span>{g.rhythm ? '↻ ' + g.rhythm + ' · ' : ''}{g.no_steps ? 'ориентир без задач' : plural(tasks.length, 'задача', 'задачи', 'задач')}</span>}
      actions={<button className="tb-btn" onClick={() => openTask(g.id)}><Icon name="pencil" size={18} /><span className="d-only">Цель</span></button>}>
      {kids.length ? (
        <div className="sect"><div className="sect-h">Цели внутри<span className="n">{kids.length}</span></div>{kids.map((k) => <GoalRow key={k.id} g={k} depth={0} />)}</div>
      ) : null}
      <div className="sect">
        <div className="sect-h">Задачи<span className="n">{tasks.length || ''}</span></div>
        <TaskList tasks={[...tasks, ...deep].sort(byPos)} rowOpts={{ showProject: true }} add={g.no_steps ? false : { why_id: id }} drop={{}} />
      </div>
    </Page>
  );
}

// ── Выполненные ──────────────────────────────────────────────────────────

export function DoneView() {
  useStore();
  const done = completedRecent(14);
  const by = new Map<string, Task[]>();
  for (const t of done) {
    const d = new Date(t.completed_at!).toLocaleString('sv', { timeZone: 'Europe/Moscow' }).slice(0, 10);
    if (!by.has(d)) by.set(d, []);
    by.get(d)!.push(t);
  }
  return (
    <Page title="Выполнено" crumbs="Выполнено" sub={<span>за две недели: {plural(done.length, 'задача', 'задачи', 'задач')}</span>}>
      {[...by.entries()].map(([d, ts]) => (
        <div key={d} className="sect">
          <div className="sect-h">{dayHead(d, S.today)[0]} · {WD[weekday(d)]}<span className="n">{ts.length}</span></div>
          <TaskList tasks={ts} rowOpts={{ showProject: true }} nest={false} />
        </div>
      ))}
      {!done.length ? <Empty big="Пока ничего" /> : null}
    </Page>
  );
}

// ── Поиск (страницей; поле — слева в боковой панели) ────────────────────

export function SearchView({ q }: { q: string }) {
  useStore();
  const res = searchAll(q);
  const n = res.tasks.length + res.projects.length + res.people.length;
  return (
    <Page title={q ? <>Поиск: «{q}»</> : 'Поиск'} crumbs="Поиск" sub={q ? <span>{n ? 'нашлось ' + plural(n, 'совпадение', 'совпадения', 'совпадений') : 'ничего не нашлось'}</span> : <span>Печатай в поле слева</span>}>
      {res.projects.length ? (
        <div className="sect"><div className="sect-h">Проекты</div>
          {res.projects.map((p) => <a key={p.id} className="fl-row" href={'#/project/' + p.id}><span className="hash" style={{ color: projectColor(p) }}>#</span>{p.name}</a>)}
        </div>
      ) : null}
      {res.people.length ? (
        <div className="sect"><div className="sect-h">Люди</div>
          {res.people.map((p) => <a key={p.id} className="fl-row" href={'#/person/' + p.id}><Icon name="user" size={18} />{p.name}</a>)}
        </div>
      ) : null}
      {res.tasks.length ? (
        <div className="sect"><div className="sect-h">Задачи<span className="n">{res.tasks.length}</span></div>
          <TaskList tasks={res.tasks} rowOpts={{ showProject: true }} nest={false} />
        </div>
      ) : null}
    </Page>
  );
}

export function TodayCount() {
  return <TodayIcon day={+S.today.slice(8, 10)} />;
}
export { reorder };
