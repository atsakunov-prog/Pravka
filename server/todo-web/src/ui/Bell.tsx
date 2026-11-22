// Колокольчик — «Новое» прежних Дел в рамке Todoist (у него так живут уведомления): вопросы автоматики с кнопками
// («Подскажи»), что она завела сама за три дня и что закрыла или уточнила сама — с «Вернуть». Автоматика встреч и
// Telegram работает как раньше (слово Саши 19.10.2026: «как сейчас»).
import { shortDate } from '../dates.ts';
import { ops, S, toast, useStore } from '../store.ts';
import type { Suggestion, Task } from '../types.ts';
import { isOpen } from '../views.ts';
import { Icon } from './icons.tsx';
import { openTask } from './state.ts';
import { Page } from './Views.tsx';
import { DirDot } from './Krug.tsx';
import { dirColor, dirShort } from '../krug.ts';

const AUTO_REASONS = ['закрыто само', 'уточнено само', 'человек заведён сам', 'спикер узнан сам'];
const SRC: Record<string, string> = { meeting: 'встреча', telegram: 'Telegram', userbot: 'Telegram', phone: 'телефон', bot: 'бот', mcp: 'Claude', user: 'вручную',
  krug: 'дерево', contacts: 'телефонная книжка', plan: 'план' };
const fromLine = (s: Suggestion) => {
  const src = SRC[s.source] || s.source;
  const b = s.batch_title || '';
  return b.toLowerCase().startsWith(src.toLowerCase()) ? b : [src, b].filter(Boolean).join(' · ');
};

const pending = (): Suggestion[] => [...S.sugs.values()]
  .filter((s) => s.status === 'pending' && s.for_user === S.me?.user && s.source !== 'plan')
  .sort((a, b) => b.created_at.localeCompare(a.created_at));
const autoDone = (days = 7): Suggestion[] => [...S.sugs.values()]
  .filter((s) => s.status === 'accepted' && AUTO_REASONS.includes(s.reason || '') && !s.seen_at && s.for_user === S.me?.user
    && s.decided_at && Date.now() - Date.parse(s.decided_at) < days * 864e5)
  .sort((a, b) => (b.decided_at || '').localeCompare(a.decided_at || ''));
const autoNew = (days = 3): Task[] => {
  const hidden = new Set<string>((S.me?.settings?.feed_hidden as string[]) || []);
  return [...S.tasks.values()].filter((t) => isOpen(t) && !t.goal && t.owner_id === S.me?.user && ['meeting', 'telegram', 'mcp', 'bot'].includes(t.source)
    && Date.now() - Date.parse(t.created_at) < days * 864e5 && !hidden.has(t.id)).sort((a, b) => b.created_at.localeCompare(a.created_at));
};

export const bellCount = () => pending().length;

function sugText(s: Suggestion): string {
  const p = s.payload || {};
  if (p.ask) return p.ask;
  const task = s.task_id ? S.tasks.get(s.task_id) : null;
  if (s.kind === 'create') return 'Завести: ' + (p.title || '…');
  if (s.kind === 'close') return 'Закрыть: ' + (task?.title || p.title || '…');
  if (s.kind === 'update') return 'Поправить: ' + (task?.title || '…') + (p.title && p.title !== task?.title ? ' → ' + p.title : '') + (p.due_date ? ', срок ' + shortDate(p.due_date, S.today) : '');
  if (s.kind === 'person') return 'Человек: ' + (p.title || p.person?.name || '…');
  return p.title || s.kind;
}

export function Bell({ close }: { close: () => void }) {
  return (
    <div>
      <div className="bell-h">Новое<span style={{ display: 'flex', gap: 2 }}>
        <a className="icon-btn sm" href="#/new" title="Открыть страницей" onClick={close}><Icon name="chevR" size={18} /></a>
        <button className="icon-btn sm" onClick={close}><Icon name="x" size={18} /></button></span></div>
      <BellBody close={close} />
    </div>
  );
}

/** «Новое» страницей (#/new; ссылки «Разобрать» из Telegram ведут сюда — #/new/<пачка>): то же, что в колокольчике. */
export function NewView({ batch }: { batch?: string }) {
  useStore();
  const n = bellCount();
  return (
    <Page title="Новое" icon={<Icon name="bell" size={26} style={{ color: 'var(--accent)' }} />} crumbs="Новое"
      sub={<span>{n ? `${n} — ждут твоего слова. Остальное автоматика сделала сама: можно вернуть.` : 'Вопросов нет. Ниже — что автоматика сделала сама.'}</span>}>
      <div className="new-page"><BellBody close={() => {}} batch={batch} /></div>
    </Page>
  );
}

