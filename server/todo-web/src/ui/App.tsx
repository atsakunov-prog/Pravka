// Каркас: боковая панель и вид, окна поверх, тосты, горячие клавиши Todoist, на телефоне — нижняя панель и «+».
import { useEffect, useState } from 'react';
import { ApiError, api } from '../api.ts';
import { go, projectColor, useMobile, useRoute } from '../lib.ts';
import { completeTask, dropToast, emit, S, setTask, syncSoon, toasts, useStore } from '../store.ts';
import { Scheduler } from './pickers.tsx';
import { TaskMenu } from './TaskMenu.tsx';
import { search } from '../views.ts';
import { BulkBar, ProjectDialog } from './Bulk.tsx';
import { BugLine } from './BugLine.tsx';
import { ClaudeBar } from './ClaudeBar.tsx';
import { Dictate } from './Dictate.tsx';
import { Editor } from './Editor.tsx';
import { Icon, TodayIcon } from './icons.tsx';
import { PopHost, closePop, openPop, popOpen } from './pop.tsx';
import { SideContent } from './Sidebar.tsx';
import { closeDialog, closeQuick, closeTask, openDictate, openQuick, openTask, toggleSide, UI } from './state.ts';
import { TaskModal } from './TaskModal.tsx';
import { Checkbox, Meta } from './TaskRow.tsx';
import {
  DealView, DoneView, FilterDialog, FiltersPage, FilterView, GoalView, InboxView, LabelView, NowView, Page, PersonView, ProjectView, SearchView,
  TodayView, TreeView, UpcomingView,
} from './Views.tsx';

function Toasts() {
  useStore();
  return (
    <div className="toasts">
      {toasts.map((t) => (
        <div key={t.id} className={'toast' + (t.error ? ' err' : '')}>
          <span>{t.text}</span>
          {t.undo ? <button onClick={() => { t.undo!(); dropToast(t.id); }}>Отменить</button> : null}
          <button className="x" onClick={() => dropToast(t.id)}><Icon name="x" size={16} /></button>
        </div>
      ))}
    </div>
  );
}

function QuickModal() {
  useStore();
  const mobile = useMobile();
  if (!UI.quick) return null;
  return (
    <div className={'scrim' + (mobile ? ' sheet' : '')} onMouseDown={(e) => { if (e.target === e.currentTarget) closeQuick(); }}>
      <div className="qa"><Editor defaults={UI.quick} onClose={closeQuick} /></div>
    </div>
  );
}

function SearchModal({ close }: { close: () => void }) {
  useStore();
  const [q, setQ] = useState('');
  const res = search(q);
  const recent = [...S.tasks.values()].filter((t) => t.status === 'open' && !t.goal).sort((a, b) => b.updated_at.localeCompare(a.updated_at)).slice(0, 6);
  const tasks = q ? res.tasks : recent;
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
      <div className="search-box">
        <input autoFocus placeholder="Поиск задач, проектов, людей" value={q} onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Escape') close(); if (e.key === 'Enter' && tasks[0]) { close(); openTask(tasks[0].id); } }} />
        <div className="res">
          {!q ? <div className="mh">Недавние</div> : null}
          {res.projects.map((p) => (
            <button key={p.id} className="mi" onClick={() => { close(); go('project/' + p.id); }}><span className="hash" style={{ color: projectColor(p) }}>#</span>{p.name}</button>
          ))}
          {res.people.map((p) => (
            <button key={p.id} className="mi" onClick={() => { close(); go('person/' + p.id); }}><Icon name="user" size={18} />{p.name}</button>
          ))}
          {q && (res.projects.length || res.people.length) && tasks.length ? <div className="msep" /> : null}
          {tasks.map((t) => (
            <div key={t.id} className="task" style={{ borderBottom: 0, padding: '6px 8px', borderRadius: 6 }} onClick={() => { close(); openTask(t.id); }}>
              <Checkbox t={t} />
              <div className="tbody"><div className="ttl">{t.title}</div><Meta t={t} showProject /></div>
            </div>
          ))}
          {q && !tasks.length && !res.projects.length && !res.people.length ? <div className="mh">Ничего не нашлось</div> : null}
        </div>
      </div>
    </div>
  );
}

function BrowseView() {
  return (
    <Page title="Обзор" crumbs="Обзор">
      <div className="side" style={{ position: 'static', width: '100%', transform: 'none', boxShadow: 'none', background: 'transparent', margin: '0 -12px' }}>
        <SideContent />
      </div>
    </Page>
  );
}

function Login({ msg }: { msg: string }) {
  return (
    <div className="empty" style={{ paddingTop: '20vh' }}>
      <Icon name="done" size={56} sw={1} />
      <div className="big">Дела</div>
      <div>{msg}</div>
    </div>
  );
}

