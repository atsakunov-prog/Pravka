// Меню задачи — по правой кнопке и «…»: быстрые сроки, приоритет, «Сейчас», переместить, метки, дублировать, ссылка, удалить.
import { shortDate } from '../dates.ts';
import { dueOptions } from '../schedule.ts';
import { crmUrl, taskLink } from '../lib.ts';
import { op1, ops, S, setTask, toast } from '../store.ts';
import type { Task } from '../types.ts';
import { Icon, PrioFlag } from './icons.tsx';
import { closePop, openPop } from './pop.tsx';
import { GoalPicker, LabelPicker, PersonPicker, postponeTasks, ProjectPicker, QUICK_LOOK, quickHint, Scheduler } from './pickers.tsx';
import { openQuick, openTask } from './state.ts';

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
  const isNow = t.focus_on === today;
  return (
    <div>
      <button className="mi" onClick={() => { close(); openTask(t.id, true); }}><Icon name="pencil" size={18} />Изменить<span className="r">Ctrl E</span></button>
      <button className="mi" onClick={() => set({ focus_on: isNow ? null : today }, isNow ? 'Убрал из «Сейчас»' : 'В «Сейчас»')}>
        <Icon name="bolt" size={18} />{isNow ? 'Убрать из «Сейчас»' : 'В «Сейчас»'}
      </button>
      <div className="msep" />
      <div className="mh">Срок</div>
      <div className="date-row">
        {/* те же варианты, что в планировщике, по правилам Todoist (schedule.ts) */}
        {dueOptions(today, [t]).map((o) => (
          <button key={o.key} title={o.label + (o.date ? ' — ' + quickHint(o.key, o.date, today) : '')} style={{ color: QUICK_LOOK[o.key][1] }}
            onClick={() => {
              if (o.key === 'postpone') {
                close();
                postponeTasks([t]);
              } else if (o.date) set({ due_date: o.date }, 'Срок — ' + (o.key === 'today' || o.key === 'tomorrow' ? o.label.toLowerCase() : shortDate(o.date, today)));
              else set({ due_date: null, due_time: null, recur: null, due_rule: null }, 'Срок снят');
            }}><Icon name={QUICK_LOOK[o.key][0]} size={20} /></button>
        ))}
        <button title="Другой срок…" onClick={() => sub((c) => <Scheduler tasks={[t]} value={t as any} close={c} onPick={(v) => setTask(t, v as any, 'Срок поменял')} />, 'sched')}><Icon name="more" size={20} /></button>
      </div>
      <div className="mh">Приоритет</div>
      <div className="prio-row">
        {[1, 2, 3, 4].map((p) => (
          <button key={p} className={(t.priority ?? 4) === p ? 'on' : ''} title={'Приоритет ' + p} onClick={() => set({ priority: p })}><PrioFlag p={p} /></button>
        ))}
      </div>
      <div className="msep" />
      <button className="mi" onClick={() => sub((c) => <ProjectPicker value={{ project_id: t.project_id, deal_id: t.deal_id, section_id: t.section_id ?? null }} close={c}
        onPick={(p) => setTask(t, { project_id: p.project_id, deal_id: p.deal_id, section_id: p.section_id }, 'Переместил')} />)}><Icon name="door" size={18} />Переместить в…<span className="r">V</span></button>
      <button className="mi" onClick={() => sub(() => <LabelPicker value={t.labels || []} onPick={(l) => setTask(t, { labels: l })} />)}><Icon name="tag" size={18} />Метки<span className="r">L</span></button>
      <button className="mi" onClick={() => sub((c) => <PersonPicker value={t.person_id} close={c} onPick={(id) => setTask(t, { person_id: id })} />)}><Icon name="user" size={18} />Человек</button>
      <button className="mi" onClick={() => sub((c) => <GoalPicker task={t} value={t.why_id ?? null} close={c} onPick={(id) => setTask(t, { why_id: id }, id ? 'Положил под цель' : 'Убрал из дерева')} />)}><Icon name="target" size={18} />Зачем (цель)</button>
      <button className="mi" onClick={() => { close(); openQuick({ project_id: t.project_id, deal_id: t.deal_id, why_id: t.id }); }}><Icon name="sub" size={18} />Добавить подзадачу</button>
      <div className="msep" />
      <button className="mi" onClick={() => {
        close();
        const copy: Record<string, unknown> = { title: t.title, notes: t.notes, project_id: t.project_id, deal_id: t.deal_id, due_date: t.due_date, due_time: t.due_time,
          priority: t.priority ?? 4, labels: t.labels, person_id: t.person_id, section_id: t.section_id ?? null, deadline: t.deadline ?? null };
        if (t.recur) Object.assign(copy, { recur: t.recur, due_rule: t.due_rule });
        op1({ op: 'task.create', task: { id: crypto.randomUUID(), ...copy } }).then(() => toast('Дублировал')).catch(() => {});
      }}><Icon name="copy" size={18} />Дублировать</button>
      <button className="mi" onClick={() => { close(); navigator.clipboard?.writeText(taskLink(t)).then(() => toast('Ссылка скопирована')); }}><Icon name="link" size={18} />Копировать ссылку</button>
      {t.project_id ? <a className="mi" href={crmUrl(t.deal_id ? 'd' : 'p', t.deal_id || t.project_id)} target="_blank" rel="noreferrer" onClick={close}><Icon name="deal" size={18} />Карточка в CRM</a> : null}
      {t.recur ? <button className="mi" onClick={() => { close(); ops([{ op: 'task.done', id: t.id, forever: true }]).then(() => toast('Выполнено навсегда')).catch(() => {}); }}><Icon name="done" size={18} />Выполнить навсегда</button> : null}
      <div className="msep" />
      <button className="mi danger" onClick={() => {
        close();
        ops([{ op: 'task.cancel', id: t.id }]).then(() => toast('Задача удалена', () => ops([{ op: 'task.reopen', id: t.id }]).catch(() => {}))).catch(() => {});
      }}><Icon name="trash" size={18} />Удалить</button>
    </div>
  );
}
