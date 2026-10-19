// Меню выбора: срок (планировщик Todoist), приоритет, проект, метки, человек, цель.
import { useMemo, useState } from 'react';
import { addMonths, daysInMonth, MONTHS_NOM, plural, shortDate, WD, WD_SHORT, weekday, ymd } from '../dates.ts';
import { parseWhen, recurWords, type When } from '../quick.ts';
import { deadlineOptions, dueOptions, postponed, type QuickKey } from '../schedule.ts';
import { ops, S, setTask, toast } from '../store.ts';
import type { Id, Recur, Task } from '../types.ts';
import { dealShort, PALETTE, projectColor } from '../lib.ts';
import { goalDir, goals, liveDeals, projectsSorted, sectionsOf, whyPath, dirName } from '../views.ts';
import { Icon, PrioFlag } from './icons.tsx';
import { quickCtx } from './ctx.ts';

// ── Срок ─────────────────────────────────────────────────────────────────

export interface DueVal {
  due_date: string | null;
  due_time: string | null;
  recur: Recur | null;
  due_rule: string | null;
}

/** Вид быстрого варианта срока: значок и цвет — общие у планировщика и меню дела. */
export const QUICK_LOOK: Record<QuickKey, [string, string]> = {
  today: ['calendar', 'var(--d-today)'], tomorrow: ['sun', 'var(--d-tom)'], later: ['upcoming', 'var(--d-week)'],
  weekend: ['home', 'var(--d-weekend)'], nextweekend: ['home', 'var(--d-weekend)'], nextweek: ['upcoming', 'var(--d-week)'],
  postpone: ['chevR', 'var(--fg2)'], none: ['x', 'var(--fg2)'],
};

/** Подпись справа у варианта: день недели, у дальних — ещё и число. */
export function quickHint(key: QuickKey, date: string | null, today: string): string {
  if (!date) return '';
  const wd = WD_SHORT[weekday(date)];
  return key === 'today' || key === 'tomorrow' || key === 'later' ? wd : wd + ' ' + shortDate(date, today);
}

/** «Отложить» у дел: каждому свой следующий срок (schedule.postponed), отмена — тостом. */
export function postponeTasks(ts: Task[]) {
  const today = S.today;
  const moves = ts.map((t) => ({ t, to: postponed(t, today) }));
  if (moves.length === 1) {
    setTask(moves[0].t, moves[0].to, 'Отложил на ' + shortDate(moves[0].to.due_date, today)).catch(() => {});
    return;
  }
  const back = moves.map(({ t }) => ({ op: 'task.set', id: t.id, set: { due_date: t.due_date, due_time: t.due_time ?? null } }));
  ops(moves.map(({ t, to }) => ({ op: 'task.set', id: t.id, set: to })))
    .then(() => toast('Отложил ' + plural(moves.length, 'задачу', 'задачи', 'задач'), () => ops(back).catch(() => {})))
    .catch(() => {});
}

/** Планировщик. mode: due — срок (время, повтор); deadline — дедлайн дела, dueOf — его срок (варианты считаются от
 *  него); date — просто день («пнуть»). tasks — для каких дел (у нового дела нет: тогда сравнивается value). */
