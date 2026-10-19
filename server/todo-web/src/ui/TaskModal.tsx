// Окно задачи — как у Todoist: слева название, описание, подзадачи, комментарии; справа — проект, срок, дедлайн,
// приоритет, метки, напоминание. Наше: клиент и сделка (ссылкой в CRM), человек, «Зачем» с путём по дереву,
// «Сейчас», история, откуда дело, и вопрос Claude про это дело.
import { setDeadline, setDue, setPlace, setPrio } from './acts.ts';
import { useEffect, useRef, useState } from 'react';
import { api, runJob, uuid } from '../api.ts';
import { deadlineWords, dueWords, shortDate } from '../dates.ts';
import { crmUrl, dealShort, initials, labelColor, personName, projectColor, STAGE } from '../lib.ts';
import { recurWords } from '../quick.ts';
import { op1, S, setTask, syncSoon, useStore } from '../store.ts';
import type { Task } from '../types.ts';
import { childrenOf, dirName, goalDir, liveDeals, whyPath } from '../views.ts';
import { Icon, PrioFlag } from './icons.tsx';
import { openPop } from './pop.tsx';
import { GoalPicker, LabelPicker, PersonPicker, PriorityMenu, ProjectPicker, Scheduler } from './pickers.tsx';
import { closeTask, openTask, UI } from './state.ts';
import { Checkbox } from './TaskRow.tsx';
import { TaskList } from './TaskList.tsx';
import { TaskMenu } from './TaskMenu.tsx';

function AutoText({ value, onSave, className, placeholder, autoFocus }: { value: string; onSave: (v: string) => void; className: string; placeholder?: string; autoFocus?: boolean }) {
  const [v, setV] = useState(value);
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => setV(value), [value]);
  useEffect(() => {
    const el = ref.current;
    if (el) {
      el.style.height = 'auto';
      el.style.height = el.scrollHeight + 'px';
    }
  }, [v]);
  useEffect(() => {
    if (autoFocus) ref.current?.focus();
  }, [autoFocus]);
  return (
    <textarea ref={ref} className={className} rows={1} value={v} placeholder={placeholder}
      onChange={(e) => setV(e.target.value)}
      onBlur={() => v !== value && onSave(v)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' && (className.includes('title') || e.ctrlKey || e.metaKey)) {
          e.preventDefault();
          (e.target as HTMLTextAreaElement).blur();
        }
        if (e.key === 'Escape') {
          setV(value);
          e.stopPropagation();
          (e.target as HTMLTextAreaElement).blur();
        }
      }} />
  );
}

const ORIGIN: Record<string, string> = { meeting: 'со встречи', telegram: 'из Telegram', voice: 'голосом', bot: 'из бота', mcp: 'от Claude', import: 'из Todoist', web: 'вручную', manual: 'вручную' };

