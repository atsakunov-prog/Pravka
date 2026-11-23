// Лента дня (23.11.2026; правила — dayplan.ts). Одна лента вместо «встречи отдельно, дела отдельно»: время слева, встреча
// или дело со своим часом, под ним — что внутри (вопросы к встрече) и «в машине можно» (дела с той же меткой без часа),
// накладки красным, между кусками — окна: брось на окно дело без часа — встанет на его начало. Сверху — ёмкость: сколько
// окон и сколько нужно делам без часа. Над «Сейчас» и «Сегодня» — что идёт сейчас или скоро, с тем же «внутри».
// 24.11.2026 (владелец: «поменяем интерфейс так, чтобы всё это было удобно дописывать, видеть, добавлять, переставлять»):
// «+» в окне — дело сразу на начало окна; «+» под блоком — вопрос к встрече (дело внутри) или ещё одно «в машину» (метка
// блока); дело, брошенное на блок, становится его частью так же; брошенное на «Без часа» — теряет час (и встречу); время
// у блока — нажатием, как срок в строке.
import { useState } from 'react';
import { CAL, dayEvents } from '../cal.ts';
import { hmMin } from '../calev.ts';
import { buildPlan, durWords, gapSlot, spanWords, type Gap, type Plan, type PlanItem } from '../dayplan.ts';
import { nowHm } from '../dates.ts';
import { cls } from '../lib.ts';
import { S, setTask, useStore } from '../store.ts';
import type { Task } from '../types.ts';
import { dayList, listed, mine } from '../views.ts';
import { setDue } from './acts.ts';
import { EventRow } from './Events.tsx';
import { Icon } from './icons.tsx';
import { Scheduler } from './pickers.tsx';
import { openPop } from './pop.tsx';
import { openQuick, openTask } from './state.ts';
import { draggingId, setDragId } from './TaskList.tsx';
import { TaskRow } from './TaskRow.tsx';

const parentOf = (t: Task) => (t.why_id && !t.why_goal ? t.why_id : null);
const dragged = () => { const id = draggingId(); return id ? S.tasks.get(id) || null : null; };
/** Куда ляжет дело, добавленное или брошенное на блок: «в машину» (метка блока) или внутрь (вопрос, шаг). */
const intoWords = (b: Task) => (b.labels?.length ? (b.labels[0] === 'машина' ? 'в машину' : `«${b.labels[0]}»`) : 'вопрос или шаг');

/** Дело — частью блока: у блока с меткой-местом — та же метка (дело «заодно»), иначе — внутрь блока (вопрос к встрече). */
export function putInto(t: Task, block: Task, day: string) {
  if (t.id === block.id || parentOf(block) === t.id) return;
  if (block.labels?.length) {
    const labels = [...new Set([...(t.labels || []), ...block.labels])];
    setTask(t, { labels, due_date: day, due_time: null }, `${alongWords(block.labels[0])}: «${t.title}»`).catch(() => {});
  } else {
    setTask(t, { why_id: block.id, due_date: day, due_time: null }, `Внутри «${block.title}»`).catch(() => {});
  }
}

/** Полоса «Без часа» под лентой: брось дело сюда — час снят (и из встречи вынуто); дату дня оставляет. */
export function RestHead({ day, count }: { day: string; count: number }) {
  const [over, setOver] = useState(false);
  return (
    <div className={cls('sect-h sub-h dp-rest', over && 'drop')}
      onDragOver={(e) => { if (draggingId()) { e.preventDefault(); if (!over) setOver(true); } }}
      onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setOver(false); }}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        const t = dragged();
        setDragId(null);
        if (!t) return;
        const p = parentOf(t) ? S.tasks.get(parentOf(t)!) : null;
        const out = !!(p && p.due_time);
        if (t.due_time || out || t.due_date !== day) setTask(t, { due_date: day, due_time: null, ...(out ? { why_id: null } : {}) }, 'Без часа').catch(() => {});
      }}>
      Без часа<span className="n">{count || ''}</span>
      <span className="dp-rest-h">{over ? 'снять час' : ''}</span>
    </div>
  );
}

/** Лента дня из своей копии: встречи без близнецов, свои дела дня, все свои открытые (для «заодно»). */
export function planFor(day: string): Plan {
  const now = day === S.today ? hmMin(nowHm()) : null;
  return buildPlan(day, dayEvents(day), dayList(day), listed().filter(mine), now);
}

