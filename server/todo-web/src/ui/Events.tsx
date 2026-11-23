// Встречи календаря Google в списках (23.11.2026): над делами дня в «Сегодня» и в каждом дне «Предстоящего» — как
// Todoist с подключённым календарём. Строка: время, полоска, название, место; «Войти» — сразу в созвон; клик — событие
// в Google (править там: правда календаря — Google). Прошедшее сегодня — бледнее, идущее — светится.
import { CAL, dayEvents, soonEvent } from '../cal.ts';
import { phase, timeWords, type CalEvent } from '../calev.ts';
import { nowHm, plural } from '../dates.ts';
import { cls } from '../lib.ts';
import { S, useStore } from '../store.ts';
import { Icon } from './icons.tsx';

const isUrl = (s: string) => /^https?:\/\//i.test(s.trim());
const openEvent = (url: string) => { if (url) window.open(url, '_blank', 'noopener'); };

function EventRow({ e, day, now }: { e: CalEvent; day: string; now: string }) {
  const ph = phase(e, day, S.today, now);
  const place = e.location && !isUrl(e.location) ? e.location : '';
  return (
    <div className={cls('ev-row', ph, !e.busy && 'free')} role="button" tabIndex={0} onClick={() => openEvent(e.url)}
      title={e.url ? 'Открыть в Google Календаре' : e.title}>
      <span className="ev-time">{timeWords(e, day)}</span>
      <i className="ev-bar" />
      <span className="ev-body">
        <span className="ev-ttl">{e.title}</span>
        {place ? <span className="ev-loc">{place}</span> : null}
      </span>
      {e.link && ph !== 'past' ? (
        <a className="ev-join" href={e.link} target="_blank" rel="noopener" onClick={(x) => x.stopPropagation()}><Icon name="video" size={14} />Войти</a>
      ) : null}
    </div>
  );
}

/** Встречи дня строками; прошедших сегодня больше трёх — свёрнуты в одну строку «ещё N прошло». */
export function DayEvents({ day }: { day: string }) {
  useStore();
  const list = dayEvents(day);
  if (!list.length) return null;
  const now = nowHm();
  const past = day === S.today ? list.filter((e) => phase(e, day, S.today, now) === 'past') : [];
  const fold = past.length > 3;
  const shown = fold ? list.filter((e) => !past.includes(e)) : list;
  return (
    <div className="evs" aria-label="Встречи календаря">
      {fold ? <div className="ev-fold"><Icon name="calendar" size={13} />{plural(past.length, 'встреча', 'встречи', 'встреч')} уже прошли</div> : null}
      {shown.map((e) => <EventRow key={e.id + day} e={e} day={day} now={now} />)}
    </div>
  );
}

/** Подсказка над «Сейчас» и «Сегодня»: встреча идёт или начнётся в ближайшие три часа. */
export function SoonNudge() {
  useStore();
  const s = soonEvent(nowHm());
  if (!s) return null;
  const { e, inMin } = s;
  const when = inMin === 0 ? 'Идёт сейчас' : inMin < 60 ? `Через ${inMin} мин` : `В ${e.start}`;
  return (
    <div className={cls('nudge n-cal', inMin === 0 && 'live')} role="button" tabIndex={0} onClick={() => openEvent(e.url)}>
      <Icon name="calendar" size={18} />
      <span><b>{when}: {e.title}</b><small>{timeWords(e, S.today)}{e.location && !isUrl(e.location) ? ' · ' + e.location : ''}</small></span>
      {e.link ? <a className="ev-join" href={e.link} target="_blank" rel="noopener" onClick={(x) => x.stopPropagation()}><Icon name="video" size={14} />Войти</a> : <Icon name="chevR" size={16} />}
    </div>
  );
}

/** Календарь давно не обновлялся (служба встреч молчит дольше получаса) — честно сказать. */
export function CalStale() {
  useStore();
  if (!CAL.sync?.stale) return null;
  return <div className="cal-stale"><Icon name="alarm" size={14} />Календарь давно не обновлялся — встречи могут быть не все</div>;
}
