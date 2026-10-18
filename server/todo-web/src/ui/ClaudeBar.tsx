// Строка Claude внизу — наша, у Todoist такой нет (слово Саши: «вопрос Клоду оставить»). Тот же /api/ask, что у
// прежнего веба и телефона: Claude видит экран (дела по порядку, какие в окне, выбранные, открытое) и все открытые дела
// коротко, отвечает словами, правит и заводит дела, решает «Новое». Разговор — до 8 реплик памяти.
import { useEffect, useRef, useState } from 'react';
import { runJob, uuid } from '../api.ts';
import { useRoute } from '../lib.ts';
import { ops, S, syncSoon, toast, useStore } from '../store.ts';
import { Icon } from './icons.tsx';
import { openTask, UI } from './state.ts';

interface Turn {
  said: string;
  d?: any;
  busy?: boolean;
  undone?: boolean;
}

function scope(title: string) {
  const main = document.querySelector('.main');
  const rows = [...document.querySelectorAll('.main [data-id]')] as HTMLElement[];
  const box = main?.getBoundingClientRect();
  const seen = rows.filter((x) => {
    const b = x.getBoundingClientRect();
    return box && b.height > 0 && b.top >= box.top - 4 && b.bottom <= Math.min(box.bottom, innerHeight) + 4;
  });
  const uniq = (xs: string[]) => [...new Set(xs)].slice(0, 300);
  const sc: Record<string, unknown> = { title, task_ids: uniq(rows.map((x) => x.dataset.id!)), visible_ids: uniq(seen.map((x) => x.dataset.id!)) };
  if (UI.sel.size) sc.selected_ids = [...UI.sel].slice(0, 300);
  if (UI.task) sc.open = UI.task;
  return sc;
}

function undoOps(d: any): any[] {
  const back: any[] = [];
  if (d.capture) {
    for (const x of d.capture.items || []) if (x.bucket !== 'cancelled') back.push(...(x.undo || []));
    return back;
  }
  if (d.route === 'new') return (d.tasks || []).map((t: any) => ({ op: 'task.cancel', id: t.id }));
  const took = (d.decided || []).filter((x: any) => x.decision === 'accept' && x.id);
  for (const c of [...(d.changed || []), ...took.filter((x: any) => !x.created)]) {
    if (Object.keys(c.before || {}).length) back.push({ op: 'task.set', id: c.id, set: c.before });
    if (c.status) back.push({ op: ({ open: 'task.reopen', done: 'task.done', cancelled: 'task.cancel' } as any)[c.status[0]], id: c.id });
  }
  for (const t of [...(d.tasks || []), ...took.filter((x: any) => x.created)]) back.push({ op: 'task.cancel', id: t.id });
  for (const c of d.crm || []) back.push(...(c.undo || []));
  return back;
}

const TITLES: Record<string, string> = { now: 'Сейчас', '': 'Сейчас', inbox: 'Входящие', today: 'Сегодня', upcoming: 'Предстоящее', filters: 'Фильтры и метки', done: 'Выполнено', tree: 'Дерево жизни' };

