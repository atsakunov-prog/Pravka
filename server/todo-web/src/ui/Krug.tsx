// Дерево жизни в /t (23.11.2026): «Круг жизни» — девять направлений карточками, у каждой свой цвет и свечение, внутри —
// её задачи; направление — цели деревом и задачи под ними; «Без места» — что ещё не разложено. Задачу можно бросить
// на карточку, направление в боковой панели или цель — она ляжет туда. Цели заводит и переставляет только владелец
// (бриф 7.2 §13): здесь их не создают и не двигают, только кладут под них задачи.
import { useState, type ReactNode } from 'react';
import { dirColor, dirFull, dirShort, dirTasks, krugOn, mainDir, unplaced, zones } from '../krug.ts';
import { go, projectColor } from '../lib.ts';
import { plural } from '../dates.ts';
import { S, setTask, toast, useStore } from '../store.ts';
import type { Id, Project, Task } from '../types.ts';
import { byPos, goalChildren, goalDir, goals, isListed, mine, smart, whyPath } from '../views.ts';
import { Icon } from './icons.tsx';
import { openPop } from './pop.tsx';
import { openQuick, openTask } from './state.ts';
import { draggingId, setDragId, TaskList } from './TaskList.tsx';
import { Checkbox } from './TaskRow.tsx';
import { Empty, Page } from './Views.tsx';

// ── Правки места ──────────────────────────────────────────────────────────

/** Положить задачу в направление (без цели). Задача клиента берёт место у клиента — её не двигаем, а говорим где. */
export function setDir(t: Task, key: string) {
  const p = t.project_id ? S.krugProj.get(t.project_id) : null;
  if (p && (p.directions.length || p.why_id) && !t.why_id) {
    const pr = S.projects.get(t.project_id!);
    toast(`Место задачи задаёт проект${pr ? ' «' + pr.name + '»' : ''} (${dirShort(mainDir(t))}) — брось её на цель или вынь из проекта`, undefined, true);
    return;
  }
  if (mainDir(t) === key && !t.why_id) return;
  setTask(t, { why_id: null, directions: [key] }, 'Направление: ' + dirShort(key)).catch(() => {});
}
/** Положить задачу под цель дерева. */
export function setGoal(t: Task, g: Task) {
  if (t.why_id === g.id) return;
  setTask(t, { why_id: g.id }, 'Под цель: ' + g.title).catch(() => {});
}

/** Принимает перетаскиваемую задачу: подсветка, пока над ним, и действие при броске. */
function useDrop(onTask: (t: Task) => void) {
  const [over, setOver] = useState(false);
  return {
    over,
    props: {
      onDragOver: (e: React.DragEvent) => { if (draggingId()) { e.preventDefault(); if (!over) setOver(true); } },
      onDragLeave: (e: React.DragEvent) => { if (!(e.currentTarget as HTMLElement).contains(e.relatedTarget as Node)) setOver(false); },
      onDrop: (e: React.DragEvent) => {
        e.preventDefault();
        setOver(false);
        const id = draggingId();
        const t = id ? S.tasks.get(id) : null;
        if (t) onTask(t);
      },
    },
  };
}

// ── Мелочи вида ──────────────────────────────────────────────────────────

/** Светящаяся точка направления. */
export function DirDot({ k, size = 8 }: { k: string | null | undefined; size?: number }) {
  return <i className="dir-dot" style={{ '--dc': dirColor(k), width: size, height: size } as React.CSSProperties} />;
}

/** Цель, к которой ведёт задача: по цепочке «Зачем», нет — цель её проекта. */
export function goalOf(t: Task): Task | null {
  let x: Task | undefined = t;
  for (let i = 0; i < 20 && x; i++) {
    if (!x.why_id) break;
    const w = S.tasks.get(x.why_id);
    if (!w) break;
    if (w.goal) return w;
    x = w;
  }
  const p = x?.project_id ? S.krugProj.get(x.project_id) : null;
  return p?.why_id ? S.tasks.get(p.why_id) || null : null;
}