function Body() {
  const r = useRoute();
  const id = r.id;
  switch (r.view) {
    case '':
    case 'now': return <NowView />;
    case 'inbox': return <InboxView />;
    case 'today': return <TodayView />;
    case 'upcoming': return <UpcomingView />;
    case 'project': return <ProjectView id={id!} key={id} />;
    case 'deal': return <DealView id={id!} key={id} />;
    case 'label': return <LabelView name={id!} key={id} />;
    case 'filter': return <FilterView id={id!} key={id} />;
    case 'filters': return <FiltersPage />;
    case 'person': return <PersonView id={id!} key={id} />;
    case 'tree': return <TreeView />;
    case 'dir': return <TreeView dir={id} key={id} />;
    case 'goal': return <GoalView id={id!} key={id} />;
    case 'done': return <DoneView />;
    case 'browse': return <BrowseView />;
    case 'search': return <SearchView q={id || ''} key="search" />;
    case 'task': return <NowView />;
    default: return <NowView />;
  }
}

/** Горячие клавиши Todoist: Q — новая задача, / или F — поиск, M — панель, G+I/T/U/N/V — виды, Esc — закрыть. */
function useKeys(setSearch: (v: boolean) => void) {
  useEffect(() => {
    let g = false;
    let gTimer = 0;
    const on = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement;
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) return;
      if (e.ctrlKey || e.metaKey || e.altKey) {
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setSearch(true); }
        if ((e.ctrlKey || e.metaKey) && (e.key.toLowerCase() === 'z' || e.key.toLowerCase() === 'я')) {
          const last = [...toasts].reverse().find((t) => t.undo);
          if (last) { e.preventDefault(); last.undo!(); dropToast(last.id); }
        }
        return;
      }
      const k = e.key.toLowerCase();
      if (g) {
        g = false;
        const to: Record<string, string> = { i: 'inbox', ш: 'inbox', t: 'today', е: 'today', u: 'upcoming', г: 'upcoming', n: 'now', т: 'now', v: 'filters', м: 'filters', h: 'now', р: 'now', d: 'done', в: 'done' };
        if (to[k]) { e.preventDefault(); go(to[k]); }
        return;
      }
      if (k === 'escape') {
        if (popOpen()) closePop();
        else if (UI.quick) closeQuick();
        else if (UI.dialog) closeDialog();
        else if (UI.dictate) openDictate(false);
        else if (UI.task) closeTask();
        else if (UI.sel.size) { UI.sel.clear(); syncSoon(); }
        return;
      }
      if (UI.task || UI.quick || UI.dictate || popOpen()) return;
      if ((k === 'q' || k === 'й') && e.shiftKey) { e.preventDefault(); openDictate(); return; }
      // Строка под клавиатурой: ↓/J, ↑/K — по списку; Enter — открыть; E — выполнить; T — срок; 1–4 — приоритет;
      // X — выделить; . — меню задачи (как у Todoist).
      const rows = [...document.querySelectorAll('.main .task[data-id]')] as HTMLElement[];
      const at = rows.findIndex((x) => x.dataset.id === UI.focus);
      const ft = UI.focus ? S.tasks.get(UI.focus) : undefined;
      const moveTo = (i: number) => {
        const row = rows[Math.max(0, Math.min(rows.length - 1, i))];
        if (!row) return;
        UI.focus = row.dataset.id!;
        row.scrollIntoView({ block: 'nearest' });
        emit();
      };
      if (k === 'arrowdown' || k === 'j' || k === 'о') { e.preventDefault(); moveTo(at + 1); return; }
      if (k === 'arrowup' || k === 'k' || k === 'л') { e.preventDefault(); moveTo(at < 0 ? 0 : at - 1); return; }
      if (ft && at >= 0) {
        const row = rows[at];
        if (k === 'enter') { e.preventDefault(); openTask(ft.id); return; }
        if (k === 'e' || k === 'у') { e.preventDefault(); completeTask(ft, e.shiftKey).catch(() => {}); moveTo(at + 1 < rows.length ? at + 1 : at - 1); return; }
        if (['1', '2', '3', '4'].includes(k)) { e.preventDefault(); setTask(ft, { priority: +k }).catch(() => {}); return; }
        if (k === 't' || k === 'е') {
          e.preventDefault();
          openPop(row, (close) => <Scheduler value={ft as any} close={close} onPick={(v) => setTask(ft, v as any, 'Срок поменял')} />, { cls: 'sched' });
          return;
        }
        if (k === 'x' || k === 'ч') { e.preventDefault(); if (UI.sel.has(ft.id)) UI.sel.delete(ft.id); else UI.sel.add(ft.id); emit(); return; }
        if (k === '.' || k === 'ю') { e.preventDefault(); openPop(row, (close) => <TaskMenu t={ft} close={close} />); return; }
      }
      if (k === 'q' || k === 'й' || k === 'a' || k === 'ф') { e.preventDefault(); openQuick(defaultsFor()); }
      else if (k === '/' || k === 'f' || k === 'а') {
        e.preventDefault();
        const box = document.getElementById('side-search') as HTMLInputElement | null;
        if (box && box.offsetParent) box.focus();
        else setSearch(true);
      }
      else if (k === 'm' || k === 'ь') { e.preventDefault(); toggleSide(); }
      else if (k === 'g' || k === 'п') { g = true; clearTimeout(gTimer); gTimer = window.setTimeout(() => (g = false), 1200); }
    };
    addEventListener('keydown', on);
    return () => removeEventListener('keydown', on);
  }, [setSearch]);
}