// Метка-место — как сказать человеку (бот говорит так же: meetings/worker/remind.py ALONG_WORDS).
const ALONG: Record<string, string> = { 'машина': 'В машине можно', 'велик': 'На велике можно', 'город': 'Заодно в городе', 'дом': 'Дома заодно', 'звонок': 'Заодно позвонить' };
export const alongWords = (label: string) => ALONG[label] || `Заодно, «${label}»`;

/** Строка дела в ленте тянется (на окно — сменить час), сама не принимает. */
const rowDrag = {
  onDragStart: (e: React.DragEvent, t: Task) => { setDragId(t.id); e.dataTransfer.setData('text/x-task', t.id); e.dataTransfer.effectAllowed = 'move'; },
  onDragOver: () => {},
  onDrop: () => {},
  onDragEnd: () => setDragId(null),
};

/** Ссылка на созвон у дела-встречи: событие календаря, поставленное из него. */
const linkOf = (t: Task) => CAL.events.find((e) => e.task_id === t.id && e.link)?.link || '';

function Sub({ title, tasks }: { title: string; tasks: Task[] }) {
  return (
    <div className="dp-sub">
      <div className="dp-sub-h">{title}</div>
      {tasks.map((k) => <TaskRow key={k.id} t={k} opts={{ hideDate: true, inPlan: true, showProject: true, drag: rowDrag }} />)}
    </div>
  );
}

function Item({ it, day, now }: { it: PlanItem; day: string; now: number | null }) {
  const [over, setOver] = useState(false);
  const past = now !== null && it.to >= 0 && it.to <= now;
  const live = now !== null && it.from >= 0 && it.from <= now && it.to > now;
  const link = it.task ? linkOf(it.task) : '';
  const b = it.task;
  // Бросить дело на блок-дело: «в машину» или внутрь. На встречу только из календаря — некуда: её правда в Google.
  const drop = b && b.status === 'open' ? {
    onDragOver: (e: React.DragEvent) => { const t = dragged(); if (t && t.id !== b.id) { e.preventDefault(); if (!over) setOver(true); } },
    onDragLeave: (e: React.DragEvent) => { if (!(e.currentTarget as HTMLElement).contains(e.relatedTarget as Node)) setOver(false); },
    onDrop: (e: React.DragEvent) => { e.preventDefault(); setOver(false); const t = dragged(); setDragId(null); if (t) putInto(t, b, day); },
  } : {};
  const pickTime = (el: HTMLElement) => b && openPop(el, (close) => <Scheduler tasks={[b]} value={b as any} close={close} onPick={(v) => setDue(b, v)} />, { cls: 'sched' });
  return (
    <div className={cls('dp-row', past && 'past', live && 'live', it.clash.length > 0 && 'clash', !!it.ev && 'is-ev', over && 'drop')} {...drop}>
      {b ? <button className="dp-time" title="Сменить время" onClick={(e) => pickTime(e.currentTarget)}>{spanWords(it.from, it.to)}</button>
        : <div className="dp-time">{spanWords(it.from, it.to)}</div>}
      <div className="dp-main">
        {it.ev ? <EventRow e={it.ev} day={day} now={nowHm()} noTime />
          : (
            <div className="dp-task">
              <TaskRow t={it.task!} opts={{ hideDate: true, inPlan: true, showProject: true, drag: rowDrag }} />
              {link && !past ? <a className="ev-join" href={link} target="_blank" rel="noopener"><Icon name="video" size={14} />Войти</a> : null}
            </div>
          )}
        {it.clash.length ? <div className="dp-clash"><Icon name="alarm" size={12} />накладка: {it.clash.join(', ')}</div> : null}
        {it.inside.length ? <Sub title="Внутри" tasks={it.inside} /> : null}
        {it.along.map((g) => <Sub key={g.label} title={alongWords(g.label)} tasks={g.tasks} />)}
        {b && b.status === 'open' && !past ? (
          <button className={cls('dp-add-in', over && 'on')} onClick={() => openQuick(b.labels?.length ? { due_date: day, labels: b.labels } : { due_date: day, why_id: b.id })}>
            <Icon name="plus" size={13} />{over ? `положить ${intoWords(b)}` : intoWords(b)}
          </button>
        ) : null}
      </div>
    </div>
  );
}

