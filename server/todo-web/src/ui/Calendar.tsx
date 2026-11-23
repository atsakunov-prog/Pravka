// Раскладка «Календарь» как у Todoist (замер 19.10, окно 1400×900): «Октябрь 2026», стрелки и «Сегодня»; неделя или 3 дня —
// шапка дней (сегодня — белым на красном круге), ряд «Весь день», часы по 68 пкс, дела со временем — блоками по времени,
// красная линия «сейчас» с временем у шкалы; месяц — сеткой. Цвет — по приоритету или проекту, будущие повторы — бледнее.
// Что где стоит — calendar.ts.
// 23.11.2026 («доделаем календарь и перетаскивание»): встречи Google рядом с делами (cal.ts; клик — событие в Google,
// значок камеры — сразу в созвон); задачу можно бросить на час (метка «куда ляжет» по 15 минут), в ряд «Весь день» (срок
// без часа) или в день месяца; нижний край блока тянется — длительность (estimate_min). Пальцем — то же, долгим
// нажатием (touchdrag.ts). Сбоку в «Предстоящем» — лоток «Разложить по часам»: сегодня без часа, просроченное, «Сейчас».
import { useEffect, useRef, useState } from 'react';
import { CAL } from '../cal.ts';
import { calDays, calItems, dayBlocks, evItems, HOUR, itemKey, itemTitle, nowTop, resized, shiftAnchor, slotAt, type CalItem, type CalMode } from '../calendar.ts';
import { timeWords } from '../calev.ts';
import { MONTHS_NOM, nowHm, WD_SHORT, weekday } from '../dates.ts';
import { cls, projectColor } from '../lib.ts';
import { S, setTask, useStore } from '../store.ts';
import { T } from '../toastText.ts';
import type { Task } from '../types.ts';
import type { ViewOpts } from '../viewopts.ts';
import { setDue } from './acts.ts';
import { Icon } from './icons.tsx';
import { openQuick, openTask, type QuickDefaults } from './state.ts';
import { draggingId, setDragId } from './TaskList.tsx';

const PRIO = ['', 'var(--p1)', 'var(--p2)', 'var(--p3)', 'var(--p4)'];
const MODES: [CalMode, string][] = [['3d', '3 дня'], ['week', 'Неделя'], ['month', 'Месяц']];

// Где схватили блок (пкс от его верха): брошенная задача встаёт верхом туда, где был верх, а не палец.
let grabDy = 0;
const dragged = () => { const id = draggingId(); return id ? S.tasks.get(id) || null : null; };
const dur = (m: number) => { const h = Math.floor(m / 60), mm = m % 60; return h ? (mm ? `${h} ч ${mm} мин` : `${h} ч`) : `${mm} мин`; };
const openEvent = (url: string) => { if (url) window.open(url, '_blank', 'noopener'); };

export function CalendarView({ tasks, o, add, events = false, onMode, tray }: {
  tasks: Task[]; o: ViewOpts; add: QuickDefaults; events?: boolean; onMode?: (m: CalMode) => void; tray?: React.ReactNode;
}) {
  useStore();
  const mode: CalMode = o.calMode || 'week';
  const today = S.today;
  const [anchor, setAnchor] = useState(today);
  const days = calDays(mode, anchor);
  const items = [...calItems(tasks, days, { repeats: o.calRepeats, done: o.done }), ...(events ? evItems(CAL.events, days, S.tasks) : [])];
  const color = (t: Task) => (o.calColor === 'project' ? projectColor(t.project_id ? S.projects.get(t.project_id) : null) : PRIO[t.priority ?? 4]);
  const mid = mode === 'month' ? anchor.slice(0, 8) + '15' : days[Math.floor(days.length / 2)];
  const title = `${MONTHS_NOM[+mid.slice(5, 7) - 1]} ${mid.slice(0, 4)}`;
  const addOn = (day: string, time?: string) => openQuick({ ...add, due_date: day, ...(time ? { due_time: time } : {}) });
  return (
    <div className={cls('cal-view', !!tray && 'with-tray')}>
      <div className="cal-bar">
        <b>{title}</b>
        {onMode ? (
          <span className="segs cal-modes">
            {MODES.map(([m, w]) => <button key={m} className={'seg' + (mode === m ? ' on' : '')} onClick={() => onMode(m)}>{w}</button>)}
          </span>
        ) : null}
        <span className="cal-nav">
          <button className="icon-btn sm" title="Назад" onClick={() => setAnchor(shiftAnchor(mode, anchor, -1))}><Icon name="chevL" size={18} /></button>
          <button className="btn sec" onClick={() => setAnchor(today)}>Сегодня</button>
          <button className="icon-btn sm" title="Вперёд" onClick={() => setAnchor(shiftAnchor(mode, anchor, 1))}><Icon name="chevR" size={18} /></button>
        </span>
      </div>
      {events && CAL.sync?.stale ? <div className="cal-stale"><Icon name="alarm" size={14} />Календарь давно не обновлялся: служба встреч молчит — встречи могут быть не все</div> : null}
      <div className="cal-wrap">
        {tray ? <div className="cal-tray">{tray}</div> : null}
        {mode === 'month' ? <Month days={days} items={items} month={mid.slice(0, 7)} color={color} addOn={addOn} />
          : <Week days={days} items={items} color={color} addOn={addOn} />}
      </div>
    </div>
  );
}