export function Scheduler({ value, onPick, close, mode = 'due', tasks, dueOf }: {
  value: DueVal; onPick: (v: DueVal) => void; close: () => void; mode?: 'due' | 'deadline' | 'date'; tasks?: Task[]; dueOf?: string | null;
}) {
  const today = S.today;
  const dateOnly = mode !== 'due';
  const [text, setText] = useState('');
  const [month, setMonth] = useState((value.due_date || today).slice(0, 7));
  const [time, setTime] = useState(value.due_time?.slice(0, 5) || '');
  const parsed: When | null = text.trim() ? parseWhen(text, quickCtx()) : null;
  const pick = (v: Partial<DueVal>) => {
    onPick({ due_date: value.due_date, due_time: dateOnly ? null : time || null, recur: value.recur, due_rule: value.due_rule, ...v });
    close();
  };
  const day = (d: string | null) => pick({ due_date: d, ...(d ? {} : { due_time: null, recur: null, due_rule: null }), ...(value.recur && d ? { recur: null, due_rule: null } : {}) });
  const busy = useMemo(() => {
    const m = new Map<string, number>();
    for (const t of S.tasks.values()) if (t.status === 'open' && t.due_date) m.set(t.due_date, (m.get(t.due_date) || 0) + 1);
    return m;
  }, []);
  // Быстрые варианты — по правилам Todoist (schedule.ts): какие видны, зависит от дня и от выбранных дел.
  const quick: { key: string; label: string; icon: string; color: string; right: string; run: () => void }[] = mode === 'deadline'
    ? deadlineOptions(today, dueOf ?? null, value.due_date).map((o) => ({ key: o.label, label: o.label, icon: o.date ? 'deadline' : 'x', color: 'var(--fg2)',
      right: o.date ? quickHint('nextweek', o.date, today) : '', run: () => day(o.date) }))
    : dueOptions(today, tasks?.length ? tasks : [{ due_date: value.due_date, recur: mode === 'due' ? value.recur : null }], !tasks?.length && mode === 'due')
      .map((o) => ({ key: o.key, label: o.label, icon: QUICK_LOOK[o.key][0], color: QUICK_LOOK[o.key][1], right: quickHint(o.key, o.date, today),
        run: o.key === 'postpone' ? () => { close(); postponeTasks(tasks || []); } : () => day(o.date) }));
  const y = +month.slice(0, 4), m = +month.slice(5, 7);
  const first = ymd(y, m, 1);
  const lead = weekday(first);
  const cells: (string | null)[] = [...Array(lead).fill(null), ...Array.from({ length: daysInMonth(y, m) }, (_, i) => ymd(y, m, i + 1))];
  const due = value.due_date;
  const presets: [string, Recur][] = due ? [
    ['Каждый день', { every: 'day' }],
    [`Каждую неделю (${WD_SHORT[weekday(due)]})`, { every: 'week', days: [weekday(due) + 1] }],
    ['Каждый рабочий день', { every: 'workday' }],
    [`Каждый месяц (${+due.slice(8, 10)}-го)`, { every: 'month', monthday: +due.slice(8, 10) }],
    [`Каждый год (${shortDate(due, '0000')})`, { every: 'year' }],
  ] : [];
  return (
    <div>
      <div className="sched-in">
        <input autoFocus placeholder={mode === 'deadline' ? 'Дедлайн: «25 окт», «пятница»' : mode === 'date' ? 'День: «25 окт», «пятница»' : 'Введите срок: «завтра в 15», «каждый пн»'} value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && parsed) {
              e.preventDefault();
              if (parsed.clear) day(null);
              else pick({ due_date: parsed.due_date, due_time: dateOnly ? null : parsed.due_time ?? (time || null), recur: dateOnly ? null : parsed.recur, due_rule: dateOnly ? null : parsed.due_rule });
            }
          }} />
        {text.trim() ? <div className="hint">{parsed ? (parsed.clear ? 'Без срока' : `${parsed.due_date ? shortDate(parsed.due_date, today) + ', ' + WD[weekday(parsed.due_date)] : ''}${parsed.due_time ? ' в ' + parsed.due_time : ''}${parsed.recur ? ' · ' + (parsed.due_rule || recurWords(parsed.recur)) : ''}`) : 'Не понял — попробуй «завтра», «25 окт», «каждый пн»'}</div> : null}
      </div>
      <div className="sched-q">
        {quick.map((o) => (
          <button key={o.key} className="mi" onClick={o.run}>
            <span style={{ color: o.color }}><Icon name={o.icon} size={20} /></span>{o.label}<span className="r">{o.right}</span>
          </button>
        ))}
      </div>
      <div className="cal">
        <div className="cal-h">
          <span>{MONTHS_NOM[m - 1]} {y}</span>
          <span style={{ display: 'flex', gap: 2 }}>
            <button className="icon-btn sm" onClick={() => setMonth(addMonths(first, -1).slice(0, 7))}><Icon name="chevL" size={18} /></button>
            <button className="icon-btn sm" onClick={() => setMonth(today.slice(0, 7))} title="Сегодня"><Icon name="target" size={14} /></button>
            <button className="icon-btn sm" onClick={() => setMonth(addMonths(first, 1).slice(0, 7))}><Icon name="chevR" size={18} /></button>
          </span>
        </div>
        <div className="cal-g">
          {WD_SHORT.map((w) => <div key={w} className="wd">{w}</div>)}
          {cells.map((d, i) => d ? (
            <button key={d} className={[d < today ? 'past' : '', d === today ? 'today' : '', d === due ? 'on' : ''].join(' ')} onClick={() => day(d)}>
              {+d.slice(8, 10)}{busy.get(d) && d !== due ? <span className="dot" /> : null}
            </button>
          ) : <span key={'e' + i} />)}
        </div>
      </div>
      {dateOnly ? null : (
        <div className="sched-foot">
          <input type="time" value={time} onChange={(e) => setTime(e.target.value)} onBlur={() => value.due_date && time !== (value.due_time || '').slice(0, 5) && pick({ due_time: time || null })} title="Время" />
          {time ? <button className="btn ghost" onClick={() => { setTime(''); pick({ due_time: null }); }}>Без времени</button> : null}
          {presets.length ? (
            <select className="chip" value="" onChange={(e) => {
              const i = +e.target.value;
              if (i === -1) pick({ recur: null, due_rule: null });
              else if (presets[i]) pick({ recur: presets[i][1], due_rule: presets[i][0].replace(/ \(.*\)$/, '').toLowerCase() + (presets[i][1].days ? ' в ' + WD_SHORT[presets[i][1].days![0] - 1] : presets[i][1].monthday ? ' ' + presets[i][1].monthday + '-го' : '') });
            }}>
              <option value="">{value.recur ? 'Повтор: ' + (value.due_rule || recurWords(value.recur)) : 'Повтор…'}</option>
              {presets.map(([label], i) => <option key={label} value={i}>{label}</option>)}
              {value.recur ? <option value={-1}>Без повтора</option> : null}
            </select>
          ) : null}
        </div>
      )}
    </div>
  );
}