export function ClaudeBar() {
  useStore();
  const r = useRoute();
  const [text, setText] = useState('');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [talkId, setTalkId] = useState(uuid());
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLTextAreaElement>(null);
  const feed = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (el) {
      el.style.height = 'auto';
      el.style.height = Math.min(120, el.scrollHeight) + 'px';
    }
  }, [text]);
  useEffect(() => {
    feed.current?.scrollTo({ top: feed.current.scrollHeight, behavior: 'smooth' });
  }, [turns]);
  // Другой экран — другой разговор (как у прежнего веба): Claude видит новый экран, а не тянет старую память.
  useEffect(() => {
    setOpen(false);
    if (!turns.some((t) => t.busy)) {
      setTurns([]);
      setTalkId(uuid());
    }
  }, [r.view, r.id]);
  useEffect(() => {
    const on = (e: MouseEvent) => {
      if (!(e.target as HTMLElement).closest?.('.claude, .pop, .scrim')) setOpen(false);
    };
    addEventListener('mousedown', on);
    return () => removeEventListener('mousedown', on);
  }, []);
  const title = (() => {
    if (r.view === 'project') return 'Проект: ' + (S.projects.get(r.id || '')?.name || '');
    if (r.view === 'deal') return 'Сделка: ' + (S.deals.get(r.id || '')?.name || '');
    if (r.view === 'goal') return 'Цель: ' + (S.tasks.get(r.id || '')?.title || '');
    return TITLES[r.view] || r.view;
  })();
  const busy = turns.some((t) => t.busy);
  const send = async (said?: string) => {
    const msg = (said ?? text).trim();
    if (!msg || busy) return;
    setText('');
    setOpen(true);
    const history = turns.filter((t) => t.d && !t.busy).slice(-8).map((t) => ({ said: t.said.slice(0, 600), reply: String(t.d.reply || '').slice(0, 900),
      done: describeDone(t.d).slice(0, 700) + (t.undone ? ' — Саша вернул всё это как было' : '') }));
    const next = [...turns, { said: msg, busy: true }];
    setTurns(next);
    try {
      const d: any = await runJob('/api/ask', { text: msg, scope: { ...scope(title), talk_id: talkId, ...(history.length ? { history } : {}) } });
      for (const t of d.tasks || []) S.tasks.set(t.id, t);
      await syncSoon();
      setTurns([...turns, { said: msg, d }]);
    } catch (e) {
      setTurns(turns);
      setText(msg);
      toast(e instanceof Error ? e.message : String(e), undefined, true);
    }
  };
  const undo = async (i: number) => {
    const t = turns[i];
    const back = undoOps(t.d);
    if (!back.length) return;
    await ops(back).catch(() => {});
    setTurns(turns.map((x, k) => (k === i ? { ...x, undone: true } : x)));
  };
  return (
    <div className={'claude' + (UI.claudeOpen ? '' : ' closed')}>
      {open && turns.length ? (
        <div className="claude-feed" ref={feed}>
          <div className="cf-h">
            <span>Разговор с Claude · {title}</span>
            <span style={{ display: 'flex', gap: 4 }}>
              <button className="btn ghost" style={{ height: 24, fontSize: 12 }} onClick={() => { setTurns([]); setTalkId(uuid()); }}>Новый разговор</button>
              <button className="icon-btn sm" onClick={() => setOpen(false)}><Icon name="chevD" size={16} /></button>
            </span>
          </div>
          {turns.map((t, i) => (
            <div key={i} className="turn">
              <div className="said">{t.said}</div>
              {t.busy ? <div className="think"><i /><i /><i /></div> : (
                <>
                  {t.d?.reply ? <div className="reply">{t.d.reply}</div> : null}
                  <Did d={t.d} />
                  {i === turns.length - 1 && !t.undone ? <Answers d={t.d} send={send} /> : null}
                  {undoOps(t.d).length ? (
                    <div style={{ marginTop: 6 }}>
                      {t.undone ? <span style={{ fontSize: 12, color: 'var(--fg3)' }}>Вернул как было</span>
                        : <button className="btn ghost" style={{ height: 24, fontSize: 12 }} onClick={() => undo(i)}>Вернуть</button>}
                    </div>
                  ) : null}
                </>
              )}
            </div>
          ))}
        </div>
      ) : null}
      <div className="claude-bar">
        <span className="spark"><Icon name="spark" size={20} /></span>
        <textarea ref={ref} rows={1} value={text} placeholder="Саша, что ты хочешь спросить?" onFocus={() => turns.length && setOpen(true)}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } if (e.key === 'Escape') (e.target as HTMLElement).blur(); }} />
        {turns.length && !open ? <button className="icon-btn sm" title="Показать разговор" onClick={() => setOpen(true)}><Icon name="chevU" size={16} /></button> : null}
        <button className="send" disabled={!text.trim() || busy} onClick={() => send()} aria-label="Отправить"><Icon name="send" size={18} sw={2} /></button>
      </div>
    </div>
  );
}

function describeDone(d: any): string {
  const out: string[] = [];
  for (const c of d.changed || []) out.push((c.after?.title || c.title) + ': изменено');
  if ((d.tasks || []).length) out.push('завёл: ' + d.tasks.map((x: any) => x.title).join(', '));
  for (const x of d.decided || []) out.push(`П${x.n} ${x.decision === 'accept' ? 'принято' : 'отклонено'}`);
  for (const c of d.crm || []) out.push(c.what);
  if (d.capture) out.push('разложил: ' + (d.capture.items || []).map((x: any) => x.line).join('; '));
  return out.join('; ') || 'ничего не менял';
}

function Did({ d }: { d: any }) {
  if (!d) return null;
  const items: React.ReactNode[] = [];
  for (const c of d.changed || []) {
    const t = S.tasks.get(c.id);
    items.push(<button key={'c' + c.id} onClick={() => openTask(c.id)}>{c.after?.title || c.title || t?.title}<div className="meta">{Object.keys(c.after || {}).map((k) => FIELD[k] || k).join(', ')}{c.status ? ' · ' + ({ done: 'сделано', cancelled: 'отменено', open: 'в работу' } as any)[c.status[1]] : ''}</div></button>);
  }
  for (const t of d.tasks || []) items.push(<button key={'t' + t.id} onClick={() => openTask(t.id)}>+ {t.title}<div className="meta">{t.project_name || ''}</div></button>);
  for (const c of d.crm || []) items.push(<div key={'r' + c.what} className="meta" style={{ padding: '2px 6px' }}>{c.what}</div>);
  for (const x of d.capture?.items || []) items.push(<div key={'k' + x.line} className="meta" style={{ padding: '2px 6px' }}>{x.line}</div>);
  for (const r of d.refs || []) {
    const t = S.tasks.get(r.id);
    items.push(<button key={'f' + r.id} onClick={() => openTask(r.id)}>{t?.title || r.title}</button>);
  }
  if (!items.length) return null;
  return <div className="did">{items}</div>;
}

const FIELD: Record<string, string> = { title: 'название', due_date: 'срок', due_time: 'время', focus_on: '«Сейчас»', project_id: 'проект', deal_id: 'сделка',
  person_id: 'человек', ball: 'кто делает', notes: 'заметка', why_id: 'место в дереве', labels: 'метки', remind_at: 'напоминание', priority: 'приоритет', estimate_min: 'оценка' };

function Answers({ d, send }: { d: any; send: (s: string) => void }) {
  if (!d) return null;
  const got = (d.answers || []).map((a: any) => String(a || '').trim()).filter(Boolean).slice(0, 3);
  const list = got.length ? got : /\?\s*$/.test(d.reply || '') ? ['Да', 'Нет'] : [];
  if (!list.length) return null;
  return <div className="answers">{list.map((a: string, i: number) => <button key={a} className={'btn ' + (i === 0 ? 'pri' : 'sec')} style={{ height: 28 }} onClick={() => send(a)}>{a}</button>)}</div>;
}
