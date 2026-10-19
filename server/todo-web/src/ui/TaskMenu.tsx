// Меню задачи — по правой кнопке и «…», порядок как у Todoist (19.10): выше / ниже, изменить, срок рядом значков,
// приоритет рядом флажков, дедлайн, напоминание, переместить, дублировать, ссылка, удалить — красным. Свои пункты Дел
// («Сейчас», человек, «Зачем», CRM) — рядом с похожими. У пункта справа — его клавиша (keys.ts).
import { useState } from 'react';
import { shortDate } from '../dates.ts';
import { dueOptions } from '../schedule.ts';
import { crmUrl, taskLink } from '../lib.ts';
import { op1, ops, S, setTask, toast } from '../store.ts';
import type { Task } from '../types.ts';
import { Icon, PrioFlag } from './icons.tsx';
import { closePop, openPop } from './pop.tsx';
import { GoalPicker, LabelPicker, PersonPicker, postponeTasks, ProjectPicker, QUICK_LOOK, quickHint, Scheduler } from './pickers.tsx';
import { openQuick, openTask } from './state.ts';
import { setDeadline, setDue, setPlace } from './acts.ts';
import { T } from '../toastText.ts';

/** Напоминание в день и час (Москва), как в карточке задачи. */
function RemindPicker({ t, close }: { t: Task; close: () => void }) {
  const cur = t.remind_at ? new Date(t.remind_at).toLocaleString('sv', { timeZone: 'Europe/Moscow' }).slice(0, 16).replace(' ', 'T') : '';
  const [v, setV] = useState(cur);
  const save = (at: string | null) => {
    close();
    setTask(t, { remind_at: at }, at ? 'Напомню ' + shortDate(at.slice(0, 10), S.today) + ' в ' + at.slice(11, 16) : 'Без напоминания').catch(() => {});
  };
  return (
    <div>
      <div className="mh">Напоминание</div>
      <input className="search" type="datetime-local" autoFocus value={v} onChange={(e) => setV(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && v) save(v + ':00+03:00'); }} />
      <button className="mi" disabled={!v} onClick={() => v && save(v + ':00+03:00')}><Icon name="alarm" size={18} />Напомнить</button>
      {t.remind_at ? <button className="mi" onClick={() => save(null)}><Icon name="x" size={18} />Убрать напоминание</button> : null}
    </div>
  );
}