// ── Приоритет ────────────────────────────────────────────────────────────

export function PriorityMenu({ value, onPick, close }: { value: number; onPick: (p: number) => void; close: () => void }) {
  return (
    <div>
      {[1, 2, 3, 4].map((p) => (
        <button key={p} className={'mi' + (value === p ? ' act' : '')} onClick={() => { onPick(p); close(); }}>
          <PrioFlag p={p} />Приоритет {p}{value === p ? <span className="r"><Icon name="check" size={16} /></span> : null}
        </button>
      ))}
    </div>
  );
}

// ── Проект, сделка, раздел ───────────────────────────────────────────────

export interface Place {
  project_id: Id | null;
  deal_id: Id | null;
  section_id: Id | null;
}

export function ProjectPicker({ value, onPick, close }: { value: Place; onPick: (p: Place) => void; close: () => void }) {
  const [q, setQ] = useState('');
  const n = q.toLowerCase().replace(/ё/g, 'е');
  const ok = (s: string) => !n || s.toLowerCase().replace(/ё/g, 'е').includes(n);
  const rows: { key: string; label: string; depth: number; icon: React.ReactNode; place: Place }[] = [];
  if (ok('Входящие')) rows.push({ key: 'inbox', label: 'Входящие', depth: 0, icon: <Icon name="inbox" size={18} />, place: { project_id: null, deal_id: null, section_id: null } });
  for (const p of projectsSorted()) {
    const deals = liveDeals(p.id), secs = sectionsOf(p.id);
    const self = ok(p.name), subs = deals.filter((d) => ok(d.name)), ss = secs.filter((s) => ok(s.name) || self);
    if (!self && !subs.length && !secs.some((s) => ok(s.name))) continue;
    rows.push({ key: p.id, label: p.name, depth: 0, icon: <span className="hash" style={{ color: projectColor(p) }}>#</span>, place: { project_id: p.id, deal_id: null, section_id: null } });
    for (const s of ss) rows.push({ key: s.id, label: s.name, depth: 1, icon: <Icon name="list" size={16} />, place: { project_id: p.id, deal_id: null, section_id: s.id } });
    for (const d of self ? deals : subs) rows.push({ key: d.id, label: dealShort(d.id), depth: 1, icon: <Icon name="deal" size={16} />, place: { project_id: p.id, deal_id: d.id, section_id: null } });
  }
  const same = (a: Place) => a.project_id === value.project_id && (a.deal_id || null) === (value.deal_id || null) && (a.section_id || null) === (value.section_id || null);
  return (
    <div>
      <input className="search" autoFocus placeholder="Проект, раздел или сделка" value={q} onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && rows[0]) { onPick(rows[0].place); close(); } }} />
      <div className="scroll">
        {rows.map((r) => (
          <button key={r.key} className={'mi' + (same(r.place) ? ' act' : '')} style={{ paddingLeft: 8 + r.depth * 20 }} onClick={() => { onPick(r.place); close(); }}>
            {r.icon}<span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.label}</span>
            {same(r.place) ? <Icon name="check" size={16} /> : null}
          </button>
        ))}
        {!rows.length ? <div className="mh">Ничего не нашлось</div> : null}
      </div>
    </div>
  );
}

// ── Метки ────────────────────────────────────────────────────────────────

