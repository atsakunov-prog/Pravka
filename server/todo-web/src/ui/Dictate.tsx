// «Надиктовать задачи» — наговорка целиком, сервер сам раскладывает: та же дверь, что «сказать» на телефоне (захват,
// capture.say): правило узнало — легло сразу, иначе Claude по промпту разбора надиктовки (Свод prompt.raznoska) с
// корзинами — дело, напоминание, «потом», заметка в хронологию, идея, «сделано». Итог — строками, у каждой «Вернуть».
import { useEffect, useRef, useState } from 'react';
import { api, inApp, uuid } from '../api.ts';
import { log, ops, S, syncSoon, toast } from '../store.ts';
import { Icon } from './icons.tsx';
import { canSpeak, useSpeech } from './speech.ts';
import { openTask } from './state.ts';

interface Item {
  bucket: string;
  line?: string;
  title?: string;
  error?: string;
  undo?: any[];
  task?: { id: string };
}
interface Cap {
  ref: string;
  status: 'none' | 'pending' | 'done' | 'error';
  head?: string;
  items?: Item[];
  error?: string;
}

const BUCKET: Record<string, string> = { now: 'В «Сейчас»', today: 'Сегодня', task: 'Задача', remind: 'Напоминание', back: 'Потом',
  note: 'В хронологию', idea: 'Идея', did: 'Сделано', error: 'Не вышло', cancelled: 'Отменено' };

export function Dictate({ close }: { close: () => void }) {
  const [text, setText] = useState('');
  const [cap, setCap] = useState<Cap | null>(null);
  const [busy, setBusy] = useState(false);
  const [undone, setUndone] = useState<Set<number>>(new Set());
  const base = useRef('');
  const ta = useRef<HTMLTextAreaElement>(null);
  const mic = useSpeech((said) => setText((base.current ? base.current + ' ' : '') + said));
  useEffect(() => {
    if (canSpeak()) {
      base.current = '';
      mic.start();
    }
    ta.current?.focus();
    return () => mic.stop();
  }, []);
  const send = async () => {
    const said = text.trim();
    if (!said || busy) return;
    mic.stop();
    setBusy(true);
    const ref = uuid();
    log('dictate', said);
    try {
      const res = await ops([{ op: 'capture.say', text: said, ref, lane: 'auto', from: 'web' }], { quiet: true, online: true });
      let c: Cap = res[0].capture;
      setCap(c);
      for (let i = 0; c.status === 'pending' && i < 120; i++) {
        await new Promise((r) => setTimeout(r, i < 10 ? 1500 : 3000));
        c = (await api<{ capture: Cap }>('/api/view/capture?ref=' + encodeURIComponent(ref))).capture;
        setCap(c);
      }
      await syncSoon();
      log('dictate-done', c.head || c.status, c.items?.map((x) => x.line));
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), undefined, true);
    } finally {
      setBusy(false);
    }
  };
  const undo = async (i: number) => {
    const it = cap?.items?.[i];
    if (!it?.undo?.length) return;
    await ops(it.undo).catch(() => {});
    setUndone(new Set([...undone, i]));
  };
  const undoAll = async () => {
    const all = (cap?.items || []).flatMap((x, i) => (undone.has(i) ? [] : x.undo || []));
    if (all.length) await ops(all).catch(() => {});
    setUndone(new Set((cap?.items || []).map((_, i) => i)));
  };
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget && !busy) close(); }}>
      <div className="qa" style={{ width: 640 }}>
        <div className="editor" style={{ border: 0, margin: 0 }}>
          <div className="ed-in">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 700, fontSize: 15, marginBottom: 6 }}>
              <Icon name="mic" size={20} style={{ color: 'var(--accent)' }} />Надиктовать задачи
            </div>
            {!cap ? (
              <textarea ref={ta} className="ed-desc" style={{ fontSize: 15, lineHeight: '22px', minHeight: 120 }} value={text}
                placeholder={canSpeak() ? 'Говори — пишу. Сколько угодно дел подряд, с паузами: «позвонить Ивану завтра, Ольге — счёт Альфе к пятнице, купить магний…»'
                  : inApp() ? 'Нажми микрофон на клавиатуре и говори сколько угодно дел подряд — разложу сам'
                    : 'Пиши или диктуй Диктовкой (Ctrl+Shift+Space): сколько угодно дел подряд — разложу сам'}
                onChange={(e) => { setText(e.target.value); base.current = e.target.value; }}
                onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); } if (e.key === 'Escape') close(); }} />
            ) : (
              <div style={{ padding: '4px 0 8px' }}>
                <div style={{ color: 'var(--fg2)', fontSize: 13, marginBottom: 8, whiteSpace: 'pre-wrap' }}>«{text}»</div>
                {cap.status === 'pending' ? <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}><div className="think"><i /><i /><i /></div>{cap.head || 'Раскладываю…'}</div> : null}
                {cap.status === 'error' ? <div style={{ color: 'var(--p1)' }}>Не разложил: {cap.error || 'ошибка'} — текст сохранён, попробуй ещё раз.</div> : null}
                {cap.status === 'done' && cap.head ? <div style={{ fontWeight: 600, marginBottom: 6 }}>{cap.head}</div> : null}
                {(cap.items || []).map((it, i) => (
                  <div key={i} className="turn" style={{ display: 'flex', gap: 8, alignItems: 'flex-start', opacity: undone.has(i) ? 0.45 : 1 }}>
                    <span className="chip" style={{ height: 22, fontSize: 11, flex: 'none' }}>{BUCKET[it.bucket] || it.bucket}</span>
                    <button style={{ flex: 1, textAlign: 'left', fontSize: 14, lineHeight: '20px' }} onClick={() => { if (it.task?.id && S.tasks.has(it.task.id)) { close(); openTask(it.task.id); } }}>
                      {it.line || it.title}{it.error ? <span style={{ color: 'var(--p1)' }}> — {it.error}</span> : null}
                    </button>
                    {it.undo?.length ? (undone.has(i) ? <span style={{ fontSize: 12, color: 'var(--fg3)' }}>вернул</span>
                      : <button className="btn ghost" style={{ height: 24, fontSize: 12 }} onClick={() => undo(i)}>Вернуть</button>) : null}
                  </div>
                ))}
              </div>
            )}
          </div>
          <div className="ed-foot">
            {!cap ? (
              canSpeak() ? (
                <button className={'btn ' + (mic.on ? 'pri' : 'sec')} onClick={() => { base.current = text; mic.toggle(); }}>
                  <Icon name={mic.on ? 'stop' : 'mic'} size={16} />{mic.on ? 'Стоп' : 'Микрофон'}
                </button>
              ) : <span style={{ fontSize: 12, color: 'var(--fg3)' }}>Ctrl+Enter — разложить</span>
            ) : (cap.items || []).some((x) => x.undo?.length) && undone.size < (cap.items || []).length ? (
              <button className="btn ghost" onClick={undoAll}>Вернуть всё</button>
            ) : <span />}
            <span style={{ display: 'flex', gap: 8 }}>
              {!cap ? (
                <>
                  <button className="btn sec" onClick={close}>Отмена</button>
                  <button className="btn pri" disabled={!text.trim() || busy} onClick={send}><Icon name="spark" size={16} />Разложить</button>
                </>
              ) : (
                <>
                  <button className="btn sec" disabled={busy} onClick={() => { setCap(null); setText(''); base.current = ''; setUndone(new Set()); }}>Ещё</button>
                  <button className="btn pri" onClick={close}>Готово</button>
                </>
              )}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
