// Боковая панель Todoist: я и колокольчик, «Добавить задачу», Поиск, виды, Избранное, наше «Дерево жизни»,
// «Мои проекты» (клиенты, под ними сделки — подпроекты), внизу — CRM и старый веб.
import { useEffect, useState } from 'react';
import { dealShort, go, initials, projectColor, useRoute } from '../lib.ts';
import { S, saveTodo, setTask, todoSettings, useStore } from '../store.ts';
import { counts, dirName, goalChildren, liveDeals, projectsSorted } from '../views.ts';
import { Bell, bellCount } from './Bell.tsx';
import { Icon, TodayIcon } from './icons.tsx';
import { openPop } from './pop.tsx';
import { openDialog, openDictate, openQuick, toggleSide, UI } from './state.ts';
import { draggingId } from './TaskList.tsx';

function Item({ href, icon, label, count, on, sub, onDropTask, tw }: {
  href: string; icon: React.ReactNode; label: string; count?: number; on?: boolean; sub?: number;
  onDropTask?: (id: string) => void; tw?: React.ReactNode;
}) {
  const [over, setOver] = useState(false);
  return (
    <a href={href} className={'nav-item' + (on ? ' on' : '') + (sub === 1 ? ' sub' : sub === 2 ? ' sub2' : '') + (over ? ' drop' : '')}
      onClick={() => { if (UI.sideMobile) { UI.sideMobile = false; } }}
      onDragOver={onDropTask ? (e) => { if (draggingId()) { e.preventDefault(); setOver(true); } } : undefined}
      onDragLeave={() => setOver(false)}
      onDrop={onDropTask ? (e) => { e.preventDefault(); setOver(false); const id = draggingId(); if (id) onDropTask(id); } : undefined}>
      {tw}
      <span className="ic">{icon}</span>
      <span className="lbl">{label}</span>
      {count ? <span className="cnt">{count}</span> : null}
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
    if (t) setTask(t, { project_id: pid, deal_id: deal, section_id: null }, 'Переместил').catch(() => {});
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
        <Item href="#/now" icon={<Icon name="bolt" size={20} />} label="Сейчас" count={c.now} on={at('now') || r.view === ''} onDropTask={(id) => { const t = S.tasks.get(id); if (t) setTask(t, { focus_on: S.today }, 'В «Сейчас»').catch(() => {}); }} />
        <Item href="#/inbox" icon={<Icon name="inbox" size={20} />} label="Входящие" count={c.inbox} on={at('inbox')} onDropTask={move(null)} />
        <Item href="#/today" icon={<TodayIcon day={+(S.today || '00').slice(8, 10)} size={20} />} label="Сегодня" count={c.today} on={at('today')} onDropTask={(id) => { const t = S.tasks.get(id); if (t) setTask(t, { due_date: S.today }, 'Срок — сегодня').catch(() => {}); }} />
        <Item href="#/upcoming" icon={<Icon name="upcoming" size={20} />} label="Предстоящее" on={at('upcoming')} />
        <Item href="#/filters" icon={<Icon name="filters" size={20} />} label="Фильтры и метки" on={at('filters') || at('filter') || at('label')} />
        <Item href="#/done" icon={<Icon name="done" size={20} />} label="Выполнено" on={at('done')} />

        {favItems.length ? (
          <div className="side-group">
            <div className="side-gh" onClick={() => toggle('fav')}>Избранное<Icon name={coll.fav ? 'chevR' : 'chevD'} size={16} /></div>
            {coll.fav ? null : favItems}
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
        {dirs.length ? (
          <div className="side-group">
            <div className="side-gh">
              <a href="#/tree" style={{ flex: 1 }}>Дерево жизни</a>
              <span className="acts"><button className="icon-btn sm" onClick={() => toggle('tree')}><Icon name={coll.tree ? 'chevR' : 'chevD'} size={16} /></button></span>
            </div>
            {coll.tree ? null : dirs.map((d) => {
              const n = goalChildren(null, d.key).length;
              return n ? <Item key={d.key} href={'#/dir/' + d.key} icon={<Icon name="target" size={18} style={{ color: 'var(--accent)' }} />} label={dirName(d.key)} count={n} on={at('dir', d.key)} /> : null;
            })}
          </div>
        ) : null}

      </div>
      <div className="side-foot">
        <a href="/#/crm" target="_blank" rel="noreferrer">Воронка ↗</a>
        <a href="/#/clients" target="_blank" rel="noreferrer">Клиенты ↗</a>
        <a href="/#/people" target="_blank" rel="noreferrer">Люди ↗</a>
        <a href="/" title="Прежний веб Дел">Старые Дела</a>
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
  const cur = document.documentElement.dataset.theme || 'system';
  const acc = document.documentElement.dataset.accent || 'todoist';
  const theme = (t: string) => {
    if (t === 'system') delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = t;
    try { localStorage.setItem('todo.theme', t); } catch { /* */ }
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
      <button className="mi" onClick={() => { close(); go('done'); }}><Icon name="done" size={18} />Выполнено</button>
      <div className="msep" />
      <div className="mh">Тема</div>
      {[['system', 'Как в системе', 'sliders'], ['light', 'Светлая', 'sun'], ['dark', 'Тёмная', 'moon']].map(([k, w, ic]) => (
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
