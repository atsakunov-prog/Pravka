// Каркас: боковая панель и вид, окна поверх, тосты, горячие клавиши Todoist, на телефоне — нижняя панель и «+».
import { useEffect, useState } from 'react';
import { ApiError, api, inApp } from '../api.ts';
import { go, projectColor, useMobile, useRoute } from '../lib.ts';
import { completeTask, dropToast, emit, S, setTask, syncSoon, toasts, useStore } from '../store.ts';
import { LabelPicker, PriorityMenu, ProjectPicker, Scheduler } from './pickers.tsx';
import { TaskMenu } from './TaskMenu.tsx';
import { matchCommands, type Cmd } from '../commands.ts';
import { setDeadline, setDue, setPlace, setPrio } from './acts.ts';
import { QuickSettingsDialog } from './QuickSettings.tsx';
import type { Task } from '../types.ts';
import { GO, globalAct, keyName, taskAct } from '../keys.ts';
import { search } from '../views.ts';
import { BulkBar, ProjectDialog } from './Bulk.tsx';
import { BugLine } from './BugLine.tsx';
import { ClaudeBar } from './ClaudeBar.tsx';
import { Dictate } from './Dictate.tsx';
import { Editor } from './Editor.tsx';
import { Icon, TodayIcon } from './icons.tsx';
import { PopHost, closePop, openPop, popOpen } from './pop.tsx';
import { SideContent } from './Sidebar.tsx';
import { closeDialog, closeQuick, closeTask, openDialog, openDictate, openQuick, openTask, recentTasks, toggleSide, UI } from './state.ts';
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
          <span>{t.text}{t.sub?.map((x) => <span key={x} className="sub">{x}</span>)}</span>
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

/** Ctrl+K — как у Todoist: поиск и команды одним списком, группами, у команды — её клавиша. ↑↓ — выбор, Enter — выполнить. */
function SearchModal({ close }: { close: () => void }) {
  useStore();
  const [q, setQ] = useState('');
  const [hi, setHi] = useState(0);
  const res = search(q);
  const open = [...S.tasks.values()].filter((t) => t.status === 'open' && !t.goal);
  const seen = recentTasks.map((id) => S.tasks.get(id)).filter((t): t is Task => !!t && t.status === 'open');
  const recent = [...seen, ...open.filter((t) => !seen.includes(t)).sort((a, b) => b.updated_at.localeCompare(a.updated_at))].slice(0, 6);
  const tasks = q ? res.tasks : recent;
  const cmds = matchCommands(q);
  const run = (c: Cmd) => {
    close();
    const [kind, what] = c.id.split(':');
    if (kind === 'go') go(what);
    else if (c.id === 'add:task') openQuick({});
    else if (c.id === 'add:dictate') openDictate();
    else if (c.id === 'add:project') openDialog('project');
    else if (c.id === 'add:filter') openDialog('filter');
    else if (c.id === 'view:side') toggleSide();
    else if (c.id === 'set:quick') openDialog('quick');
  };
  // Плоский список для ↑↓ и Enter: проекты, люди, дела, потом команды.
  const items: { key: string; run: () => void }[] = [
    ...res.projects.map((p) => ({ key: 'p' + p.id, run: () => { close(); go('project/' + p.id); } })),
    ...res.people.map((p) => ({ key: 'h' + p.id, run: () => { close(); go('person/' + p.id); } })),
    ...tasks.map((t) => ({ key: 't' + t.id, run: () => { close(); openTask(t.id); } })),
    ...cmds.map((c) => ({ key: 'c' + c.id, run: () => run(c) })),
  ];
  const at = Math.min(hi, items.length - 1);
  const cls = (key: string) => (items[at]?.key === key ? ' act' : '');
  const groups = [...new Set(cmds.map((c) => c.group))];
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
      <div className="search-box">
        <input autoFocus placeholder="Поиск и команды: задачи, проекты, люди, «сегодня», «добавить проект»" value={q} onChange={(e) => { setQ(e.target.value); setHi(0); }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') close();
            if (e.key === 'ArrowDown') { e.preventDefault(); setHi((at + 1) % Math.max(items.length, 1)); }
            if (e.key === 'ArrowUp') { e.preventDefault(); setHi((at - 1 + items.length) % Math.max(items.length, 1)); }
            if (e.key === 'Enter' && items[at]) { e.preventDefault(); items[at].run(); }
          }} />
        <div className="res">
          {res.projects.map((p) => (
            <button key={p.id} className={'mi' + cls('p' + p.id)} onClick={() => { close(); go('project/' + p.id); }}><span className="hash" style={{ color: projectColor(p) }}>#</span>{p.name}</button>
          ))}
          {res.people.map((p) => (
            <button key={p.id} className={'mi' + cls('h' + p.id)} onClick={() => { close(); go('person/' + p.id); }}><Icon name="user" size={18} />{p.name}</button>
          ))}
          {tasks.length ? <div className="mh">{q ? 'Задачи' : 'Недавнее'}</div> : null}
          {tasks.map((t) => (
            <div key={t.id} className={'task' + cls('t' + t.id)} style={{ borderBottom: 0, padding: '6px 8px', borderRadius: 6 }} onClick={() => { close(); openTask(t.id); }}>
              <Checkbox t={t} />
              <div className="tbody"><div className="ttl">{t.title}</div><Meta t={t} showProject /></div>
            </div>
          ))}
          {groups.map((g) => (
            <div key={g}>
              <div className="mh">{g}</div>
              {cmds.filter((c) => c.group === g).map((c) => (
                <button key={c.id} className={'mi' + cls('c' + c.id)} onClick={() => run(c)}>
                  <Icon name={c.id.startsWith('add') ? 'plus' : c.id.startsWith('go') ? 'chevR' : 'sidebar'} size={18} />{c.label}
                  {c.keys ? <span className="r">{c.keys}</span> : null}
                </button>
              ))}
            </div>
          ))}
          {q && !items.length ? <div className="mh">Ничего не нашлось</div> : null}
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