/** Вопросы автоматики, поставленное и сделанное само — общее для колокольчика и страницы. */
export function BellBody({ close, batch }: { close: () => void; batch?: string }) {
  useStore();
  // Пачка из ссылки Telegram — её вопросы первыми.
  const asks = batch ? [...pending()].sort((a, b) => (b.batch_ref === batch ? 1 : 0) - (a.batch_ref === batch ? 1 : 0)) : pending();
  const done = autoDone();
  const fresh = autoNew();
  const decide = (s: Suggestion, extra: Record<string, unknown>) => ops([{ op: 'suggestion.decide', id: s.id, ...extra }]).catch(() => {});
  return (
    <div>
      {asks.length ? (
        <div className="bell-g">
          <div className="bell-gh"><Icon name="comment" size={14} />Подскажи · {asks.length}</div>
          {asks.slice(0, 30).map((s) => {
            const opts: any[] = Array.isArray(s.payload?.options) ? s.payload.options : [];
            return (
              <div key={s.id} className="sug">
                <div className="q">{sugText(s)}</div>
                {s.payload?.ask && s.payload?.title ? <div className="from">{s.payload.title}</div> : null}
                <div className="from">{fromLine(s)}</div>
                {s.quote ? <div className="quote">{s.quote}</div> : null}
                {s.source === 'krug' && !opts.length && S.dirs.length ? (
                  // Вопрос разборщика дерева (как в прежнем вебе): все направления кнопками, предложенное — светится;
                  // выбрал другое — ответ с правкой, её разборщик возьмёт примером на будущее (place.learned).
                  <div className="btns kr-ask">
                    {s.payload?.why_id && S.tasks.get(s.payload.why_id) ? (
                      <button className="btn pri" onClick={() => decide(s, { decision: 'accept' })}>Под цель: {S.tasks.get(s.payload.why_id)!.title}</button>
                    ) : null}
                    {S.dirs.map((d) => {
                      const sug = ((s.payload?.directions as string[]) || []).includes(d.key) && !s.payload?.why_id;
                      return (
                        <button key={d.key} className={'pp-dir' + (sug ? ' on' : '')} style={{ '--dc': dirColor(d.key) } as React.CSSProperties}
                          onClick={() => decide(s, sug && ((s.payload?.directions as string[]) || []).length === 1 ? { decision: 'accept' } : { decision: 'accept', set: { directions: [d.key], why_id: null } })}>
                          <DirDot k={d.key} />{dirShort(d.key)}
                        </button>
                      );
                    })}
                    <button className="btn sec" onClick={() => decide(s, { decision: 'reject' })}>Не надо</button>
                  </div>
                ) : (
                <div className="btns">
                  {opts.length ? opts.map((o, i) => <button key={i} className={'btn ' + (i === 0 ? 'pri' : 'sec')} onClick={() => decide(s, { option: i })}>{o.label}</button>) : (
                    <>
                      <button className="btn pri" onClick={() => decide(s, { decision: 'accept' })}>{s.kind === 'close' ? 'Закрыть' : s.kind === 'update' ? 'Поправить' : 'Поставить'}</button>
                      <button className="btn sec" onClick={() => decide(s, { decision: 'reject' })}>Не надо</button>
                    </>
                  )}
                </div>
                )}
              </div>
            );
          })}
        </div>
      ) : null}
      {fresh.length ? (
        <div className="bell-g">
          <div className="bell-gh"><Icon name="plus" size={14} />Поставлено само · {fresh.length}</div>
          {fresh.slice(0, 20).map((t) => (
            <div key={t.id} className="sug" style={{ cursor: 'pointer' }} onClick={() => { close(); openTask(t.id); }}>
              <div className="q">{t.title}</div>
              <div className="from">{SRC[t.source] || t.source} · {shortDate(t.created_at.slice(0, 10), S.today)}{t.project_name ? ' · ' + t.project_name : ''}</div>
            </div>
          ))}
          <div className="btns" style={{ marginTop: 6 }}>
            <button className="btn ghost" onClick={() => {
              const hidden = [...new Set([...((S.me?.settings?.feed_hidden as string[]) || []), ...fresh.map((t) => t.id)])].slice(-500);
              ops([{ op: 'user.settings', settings: { feed_hidden: hidden } }]).then(() => { if (S.me) S.me.settings = { ...S.me.settings, feed_hidden: hidden }; toast('Понятно — убрал'); }).catch(() => {});
            }}>Понятно</button>
          </div>
        </div>
      ) : null}
      {done.length ? (
        <div className="bell-g">
          <div className="bell-gh"><Icon name="done" size={14} />Сделано само · {done.length}</div>
          {done.map((s) => (
            <div key={s.id} className="sug">
              <div className="q">{s.reason === 'закрыто само' ? 'Закрыл: ' : s.reason === 'уточнено само' ? 'Уточнил: ' : ''}{(s.task_id && S.tasks.get(s.task_id)?.title) || s.payload?.title || ''}</div>
              <div className="from">{fromLine(s)}</div>
              {s.quote ? <div className="quote">{s.quote}</div> : null}
              <div className="btns">
                <button className="btn sec" onClick={() => ops([{ op: 'suggestion.undo', id: s.id }]).then(() => toast('Вернул как было')).catch(() => {})}>Вернуть</button>
                <button className="btn ghost" onClick={() => ops([{ op: 'suggestion.seen', ids: [s.id] }]).catch(() => {})}>Понятно</button>
              </div>
            </div>
          ))}
        </div>
      ) : null}
      {!asks.length && !done.length && !fresh.length ? <div className="empty" style={{ padding: 32 }}><Icon name="bell" size={40} sw={1} /><div>Ничего нового</div></div> : null}
    </div>
  );
}
