// Редактор новой задачи — Quick Add Todoist: строка с подсветкой распознанного, описание, чипы, проект внизу слева.
// Тот же редактор — окном (Q, «+») и в строке списка («Добавить задачу»).
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { uuid } from '../api.ts';
import { addDays, deadlineWords, dueTone, dueWords, nowHm, shortDate } from '../dates.ts';
import { dealShort, labelColor, personName, projectColor } from '../lib.ts';
import { cutTokens, parseQuick, recurWords, type Parsed, type TokKind } from '../quick.ts';
import { ops, S } from '../store.ts';
import { reorder } from './TaskList.tsx';
import type { Id, Recur } from '../types.ts';
import { byPos, liveDeals, parentOf, projectsSorted, sectionsOf } from '../views.ts';
import { quickCtx } from './ctx.ts';
import { Icon, PrioFlag } from './icons.tsx';
import { openPop } from './pop.tsx';
import { GoalPicker, LabelPicker, PersonPicker, PriorityMenu, ProjectPicker, Scheduler, type DueVal, type Place } from './pickers.tsx';
import { openDictate, type QuickDefaults } from './state.ts';

interface Manual {
  due?: DueVal;
  priority?: number;
  place?: Place;
  labels?: string[];
  person_id?: Id | null;
  deadline?: string | null;
  why_id?: Id | null;
  now?: boolean;
}

/** Строка с подсветкой: прозрачное поле поверх «зеркала», в котором распознанное — с подложкой. */
function HighlightInput({ value, onChange, parsed, onKeyDown, placeholder, inputRef, onCaret }: {
  value: string; onChange: (v: string) => void; parsed: Parsed; onKeyDown: (e: KeyboardEvent<HTMLTextAreaElement>) => void;
  placeholder: string; inputRef: React.RefObject<HTMLTextAreaElement | null>; onCaret: () => void;
}) {
  const parts: React.ReactNode[] = [];
  let at = 0;
  for (const t of parsed.tokens) {
    if (t.start < at) continue;
    parts.push(value.slice(at, t.start));
    parts.push(<mark key={t.start}>{value.slice(t.start, t.end)}</mark>);
    at = t.end;
  }
  parts.push(value.slice(at) + '​');
  useEffect(() => {
    const el = inputRef.current;
    if (el) {
      el.style.height = 'auto';
      el.style.height = el.scrollHeight + 'px';
    }
  }, [value, inputRef]);
  return (
    <div className="hl-wrap">
      <div className="hl-mirror" aria-hidden>{parts}</div>
      <textarea ref={inputRef} className="hl-input" rows={1} value={value} placeholder={placeholder} autoFocus spellCheck={false}
        onChange={(e) => { onChange(e.target.value); onCaret(); }} onKeyDown={onKeyDown} onKeyUp={onCaret} onClick={onCaret} />
    </div>
  );
}

interface Sugg { label: string; insert: string; icon: React.ReactNode }