function defaultsFor() {
  const h = location.hash.replace(/^#\/?/, '').split('/');
  if (h[0] === 'project') return { project_id: h[1] };
  if (h[0] === 'deal') { const d = S.deals.get(h[1]); return d ? { project_id: d.project_id, deal_id: d.id } : {}; }
  if (h[0] === 'today') return { due_date: S.today };
  if (h[0] === 'now' || h[0] === '') return { focus_on: S.today };
  if (h[0] === 'goal') return { why_id: h[1] };
  if (h[0] === 'label') return { labels: [decodeURIComponent(h[1])] };
  return {};
}

export function App() {
  useStore();
  const [err, setErr] = useState('');
  const [searchOpen, setSearch] = useState(false);
  const mobile = useMobile();
  const r = useRoute();
  useKeys(setSearch);
  useEffect(() => {
    (async () => {
      const m = location.hash.match(/invite=([\w-]+)/);
      if (m) {
        history.replaceState(null, '', location.pathname + '#/now');
        try { await api('/auth/redeem', { code: m[1] }); } catch { /* ссылка уже использована — может, вход жив */ }
      }
      try {
        S.me = await api('/api/me');
        await syncSoon(true);
      } catch (e) {
        setErr(e instanceof ApiError && e.status === 401 ? 'Вход — по ссылке-приглашению. Открой её на этом устройстве один раз.' : 'Не открылось: ' + (e instanceof Error ? e.message : String(e)));
      }
    })();
    try {
      const t = localStorage.getItem('todo.theme');
      if (t && t !== 'system') document.documentElement.dataset.theme = t;
      const a = localStorage.getItem('todo.accent');
      if (a && a !== 'todoist') document.documentElement.dataset.accent = a;
    } catch { /* без хранилища — тема системы */ }
    // Фон: синк раз в 30 с и при возвращении на вкладку.
    const timer = setInterval(() => { if (!document.hidden && S.ready) syncSoon().catch(() => {}); }, 30000);
    const vis = () => { if (!document.hidden && S.ready) syncSoon().catch(() => {}); };
    document.addEventListener('visibilitychange', vis);
    return () => { clearInterval(timer); document.removeEventListener('visibilitychange', vis); };
  }, []);
  useEffect(() => {
    if (r.view === 'task' && r.id && S.ready) {
      const t = [...S.tasks.values()].find((x) => String(x.num) === r.id || x.id === r.id);
      if (t) openTask(t.id);
    }
  }, [r.view, r.id, S.ready]);
  if (err) return <Login msg={err} />;
  if (!S.ready) return <Login msg="Загружаю дела…" />;
  const tab = (view: string, icon: React.ReactNode, label: string) => (
    <a href={'#/' + view} className={r.view === view || (view === 'now' && r.view === '') ? 'on' : ''}>{icon}{label}</a>
  );
  return (
    <div className={'app' + (UI.sideOff ? ' side-off' : '') + (UI.sideMobile ? ' side-m' : '')}>
      {UI.sideMobile ? <div className="side-scrim" onClick={toggleSide} /> : null}
      <aside className="side"><SideContent /></aside>
      <main className="main">
        <BugLine />
        {UI.sideOff && !mobile ? <button className="icon-btn" style={{ position: 'fixed', top: 12, left: 12, zIndex: 6 }} onClick={toggleSide} title="Показать панель (M)"><Icon name="sidebar" size={20} /></button> : null}
        <Body />
      </main>
      <ClaudeBar />
      <nav className="bottom-nav">
        {tab('now', <Icon name="bolt" size={22} />, 'Сейчас')}
        {tab('inbox', <Icon name="inbox" size={22} />, 'Входящие')}
        {tab('today', <TodayIcon day={+S.today.slice(8, 10)} size={22} />, 'Сегодня')}
        {tab('upcoming', <Icon name="upcoming" size={22} />, 'Предстоящее')}
        {tab('browse', <Icon name="menu" size={22} />, 'Обзор')}
      </nav>
      <button className="fab" onClick={() => openQuick(defaultsFor())} aria-label="Добавить задачу"><Icon name="plus" size={28} sw={2} /></button>
      <TaskModal />
      <QuickModal />
      {searchOpen ? <SearchModal close={() => setSearch(false)} /> : null}
      {UI.dialog?.kind === 'filter' ? <FilterDialog f={UI.dialog.data} close={closeDialog} /> : null}
      {UI.dialog?.kind === 'project' ? <ProjectDialog close={closeDialog} /> : null}
      {UI.dictate ? <Dictate close={() => openDictate(false)} /> : null}
      <BulkBar />
      <PopHost />
      <Toasts />
    </div>
  );
}
