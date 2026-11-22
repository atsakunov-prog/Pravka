// «Ждут от меня» и «Жду от других» (23.11.2026) — то, что в прежних Делах работало и чего у Todoist нет: группы
// «Отбить / Мониторить» (10.10.2026, слово владельца — «базовая группировка»), по людям, сколько дней жду и пора ли
// пнуть. Третья вкладка — «Обсудить» (повестка: что сказать человеку при встрече).
import { diffDays, plural } from '../dates.ts';
import { personName } from '../lib.ts';
import { S, setTask, useStore } from '../store.ts';
import type { Id, Task } from '../types.ts';
import { listed, mine, smart } from '../views.ts';
import { Icon } from './icons.tsx';
import { TaskList } from './TaskList.tsx';
import { Empty, Page } from './Views.tsx';

const owedList = () => listed().filter((t) => mine(t) && t.owed && t.ball !== 'waiting');
const waitList = () => listed().filter((t) => mine(t) && t.ball === 'waiting');
const agendaList = () => listed().filter((t) => mine(t) && t.ball === 'agenda');
const nudgeDue = (t: Task) => !!t.nudge_on && t.nudge_on <= S.today;
const waitDays = (t: Task) => (t.waiting_since ? diffDays(S.today, t.waiting_since) : 0);

export function ballCounts(): { owed: number; waiting: number; nudge: number; agenda: number } {
  const w = waitList();
  return { owed: owedList().length, waiting: w.length, nudge: w.filter(nudgeDue).length, agenda: agendaList().length };
}

/** По людям: у каждого — его задачи; люди — по самому долгому ожиданию / самому раннему сроку. */
function byPerson(ts: Task[], key: (t: Task) => Id | null): { id: Id | null; name: string; tasks: Task[]; days: number }[] {
  const m = new Map<string, { id: Id | null; name: string; tasks: Task[]; days: number }>();
  for (const t of ts) {
    const id = key(t);
    const k = id || '~';
    if (!m.has(k)) m.set(k, { id, name: id ? personName(id) : 'Без человека', tasks: [], days: 0 });
    const g = m.get(k)!;
    g.tasks.push(t);
    g.days = Math.max(g.days, waitDays(t));
  }
  return [...m.values()].map((g) => ({ ...g, tasks: g.tasks.sort(smart) }))
    .sort((a, b) => (a.id ? 0 : 1) - (b.id ? 0 : 1) || b.days - a.days || b.tasks.length - a.tasks.length || a.name.localeCompare(b.name, 'ru'));
}

function Tabs({ kind }: { kind: string }) {
  const c = ballCounts();
  const tab = (k: string, w: string, n: number) => <a href={'#/ball/' + k} className={'seg' + (kind === k ? ' on' : '')}>{w}{n ? <b>{n}</b> : null}</a>;
  return <div className="segs">{tab('owed', 'Ждут от меня', c.owed)}{tab('waiting', 'Жду от других', c.waiting)}{tab('agenda', 'Обсудить', c.agenda)}</div>;
}

export function BallView({ kind }: { kind: string }) {
  useStore();
  if (kind === 'waiting') {
    const all = waitList();
    const nudge = all.filter(nudgeDue).sort(smart);
    const groups = byPerson(all.filter((t) => !nudgeDue(t)), (t) => t.person_id);
    return (
      <Page title="Жду от других" icon={<Icon name="users" size={26} style={{ color: 'var(--d-week)' }} />} crumbs="Жду"
        sub={<span>{all.length ? plural(all.length, 'задача', 'задачи', 'задач') + ' на чужой стороне. Пора пнуть — сверху.' : 'Ни от кого ничего не жду'}</span>}>
        <Tabs kind={kind} />
        {nudge.length ? (
          <div className="sect hot-sect">
            <div className="sect-h"><Icon name="bell" size={16} style={{ color: 'var(--d-over)' }} />Пора пнуть<span className="n">{nudge.length}</span></div>
            <TaskList tasks={nudge} rowOpts={{ showProject: true }} nest={false} drop={{ nest: false, onDrop: () => {} }} />
          </div>
        ) : null}
        {groups.map((g) => (
          <div key={g.id || '~'} className="sect">
            <div className="sect-h">
              {g.id ? <a href={'#/person/' + g.id}>{g.name}</a> : g.name}
              <span className="n">{g.tasks.length}{g.days >= 2 ? ` · жду до ${g.days} дн.` : ''}</span>
            </div>
            <TaskList tasks={g.tasks} rowOpts={{ showProject: true }} nest={false} drop={{ nest: false, onDrop: () => {} }} />
          </div>
        ))}
        {!all.length ? <Empty icon="users" big="Никого не жду" text="Задача, где мяч у другого: в окне задачи «кто делает» → «жду от…», или «жду» в строке." /> : null}
      </Page>
    );
  }
  if (kind === 'agenda') {
    const groups = byPerson(agendaList(), (t) => t.person_id);
    return (
      <Page title="Обсудить" icon={<Icon name="comment" size={26} style={{ color: 'var(--d-weekend)' }} />} crumbs="Обсудить"
        sub={<span>Что сказать или спросить при встрече — по людям.</span>}>
        <Tabs kind={kind} />
        {groups.map((g) => (
          <div key={g.id || '~'} className="sect">
            <div className="sect-h">{g.id ? <a href={'#/person/' + g.id}>{g.name}</a> : g.name}<span className="n">{g.tasks.length}</span></div>
            <TaskList tasks={g.tasks} rowOpts={{ showProject: true }} nest={false} />
          </div>
        ))}
        {!groups.length ? <Empty icon="comment" big="Повестка пуста" /> : null}
      </Page>
    );
  }
  const all = owedList();
  const groups = byPerson(all, (t) => t.requested_by || t.person_id);
  return (
    <Page title="Ждут от меня" icon={<Icon name="arrowUp" size={26} style={{ color: 'var(--d-over)' }} />} crumbs="Ждут от меня"
      sub={<span>{all.length ? 'Люди ждут, что ты сделаешь. Отбить — первым делом.' : 'Никто ничего от тебя не ждёт'}</span>}>
      <Tabs kind="owed" />
      {groups.map((g) => (
        <div key={g.id || '~'} className="sect">
          <div className="sect-h">{g.id ? <a href={'#/person/' + g.id}>{g.name}</a> : g.name}<span className="n">{g.tasks.length}</span>
            <span className="right"><button className="act" onClick={() => g.tasks.forEach((t) => { if (t.focus_on !== S.today) setTask(t, { focus_on: S.today }).catch(() => {}); })}>Всё в «Сейчас»</button></span>
          </div>
          <TaskList tasks={g.tasks} rowOpts={{ showProject: true }} nest={false} drop={{ nest: false, onDrop: () => {} }} />
        </div>
      ))}
      {!all.length ? <Empty icon="done" big="Всё отбито" text="Новые просьбы людей сюда кладут встречи и Telegram сами." /> : null}
    </Page>
  );
}