// Цель → её задачи — раз на изменение памяти (S.version): дерево на живых данных — сотня целей и тысячи дел, а
// перебор всех дел на каждую цель и подцель тормозил бы телефон.
let gAt = -1;
let gIdx = new Map<Id, Task[]>();
/** Все задачи под целью: прямо на ней, внутри задач на ней и в проектах, висящих на ней. */
function goalAll(g: Task): Task[] {
  if (gAt !== S.version) {
    gAt = S.version;
    gIdx = new Map();
    for (const t of S.tasks.values()) {
      if (!isListed(t) || !mine(t)) continue;
      const k = goalOf(t)?.id;
      if (!k) continue;
      const arr = gIdx.get(k);
      if (arr) arr.push(t);
      else gIdx.set(k, [t]);
    }
  }
  return [...(gIdx.get(g.id) || [])];
}
/** Проекты, висящие на цели. */
function goalProjects(g: Task): Project[] {
  return [...S.krugProj.entries()].filter(([, k]) => k.why_id === g.id).map(([id]) => S.projects.get(id)).filter((p): p is Project => !!p && !p.archived_at);
}
/** Цели и их подцели — сколько задач во всём поддереве. */
function treeCount(g: Task): number {
  return goalAll(g).length + goalChildren(g.id).reduce((n, k) => n + treeCount(k), 0);
}

/** Строка задачи в карточке: галочка, название, срок. */
function MiniTask({ t }: { t: Task }) {
  const over = t.due_date && t.due_date < S.today;
  const today = t.due_date === S.today;
  return (
    <div className="kr-t" data-id={t.id} draggable
      onDragStart={(e) => { e.dataTransfer.setData('text/x-task', t.id); setDragId(t.id); }}
      onDragEnd={() => setDragId(null)}
      onClick={() => openTask(t.id)}>
      <Checkbox t={t} />
      <span className="kr-tt">{t.title}</span>
      {t.focus_on === S.today ? <Icon name="bolt" size={13} style={{ color: 'var(--accent)' }} /> : null}
      {over ? <span className="kr-due d-over">просрочено</span> : today ? <span className="kr-due d-today">сегодня</span> : null}
    </div>
  );
}

// ── Круг жизни ───────────────────────────────────────────────────────────

function DirCard({ k }: { k: string }) {
  const d = S.dirs.find((x) => x.key === k)!;
  const ts = [...dirTasks(k)].sort(smart);
  const top = goalChildren(null, k);
  const drop = useDrop((t) => setDir(t, k));
  const today = ts.filter((t) => t.focus_on === S.today || (t.due_date && t.due_date <= S.today)).length;
  return (
    <section className={'kr-card' + (drop.over ? ' drop' : '')} style={{ '--dc': dirColor(k) } as React.CSSProperties} {...drop.props}>
      <div className="kr-top">
        <a className="kr-ch" href={'#/dir/' + k}>
          <DirDot k={k} size={10} />
          <span className="kr-name">{dirFull(k)}</span>
          <span className="kr-n">{ts.length || ''}</span>
        </a>
        <button className="icon-btn sm kr-add" title={'Записать в «' + dirShort(k) + '»'} onClick={() => openQuick({ directions: [k] })}><Icon name="plus" size={16} /></button>
      </div>
      {d.formula ? <div className="kr-f">{String(d.formula)}</div> : null}
      <div className="kr-stats">
        {top.length ? <span><Icon name="target" size={12} />{plural(top.length, 'цель', 'цели', 'целей')}</span> : null}
        {today ? <span className="hot">{today} на сегодня</span> : null}
      </div>
      <div className="kr-list">
        {ts.slice(0, 5).map((t) => <MiniTask key={t.id} t={t} />)}
        {!ts.length ? <div className="kr-empty">Пусто — брось сюда задачу</div> : null}
      </div>
      {ts.length > 5 ? <a className="kr-more" href={'#/dir/' + k}>ещё {ts.length - 5}</a> : null}
    </section>
  );
}