function GapRow({ g, day }: { g: Gap; day: string }) {
  const [over, setOver] = useState(false);
  const slot = gapSlot(g);
  return (
    <div className={cls('dp-gap', over && 'drop')}
      onDragOver={(e) => { if (draggingId()) { e.preventDefault(); if (!over) setOver(true); } }}
      onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setOver(false); }}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        const id = draggingId();
        const t = id ? S.tasks.get(id) : null;
        setDragId(null);
        if (t && (t.due_date !== day || (t.due_time || '').slice(0, 5) !== slot)) setTask(t, { due_date: day, due_time: slot }, `Поставил на ${slot}`).catch(() => {});
      }}>
      <span className="dp-time">{spanWords(g.from, g.to)}</span>
      <span className="dp-gap-w">Окно {durWords(g.to - g.from)}</span>
      <span className="dp-gap-h">{over ? `встанет на ${slot}` : 'брось сюда дело'}</span>
      <button className="dp-gap-add" title={`Новое дело на ${slot}`} onClick={() => openQuick({ due_date: day, due_time: slot })}><Icon name="plus" size={14} />{slot}</button>
    </div>
  );
}

/** Лента дня. compact — без окон и ёмкости (дни «Предстоящего»). */
export function DayPlanView({ day, compact = false }: { day: string; compact?: boolean }) {
  useStore();
  const plan = planFor(day);
  if (!plan.items.length && (compact || !plan.untimed.length)) return null;
  const now = day === S.today ? hmMin(nowHm()) : null;
  const rows: { from: number; node: React.ReactNode }[] = plan.items.map((it) => ({ from: it.from, node: <Item key={it.key} it={it} day={day} now={now} /> }));
  if (!compact) for (const g of plan.gaps) rows.push({ from: g.from - 0.5, node: <GapRow key={'g' + g.from} g={g} day={day} /> });
  rows.sort((a, b) => a.from - b.from);
  const nowAt = now === null ? -1 : rows.findIndex((r) => r.from > now);
  const ok = plan.need <= plan.free;
  return (
    <div className={cls('dp', compact && 'compact')}>
      {!compact ? (
        <div className={cls('dp-cap', !ok && 'over')}>
          <Icon name="upcoming" size={14} />
          <span>Окна {durWords(plan.free)}</span>
          <span className="dot">·</span>
          <span>{plan.untimed.length ? `без часа ${durWords(plan.need)}` : 'без часа ничего'}</span>
          <b>{plan.untimed.length ? (ok ? 'влезает' : `не влезает на ${durWords(plan.need - plan.free)}`) : ''}</b>
        </div>
      ) : null}
      {rows.map((r, i) => (
        <div key={i} className="dp-slot">
          {i === nowAt ? <div className="dp-now"><span>{nowHm()}</span></div> : null}
          {r.node}
        </div>
      ))}
      {nowAt === -1 && now !== null && rows.length ? <div className="dp-now"><span>{nowHm()}</span></div> : null}
    </div>
  );
}

/** Что идёт сейчас или начнётся в ближайшие три часа — с тем же «внутри» и «заодно» (над «Сейчас» и «Сегодня»). */
export function nowBlock(): PlanItem | null {
  if (!S.today) return null;
  const now = hmMin(nowHm());
  const p = planFor(S.today);
  return p.items.find((x) => x.busy && x.from >= 0 && x.to > now && x.from - now <= 180) || null;
}

export function NowBlock() {
  useStore();
  const it = nowBlock();
  if (!it) return null;
  const now = hmMin(nowHm());
  const inMin = Math.max(0, it.from - now);
  const title = it.ev ? it.ev.title : it.task!.title;
  const when = inMin === 0 ? 'Сейчас' : inMin < 60 ? `Через ${inMin} мин` : `В ${spanWords(it.from, it.from)}`;
  const extra = [
    it.inside.length ? `внутри ${it.inside.length}` : '',
    ...it.along.map((g) => `${alongWords(g.label).toLowerCase()}: ${g.tasks.length}`),
    it.clash.length ? 'накладка' : '',
  ].filter(Boolean);
  const link = it.ev?.link || (it.task ? linkOf(it.task) : '');
  const open = () => { if (it.task) openTask(it.task.id); else if (it.ev?.url) window.open(it.ev.url, '_blank', 'noopener'); };
  return (
    <div className={cls('nudge n-cal', inMin === 0 && 'live')} role="button" tabIndex={0} onClick={open}>
      <Icon name={it.task ? 'bolt' : 'calendar'} size={18} />
      <span><b>{when}: {title}</b><small>{[spanWords(it.from, it.to), ...extra].join(' · ')}</small></span>
      {link ? <a className="ev-join" href={link} target="_blank" rel="noopener" onClick={(x) => x.stopPropagation()}><Icon name="video" size={14} />Войти</a> : <Icon name="chevR" size={16} />}
    </div>
  );
}
