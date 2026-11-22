// Боковая панель Todoist: я и колокольчик, «Добавить задачу», Поиск, виды, Избранное, наше «Дерево жизни»,
// «Мои проекты» (клиенты, под ними сделки — подпроекты), внизу — CRM и старый веб.
import { applyLook, lookChoice } from '../look.ts';
import { setDue, setPlace } from './acts.ts';
import { useEffect, useState } from 'react';
import { dealShort, go, initials, projectColor, useRoute } from '../lib.ts';
import { S, saveTodo, setTask, todoSettings, useStore } from '../store.ts';
import { counts, liveDeals, projectsSorted } from '../views.ts';
import { dirColor, dirShort, dirTasks, krugOn, unplaced } from '../krug.ts';
import { DirDot, setDir } from './Krug.tsx';
import { ballCounts } from './Ball.tsx';
import { planDone } from './Plan.tsx';
import { Bell, bellCount } from './Bell.tsx';
import { Icon, TodayIcon } from './icons.tsx';
import { openPop } from './pop.tsx';
import { openDialog, openDictate, openQuick, toggleSide, UI } from './state.ts';
import { draggingId } from './TaskList.tsx';

function Item({ href, icon, label, count, on, sub, onDropTask, tw, dc, hot }: {
  href: string; icon: React.ReactNode; label: string; count?: number; on?: boolean; sub?: number;
  onDropTask?: (id: string) => void; tw?: React.ReactNode;
  /** Цвет направления дерева жизни — свечение пункта. */
  dc?: string;
  /** Число горит (ждут от меня, план дня не сделан). */
  hot?: boolean;
}) {
  const [over, setOver] = useState(false);
  return (
    <a href={href} className={'nav-item' + (on ? ' on' : '') + (sub === 1 ? ' sub' : sub === 2 ? ' sub2' : '') + (over ? ' drop' : '') + (dc ? ' dir' : '')}
      style={dc ? { '--dc': dc } as React.CSSProperties : undefined}
      onClick={() => { if (UI.sideMobile) { UI.sideMobile = false; } }}
      onDragOver={onDropTask ? (e) => { if (draggingId()) { e.preventDefault(); setOver(true); } } : undefined}
      onDragLeave={() => setOver(false)}
      onDrop={onDropTask ? (e) => { e.preventDefault(); setOver(false); const id = draggingId(); if (id) onDropTask(id); } : undefined}>
      {tw}
      <span className="ic">{icon}</span>
      <span className="lbl">{label}</span>
      {count ? <span className={'cnt' + (hot ? ' hot' : '')}>{count}</span> : null}
    </a>
  );
}