export function TaskModal() {
  useStore();
  const t = UI.task ? S.tasks.get(UI.task) || [...S.tasks.values()].find((x) => String(x.num) === UI.task) : undefined;
  const [card, setCard] = useState<any>(null);
  const [comment, setComment] = useState('');
  const [ask, setAsk] = useState('');
  const [talk, setTalk] = useState<{ said: string; reply?: string; busy?: boolean }[]>([]);
  const comRef = useRef<HTMLTextAreaElement>(null);
  const [tl, setTl] = useState<any[] | null>(null);
  const tid = t?.id;
  // Последнее с клиентом (из прежних Дел): хронология CRM сделки или клиента — три свежих записи, чтобы не ходить
  // в CRM за контекстом. Готовые виды сервера deal и client (RLS решает, что видно).
  const tlDeal = t?.deal_id || null, tlProj = t?.project_id || null;
  useEffect(() => {
    setTl(null);
    const p = tlProj ? S.projects.get(tlProj) : null;
    const q = tlDeal ? '/api/view/deal?deal_id=' + tlDeal : p?.kind === 'client' ? '/api/view/client?project_id=' + tlProj : null;
    if (!q) return;
    let live = true;
    api<any>(q).then((d) => { if (live) setTl((d.timeline || []).filter((x: any) => !x.deleted_at).slice(0, 3)); }).catch(() => live && setTl([]));
    return () => { live = false; };
  }, [tlDeal, tlProj]);
  useEffect(() => {
    setCard(null);
    setTalk([]);
    if (!tid) return;
    api('/api/task/' + tid).then(setCard).catch(() => setCard(null));
  }, [tid]);
  useEffect(() => {
    if (UI.taskComment) comRef.current?.focus();
  }, [tid]);
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && UI.task && !document.querySelector('.pop')) closeTask();
    };
    addEventListener('keydown', on);
    return () => removeEventListener('keydown', on);
  }, []);
  if (!t) {
    if (UI.task && S.ready) setTimeout(closeTask, 0);
    return null;
  }
  const proj = t.project_id ? S.projects.get(t.project_id) : null;
  const deal = t.deal_id ? S.deals.get(t.deal_id) : null;
  const comments = [...S.comments.values()].filter((c) => c.task_id === t.id && !c.deleted_at).sort((a, b) => a.created_at.localeCompare(b.created_at));
  const kids = childrenOf(t.id);
  const path = whyPath(t);
  const dir = t.goal ? goalDir(t) : path.length ? goalDir(path[0]) : (t.dirs && t.dirs[0]) || null;
  const today = S.today;
  const isNow = t.focus_on === today;

  // Соседи по списку на экране — стрелки ↑ ↓.
  const rows = [...document.querySelectorAll('.main [data-id]')].map((x) => (x as HTMLElement).dataset.id!);
  const at = rows.indexOf(t.id);
  const prev = at > 0 ? rows[at - 1] : null, next = at >= 0 && at < rows.length - 1 ? rows[at + 1] : null;

  const sendComment = async () => {
    const text = comment.trim();
    if (!text) return;
    setComment('');
    await op1({ op: 'comment.add', comment: { id: uuid(), task_id: t.id, text } }).catch(() => setComment(text));
    syncSoon();
  };
  const sendAsk = async () => {
    const text = ask.trim();
    if (!text) return;
    setAsk('');
    const turns = [...talk, { said: text, busy: true }];
    setTalk(turns);
    try {
      const d: any = await runJob('/api/ask', { text, scope: { title: 'Карточка дела', card: 'task', open: t.id,
        history: talk.filter((x) => !x.busy).map((x) => ({ said: x.said, reply: x.reply || '', done: '' })).slice(-6) } });
      for (const x of d.tasks || []) S.tasks.set(x.id, x);
      await syncSoon();
      setTalk([...talk, { said: text, reply: d.reply || ((d.changed || []).length ? 'Сделал.' : 'Готово.') }]);
    } catch (e) {
      setTalk([...talk, { said: text, reply: 'Не вышло: ' + (e instanceof Error ? e.message : String(e)) }]);
    }
  };
  const ball = t.ball;

  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) closeTask(); }}>
      <div className="tm" role="dialog" aria-label={t.title}>
        <div className="tm-top">
          <div className="crumbs">
            {proj ? <><span className="hash" style={{ color: projectColor(proj) }}>#</span><a href={'#/project/' + proj.id} onClick={closeTask}>{proj.name}</a></> : <><Icon name="inbox" size={16} /><a href="#/inbox" onClick={closeTask}>Входящие</a></>}
            {deal ? <><span>/</span><a href={'#/deal/' + deal.id} onClick={closeTask}>{dealShort(deal.id)}</a></> : null}
            {t.section_name ? <><span>/</span><span>{t.section_name}</span></> : null}
          </div>
          <button className="icon-btn" disabled={!prev} title="Предыдущая" onClick={() => prev && openTask(prev)}><Icon name="chevU" size={20} /></button>
          <button className="icon-btn" disabled={!next} title="Следующая" onClick={() => next && openTask(next)}><Icon name="chevD" size={20} /></button>
          <button className="icon-btn" title="Ещё" onClick={(e) => openPop(e.currentTarget, (close) => <TaskMenu t={t} close={close} />)}><Icon name="more" size={20} /></button>
          <button className="icon-btn" title="Закрыть" onClick={closeTask}><Icon name="x" size={20} /></button>
        </div>
        <div className="tm-body">
          <div className="tm-main">
            <div className="tm-head">
              <Checkbox t={t} />
              <AutoText className="tm-title" value={t.title} autoFocus={UI.taskEdit} onSave={(v) => v.trim() && setTask(t, { title: v.trim() })} />
            </div>
            <AutoText className="tm-desc" value={t.notes || ''} placeholder="Описание" onSave={(v) => setTask(t, { notes: v.trim() || null })} />

            <div className="tm-block">
              <div className="tm-bh"><Icon name="sub" size={16} />Подзадачи{kids.length ? <span className="n">{kids.filter((k) => k.status === 'done').length}/{kids.length}</span> : null}</div>
              <TaskList tasks={kids.filter((k) => k.status === 'open')} nest={false}
                add={{ why_id: t.id, project_id: t.project_id, deal_id: t.deal_id, section_id: t.section_id ?? null }} drop={{}} addLabel="Добавить подзадачу" />
            </div>

            <div className="tm-block">
              <div className="tm-bh"><Icon name="comment" size={16} />Комментарии{comments.length ? <span className="n">{comments.length}</span> : null}</div>
              {comments.map((c) => {
                const who = S.users.get(c.author_id)?.name || c.author_id;
                return (
                  <div key={c.id} className="com">
                    <span className="avatar">{initials(who)}</span>
                    <div style={{ minWidth: 0 }}>
                      <div className="who">{who}<span className="when">{shortDate(c.created_at.slice(0, 10), today)} {new Date(c.created_at).toLocaleTimeString('ru', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Moscow' })}</span></div>
                      <div className="txt">{c.text || c.body}</div>
                    </div>
                  </div>
                );
              })}
              <div className="com-in">
                <span className="avatar">{initials(S.me?.name || '?')}</span>
                <textarea ref={comRef} rows={1} placeholder="Комментарий" value={comment} onChange={(e) => setComment(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendComment(); } }} />
              </div>
            </div>

            <div className="tm-block">
              <div className="tm-bh"><Icon name="spark" size={16} style={{ color: 'var(--accent)' }} />Спросить Claude об этом деле</div>
              {talk.map((x, i) => (
                <div key={i} className="turn">
                  <div className="said">{x.said}</div>
                  {x.busy ? <div className="think"><i /><i /><i /></div> : <div className="reply">{x.reply}</div>}
                </div>
              ))}
              <div className="com-in">
                <textarea rows={1} placeholder="«перенеси на после встречи с Иваном», «разбей на шаги»" value={ask} onChange={(e) => setAsk(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendAsk(); } }} />
                <button className="btn pri" disabled={!ask.trim()} onClick={sendAsk}><Icon name="send" size={16} /></button>
              </div>
            </div>

            {card?.history?.length ? (
              <details className="tm-block">
                <summary className="tm-bh" style={{ cursor: 'pointer' }}><Icon name="list" size={16} />История<span className="n">{card.history.length}</span></summary>
                {card.history.slice(-30).reverse().map((h: any, i: number) => (
                  <div key={i} className="hist">{shortDate(String(h.at || h.created_at || '').slice(0, 10) || today, today)} · {h.actor || h.by || ''} · {h.op || ''} {Object.keys(h.after || {}).slice(0, 4).join(', ')}</div>
                ))}
              </details>
            ) : null}
          </div>

          <div className="tm-side">
            <div className="tm-row">
              <div className="tm-lbl">Проект</div>
              <button className="tm-val" onClick={(e) => openPop(e.currentTarget, (close) => <ProjectPicker value={{ project_id: t.project_id, deal_id: t.deal_id, section_id: t.section_id ?? null }} close={close}
                onPick={(p) => setPlace(t, p)} />)}>
                {proj ? <span className="hash" style={{ color: projectColor(proj) }}>#</span> : <Icon name="inbox" size={16} />}
                {proj ? proj.name : 'Входящие'}{deal ? ' / ' + dealShort(deal.id) : ''}{t.section_name ? ' / ' + t.section_name : ''}
              </button>
            </div>
            <div className="tm-sep" />
            <div className="tm-row">
              <div className="tm-lbl">Срок</div>
              <button className={'tm-val' + (t.due_date ? '' : ' muted')} onClick={(e) => openPop(e.currentTarget, (close) => <Scheduler tasks={[t]} value={t as any} close={close} onPick={(v) => setDue(t, v)} />, { cls: 'sched' })}>
                <Icon name="calendar" size={16} />
                {t.due_date ? `${dueWords(t.due_date, today)}${t.due_time ? ' ' + t.due_time.slice(0, 5) : ''}` : 'Без срока'}
                {t.recur ? <span style={{ color: 'var(--fg2)', display: 'inline-flex', alignItems: 'center', gap: 3 }}><Icon name="repeat" size={14} />{t.due_rule || recurWords(t.recur)}</span> : null}
              </button>
            </div>
            <div className="tm-row">
              <div className="tm-lbl">Дедлайн</div>
              <button className={'tm-val' + (t.deadline ? '' : ' muted')} onClick={(e) => openPop(e.currentTarget, (close) => <Scheduler mode="deadline" dueOf={t.due_date} value={{ due_date: t.deadline ?? null, due_time: null, recur: null, due_rule: null }} close={close} onPick={(v) => setDeadline(t, v.due_date)} />, { cls: 'sched' })}>
                <Icon name="deadline" size={16} />{t.deadline ? deadlineWords(t.deadline, today) + ' · ' + shortDate(t.deadline, today) : 'Без дедлайна'}
              </button>
            </div>
            <div className="tm-row">
              <div className="tm-lbl">Приоритет</div>
              <button className="tm-val" onClick={(e) => openPop(e.currentTarget, (close) => <PriorityMenu value={t.priority ?? 4} close={close} onPick={(p) => setPrio(t, p)} />)}>
                <PrioFlag p={t.priority ?? 4} size={16} />P{t.priority ?? 4}
              </button>
            </div>
            <div className="tm-row">
              <div className="tm-lbl">Метки</div>
              <button className={'tm-val' + ((t.labels || []).length ? '' : ' muted')} onClick={(e) => openPop(e.currentTarget, () => <LabelPicker value={t.labels || []} onPick={(l) => setTask(t, { labels: l })} />)}>
                {(t.labels || []).length ? t.labels.map((l) => <span key={l} className="lbl-chip" style={{ color: labelColor(l) }}><Icon name="tag" size={14} />{l}</span>) : <><Icon name="tag" size={16} />Без меток</>}
              </button>
            </div>
            <div className="tm-row">
              <div className="tm-lbl">Напоминание</div>
              <div className="tm-val" style={{ cursor: 'default' }}>
                <Icon name="alarm" size={16} />
                <input type="datetime-local" style={{ border: 0, background: 'transparent', font: 'inherit', color: 'inherit', minWidth: 0, flex: 1 }}
                  value={t.remind_at ? new Date(t.remind_at).toLocaleString('sv', { timeZone: 'Europe/Moscow' }).slice(0, 16).replace(' ', 'T') : ''}
                  onChange={(e) => setTask(t, { remind_at: e.target.value ? e.target.value + ':00+03:00' : null })} />
                {t.remind_place ? <span style={{ color: 'var(--fg2)' }}>· {t.remind_place}</span> : null}
              </div>
            </div>
            <div className="tm-row">
              <button className="tm-val" style={isNow ? { color: 'var(--accent-text)' } : undefined} onClick={() => setTask(t, { focus_on: isNow ? null : today }, isNow ? 'Убрал из «Сейчас»' : 'В «Сейчас»')}>
                <Icon name="bolt" size={16} />{isNow ? 'В «Сейчас» — убрать' : 'Добавить в «Сейчас»'}
              </button>
            </div>

            <div className="tm-sep" />
            <div className="tm-row">
              <div className="tm-lbl">CRM{proj?.kind === 'client' ? <a href={crmUrl('p', proj.id)} target="_blank" rel="noreferrer" style={{ color: 'var(--accent-text)', fontWeight: 400 }}>карточка ↗</a> : null}</div>
              {proj ? (
                <button className={'tm-val' + (deal ? '' : ' muted')} onClick={(e) => openPop(e.currentTarget, (close) => (
                  <div>
                    <div className="mh">Сделка клиента</div>
                    <button className="mi" onClick={() => { close(); setPlace(t, { project_id: t.project_id, deal_id: null }); }}><Icon name="x" size={16} />Без сделки</button>
                    {liveDeals(proj.id).map((d) => (
                      <button key={d.id} className={'mi' + (d.id === t.deal_id ? ' act' : '')} onClick={() => { close(); setPlace(t, { project_id: t.project_id, deal_id: d.id }); }}>
                        <Icon name="deal" size={16} />{dealShort(d.id)}<span className="r">{STAGE[d.stage] || d.stage}</span>
                      </button>
                    ))}
                  </div>
                ))}>
                  <Icon name="deal" size={16} />{deal ? dealShort(deal.id) + ' · ' + (STAGE[deal.stage] || deal.stage) : 'Без сделки'}
                </button>
              ) : <div className="tm-val muted"><Icon name="deal" size={16} />Без клиента</div>}
              {deal ? <a className="tm-val" style={{ color: 'var(--fg2)', fontSize: 12 }} href={crmUrl('d', deal.id)} target="_blank" rel="noreferrer">Сделка в CRM ↗</a> : null}
            </div>
            <div className="tm-row">
              <div className="tm-lbl">Человек{t.person_id ? <a href={crmUrl('h', t.person_id)} target="_blank" rel="noreferrer" style={{ color: 'var(--accent-text)', fontWeight: 400 }}>карточка ↗</a> : null}</div>
              <button className={'tm-val' + (t.person_id ? '' : ' muted')} onClick={(e) => openPop(e.currentTarget, (close) => <PersonPicker value={t.person_id} close={close} onPick={(id) => setTask(t, { person_id: id, ...(id ? {} : { ball: 'mine' }) })} />)}>
                <Icon name="user" size={16} />{t.person_id ? personName(t.person_id) : 'Без человека'}
              </button>
              {t.person_id ? (
                <div style={{ display: 'flex', gap: 4, marginTop: 4, flexWrap: 'wrap' }}>
                  {([['mine', 'делаю я'], ['waiting', 'жду от него'], ['agenda', 'обсудить']] as const).map(([b, w]) => (
                    <button key={b} className={'chip' + (ball === b ? ' set' : '')} style={ball === b ? { borderColor: 'var(--accent)', color: 'var(--accent-text)' } : undefined}
                      onClick={() => setTask(t, { ball: b })}>{w}</button>
                  ))}
                </div>
              ) : null}
            </div>
            {ball === 'waiting' ? (
              <div className="tm-row">
                <div className="tm-lbl">Пнуть{t.waiting_since ? <span style={{ fontWeight: 400 }}>жду с {shortDate(t.waiting_since, today)}</span> : null}</div>
                <button className={'tm-val' + (t.nudge_on ? '' : ' muted')} style={t.nudge_on && t.nudge_on <= today ? { color: 'var(--d-over)' } : undefined}
                  onClick={(e) => openPop(e.currentTarget, (close) => <Scheduler mode="date" value={{ due_date: t.nudge_on ?? null, due_time: null, recur: null, due_rule: null }} close={close}
                    onPick={(v) => setTask(t, { nudge_on: v.due_date }, v.due_date ? 'Пнуть — ' + shortDate(v.due_date, today) : 'Без напоминания пнуть')} />, { cls: 'sched' })}>
                  <Icon name="bell" size={16} />{t.nudge_on ? dueWords(t.nudge_on, today) : 'Когда напомнить пнуть'}
                </button>
              </div>
            ) : null}
            {tl && tl.length ? (
              <div className="tm-row">
                <div className="tm-lbl">Последнее {deal ? 'по сделке' : 'с клиентом'}</div>
                {tl.map((x, i) => (
                  <div key={x.id || i} className="tm-path" style={{ marginBottom: 6 }}>
                    <b>{shortDate(String(x.at).slice(0, 10), today)}</b> · {x.summary}
                    {x.next_step ? <div style={{ color: 'var(--fg3)' }}>дальше: {x.next_step}</div> : null}
                  </div>
                ))}
              </div>
            ) : null}
            <div className="tm-row">
              <div className="tm-lbl">Зачем — место в дереве</div>
              <button className={'tm-val' + (path.length ? '' : ' muted')} onClick={(e) => openPop(e.currentTarget, (close) => <GoalPicker task={t} value={t.why_id ?? null} close={close} onPick={(id) => setTask(t, { why_id: id }, id ? 'Положил под цель' : 'Убрал из дерева')} />)}>
                <Icon name="target" size={16} style={{ color: path.length ? 'var(--accent)' : undefined }} />
                {path.length ? (
                  <span className="tm-path">{dir ? dirName(dir) + ' › ' : ''}{path.map((x, i) => <span key={x.id}>{i ? ' › ' : ''}{i === path.length - 1 ? <b>{x.title}</b> : x.title}</span>)}</span>
                ) : 'Не в дереве'}
              </button>
            </div>
            {card?.origin || t.source ? (
              <div className="tm-row">
                <div className="tm-lbl">Откуда</div>
                <div className="tm-path">
                  {ORIGIN[t.source] || t.source}{t.created_at ? ', ' + shortDate(t.created_at.slice(0, 10), today) : ''}
                  {card?.origin?.title ? <div>{card.origin.title}</div> : null}
                  {card?.origin?.quote ? <div style={{ fontStyle: 'italic' }}>«{card.origin.quote}»</div> : null}
                  {card?.origin?.text ? <div style={{ fontStyle: 'italic' }}>«{String(card.origin.text).slice(0, 300)}»</div> : null}
                </div>
              </div>
            ) : null}
            <div className="tm-row"><div className="tm-path" style={{ color: 'var(--fg3)' }}>#{t.num}{t.done_count ? ` · выполнено ${t.done_count} раз` : ''}</div></div>
          </div>
        </div>
      </div>
    </div>
  );
}

export type { Task };