/** Полоса: сколько открытых задач в каждом направлении — неоновыми отрезками. */
function DirBar() {
  const parts = S.dirs.map((d) => ({ k: d.key, n: dirTasks(d.key).length })).filter((x) => x.n);
  const none = unplaced().length;
  const total = parts.reduce((s, x) => s + x.n, 0) + none;
  if (!total) return null;
  return (
    <div className="kr-bar-w">
      <div className="kr-bar">
        {parts.map((x) => (
          <a key={x.k} href={'#/dir/' + x.k} title={`${dirFull(x.k)}: ${x.n}`} style={{ flexGrow: x.n, '--dc': dirColor(x.k) } as React.CSSProperties} />
        ))}
        {none ? <a href="#/dir/none" className="none" title={`Без места: ${none}`} style={{ flexGrow: none }} /> : null}
      </div>
      <div className="kr-legend">
        {parts.map((x) => <a key={x.k} href={'#/dir/' + x.k}><DirDot k={x.k} />{dirShort(x.k)} <b>{x.n}</b></a>)}
        {none ? <a href="#/dir/none" className="muted"><i className="dir-dot none" />без места <b>{none}</b></a> : null}
      </div>
    </div>
  );
}

export function KrugView() {
  useStore();
  if (!krugOn()) return <Page title="Круг жизни" crumbs="Круг жизни"><Empty icon="tree" big="Дерево жизни не настроено" text="В Своде нет направлений (krug.directions)." /></Page>;
  const loose = [...unplaced()].sort(smart);
  return (
    <Page title="Круг жизни" icon={<Icon name="tree" size={26} style={{ color: 'var(--accent)' }} />} crumbs="Круг жизни" wide
      sub={<span>Девять направлений и задачи в них. Брось задачу на карточку — она ляжет туда; разборщик раскладывает новое сам раз в полчаса.</span>}
      actions={<a className="tb-btn" href="#/tree"><Icon name="list" size={20} /><span className="d-only">Деревом</span></a>}>
      <DirBar />
      {zones().map((z) => (
        <div key={z.zone} className="kr-zone">
          {z.zone ? <div className="kr-zh">{z.zone}</div> : null}
          <div className="kr-grid">{z.dirs.map((d) => <DirCard key={d.key} k={d.key} />)}</div>
        </div>
      ))}
      {loose.length ? (
        <div className="kr-loose">
          <div className="sect-h"><i className="dir-dot none" />Без места<span className="n">{loose.length}</span>
            <span className="right"><a className="act" href="#/dir/none">Все</a></span></div>
          <div className="view-sub" style={{ margin: '6px 0 0' }}>Ни цели, ни направления. Перетащи на карточку выше — или оставь разборщику.</div>
          <TaskList tasks={loose.slice(0, 12)} rowOpts={{ showProject: true, hideDir: true, placeBtn: true }} nest={false} drop={{ nest: false, onDrop: () => {} }} />
        </div>
      ) : null}
    </Page>
  );
}

// ── Направление: цели деревом, задачи под ними ───────────────────────────

function GoalBlock({ g, depth, dir }: { g: Task; depth: number; dir: string }) {
  const kids = goalChildren(g.id);
  const own = goalAll(g).sort(byPos);
  const projs = goalProjects(g);
  const n = treeCount(g);
  const [open, setOpen] = useState(depth === 0 && n > 0 && n < 12);
  const drop = useDrop((t) => setGoal(t, g));
  const projTasks = new Set(projs.map((p) => p.id));
  // Прямо под целью — задачи не из висящих на ней проектов (те — чипами проекта, кликом в проект).
  const direct = own.filter((t) => !(t.project_id && projTasks.has(t.project_id) && !t.why_id));
  return (
    <div className={'gb' + (depth ? ' sub' : '')} style={{ '--dc': dirColor(dir), marginLeft: depth ? 18 : 0 } as React.CSSProperties}>
      <div className={'gb-h' + (drop.over ? ' drop' : '')} {...drop.props} onClick={() => setOpen(!open)}>
        <button className="tw" onClick={(e) => { e.stopPropagation(); setOpen(!open); }} aria-label={open ? 'Свернуть' : 'Раскрыть'}>
          <Icon name={open ? 'chevD' : 'chevR'} size={16} />
        </button>
        <span className="gb-ic"><Icon name="target" size={17} /></span>
        <span className="gb-t">{g.title}</span>
        {g.rhythm ? <span className="gb-rh">↻ {g.rhythm}</span> : null}
        {g.no_steps ? <span className="gb-rh">ориентир</span> : null}
        <span className="gb-n">{n || ''}</span>
        <button className="icon-btn sm gb-go" title="Открыть цель" onClick={(e) => { e.stopPropagation(); go('goal/' + g.id); }}><Icon name="chevR" size={16} /></button>
      </div>
      {open ? (
        <div className="gb-body">
          {projs.length ? (
            <div className="gb-projs">
              {projs.map((p) => (
                <a key={p.id} className="chip" href={'#/project/' + p.id}><span className="hash" style={{ color: projectColor(p) }}>#</span>{p.name}
                  <span style={{ color: 'var(--fg3)' }}>{own.filter((t) => t.project_id === p.id).length || ''}</span></a>
              ))}
            </div>
          ) : null}
          {direct.length || (!g.no_steps && !kids.length) ? (
            <TaskList tasks={direct} rowOpts={{ showProject: true, hideDir: true }} nest={false} drop={{ nest: false, onDrop: () => {} }}
              add={g.no_steps ? false : { why_id: g.id }} addLabel="Добавить задачу к цели" />
          ) : null}
          {kids.map((k) => <GoalBlock key={k.id} g={k} depth={depth + 1} dir={dir} />)}
        </div>
      ) : null}
    </div>
  );
}