/** Горячие клавиши Todoist — и в русской раскладке (keys.ts): Q — новая задача, / или F — поиск, M — панель, Z — отмена,
 *  G и вторая клавиша — виды, Esc — закрыть; на задаче в фокусе — Enter, E, T, D, Y, L, V, X, 1–4, «.», Shift+G. */
function useKeys(setSearch: (v: boolean) => void) {
  useEffect(() => {
    let g = false;
    let gTimer = 0;
    const undo = (e: KeyboardEvent) => {
      const last = [...toasts].reverse().find((t) => t.undo);
      if (last) { e.preventDefault(); last.undo!(); dropToast(last.id); }
    };
    const on = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement;
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) return;
      const k = keyName(e.key);
      if (e.ctrlKey || e.metaKey || e.altKey) {
        if ((e.ctrlKey || e.metaKey) && k === 'k') { e.preventDefault(); setSearch(true); }
        if ((e.ctrlKey || e.metaKey) && k === 'z') undo(e);
        return;
      }
      if (g) {
        g = false;
        if (GO[k]) { e.preventDefault(); go(GO[k]); }
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
      // Строка под клавиатурой: ↓/J, ↑/K — по списку; дальше — действия над ней (keys.taskAct).
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
      const act = ft && at >= 0 ? taskAct(k, e.shiftKey) : null;
      if (ft && act) {
        e.preventDefault();
        const row = rows[at];
        const pop = (render: (close: () => void) => React.ReactNode, cls?: string) => openPop(row, render, cls ? { cls } : undefined);
        switch (act) {
          case 'open': openTask(ft.id); break;
          case 'done': case 'done-forever':
            completeTask(ft, act === 'done-forever').catch(() => {});
            moveTo(at + 1 < rows.length ? at + 1 : at - 1);
            break;
          case 'prio1': case 'prio2': case 'prio3': case 'prio4': setPrio(ft, +act.slice(4)); break;
          case 'date': pop((close) => <Scheduler tasks={[ft]} value={ft as any} close={close} onPick={(v) => setDue(ft, v)} />, 'sched'); break;
          case 'date-clear': if (ft.due_date) setTask(ft, { due_date: null, due_time: null, recur: null, due_rule: null }, 'Срок снят').catch(() => {}); break;
          case 'deadline':
            pop((close) => <Scheduler mode="deadline" dueOf={ft.due_date} value={{ due_date: ft.deadline ?? null, due_time: null, recur: null, due_rule: null }} close={close}
              onPick={(v) => setDeadline(ft, v.due_date)} />, 'sched');
            break;
          case 'deadline-clear': if (ft.deadline) setTask(ft, { deadline: null }, 'Дедлайн снят').catch(() => {}); break;
          case 'prio': pop((close) => <PriorityMenu value={ft.priority ?? 4} close={close} onPick={(p) => setPrio(ft, p)} />); break;
          case 'labels': pop(() => <LabelPicker value={ft.labels || []} onPick={(l) => setTask(ft, { labels: l })} />); break;
          case 'move':
            pop((close) => <ProjectPicker value={{ project_id: ft.project_id, deal_id: ft.deal_id, section_id: ft.section_id ?? null }} close={close}
              onPick={(p) => setPlace(ft, p)} />);
            break;
          case 'select': if (UI.sel.has(ft.id)) UI.sel.delete(ft.id); else UI.sel.add(ft.id); emit(); break;
          case 'menu': pop((close) => <TaskMenu t={ft} close={close} />); break;
          case 'in-project': go(ft.deal_id ? 'deal/' + ft.deal_id : ft.project_id ? 'project/' + ft.project_id : 'inbox'); break;
        }
        return;
      }
      switch (globalAct(k, e.shiftKey)) {
        case 'next': e.preventDefault(); moveTo(at + 1); break;
        case 'prev': e.preventDefault(); moveTo(at < 0 ? 0 : at - 1); break;
        case 'dictate': e.preventDefault(); openDictate(); break;
        case 'quick': e.preventDefault(); openQuick(defaultsFor()); break;
        case 'search': {
          e.preventDefault();
          const box = document.getElementById('side-search') as HTMLInputElement | null;
          if (box && box.offsetParent) box.focus();
          else setSearch(true);
          break;
        }
        case 'side': e.preventDefault(); toggleSide(); break;
        case 'undo': undo(e); break;
        case 'go': g = true; clearTimeout(gTimer); gTimer = window.setTimeout(() => (g = false), 1200); break;
      }
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
        setErr(e instanceof ApiError && e.status === 401
          ? (inApp() ? 'Правка не подключена к Делам: Настройки → Дела → подключить по QR с компа.' : 'Вход — по ссылке-приглашению. Открой её на этом устройстве один раз.')
          : 'Не открылось: ' + (e instanceof Error ? e.message : String(e)));
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
      {UI.dialog?.kind === 'quick' ? <QuickSettingsDialog close={closeDialog} /> : null}
      {UI.dictate ? <Dictate close={() => openDictate(false)} /> : null}
      <BulkBar />
      <PopHost />
      <Toasts />
    </div>
  );
}
