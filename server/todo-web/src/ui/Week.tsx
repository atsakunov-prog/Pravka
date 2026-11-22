// «Неделя» (23.11.2026) — обзор недели, как еженедельный разбор GTD, но по дереву жизни: что сделано в каждом
// направлении (неоновыми полосами), какие направления молчат, что застряло, кого жду дольше недели. Каждой застрявшей —
// одно решение. «Готово» — неделя разобрана. Лучше всего — в воскресенье вечером (как выводы krug.lessons, вс 21:00).
import { diffDays, mondayOf, plural } from '../dates.ts';
import { dirColor, dirFull, dirsOf, dirShort, dirTasks, krugOn, unplaced } from '../krug.ts';
import { S, saveTodo, setTask, toast, todoSettings, useStore } from '../store.ts';
import type { Task } from '../types.ts';
import { completedRecent, listed, mine, smart } from '../views.ts';
import { Icon } from './icons.tsx';
import { DirDot } from './Krug.tsx';
import { PlanRow } from './Plan.tsx';
import { openQuick } from './state.ts';
import { TaskList } from './TaskList.tsx';
import { Page } from './Views.tsx';

export const reviewDue = () => {
  const r = todoSettings().reviewed;
  return !r || r < mondayOf(S.today);
};

export function WeekView() {
  useStore();
  const done = completedRecent(7).filter((t) => mine(t) && (t.kind ?? 'task') === 'task');
  const byDir = new Map<string, number>();
  let noDir = 0;
  for (const t of done) {
    const k = dirsOf(t)[0];
    if (k) byDir.set(k, (byDir.get(k) || 0) + 1);
    else noDir++;
  }
  const max = Math.max(1, ...byDir.values(), noDir);
  const quiet = krugOn() ? S.dirs.filter((d) => !byDir.get(d.key) && dirTasks(d.key).length) : [];
  const stuck = listed().filter((t) => mine(t) && t.ball !== 'waiting' && !t.due_date && t.focus_on !== S.today
    && diffDays(S.today, t.created_at.slice(0, 10)) > 21 && diffDays(S.today, t.updated_at.slice(0, 10)) > 14).sort(smart).slice(0, 15);
  const longWait = listed().filter((t) => mine(t) && t.ball === 'waiting' && t.waiting_since && diffDays(S.today, t.waiting_since) >= 7).sort(smart);
  const finish = () => saveTodo({ reviewed: S.today }).then(() => toast('Неделя разобрана')).catch(() => {});
  return (
    <Page title="Неделя" icon={<Icon name="upcoming" size={26} style={{ color: 'var(--accent)' }} />} crumbs="Обзор недели"
      sub={<span>За 7 дней сделано {plural(done.length, 'задача', 'задачи', 'задач')}. Раз в неделю — посмотреть, куда ушла жизнь, и решить, что застряло.</span>}
      actions={<button className="tb-btn" onClick={finish}><Icon name="check" size={20} /><span className="d-only">Неделя разобрана</span></button>}>
      {krugOn() ? (
        <div className="wk-bars">
          {S.dirs.map((d) => {
            const n = byDir.get(d.key) || 0;
            const open = dirTasks(d.key).length;
            return (
              <a key={d.key} className="wk-row" href={'#/dir/' + d.key} style={{ '--dc': dirColor(d.key) } as React.CSSProperties}>
                <span className="wk-name"><DirDot k={d.key} />{dirShort(d.key)}</span>
                <span className="wk-track"><i style={{ width: (n / max) * 100 + '%' }} /></span>
                <span className="wk-n">{n || '—'}</span>
                <span className="wk-open">{open ? 'открыто ' + open : ''}</span>
              </a>
            );
          })}
          {noDir ? (
            <a className="wk-row muted" href="#/dir/none">
              <span className="wk-name"><i className="dir-dot none" />без места</span>
              <span className="wk-track"><i style={{ width: (noDir / max) * 100 + '%' }} /></span>
              <span className="wk-n">{noDir}</span>
              <span className="wk-open">{unplaced().length ? 'открыто ' + unplaced().length : ''}</span>
            </a>
          ) : null}
        </div>
      ) : null}

      {quiet.length ? (
        <div className="sect">
          <div className="sect-h">Молчат на этой неделе<span className="n">{quiet.length}</span></div>
          <div className="view-sub" style={{ margin: '6px 0 4px' }}>Ни одной сделанной задачи. Одно маленькое дело в каждом — и колесо крутится.</div>
          <div className="wk-quiet">
            {quiet.map((d) => {
              const top = [...dirTasks(d.key)].sort(smart)[0];
              return (
                <div key={d.key} className="wk-q" style={{ '--dc': dirColor(d.key) } as React.CSSProperties}>
                  <div className="wk-qh"><DirDot k={d.key} size={9} />{dirFull(d.key)}</div>
                  {top ? (
                    <button className="wk-qt" onClick={() => setTask(top, { focus_on: S.today }, 'В «Сейчас»: ' + top.title).catch(() => {})} title="Взять в «Сейчас»">
                      <Icon name="bolt" size={14} />{top.title}
                    </button>
                  ) : null}
                  <button className="wk-add" onClick={() => openQuick({ directions: [d.key] })}>
                    <Icon name="plus" size={14} />Своё дело
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      ) : null}

      {stuck.length ? (
        <div className="sect">
          <div className="sect-h">Застряло<span className="n">{stuck.length}</span></div>
          <div className="view-sub" style={{ margin: '6px 0 4px' }}>Больше трёх недель без срока и без движения. Взять, назначить или отпустить — лишнее в списке крадёт внимание у нужного.</div>
          {stuck.map((t: Task) => <PlanRow key={t.id} t={t} />)}
        </div>
      ) : null}

      {longWait.length ? (
        <div className="sect">
          <div className="sect-h">Жду дольше недели<span className="n">{longWait.length}</span><span className="right"><a className="act" href="#/ball/waiting">Все ожидания</a></span></div>
          <TaskList tasks={longWait} rowOpts={{ showProject: true }} nest={false} />
        </div>
      ) : null}

      <button className="btn pri" style={{ margin: '24px 0' }} onClick={finish}><Icon name="check" size={16} />Неделя разобрана</button>
    </Page>
  );
}