export function DirView({ dir }: { dir: string }) {
  useStore();
  if (dir === 'none') {
    const loose = [...unplaced()].sort(smart);
    return (
      <Page title={<><i className="dir-dot none" style={{ width: 12, height: 12 }} />Без места</>} crumbs={<a href="#/krug">Круг жизни</a>}
        sub={<span>Задачи без цели и направления. Нажми «Разложить» или брось на направление в боковой панели. Разборщик раскладывает их и сам раз в полчаса.</span>}>
        <TaskList tasks={loose} rowOpts={{ showProject: true, hideDir: true, placeBtn: true }} nest={false} drop={{ nest: false, onDrop: () => {} }}
          empty={<Empty icon="tree" big="Всё разложено" text="У каждой задачи есть место в дереве жизни." />} />
      </Page>
    );
  }
  const d = S.dirs.find((x) => x.key === dir);
  if (!d) return <Page title="Направление не найдено"><Empty icon="tree" big="Такого направления нет" /></Page>;
  const top = goalChildren(null, dir);
  const all = dirTasks(dir);
  const free = all.filter((t) => !goalOf(t)).sort(smart);
  const projs = [...S.krugProj.entries()].filter(([, k]) => !k.why_id && k.directions[0] === dir).map(([id]) => S.projects.get(id))
    .filter((p): p is Project => !!p && !p.archived_at);
  return (
    <div style={{ '--dc': dirColor(dir) } as React.CSSProperties} className="dir-page">
      <Page title={<><DirDot k={dir} size={14} />{dirFull(dir)}</>} crumbs={<a href="#/krug">Круг жизни</a>}
        sub={<span>{d.formula ? <i>{String(d.formula)}</i> : null}{d.formula ? <span className="dot"> · </span> : null}{plural(all.length, 'задача', 'задачи', 'задач')}</span>}
        actions={<a className="tb-btn" href="#/tree"><Icon name="list" size={20} /><span className="d-only">Всё дерево</span></a>}>
        {top.length ? (
          <div className="sect">
            <div className="sect-h">Цели<span className="n">{top.length}</span></div>
            {top.map((g) => <GoalBlock key={g.id} g={g} depth={0} dir={dir} />)}
          </div>
        ) : null}
        {projs.length ? (
          <div className="sect">
            <div className="sect-h">Проекты направления<span className="n">{projs.length}</span></div>
            <div className="gb-projs" style={{ padding: '8px 0' }}>
              {projs.map((p) => <a key={p.id} className="chip" href={'#/project/' + p.id}><span className="hash" style={{ color: projectColor(p) }}>#</span>{p.name}</a>)}
            </div>
          </div>
        ) : null}
        <div className="sect">
          <div className="sect-h">Без цели<span className="n">{free.length || ''}</span></div>
          <TaskList tasks={free} rowOpts={{ showProject: true, hideDir: true }} nest={false} drop={{ nest: false, onDrop: () => {} }}
            add={{ directions: [dir] }} addLabel={'Добавить задачу в «' + dirShort(dir) + '»'} />
        </div>
      </Page>
    </div>
  );
}

// ── Дерево целиком (списком) ─────────────────────────────────────────────

export function TreeView() {
  useStore();
  if (!krugOn()) return <Page title="Дерево жизни" crumbs="Дерево жизни"><Empty icon="tree" big="Дерево жизни не настроено" /></Page>;
  return (
    <Page title={<><Icon name="tree" size={24} style={{ color: 'var(--accent)' }} />Дерево жизни</>} crumbs={<a href="#/krug">Круг жизни</a>}
      sub={<span>Направления, под ними цели, под целями задачи. Брось задачу на цель — она ляжет под неё. Цели видишь только ты.</span>}
      actions={<a className="tb-btn" href="#/krug"><Icon name="board" size={20} /><span className="d-only">Кругом</span></a>}>
      {S.dirs.map((d) => {
        const top = goalChildren(null, d.key);
        const n = dirTasks(d.key).length;
        return (
          <div key={d.key} className="sect tree-dir" style={{ '--dc': dirColor(d.key) } as React.CSSProperties}>
            <div className="sect-h"><DirDot k={d.key} size={10} /><a href={'#/dir/' + d.key}>{dirFull(d.key)}</a><span className="n">{n || ''}</span></div>
            {top.map((g) => <GoalBlock key={g.id} g={g} depth={0} dir={d.key} />)}
            {!top.length ? <div className="kr-empty" style={{ padding: '8px 0' }}>Целей нет</div> : null}
          </div>
        );
      })}
    </Page>
  );
}

// ── Цель ─────────────────────────────────────────────────────────────────

export function GoalView({ id }: { id: Id }) {
  useStore();
  const g = S.tasks.get(id);
  if (!g) return <Page title="Цель не найдена"><Empty icon="target" big="Такой цели нет" /></Page>;
  const path = whyPath(g);
  const dir = goalDir(g);
  const kids = goalChildren(id);
  const own = goalAll(g).sort(byPos);
  const projs = goalProjects(g);
  return (
    <div style={{ '--dc': dirColor(dir) } as React.CSSProperties} className="dir-page">
      <Page title={<><span className="gb-ic"><Icon name="target" size={24} /></span>{g.title}</>}
        crumbs={<span><a href="#/krug">Круг жизни</a>{dir ? <> / <a href={'#/dir/' + dir}>{dirShort(dir)}</a></> : null}{path.map((x) => <span key={x.id}> / <a href={'#/goal/' + x.id}>{x.title}</a></span>)}</span>}
        sub={<span>{g.rhythm ? '↻ ' + g.rhythm + ' · ' : ''}{g.no_steps ? 'ориентир без задач' : plural(own.length, 'задача', 'задачи', 'задач')}</span>}
        actions={<button className="tb-btn" onClick={() => openTask(g.id)}><Icon name="pencil" size={18} /><span className="d-only">Цель</span></button>}>
        {kids.length ? (
          <div className="sect"><div className="sect-h">Цели внутри<span className="n">{kids.length}</span></div>{kids.map((k) => <GoalBlock key={k.id} g={k} depth={0} dir={dir || ''} />)}</div>
        ) : null}
        {projs.length ? (
          <div className="sect"><div className="sect-h">Проекты<span className="n">{projs.length}</span></div>
            <div className="gb-projs" style={{ padding: '8px 0' }}>{projs.map((p) => <a key={p.id} className="chip" href={'#/project/' + p.id}><span className="hash" style={{ color: projectColor(p) }}>#</span>{p.name}</a>)}</div>
          </div>
        ) : null}
        <div className="sect">
          <div className="sect-h">Задачи<span className="n">{own.length || ''}</span></div>
          <TaskList tasks={own} rowOpts={{ showProject: true, hideDir: true }} add={g.no_steps ? false : { why_id: id }} drop={{}} />
        </div>
      </Page>
    </div>
  );
}

// ── Место в дереве: направление или цель ─────────────────────────────────

export interface PlaceVal { why_id: Id | null; dir: string | null }

/** Выбор места: девять направлений плитками сверху (без цели), ниже — цели с поиском. */
export function PlacePicker({ task, value, onPick, close }: { task?: Task; value: PlaceVal; onPick: (v: PlaceVal) => void; close: () => void }) {
  const [q, setQ] = useState('');
  const n = q.toLowerCase().replace(/ё/g, 'е');
  const cur = value.why_id || null;
  const curDir = value.dir || (task ? mainDir(task) : null);
  const list = goals().filter((g) => g.id !== task?.id && !g.no_steps && (!n || g.title.toLowerCase().replace(/ё/g, 'е').includes(n)))
    .map((g) => ({ g, d: goalDir(g), path: whyPath(g).map((x) => x.title).join(' › ') }))
    .sort((a, b) => (S.dirs.findIndex((x) => x.key === a.d) - S.dirs.findIndex((x) => x.key === b.d)) || (a.path + a.g.title).localeCompare(b.path + b.g.title, 'ru'))
    .slice(0, 80);
  const dirs = S.dirs.filter((d) => !n || dirFull(d.key).toLowerCase().replace(/ё/g, 'е').includes(n) || dirShort(d.key).toLowerCase().includes(n));
  return (
    <div style={{ width: 320 }} className="place-pick">
      <input className="search" autoFocus placeholder="Направление или цель" value={q} onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => {
          if (e.key !== 'Enter') return;
          if (dirs[0] && n) { onPick({ why_id: null, dir: dirs[0].key }); close(); }
          else if (list[0]) { onPick({ why_id: list[0].g.id, dir: null }); close(); }
        }} />
      {dirs.length ? (
        <div className="pp-dirs">
          {dirs.map((d) => (
            <button key={d.key} className={'pp-dir' + (!cur && curDir === d.key ? ' on' : '')} style={{ '--dc': dirColor(d.key) } as React.CSSProperties}
              onClick={() => { onPick({ why_id: null, dir: d.key }); close(); }}>
              <DirDot k={d.key} />{dirShort(d.key)}
            </button>
          ))}
        </div>
      ) : null}
      <div className="scroll">
        {cur || value.dir ? <button className="mi" onClick={() => { onPick({ why_id: null, dir: null }); close(); }}><Icon name="x" size={16} />Без места</button> : null}
        {list.length ? <div className="mh">Цели</div> : null}
        {list.map(({ g, d, path }) => (
          <button key={g.id} className={'mi' + (g.id === cur ? ' act' : '')} onClick={() => { onPick({ why_id: g.id, dir: null }); close(); }}>
            <Icon name="target" size={16} style={{ color: dirColor(d) }} />
            <span style={{ flex: 1, minWidth: 0 }}>
              <div>{g.title}</div>
              <div style={{ fontSize: 11, color: 'var(--fg3)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{[dirShort(d), path].filter(Boolean).join(' › ')}</div>
            </span>
          </button>
        ))}
        {!list.length && !dirs.length ? <div className="mh">Ничего не нашлось</div> : null}
      </div>
    </div>
  );
}

/** Подпись места задачи: «Здоровье › Сильное тело» или «Здоровье». */
export function placeLine(t: Task): ReactNode {
  const g = goalOf(t);
  const k = mainDir(t);
  if (!k && !g) return null;
  return <span className="place-line"><DirDot k={k} />{dirShort(k)}{g ? <> › {g.title}</> : null}</span>;
}

/** Поставить выбранное место: цель, направление или «без места». */
export function placeTo(t: Task, v: PlaceVal) {
  if (v.why_id) { const g = S.tasks.get(v.why_id); if (g) setGoal(t, g); }
  else if (v.dir) setDir(t, v.dir);
  else setTask(t, { why_id: null, directions: [] }, 'Убрал из дерева').catch(() => {});
}

/** Открыть выбор места для задачи и сразу поставить. */
export function pickPlace(el: HTMLElement, t: Task) {
  openPop(el, (close) => (
    <PlacePicker task={t} value={{ why_id: t.why_id && t.why_goal ? t.why_id : null, dir: null }} close={close} onPick={(v) => placeTo(t, v)} />
  ));
}