/** Задача — тянется (не будущий раз повтора и не закрытая). */
function dragProps(it: CalItem) {
  const t = it.task;
  if (!t || it.ghost || t.status !== 'open') return {};
  return {
    draggable: true,
    onDragStart: (e: React.DragEvent) => {
      if ((e.target as HTMLElement).closest('.calw-rs')) { e.preventDefault(); return; }
      const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
      grabDy = Math.max(0, Math.min(r.height, e.clientY - r.top));
      setDragId(t.id);
      e.dataTransfer.setData('text/x-task', t.id);
      e.dataTransfer.effectAllowed = 'move';
    },
    onDragEnd: () => { setDragId(null); grabDy = 0; },
  };
}

function Chip({ it, color }: { it: CalItem; color: (t: Task) => string }) {
  if (it.ev) {
    const e = it.ev;
    return (
      <div role="button" tabIndex={0} className={cls('cal-chip ev', !e.busy && 'free')} title={[timeWords(e, it.day), e.title, e.location].filter(Boolean).join(' · ')}
        onClick={(x) => { x.stopPropagation(); openEvent(e.url); }}>
        {it.time ? <span className="t">{it.time}</span> : null}{e.title}
      </div>
    );
  }
  const t = it.task!;
  return (
    <div role="button" tabIndex={0} className={cls('cal-chip', it.ghost && 'ghost', t.status !== 'open' && 'done')} style={{ borderLeftColor: color(t) }}
      title={t.title} onClick={(e) => { e.stopPropagation(); openTask(t.id); }} {...dragProps(it)}>
      {it.time ? <span className="t">{it.time}</span> : null}{t.title}
    </div>
  );
}

/** Растягивание нижнего края блока: длительность дела по 15 минут; отпустил — сохранить с «Отменить». */
function useResize() {
  const [rs, setRs] = useState<{ id: string; mins: number } | null>(null);
  const start = (e: React.PointerEvent, t: Task, mins: number) => {
    e.stopPropagation();
    e.preventDefault();
    const y0 = e.clientY;
    let now = mins;
    setRs({ id: t.id, mins });
    const move = (m: PointerEvent) => { now = resized(mins, m.clientY - y0); setRs({ id: t.id, mins: now }); };
    const up = () => {
      removeEventListener('pointermove', move);
      removeEventListener('pointerup', up);
      removeEventListener('pointercancel', up);
      setRs(null);
      if (now !== mins) setTask(t, { estimate_min: now }, 'Длительность: ' + dur(now)).catch(() => {});
    };
    addEventListener('pointermove', move);
    addEventListener('pointerup', up);
    addEventListener('pointercancel', up);
  };
  return { rs, start };
}