export function SideContent() {
  useStore();
  const r = useRoute();
  const c = counts();
  const st = todoSettings();
  const coll = st.collapsed || {};
  const fav = st.fav || [];
  const at = (v: string, id?: string) => r.view === v && (id === undefined || r.id === id);
  const toggle = (k: string) => saveTodo({ collapsed: { ...coll, [k]: !coll[k] } }).catch(() => {});
  const move = (pid: string | null, deal: string | null = null) => (id: string) => {
    const t = S.tasks.get(id);
    if (t) setPlace(t, { project_id: pid, deal_id: deal });
  };
  const favItems = fav.map((k) => {
    const [kind, id] = [k.slice(0, 1), k.slice(2)];
    if (kind === 'p') { const p = S.projects.get(id); return p ? <Item key={k} href={'#/project/' + id} icon={<span className="hash" style={{ color: projectColor(p) }}>#</span>} label={p.name} on={at('project', id)} count={c.byProject.get(id)} /> : null; }
    if (kind === 'd') { const d = S.deals.get(id); return d ? <Item key={k} href={'#/deal/' + id} icon={<Icon name="deal" size={18} />} label={d.name} on={at('deal', id)} count={c.byDeal.get(id)} /> : null; }
    if (kind === 'l') return <Item key={k} href={'#/label/' + encodeURIComponent(id)} icon={<Icon name="tag" size={18} />} label={id} on={at('label', id)} />;
    if (kind === 'f') { const f = (st.filters || []).find((x) => x.id === id); return f ? <Item key={k} href={'#/filter/' + id} icon={<Icon name="filters" size={18} />} label={f.name} on={at('filter', id)} /> : null; }
    return null;
  }).filter(Boolean);
  const dirs = S.dirs.length ? S.dirs : [];
  const b = ballCounts();
  return (
    <>
      <div className="side-top">
        <button className="side-me" onClick={(e) => openPop(e.currentTarget, (close) => <MeMenu close={close} />)}>
          <span className="avatar">{initials(S.me?.name || '?')}</span><span className="name">{S.me?.name}</span><Icon name="chevD" size={16} />
        </button>
        <button className="icon-btn" title="Новое от автоматики" onClick={(e) => openPop(e.currentTarget, (close) => <Bell close={close} />, { cls: 'bell' })}>
          <Icon name="bell" size={20} />{bellCount() ? <span className="badge">{bellCount()}</span> : null}
        </button>
        <button className="icon-btn" title="Скрыть панель (M)" onClick={toggleSide}><Icon name="sidebar" size={20} /></button>
      </div>
      <div className="side-scroll">
        <button className="side-add" onClick={() => openQuick()}><span className="plus"><Icon name="plus" size={16} sw={2} /></span>Добавить задачу</button>
        <button className="side-add dict" onClick={() => openDictate()}><span className="plus"><Icon name="mic" size={16} sw={2} /></span>Надиктовать задачи</button>
        <SideSearch />
        {!planDone() ? (
          <a href="#/plan" className={'side-plan' + (at('plan') ? ' on' : '')}><Icon name="sun" size={18} /><span>Спланировать день</span><Icon name="chevR" size={16} /></a>
        ) : null}
        <Item href="#/now" icon={<Icon name="bolt" size={20} />} label="Сейчас" count={c.now} on={at('now') || r.view === ''} onDropTask={(id) => { const t = S.tasks.get(id); if (t) setTask(t, { focus_on: S.today }, 'В «Сейчас»').catch(() => {}); }} />
        <Item href="#/inbox" icon={<Icon name="inbox" size={20} />} label="Входящие" count={c.inbox} on={at('inbox')} onDropTask={move(null)} />
        <Item href="#/today" icon={<TodayIcon day={+(S.today || '00').slice(8, 10)} size={20} />} label="Сегодня" count={c.today} on={at('today')} onDropTask={(id) => { const t = S.tasks.get(id); if (t) setDue(t, { due_date: S.today }); }} />
        <Item href="#/upcoming" icon={<Icon name="upcoming" size={20} />} label="Предстоящее" on={at('upcoming')} />
        <Item href="#/ball/owed" icon={<Icon name="arrowUp" size={20} />} label="Ждут от меня" count={b.owed} hot={b.owed > 0} on={at('ball', 'owed')} />
        <Item href="#/ball/waiting" icon={<Icon name="users" size={20} />} label="Жду от других" count={b.waiting} hot={b.nudge > 0} on={at('ball', 'waiting')} />
        <Item href="#/filters" icon={<Icon name="filters" size={20} />} label="Фильтры и метки" on={at('filters') || at('filter') || at('label')} />
        <Item href="#/done" icon={<Icon name="done" size={20} />} label="Выполнено" on={at('done')} />

        {favItems.length ? (
          <div className="side-group">
            <div className="side-gh" onClick={() => toggle('fav')}>Избранное<Icon name={coll.fav ? 'chevR' : 'chevD'} size={16} /></div>
            {coll.fav ? null : favItems}
          </div>
        ) : null}

        {krugOn() ? (
          <div className="side-group">
            <div className="side-gh">
              <a href="#/krug" style={{ flex: 1 }} className={at('krug') || at('tree') ? 'gh-on' : ''}>Дерево жизни</a>
              <span className="acts"><button className="icon-btn sm" onClick={() => toggle('tree')}><Icon name={coll.tree ? 'chevR' : 'chevD'} size={16} /></button></span>
            </div>
            {coll.tree ? null : (
              <>
                {dirs.map((d) => (
                  <Item key={d.key} href={'#/dir/' + d.key} icon={<DirDot k={d.key} size={9} />} label={dirShort(d.key)} count={dirTasks(d.key).length}
                    on={at('dir', d.key)} onDropTask={(id) => { const t = S.tasks.get(id); if (t) setDir(t, d.key); }} dc={dirColor(d.key)} />
                ))}
                {unplaced().length ? <Item href="#/dir/none" icon={<i className="dir-dot none" />} label="Без места" count={unplaced().length} on={at('dir', 'none')} /> : null}
              </>
            )}
          </div>
        ) : null}

        <div className="side-group">
          <div className="side-gh">
            <span>Мои проекты</span>
            <span className="acts">
              <button className="icon-btn sm" title="Новый проект" onClick={() => openDialog('project')}><Icon name="plus" size={16} /></button>
              <button className="icon-btn sm" onClick={() => toggle('projects')}><Icon name={coll.projects ? 'chevR' : 'chevD'} size={16} /></button>
            </span>
          </div>
          {coll.projects ? null : projectsSorted(st.porder).map((p) => {
            const deals = liveDeals(p.id);
            const open = !coll['p:' + p.id];
            return (
              <div key={p.id}>
                <Item href={'#/project/' + p.id} icon={<span className="hash" style={{ color: projectColor(p) }}>#</span>} label={p.name}
                  count={c.byProject.get(p.id)} on={at('project', p.id)} onDropTask={move(p.id)}
                  tw={deals.length ? (
                    <span className="tw" onClick={(e) => { e.preventDefault(); e.stopPropagation(); toggle('p:' + p.id); }}><Icon name={open ? 'chevD' : 'chevR'} size={14} /></span>
                  ) : <span style={{ width: 16, marginLeft: -4 }} />} />
                {open ? deals.map((d) => (
                  <Item key={d.id} href={'#/deal/' + d.id} sub={1} icon={<Icon name="deal" size={16} style={{ color: projectColor(p) }} />} label={dealShort(d.id)}
                    count={c.byDeal.get(d.id)} on={at('deal', d.id)} onDropTask={move(p.id, d.id)} />
                )) : null}
              </div>
            );
          })}
        </div>
      </div>
      <div className="side-foot">
        <a href="/#/crm" target="_blank" rel="noreferrer">Воронка ↗</a>
        <a href="/#/clients" target="_blank" rel="noreferrer">Клиенты ↗</a>
        <a href="/#/people" target="_blank" rel="noreferrer">Люди ↗</a>
        <a href="#/new">Новое</a>
        <a href="#/week">Неделя</a>
        <a href="/?old=1" onClick={() => { try { sessionStorage.setItem('dela.old', '1'); } catch { /* */ } }} title="Прежний веб Дел">Старые Дела</a>
      </div>
    </>
  );
}

/** Поиск полем слева (слово Саши 19.10: «слева должен быть поиск»): печатаешь — справа список найденного. */
function SideSearch() {
  const r = useRoute();
  const [q, setQ] = useState(r.view === 'search' ? r.id || '' : '');
  useEffect(() => {
    if (r.view !== 'search') setQ('');
  }, [r.view]);
  const goQ = (v: string) => {
    const h = '#/search/' + encodeURIComponent(v);
    if (location.hash.startsWith('#/search')) {
      history.replaceState(null, '', h);
      dispatchEvent(new HashChangeEvent('hashchange'));
    } else location.hash = h;
  };
  return (
    <label className="side-search">
      <Icon name="search" size={18} />
      <input id="side-search" value={q} placeholder="Поиск" autoComplete="off"
        onChange={(e) => { setQ(e.target.value); goQ(e.target.value); }}
        onKeyDown={(e) => { if (e.key === 'Escape') { setQ(''); (e.target as HTMLInputElement).blur(); if (r.view === 'search') history.back(); } }} />
      {q ? <button className="icon-btn sm" onClick={(e) => { e.preventDefault(); setQ(''); if (r.view === 'search') history.back(); }}><Icon name="x" size={14} /></button> : <span className="kbd">/</span>}
    </label>
  );
}

function MeMenu({ close }: { close: () => void }) {
  const cur = lookChoice();
  const acc = document.documentElement.dataset.accent || 'todoist';
  const theme = (t: string) => {
    try { localStorage.setItem('todo.look', t); } catch { /* */ }
    applyLook();
    close();
  };
  const accent = (a: string) => {
    if (a === 'todoist') delete document.documentElement.dataset.accent;
    else document.documentElement.dataset.accent = a;
    try { localStorage.setItem('todo.accent', a); } catch { /* */ }
    close();
  };
  return (
    <div>
      <div className="mh">{S.me?.name}</div>
      <button className="mi" onClick={() => { close(); openDialog('prod'); }}><Icon name="target" size={18} />Продуктивность<span className="r">O P</span></button>
      <button className="mi" onClick={() => { close(); go('done'); }}><Icon name="done" size={18} />Выполнено</button>
      <div className="msep" />
      <div className="mh">Тема</div>
      {[['dark', 'Ночь', 'moon'], ['light', 'Светлая', 'sun'], ['system', 'Как в системе', 'sliders']].map(([k, w, ic]) => (
        <button key={k} className={'mi' + (cur === k ? ' act' : '')} onClick={() => theme(k)}><Icon name={ic} size={18} />{w}</button>
      ))}
      <div className="mh">Цвет</div>
      <div style={{ display: 'flex', gap: 6, padding: '4px 8px 8px', flexWrap: 'wrap' }}>
        {[['todoist', '#dc4c3e'], ['kale', '#4c7a45'], ['blueberry', '#297abc'], ['moonstone', '#39485e'], ['tangerine', '#d68400'], ['lavender', '#766bbd'], ['raspberry', '#c5496c']].map(([k, c]) => (
          <button key={k} title={k} onClick={() => accent(k)} style={{ width: 24, height: 24, borderRadius: 12, background: c, boxShadow: acc === k ? '0 0 0 2px var(--raised), 0 0 0 4px ' + c : undefined }} />
        ))}
      </div>
      <div className="msep" />
      <a className="mi" href="/#/settings"><Icon name="gear" size={18} />Настройки Дел (прежний веб)</a>
    </div>
  );
}