export function TaskMenu({ t, close }: { t: Task; close: () => void }) {
  const today = S.today;
  const set = (v: Record<string, unknown>, said?: string) => {
    close();
    setTask(t, v, said).catch(() => {});
  };
  const anchor = () => document.querySelector(`[data-id="${t.id}"]`) || document.body;
  const sub = (render: (close: () => void) => React.ReactNode, cls?: string) => {
    const a = anchor();
    closePop();
    setTimeout(() => openPop(a, render, { cls }), 0);
  };
  const near = (mark: 'before' | 'after') => {
    close();
    openQuick({ project_id: t.project_id, deal_id: t.deal_id, section_id: t.section_id ?? null, why_id: t.why_id ?? null, near: { id: t.id, mark } });
  };
  const isNow = t.focus_on === today;
  return (
    <div className="task-menu">
      <button className="mi" onClick={() => near('before')}><Icon name="arrowUp" size={18} />Добавить задачу выше</button>
      <button className="mi" onClick={() => near('after')}><Icon name="arrowDown" size={18} />Добавить задачу ниже</button>
      <div className="msep" />
      <button className="mi" onClick={() => { close(); openTask(t.id, true); }}><Icon name="pencil" size={18} />Изменить<span className="r">Ctrl E</span></button>
      <button className="mi" onClick={() => set({ focus_on: isNow ? null : today }, isNow ? 'Убрал из «Сейчас»' : 'В «Сейчас»')}>
        <Icon name="bolt" size={18} />{isNow ? 'Убрать из «Сейчас»' : 'В «Сейчас»'}
      </button>
      <div className="msep" />
      <div className="mh">Срок<span className="r">T</span></div>
      <div className="date-row">
        {/* те же варианты, что в планировщике, по правилам Todoist (schedule.ts) */}
        {dueOptions(today, [t]).map((o) => (
          <button key={o.key} title={o.label + (o.date ? ' — ' + quickHint(o.key, o.date, today) : '')} style={{ color: QUICK_LOOK[o.key][1] }}
            onClick={() => {
              if (o.key === 'postpone') {
                close();
                postponeTasks([t]);
              } else if (o.date) set({ due_date: o.date }, T.due(o.date, today));
              else set({ due_date: null, due_time: null, recur: null, due_rule: null }, 'Срок снят');
            }}><Icon name={QUICK_LOOK[o.key][0]} size={20} /></button>
        ))}
        <button title="Другой срок…" onClick={() => sub((c) => <Scheduler tasks={[t]} value={t as any} close={c} onPick={(v) => setDue(t, v)} />, 'sched')}><Icon name="more" size={20} /></button>
      </div>
      <div className="mh">Приоритет<span className="r">Y</span></div>
      <div className="prio-row">
        {[1, 2, 3, 4].map((p) => (
          <button key={p} className={(t.priority ?? 4) === p ? 'on' : ''} title={'Приоритет ' + p} onClick={() => set({ priority: p }, T.priority(p))}><PrioFlag p={p} /></button>
        ))}
      </div>
      <div className="msep" />
      <button className="mi" onClick={() => sub((c) => <Scheduler mode="deadline" dueOf={t.due_date} value={{ due_date: t.deadline ?? null, due_time: null, recur: null, due_rule: null }} close={c}
        onPick={(v) => setDeadline(t, v.due_date)} />, 'sched')}>
        <Icon name="deadline" size={18} />Дедлайн<span className="r">D</span></button>
      <button className="mi" onClick={() => sub((c) => <RemindPicker t={t} close={c} />)}><Icon name="alarm" size={18} />Напоминание</button>
      <button className="mi" onClick={() => sub(() => <LabelPicker value={t.labels || []} onPick={(l) => setTask(t, { labels: l })} />)}><Icon name="tag" size={18} />Метки<span className="r">L</span></button>
      <button className="mi" onClick={() => sub((c) => <PersonPicker value={t.person_id} close={c} onPick={(id) => setTask(t, { person_id: id })} />)}><Icon name="user" size={18} />Человек</button>
      <button className="mi" onClick={() => sub((c) => <GoalPicker task={t} value={t.why_id ?? null} close={c} onPick={(id) => setTask(t, { why_id: id }, id ? 'Положил под цель' : 'Убрал из дерева')} />)}><Icon name="target" size={18} />Зачем (цель)</button>
      <button className="mi" onClick={() => { close(); openQuick({ project_id: t.project_id, deal_id: t.deal_id, why_id: t.id }); }}><Icon name="sub" size={18} />Добавить подзадачу</button>
      <div className="msep" />
      <button className="mi" onClick={() => sub((c) => <ProjectPicker value={{ project_id: t.project_id, deal_id: t.deal_id, section_id: t.section_id ?? null }} close={c}
        onPick={(p) => setPlace(t, p)} />)}><Icon name="door" size={18} />Переместить в…<span className="r">V</span></button>
      <button className="mi" onClick={() => {
        close();
        const copy: Record<string, unknown> = { title: t.title, notes: t.notes, project_id: t.project_id, deal_id: t.deal_id, due_date: t.due_date, due_time: t.due_time,
          priority: t.priority ?? 4, labels: t.labels, person_id: t.person_id, section_id: t.section_id ?? null, deadline: t.deadline ?? null };
        if (t.recur) Object.assign(copy, { recur: t.recur, due_rule: t.due_rule });
        const id = crypto.randomUUID();
        op1({ op: 'task.create', task: { id, ...copy } }).then(() => toast('Дублировал', () => ops([{ op: 'task.cancel', id }]).catch(() => {}))).catch(() => {});
      }}><Icon name="copy" size={18} />Дублировать</button>
      <button className="mi" onClick={() => { close(); navigator.clipboard?.writeText(taskLink(t)).then(() => toast('Ссылка скопирована')); }}><Icon name="link" size={18} />Копировать ссылку</button>
      {t.project_id ? <a className="mi" href={crmUrl(t.deal_id ? 'd' : 'p', t.deal_id || t.project_id)} target="_blank" rel="noreferrer" onClick={close}><Icon name="deal" size={18} />Карточка в CRM</a> : null}
      {t.recur ? <button className="mi" onClick={() => { close(); ops([{ op: 'task.done', id: t.id, forever: true }]).then(() => toast('Выполнено навсегда', () => ops([{ op: 'task.reopen', id: t.id }]).catch(() => {}))).catch(() => {}); }}><Icon name="done" size={18} />Выполнить навсегда<span className="r">Shift E</span></button> : null}
      <div className="msep" />
      <button className="mi danger" onClick={() => {
        close();
        ops([{ op: 'task.cancel', id: t.id }]).then(() => toast(T.deleted(), () => ops([{ op: 'task.reopen', id: t.id }]).catch(() => {}))).catch(() => {});
      }}><Icon name="trash" size={18} />Удалить</button>
    </div>
  );
}