export function Editor({ defaults = {}, onClose, inline, keepOpen }: { defaults?: QuickDefaults; onClose: () => void; inline?: boolean; keepOpen?: boolean }) {
  const [text, setText] = useState('');
  const [desc, setDesc] = useState('');
  const [man, setMan] = useState<Manual>({});
  const [caret, setCaret] = useState(0);
  const [hi, setHi] = useState(0);
  const ref = useRef<HTMLTextAreaElement>(null);
  const ctx = useMemo(() => quickCtx(), []);
  const parsed = useMemo(() => parseQuick(text, ctx), [text, ctx]);
  // Крестик на чипе распознанного (как «Убрать» у Todoist): вырезать его слова из строки — иначе строка распознает снова.
  const cut = (kind: TokKind, word?: string) => setText(cutTokens(text, parsed, kind, word));

  // Итог: умолчания вида ← чипы ← распознанное в строке (видно — значит главнее).
  const place: Place = parsed.project_id !== undefined || parsed.section_id
    ? { project_id: parsed.project_id ?? null, deal_id: parsed.deal_id ?? null, section_id: parsed.section_id ?? null }
    : man.place || { project_id: defaults.project_id ?? null, deal_id: defaults.deal_id ?? null, section_id: defaults.section_id ?? null };
  const due: DueVal = parsed.due_date !== undefined || parsed.clear
    ? { due_date: parsed.due_date ?? null, due_time: parsed.due_time ?? null, recur: parsed.recur ?? null, due_rule: parsed.due_rule ?? null }
    : man.due || { due_date: defaults.due_date ?? null, due_time: null, recur: null, due_rule: null };
  const priority = parsed.priority ?? man.priority ?? defaults.priority ?? 4;
  const labels = [...new Set([...(man.labels ?? defaults.labels ?? []), ...parsed.labels])];
  const person = parsed.person_id ?? (man.person_id !== undefined ? man.person_id : defaults.person_id ?? null);
  const deadline = parsed.deadline ?? (man.deadline !== undefined ? man.deadline : null);
  const why = man.why_id !== undefined ? man.why_id : defaults.why_id ?? null;
  const now = parsed.now || man.now || defaults.focus_on === S.today;
  const notes = [parsed.notes, desc.trim()].filter(Boolean).join('\n') || null;
  // «!15:00» без дня — в день срока; срока нет — сегодня, а если время прошло — завтра.
  const rw = parsed.remind_when;
  const remindDay = rw ? rw.date || due.due_date || (rw.time > nowHm() ? S.today : addDays(S.today, 1)) : null;
  const remindAt = rw && remindDay ? `${remindDay}T${rw.time}:00+03:00` : null;
  const remindPlace = parsed.remind_place || null;

  // Подсказки при наборе #, /, @, %, +
  const before = text.slice(0, caret);
  // Набранное после знака — поиск, хоть с пробелами («#Альфа Г»): список живёт, пока что-то совпадает.
  const tm = before.match(/(?:^|\s)([#@%+/])([^#@%+/\n]{0,40})$/);
  const trig = tm ? { ch: tm[1], part: tm[2] } : null;
  const sugg: Sugg[] = useMemo(() => {
    if (!trig) return [];
    const { ch, part } = trig;
    if ((ch === '@' || ch === '%') && /\s/.test(part)) return [];
    const n = part.toLowerCase().replace(/ё/g, 'е');
    const ok = (s: string) => s.toLowerCase().replace(/ё/g, 'е').includes(n);
    if (ch === '#') {
      const out: Sugg[] = [];
      for (const p of projectsSorted()) {
        if (ok(p.name)) out.push({ label: p.name, insert: p.name, icon: <span className="hash" style={{ color: projectColor(p) }}>#</span> });
        for (const d of liveDeals(p.id)) if (ok(d.name)) out.push({ label: d.name, insert: d.name, icon: <Icon name="deal" size={16} /> });
      }
      return out.slice(0, 8);
    }
    if (ch === '/') {
      return sectionsOf(place.project_id || '').filter((s) => ok(s.name)).map((s) => ({ label: s.name, insert: s.name, icon: <Icon name="list" size={16} /> })).slice(0, 8);
    }
    if (ch === '@' || ch === '%') {
      const list = S.labels.map((l) => l.name).filter(ok).map((l) => ({ label: l, insert: l, icon: <Icon name="tag" size={16} /> }));
      if (part && !S.labels.some((l) => l.name.toLowerCase() === part.toLowerCase())) list.push({ label: `Создать «${part}»`, insert: part, icon: <Icon name="plus" size={16} /> });
      return list.slice(0, 8);
    }
    return [...S.people.values()].filter((p) => !p.merged_into && (ok(p.name) || ok(p.short || '')))
      .slice(0, 8).map((p) => ({ label: p.name, insert: p.short || p.name, icon: <Icon name="user" size={16} /> }));
  }, [trig?.ch, trig?.part, place.project_id]);
  const showSugg = sugg.length > 0 && !!trig;

  const pickSugg = (s: Sugg) => {
    if (!trig) return;
    const start = caret - trig.part.length;
    const next = text.slice(0, start) + s.insert + ' ' + text.slice(caret);
    setText(next);
    const c = start + s.insert.length + 1;
    setCaret(c);
    requestAnimationFrame(() => { ref.current?.setSelectionRange(c, c); ref.current?.focus(); });
  };

  const can = parsed.title.trim().length > 0;
  const submit = async () => {
    if (!can) return;
    const lines = text.split('\n').map((x) => x.trim()).filter(Boolean);
    const one = (title: string) => {
      const task: Record<string, unknown> = {
        id: uuid(), title, notes, project_id: place.project_id, deal_id: place.deal_id, section_id: place.section_id,
        due_date: due.due_date, due_time: due.due_date ? due.due_time : null, priority, labels, person_id: person, deadline,
        source: 'web',
      };
      if (due.recur && due.due_date) Object.assign(task, { recur: due.recur, due_rule: due.due_rule || recurWords(due.recur) });
      if (now) task.focus_on = S.today;
      if (remindAt) task.remind_at = remindAt;
      if (remindPlace) task.remind_place = remindPlace;
      if (why) task.why_id = why;
      return { op: 'task.create', task };
    };
    const items = lines.length > 1 && confirm(`Добавить ${lines.length} задач — по одной на строку?`)
      ? lines.map((l) => one(parseQuick(l, ctx).title || l))
      : [one(parsed.title)];
    setText('');
    setDesc('');
    setMan({});
    if (!keepOpen) onClose();
    else requestAnimationFrame(() => ref.current?.focus());
    await ops(items).catch(() => {});
    // «Добавить задачу выше / ниже» из меню дела: новое встаёт рядом с тем делом (как перетаскивание)
    const near = defaults.near ? S.tasks.get(defaults.near.id) : null;
    const made = items.length === 1 ? S.tasks.get((items[0].task as { id: string }).id) : null;
    if (near && made) {
      const sibs = [...S.tasks.values()].filter((x) => x.status === 'open' && parentOf(x) === parentOf(near) && (x.section_id ?? null) === (near.section_id ?? null)
        && x.project_id === near.project_id && x.deal_id === near.deal_id).sort(byPos);
      await reorder(sibs, made, near, defaults.near!.mark);
    }
  };

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (showSugg) {
      if (e.key === 'ArrowDown') { e.preventDefault(); setHi((hi + 1) % sugg.length); return; }
      if (e.key === 'ArrowUp') { e.preventDefault(); setHi((hi - 1 + sugg.length) % sugg.length); return; }
      if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); pickSugg(sugg[Math.min(hi, sugg.length - 1)]); return; }
    }
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); }
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onClose(); }
  };

  const chip = (key: string, icon: React.ReactNode, label: string | null, placeholder: string, onClick: (el: HTMLElement) => void, clear?: () => void) => (
    <button key={key} className={'chip' + (label ? ' set' : '')} onClick={(e) => onClick(e.currentTarget)}>
      {icon}{label || placeholder}
      {label && clear ? <span className="x" onClick={(e) => { e.stopPropagation(); clear(); }}><Icon name="x" size={12} /></span> : null}
    </button>
  );
  const dueLabel = due.due_date ? `${dueWords(due.due_date, S.today)}${due.due_time ? ' ' + due.due_time.slice(0, 5) : ''}` : null;
  const proj = place.project_id ? S.projects.get(place.project_id) : null;
  const placeLabel = place.deal_id ? `${proj?.name || ''} / ${dealShort(place.deal_id)}`
    : proj ? proj.name + (place.section_id ? ' / ' + (S.sections.get(place.section_id)?.name || '') : '') : 'Входящие';
  const whyTask = why ? S.tasks.get(why) : null;

  return (
    <div className={'editor' + (inline ? ' inline' : '')} onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); onClose(); } }}>
      <div className="ed-in" style={{ position: 'relative' }}>
        <HighlightInput value={text} onChange={(v) => { setText(v); setHi(0); }} parsed={parsed} onKeyDown={onKey} inputRef={ref}
          placeholder={inline ? 'Название задачи' : 'Например: позвонить Ивану завтра в 15 #Альфа p1'}
          onCaret={() => setCaret(ref.current?.selectionStart ?? text.length)} />
        <textarea className="ed-desc" rows={1} placeholder="Описание" value={desc} onChange={(e) => setDesc(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(); } }} />
        {showSugg ? (
          <div className="pop" style={{ position: 'absolute', left: 8, top: '100%', zIndex: 80 }}>
            {sugg.map((s, i) => (
              <button key={s.label + i} className={'mi' + (i === hi ? ' act' : '')} onMouseDown={(e) => { e.preventDefault(); pickSugg(s); }}>
                {s.icon}<span>{s.label}</span>
              </button>
            ))}
          </div>
        ) : null}
      </div>
      <div className="ed-chips">
        {chip('due', <Icon name="calendar" size={16} style={{ color: due.due_date ? `var(--${dueTone(due.due_date, S.today).slice(0)})` : undefined }} />, dueLabel ? dueLabel + (due.recur ? ' ↻' : '') : null, 'Срок',
          (el) => openPop(el, (close) => <Scheduler value={due} close={close} onPick={(v) => setMan({ ...man, due: v })} />, { cls: 'sched' }),
          () => { cut('date'); setMan({ ...man, due: { due_date: null, due_time: null, recur: null, due_rule: null } }); })}
        {chip('pr', <PrioFlag p={priority} size={16} />, priority < 4 ? 'P' + priority : null, 'Приоритет',
          (el) => openPop(el, (close) => <PriorityMenu value={priority} close={close} onPick={(p) => setMan({ ...man, priority: p })} />),
          () => { cut('priority'); setMan({ ...man, priority: 4 }); })}
        {chip('dl', <Icon name="deadline" size={16} />, deadline ? deadlineWords(deadline, S.today) : null, 'Дедлайн',
          (el) => openPop(el, (close) => <Scheduler mode="deadline" dueOf={due.due_date} value={{ due_date: deadline, due_time: null, recur: null, due_rule: null }} close={close}
            onPick={(v) => setMan({ ...man, deadline: v.due_date })} />, { cls: 'sched' }),
          () => { cut('deadline'); setMan({ ...man, deadline: null }); })}
        {/* метки — каждая своим чипом, как у Todoist («Убрать метку») */}
        {(labels.length ? labels : [null]).map((l) => chip('lb:' + (l ?? ''), <Icon name="tag" size={16} />, l, 'Метки',
          (el) => openPop(el, () => <LabelPicker value={labels} onPick={(x) => setMan({ ...man, labels: x })} />),
          () => { cut('label', l!); setMan({ ...man, labels: labels.filter((x) => x !== l) }); }))}
        {chip('ps', <Icon name="user" size={16} />, person ? personName(person) : null, 'Человек',
          (el) => openPop(el, (close) => <PersonPicker value={person} close={close} onPick={(id) => setMan({ ...man, person_id: id })} />),
          () => { cut('person'); setMan({ ...man, person_id: null }); })}
        {chip('why', <Icon name="target" size={16} />, whyTask ? whyTask.title : null, 'Зачем',
          (el) => openPop(el, (close) => <GoalPicker value={why} close={close} onPick={(id) => setMan({ ...man, why_id: id })} />),
          () => setMan({ ...man, why_id: null }))}
        {remindAt || remindPlace ? (
          <span className="chip set"><Icon name="alarm" size={16} />{remindPlace ? 'напомню: ' + remindPlace : 'напомню ' + (remindDay === S.today ? '' : dueWords(remindDay!, S.today) + ' ') + rw!.time}
            <span className="x" title="Убрать напоминание" onClick={() => cut('remind')}><Icon name="x" size={12} /></span></span>
        ) : null}
        <button className={'chip' + (now ? ' set' : '')} onClick={() => (parsed.now ? cut('now') : setMan({ ...man, now: !now }))} style={now ? { color: 'var(--accent-text)', borderColor: 'var(--accent)' } : undefined}>
          <Icon name="bolt" size={16} />Сейчас
        </button>
      </div>
      <div className="ed-foot">
        <span style={{ display: 'flex', alignItems: 'center', gap: 4, minWidth: 0 }}>
        <button className="icon-btn sm" title="Надиктовать много задач (Shift+Q)" onClick={() => { onClose(); openDictate(); }}><Icon name="mic" size={18} /></button>
        <button className="proj-pick" onClick={(e) => openPop(e.currentTarget, (close) => <ProjectPicker value={place} close={close} onPick={(p) => setMan({ ...man, place: p })} />)}>
          {proj ? <span className="hash" style={{ color: projectColor(proj) }}>#</span> : <Icon name="inbox" size={16} />}
          <span>{placeLabel}</span><Icon name="chevD" size={14} />
        </button>
        </span>
        <span style={{ display: 'flex', gap: 8 }}>
          <button className="btn sec" onClick={onClose}>Отмена</button>
          <button className="btn pri" disabled={!can} onClick={submit}>Добавить задачу</button>
        </span>
      </div>
    </div>
  );
}

export { labelColor, shortDate };
export type { Recur };
