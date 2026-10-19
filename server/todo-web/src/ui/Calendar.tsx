// Раскладка «Календарь» как у Todoist (замер 19.10, окно 1400×900): «Октябрь 2026», стрелки и «Сегодня»; неделя или 3 дня —
// шапка дней (сегодня — белым на красном круге), ряд «Весь день», часы по 68 пкс, дела со временем — блоками по времени,
// красная линия «сейчас» с временем у шкалы; месяц — сеткой. Цвет — по приоритету или проекту, будущие повторы — бледнее.
// Что где стоит — calendar.ts. Перетаскивание по сетке — следующим шагом.
import { useEffect, useRef, useState } from 'react';
import { calDays, calItems, dayBlocks, HOUR, nowTop, shiftAnchor, type CalItem, type CalMode } from '../calendar.ts';
import { MONTHS_NOM, nowHm, WD_SHORT, weekday } from '../dates.ts';
import { projectColor } from '../lib.ts';
import { S, useStore } from '../store.ts';
import type { Task } from '../types.ts';
import type { ViewOpts } from '../viewopts.ts';
import { Icon } from './icons.tsx';
import { openQuick, openTask, type QuickDefaults } from './state.ts';

const PRIO = ['', 'var(--p1)', 'var(--p2)', 'var(--p3)', 'var(--p4)'];

export function CalendarView({ tasks, o, add }: { tasks: Task[]; o: ViewOpts; add: QuickDefaults }) {
  useStore();
  const mode: CalMode = o.calMode || 'week';
  const today = S.today;
  const [anchor, setAnchor] = useState(today);
  const days = calDays(mode, anchor);
  const items = calItems(tasks, days, { repeats: o.calRepeats, done: o.done });
  const color = (t: Task) => (o.calColor === 'project' ? projectColor(t.project_id ? S.projects.get(t.project_id) : null) : PRIO[t.priority ?? 4]);
  const mid = mode === 'month' ? anchor.slice(0, 8) + '15' : days[Math.floor(days.length / 2)];
  const title = `${MONTHS_NOM[+mid.slice(5, 7) - 1]} ${mid.slice(0, 4)}`;
  const addOn = (day: string) => openQuick({ ...add, due_date: day });
  return (
    <div className="cal-view">
      <div className="cal-bar">
        <b>{title}</b>
        <span className="cal-nav">
          <button className="icon-btn sm" title="Назад" onClick={() => setAnchor(shiftAnchor(mode, anchor, -1))}><Icon name="chevL" size={18} /></button>
          <button className="btn sec" onClick={() => setAnchor(today)}>Сегодня</button>
          <button className="icon-btn sm" title="Вперёд" onClick={() => setAnchor(shiftAnchor(mode, anchor, 1))}><Icon name="chevR" size={18} /></button>
        </span>
      </div>
      {mode === 'month' ? <Month days={days} items={items} month={mid.slice(0, 7)} color={color} addOn={addOn} />
        : <Week days={days} items={items} color={color} addOn={addOn} />}
    </div>
  );
}

function Chip({ it, color }: { it: CalItem; color: (t: Task) => string }) {
  return (
    <button className={'cal-chip' + (it.ghost ? ' ghost' : '') + (it.task.status !== 'open' ? ' done' : '')} style={{ borderLeftColor: color(it.task) }}
      title={it.task.title} onClick={(e) => { e.stopPropagation(); openTask(it.task.id); }}>
      {it.time ? <span className="t">{it.time}</span> : null}{it.task.title}
    </button>
  );
}

function Week({ days, items, color, addOn }: { days: string[]; items: CalItem[]; color: (t: Task) => string; addOn: (d: string) => void }) {
  const today = S.today;
  const body = useRef<HTMLDivElement>(null);
  const [now, setNow] = useState(nowHm());
  useEffect(() => {
    // с утра (или за два часа до «сейчас»), как календарь открывается у Todoist
    if (body.current) body.current.scrollTop = days.includes(today) ? Math.max(0, nowTop(now) - 2 * HOUR) : 7 * HOUR;
    const t = setInterval(() => setNow(nowHm()), 60_000);
    return () => clearInterval(t);
  }, []);
  const cols = { gridTemplateColumns: `56px repeat(${days.length}, minmax(0, 1fr))` };
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
          <div key={d} className="calw-ad" onDoubleClick={() => addOn(d)}>
            {items.filter((x) => x.day === d && !x.time).map((x) => <Chip key={x.task.id + (x.ghost ? x.day : '')} it={x} color={color} />)}
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
            <div key={d} className="calw-col" onDoubleClick={() => addOn(d)}>
              {dayBlocks(items.filter((x) => x.day === d)).map((b) => (
                <button key={b.item.task.id + (b.item.ghost ? d : '')} className={'calw-block' + (b.item.ghost ? ' ghost' : '') + (b.item.task.status !== 'open' ? ' done' : '')}
                  style={{ top: b.top, height: b.height - 2, left: `calc(${(100 * b.lane) / b.lanes}% + 2px)`, width: `calc(${100 / b.lanes}% - 4px)`, borderLeftColor: color(b.item.task) }}
                  title={`${b.item.time} ${b.item.task.title}`} onClick={(e) => { e.stopPropagation(); openTask(b.item.task.id); }}>
                  <span className="t">{b.item.time}</span>{b.item.task.title}
                </button>
              ))}
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
  return (
    <div className="calm">
      {WD_SHORT.map((w) => <div key={w} className="calm-wd">{w}</div>)}
      {days.map((d) => {
        const its = items.filter((x) => x.day === d).sort((a, b) => (a.time || '99').localeCompare(b.time || '99'));
        return (
          <div key={d} className={'calm-cell' + (d.slice(0, 7) !== month ? ' out' : '')} onDoubleClick={() => addOn(d)}>
            <b className={d === today ? 'today' : ''}>{+d.slice(8, 10)}</b>
            {its.slice(0, 3).map((x) => <Chip key={x.task.id + (x.ghost ? x.day : '')} it={x} color={color} />)}
            {its.length > 3 ? <span className="more">ещё {its.length - 3}</span> : null}
          </div>
        );
      })}
    </div>
  );
}
