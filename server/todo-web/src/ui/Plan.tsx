// «План дня» (23.11.2026) — утренний ритуал, как у Sunsama и Akiflow: система не работает, пока список копится, а день
// не выбран. Один экран: что горит (просрочено, ждут от меня, срок сегодня, пора пнуть) и по одной задаче из каждого
// направления дерева жизни — то, что двигает жизнь и чего никто не ждёт. Каждой — одно решение: в «Сейчас», завтра,
// без срока или «не буду». Справа — «Сейчас» дня (до пяти, как решил владелец 06.10). «Готово» — день спланирован,
// кнопка в боковой панели гаснет до завтра.
import { addDays, nowHm, plural } from '../dates.ts';
import { dirColor, dirShort, dirTasks, krugOn, mainDir } from '../krug.ts';
import { go } from '../lib.ts';
import { ops, S, saveTodo, setTask, toast, todoSettings, useStore } from '../store.ts';
import type { Task } from '../types.ts';
import { completedRecent, dayList, listed, mine, nowList, overdue, smart } from '../views.ts';
import { setDue } from './acts.ts';
import { Icon } from './icons.tsx';
import { DirDot } from './Krug.tsx';
import { openTask } from './state.ts';
import { Checkbox, Meta } from './TaskRow.tsx';
import { Page } from './Views.tsx';

const NOW_MAX = 5;

export const planDone = () => todoSettings().planned === S.today;
/** Вечер — с 17:00: «План дня» становится «Итогом дня» (как shutdown у Sunsama): что сделано и несделанное — на завтра. */
export const isEvening = () => nowHm() >= '17:00';
/** Сделано сегодня (по Москве) — своё. */
export const doneToday = () => completedRecent(1).filter((t) => mine(t) && t.completed_at
  && new Date(t.completed_at).toLocaleString('sv', { timeZone: 'Europe/Moscow' }).slice(0, 10) === S.today);

export function PlanRow({ t, why }: { t: Task; why?: React.ReactNode }) {
  const isNow = t.focus_on === S.today;
  const tomorrow = addDays(S.today, 1);
  return (
    <div className={'plan-row' + (isNow ? ' picked' : '')}>
      <Checkbox t={t} />
      <div className="tbody" onClick={() => openTask(t.id)}>
        <div className="ttl">{t.title}</div>
        <Meta t={t} showProject />
        {why ? <div className="plan-why">{why}</div> : null}
      </div>
      <div className="plan-acts">
        <button className={'pa now' + (isNow ? ' on' : '')} title={isNow ? 'Убрать из «Сейчас»' : 'Взять в «Сейчас»'}
          onClick={() => setTask(t, { focus_on: isNow ? null : S.today }).catch(() => {})}>
          <Icon name="bolt" size={16} />{isNow ? 'В «Сейчас»' : 'Сейчас'}
        </button>
        {t.due_date !== tomorrow ? <button className="pa" title="Перенести на завтра" onClick={() => setDue(t, { due_date: tomorrow })}>Завтра</button> : null}
        {t.due_date ? <button className="pa" title="Снять срок — пусть лежит без даты" onClick={() => setDue(t, { due_date: null, due_time: null })}>Без срока</button> : null}
        <button className="pa ghost" title="Не буду — отменить задачу"
          onClick={() => ops([{ op: 'task.cancel', id: t.id }]).then(() => toast('Отменил: ' + t.title, () => ops([{ op: 'task.reopen', id: t.id }]).catch(() => {}))).catch(() => {})}>
          <Icon name="x" size={14} />
        </button>
      </div>
    </div>
  );
}

function Block({ title, icon, tasks, tone, hint, why }: { title: string; icon: string; tasks: Task[]; tone?: string; hint?: string; why?: (t: Task) => React.ReactNode }) {
  if (!tasks.length) return null;
  return (
    <div className="plan-block" style={tone ? { '--dc': tone } as React.CSSProperties : undefined}>
      <div className="plan-bh"><Icon name={icon} size={16} />{title}<span className="n">{tasks.length}</span></div>
      {hint ? <div className="plan-hint">{hint}</div> : null}
      {tasks.map((t) => <PlanRow key={t.id} t={t} why={why?.(t)} />)}
    </div>
  );
}