export function LabelPicker({ value, onPick }: { value: string[]; onPick: (l: string[]) => void }) {
  const [q, setQ] = useState('');
  const [cur, setCur] = useState(value);
  const all = [...new Set([...S.labels.map((l) => l.name), ...value])].sort((a, b) => a.localeCompare(b, 'ru'));
  const list = all.filter((l) => !q || l.toLowerCase().includes(q.toLowerCase()));
  const toggle = (l: string) => {
    const next = cur.includes(l) ? cur.filter((x) => x !== l) : [...cur, l];
    setCur(next);
    onPick(next);
  };
  const fresh = q.trim() && !all.some((l) => l.toLowerCase() === q.trim().toLowerCase());
  return (
    <div>
      <input className="search" autoFocus placeholder="Метка" value={q} onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && q.trim()) { toggle(fresh ? q.trim() : list[0] || q.trim()); setQ(''); } }} />
      <div className="scroll">
        {list.map((l) => (
          <button key={l} className="mi" onClick={() => toggle(l)}>
            <Icon name="tag" size={16} /><span style={{ flex: 1 }}>{l}</span>
            <input type="checkbox" readOnly checked={cur.includes(l)} />
          </button>
        ))}
        {fresh ? <button className="mi" onClick={() => { toggle(q.trim()); setQ(''); }}><Icon name="plus" size={16} />Создать «{q.trim()}»</button> : null}
      </div>
    </div>
  );
}

// ── Человек из CRM ───────────────────────────────────────────────────────

export function PersonPicker({ value, onPick, close }: { value: Id | null; onPick: (id: Id | null) => void; close: () => void }) {
  const [q, setQ] = useState('');
  const n = q.toLowerCase().replace(/ё/g, 'е');
  const list = [...S.people.values()].filter((p) => !p.merged_into && (!n || (p.name + ' ' + (p.short || '')).toLowerCase().replace(/ё/g, 'е').includes(n)))
    .sort((a, b) => a.name.localeCompare(b.name, 'ru')).slice(0, 60);
  return (
    <div>
      <input className="search" autoFocus placeholder="Человек" value={q} onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && list[0]) { onPick(list[0].id); close(); } }} />
      <div className="scroll">
        {value ? <button className="mi" onClick={() => { onPick(null); close(); }}><Icon name="x" size={16} />Без человека</button> : null}
        {list.map((p) => (
          <button key={p.id} className={'mi' + (p.id === value ? ' act' : '')} onClick={() => { onPick(p.id); close(); }}>
            <Icon name="user" size={16} /><span style={{ flex: 1 }}>{p.name}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ── Цель («Зачем») ───────────────────────────────────────────────────────

export function GoalPicker({ task, onPick, close }: { task?: Task; value: Id | null; onPick: (id: Id | null) => void; close: () => void }) {
  const [q, setQ] = useState('');
  const n = q.toLowerCase().replace(/ё/g, 'е');
  const list = goals().filter((g) => g.id !== task?.id && (!n || g.title.toLowerCase().replace(/ё/g, 'е').includes(n)))
    .map((g) => ({ g, path: [dirName(goalDir(g)), ...whyPath(g).map((x) => x.title)].filter(Boolean).join(' › ') }))
    .sort((a, b) => (a.path + a.g.title).localeCompare(b.path + b.g.title, 'ru')).slice(0, 80);
  return (
    <div style={{ width: 300 }}>
      <input className="search" autoFocus placeholder="Цель из дерева жизни" value={q} onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && list[0]) { onPick(list[0].g.id); close(); } }} />
      <div className="scroll">
        {task?.why_id ? <button className="mi" onClick={() => { onPick(null); close(); }}><Icon name="x" size={16} />Без цели</button> : null}
        {list.map(({ g, path }) => (
          <button key={g.id} className={'mi' + (g.id === task?.why_id ? ' act' : '')} onClick={() => { onPick(g.id); close(); }}>
            <Icon name="target" size={16} style={{ color: 'var(--accent)' }} />
            <span style={{ flex: 1, minWidth: 0 }}>
              <div>{g.title}</div>
              {path ? <div style={{ fontSize: 11, color: 'var(--fg3)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{path}</div> : null}
            </span>
          </button>
        ))}
        {!list.length ? <div className="mh">Целей не нашлось</div> : null}
      </div>
    </div>
  );
}

// ── Цвет ─────────────────────────────────────────────────────────────────

export function ColorPicker({ value, onPick, close }: { value: string | null; onPick: (c: string | null) => void; close: () => void }) {
  return (
    <div className="scroll">
      {Object.entries(PALETTE).map(([k, [hex, name]]) => (
        <button key={k} className={'mi' + (value === k ? ' act' : '')} onClick={() => { onPick(k); close(); }}>
          <span style={{ width: 12, height: 12, borderRadius: '50%', background: hex }} />{name}
        </button>
      ))}
    </div>
  );
}