function Week({ days, items, color, addOn }: { days: string[]; items: CalItem[]; color: (t: Task) => string; addOn: (d: string, time?: string) => void }) {
  const today = S.today;
  const body = useRef<HTMLDivElement>(null);
  const [now, setNow] = useState(nowHm());
  const [hover, setHover] = useState<{ day: string; slot: string; allDay?: boolean } | null>(null);
  const { rs, start } = useResize();
  useEffect(() => {
    // с утра (или за два часа до «сейчас»), как календарь открывается у Todoist
    if (body.current) body.current.scrollTop = days.includes(today) ? Math.max(0, nowTop(now) - 2 * HOUR) : 7 * HOUR;
    const t = setInterval(() => setNow(nowHm()), 60_000);
    return () => clearInterval(t);
  }, []);
  const cols = { gridTemplateColumns: `56px repeat(${days.length}, minmax(0, 1fr))` };
  const over = (d: string) => ({
    onDragOver: (e: React.DragEvent) => {
      if (!draggingId()) return;
      e.preventDefault();
      const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
      const slot = slotAt(e.clientY - r.top - grabDy);
      if (!hover || hover.day !== d || hover.slot !== slot || hover.allDay) setHover({ day: d, slot });
    },
    onDragLeave: (e: React.DragEvent) => { if (!(e.currentTarget as HTMLElement).contains(e.relatedTarget as Node)) setHover(null); },
    onDrop: (e: React.DragEvent) => {
      e.preventDefault();
      const t = dragged();
      const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
      const slot = slotAt(e.clientY - r.top - grabDy);
      setHover(null);
      setDragId(null);
      grabDy = 0;
      if (t && (t.due_date !== d || (t.due_time || '').slice(0, 5) !== slot)) setTask(t, { due_date: d, due_time: slot }, T.due(d, S.today) + ' в ' + slot).catch(() => {});
    },
  });
  const overAll = (d: string) => ({
    onDragOver: (e: React.DragEvent) => { if (draggingId()) { e.preventDefault(); if (!hover || hover.day !== d || !hover.allDay) setHover({ day: d, slot: '', allDay: true }); } },
    onDragLeave: (e: React.DragEvent) => { if (!(e.currentTarget as HTMLElement).contains(e.relatedTarget as Node)) setHover(null); },
    onDrop: (e: React.DragEvent) => {
      e.preventDefault();
      const t = dragged();
      setHover(null);
      setDragId(null);
      if (t && (t.due_date !== d || t.due_time)) setDue(t, { due_date: d, due_time: null });
    },
  });
  const ghostMins = dragged()?.estimate_min || 30;
  return (
    <div className="calw">
      <div className="calw-row calw-head" style={cols}>
        <div />
        {days.map((d) => (
          <div key={d} className={'calw-dh' + (d === today ? ' today' : '')}><span>{WD_SHORT[weekday(d)]}</span><b>{+d.slice(8, 10)}</b></div>
        ))}
      </div>
      <div className="calw-row calw-allday" style={cols}>
        <div className="calw-gutter">Весь день</div>
        {days.map((d) => (
          <div key={d} className={cls('calw-ad', hover?.allDay && hover.day === d && 'drop')} onDoubleClick={() => addOn(d)} {...overAll(d)}>
            {items.filter((x) => x.day === d && !x.time).map((x) => <Chip key={itemKey(x)} it={x} color={color} />)}
          </div>
        ))}
      </div>
      <div className="calw-body" ref={body}>
        <div className="calw-row" style={{ ...cols, height: 24 * HOUR }}>
          <div className="calw-scale">
            {Array.from({ length: 23 }, (_, h) => <span key={h} style={{ top: (h + 1) * HOUR }}>{String(h + 1).padStart(2, '0')}:00</span>)}
            {days.includes(today) ? <span className="now" style={{ top: nowTop(now) }}>{now}</span> : null}
          </div>
          {days.map((d) => (
            <div key={d} className={cls('calw-col', hover && !hover.allDay && hover.day === d && 'drop')}
              onDoubleClick={(e) => { const r = e.currentTarget.getBoundingClientRect(); addOn(d, slotAt(e.clientY - r.top)); }} {...over(d)}>
              {dayBlocks(items.filter((x) => x.day === d)).map((b) => {
                const t = b.item.task, ev = b.item.ev;
                const h = t && rs?.id === t.id ? (rs.mins / 60) * HOUR : b.height;
                const pos = { top: b.top, height: h - 2, left: `calc(${(100 * b.lane) / b.lanes}% + 2px)`, width: `calc(${100 / b.lanes}% - 4px)` };
                if (ev) {
                  return (
                    <div key={itemKey(b.item)} role="button" tabIndex={0} className={cls('calw-block ev', !ev.busy && 'free', d === today && ev.end && b.top + b.height <= nowTop(now) && 'past')}
                      style={pos} title={[timeWords(ev, d), ev.title, ev.location].filter(Boolean).join(' · ')}
                      onClick={(x) => { x.stopPropagation(); openEvent(ev.url); }} onDoubleClick={(x) => x.stopPropagation()}>
                      <span className="t">{timeWords(ev, d)}</span>{ev.title}
                      {ev.link ? <a className="ev-go" href={ev.link} target="_blank" rel="noopener" title="Войти в созвон" onClick={(x) => x.stopPropagation()}><Icon name="video" size={13} /></a> : null}
                    </div>
                  );
                }
                return (
                  <div key={itemKey(b.item)} role="button" tabIndex={0} className={cls('calw-block', b.item.ghost && 'ghost', t!.status !== 'open' && 'done', rs?.id === t!.id && 'rs')}
                    style={{ ...pos, borderLeftColor: color(t!) }}
                    title={`${b.item.time} ${t!.title}`} onClick={(e) => { e.stopPropagation(); openTask(t!.id); }} onDoubleClick={(x) => x.stopPropagation()} {...dragProps(b.item)}>
                    <span className="t">{b.item.time}{rs?.id === t!.id ? ' · ' + dur(rs.mins) : ''}</span>{t!.title}
                    {!b.item.ghost && t!.status === 'open' ? <i className="calw-rs" draggable={false} title="Потянуть — длительность" onPointerDown={(e) => start(e, t!, b.item.mins)} /> : null}
                  </div>
                );
              })}
              {hover && !hover.allDay && hover.day === d ? (
                <i className="calw-drop" style={{ top: (+hover.slot.slice(0, 2) * 60 + +hover.slot.slice(3, 5)) / 60 * HOUR, height: (ghostMins / 60) * HOUR - 2 }}>{hover.slot}</i>
              ) : null}
              {d === today ? <i className="calw-now" style={{ top: nowTop(now) }} /> : null}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function Month({ days, items, month, color, addOn }: { days: string[]; items: CalItem[]; month: string; color: (t: Task) => string; addOn: (d: string) => void }) {
  const today = S.today;
  const [hover, setHover] = useState<string | null>(null);
  return (
    <div className="calm">
      {WD_SHORT.map((w) => <div key={w} className="calm-wd">{w}</div>)}
      {days.map((d) => {
        const its = items.filter((x) => x.day === d).sort((a, b) => (a.time || '99').localeCompare(b.time || '99') || itemTitle(a).localeCompare(itemTitle(b), 'ru'));
        return (
          <div key={d} className={cls('calm-cell', d.slice(0, 7) !== month && 'out', hover === d && 'drop')} onDoubleClick={() => addOn(d)}
            onDragOver={(e) => { if (draggingId()) { e.preventDefault(); if (hover !== d) setHover(d); } }}
            onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setHover(null); }}
            onDrop={(e) => { e.preventDefault(); const t = dragged(); setHover(null); setDragId(null); if (t && t.due_date !== d) setDue(t, { due_date: d }); }}>
            <b className={d === today ? 'today' : ''}>{+d.slice(8, 10)}</b>
            {its.slice(0, 3).map((x) => <Chip key={itemKey(x)} it={x} color={color} />)}
            {its.length > 3 ? <span className="more">ещё {its.length - 3}</span> : null}
          </div>
        );
      })}
    </div>
  );
}

/** Лоток «Разложить по часам» (как у Sunsama и Akiflow): сегодняшнее без часа, просроченное и «Сейчас» без часа —
 *  тянуть на сетку. Пусто — подсказка, что сетка принимает и задачи из любого списка. */
export function CalTray({ groups }: { groups: { title: string; tasks: Task[] }[] }) {
  const all = groups.filter((g) => g.tasks.length);
  return (
    <>
      <div className="tray-h"><Icon name="grip" size={14} />Разложить по часам</div>
      {all.length ? all.map((g) => (
        <div key={g.title} className="tray-g">
          <div className="tray-t">{g.title}<span>{g.tasks.length}</span></div>
          {g.tasks.slice(0, 30).map((t) => (
            <div key={t.id} className="tray-i" role="button" tabIndex={0} draggable style={{ borderLeftColor: PRIO[t.priority ?? 4] || 'var(--border)' }}
              onDragStart={(e) => { grabDy = 0; setDragId(t.id); e.dataTransfer.setData('text/x-task', t.id); e.dataTransfer.effectAllowed = 'move'; }}
              onDragEnd={() => setDragId(null)} onClick={() => openTask(t.id)} title={t.title}>
              {t.title}{t.estimate_min ? <span className="d">{dur(t.estimate_min)}</span> : null}
            </div>
          ))}
        </div>
      )) : <div className="tray-empty">Всё разложено. Сюда приходят задачи на сегодня без часа и просроченные.</div>}
    </>
  );
}