export function PlanView() {
  useStore();
  const seen = new Set<string>();
  const take = (ts: Task[]) => ts.filter((t) => (seen.has(t.id) ? false : (seen.add(t.id), true)));
  const now = nowList();
  now.forEach((t) => seen.add(t.id));
  const over = take(overdue());
  const owed = take(listed().filter((t) => mine(t) && t.owed && t.ball !== 'waiting').sort(smart));
  const today = take(dayList(S.today));
  const nudge = take(listed().filter((t) => mine(t) && t.ball === 'waiting' && t.nudge_on && t.nudge_on <= S.today).sort(smart));
  // Двигает жизнь: из каждого направления — первая задача без срока или со сроком позже, своя, не на чужой стороне.
  const life = krugOn() ? take(S.dirs.map((d) => [...dirTasks(d.key)].filter((t) => t.ball !== 'waiting' && !seen.has(t.id) && (!t.due_date || t.due_date > S.today)).sort(smart)[0])
    .filter((t): t is Task => !!t)) : [];
  const total = over.length + owed.length + today.length + nudge.length;
  const done = () => saveTodo({ planned: S.today }).then(() => { toast('День спланирован'); go('now'); }).catch(() => {});
  const evening = isEvening();
  const did = doneToday();
  const tomorrow = addDays(S.today, 1);
  const leftNow = now.filter((t) => t.status === 'open');
  const toTomorrow = () => {
    const back = leftNow.map((t) => ({ op: 'task.set', id: t.id, set: { focus_on: t.focus_on ?? null } }));
    ops(leftNow.map((t) => ({ op: 'task.set', id: t.id, set: { focus_on: tomorrow } })))
      .then(() => toast('На завтра в «Сейчас»: ' + plural(leftNow.length, 'задача', 'задачи', 'задач'), () => ops(back).catch(() => {}))).catch(() => {});
  };
  return (
    <Page title={evening ? 'Итог дня' : 'План дня'} icon={<Icon name={evening ? 'moon' : 'sun'} size={26} style={{ color: 'var(--accent)' }} />} crumbs={evening ? 'Итог дня' : 'План дня'} wide
      sub={<span>{evening
        ? `Сегодня сделано: ${plural(did.length, 'задача', 'задачи', 'задач')}. Несделанное из «Сейчас» — на завтра, горящее — решить.`
        : total ? `Горит ${plural(total, 'задача', 'задачи', 'задач')}. Каждой — одно решение: «Сейчас», завтра, без срока или не буду.` : 'Ничего не горит — выбери, что двигает жизнь.'}</span>}>
      <div className="plan">
        <div className="plan-main">
          <Block title="Просрочено" icon="calendar" tasks={over} tone="var(--d-over)" hint="Честно: сделать сегодня, перенести или отпустить." />
          <Block title="Ждут от меня" icon="arrowUp" tasks={owed} tone="var(--p2)" hint="Люди ждут — это отбить первым." />
          <Block title="Срок сегодня" icon="calendar" tasks={today} tone="var(--d-today)" />
          <Block title="Пора пнуть" icon="bell" tasks={nudge} tone="var(--d-week)" hint="Мяч у них, но пора напомнить." />
          <Block title="Двигает жизнь" icon="tree" tasks={life} tone="var(--accent)" hint="По одной из направлений дерева: никто не ждёт, но это и есть жизнь."
            why={(t) => { const k = mainDir(t); return k ? <span style={{ color: dirColor(k) }}><DirDot k={k} /> {dirShort(k)}</span> : null; }} />
          {!total && !life.length ? <div className="empty"><Icon name="done" size={48} sw={1} /><div className="big">Пусто — и это прекрасно</div></div> : null}
          {/* на телефоне «Сейчас» сверху, а решения — ниже: «Готово» и здесь, под рукой */}
          {!evening || !planDone() ? <button className="btn pri plan-go m-only" onClick={done}><Icon name="check" size={16} />Готово — день спланирован</button> : null}
        </div>
        <aside className="plan-side">
          <div className={'plan-now' + (now.length > NOW_MAX ? ' over' : '')}>
            <div className="plan-bh"><Icon name="bolt" size={16} />Сейчас<span className="n">{now.length} из {NOW_MAX}</span></div>
            {now.map((t) => (
              <div key={t.id} className="pn-row">
                <Checkbox t={t} />
                <span className="ttl" onClick={() => openTask(t.id)}>{t.title}</span>
                <button className="icon-btn sm" title="Убрать из «Сейчас»" onClick={() => setTask(t, { focus_on: null }).catch(() => {})}><Icon name="x" size={14} /></button>
              </div>
            ))}
            {!now.length ? <div className="plan-hint">Возьми слева до пяти задач — это и есть твой день.</div> : null}
            {now.length > NOW_MAX ? <div className="plan-hint warn">Больше пяти — день не вывезет. Оставь главное.</div> : null}
            {evening && leftNow.length ? (
              <button className="btn sec plan-go" onClick={toTomorrow}><Icon name="moon" size={16} />Несделанное — на завтра</button>
            ) : null}
            {!evening || !planDone() ? <button className="btn pri plan-go" onClick={done}><Icon name="check" size={16} />Готово — день спланирован</button> : null}
          </div>
          {did.length ? (
            <div className="plan-did">
              <div className="plan-bh"><Icon name="done" size={16} />Сделано сегодня<span className="n">{did.length}</span></div>
              {did.slice(0, 12).map((t) => <div key={t.id} className="pd-row" onClick={() => openTask(t.id)}><Icon name="check" size={14} />{t.title}</div>)}
            </div>
          ) : null}
        </aside>
      </div>
    </Page>
  );
}
