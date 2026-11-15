// Полоса дня над «Сейчас» и «Сегодня» (16.11.2026, владелец: «там была у нас сверху несколько плашек с едой, деньгами и
// т. п. в разложенном и сложенном, но при этом ещё была линия, которая внизу показывала день. И я что-то скучаю»).
// Как «Сегодня» Правки 4.0, только в виде «Ночь»: четыре плашки — Дела, Спорт, Еда, Деньги — и под ними линия суток
// из ленты Засечки (цвет — категория, как core/CategoryTone.kt и twins.js; «не размечено» — штриховкой; «сейчас» —
// чертой), справа — очки дня. Нажатие на линию — страница «Сегодня» прежнего веба (#/day: гора дня и вся лента).
// Данные — вид day (twins.py, только владельцу): тот же, что у #/day. Нет права или нет архива — полосы нет, список
// живёт как раньше. Дела считает сам по своей копии синка: закрыто сегодня и сколько ещё со сроком на сегодня.
import { useEffect, useState } from 'react';
import { api } from '../api.ts';
import { S, useStore } from '../store.ts';
import { dayList } from '../views.ts';
import { Icon } from './icons.tsx';

type Row = Record<string, any>;
interface Day { day: string; today: string; now: string; entries: Row[]; categories: Row[]; tiles: Row; archive?: { ok: boolean } }

// ── Цвет категории — core/CategoryTone.kt (палитра --sp0…7): по верхнему уровню имени ──
const FIXED: Record<string, number> = { 'работа': 0, 'быт': 1, 'спорт': 2, 'семья': 3, 'отдых': 4, 'систематизация': 5, 'еда': 6, 'сон': 7 };
const top = (c: unknown) => String(c || '').split(':')[0].trim().toLowerCase();
function javaHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return h;
}
/** Класс отрезка: c0…c7, пополам с фоном (дорога, потери) — h6/h7, «не размечено» — gap, сон — sl. */
export function toneClass(category: unknown, gap: boolean): string {
  const t = top(category);
  if (gap || t === 'не размечено') return 'gap';
  if (t === 'сон') return 'sl';
  if (!t) return 'c7';
  if (t === 'передвижение' || t === 'дорога') return 'h7';
  if (t === 'потери') return 'h6';
  if (t in FIXED) return 'c' + FIXED[t];
  return 'c' + (((javaHash(t) % 7) + 7) % 7);
}

const hmMin = (s: unknown): number | null => {
  const m = /^(\d{1,2}):(\d{2})/.exec(String(s || ''));
  return m ? +m[1] * 60 + +m[2] : null;
};

interface Seg { from: number; to: number; cls: string; title: string; category: string }
/** Отрезки суток day из записей ленты (вчерашние — головой ночного сна), очки и «сейчас» — как twins.js spans/profile. */
export function daySegments(d: Pick<Day, 'day' | 'today' | 'now' | 'entries' | 'categories'>): { segs: Seg[]; points: number; nowMin: number | null } {
  const worth = new Map((d.categories || []).map((c) => [String(c.name || '').trim().toLowerCase(), +c.value_per_hour || 0]));
  const nowHm = /T(\d{2}):(\d{2})/.exec(d.now || '');
  const nowMin = d.day === d.today && nowHm ? +nowHm[1] * 60 + +nowHm[2] : null;
  const cut = nowMin === null ? 1440 : nowMin;
  const segs: Seg[] = [];
  let points = 0;
  for (const e of d.entries || []) {
    const shift = e.day === d.day ? 0 : e.day < d.day ? -1440 : null;
    if (shift === null) continue;
    const from = hmMin(e.start_local);
    if (from === null) continue;
    let to: number;
    if (e.open) to = e.day === d.day && nowMin !== null ? Math.max(from, nowMin) : from + (+e.minutes || 0);
    else {
      const end = hmMin(e.end_local);
      to = end === null ? from + (+e.minutes || 0) : end < from || (end === from && +e.minutes > 0) ? end + 1440 : end;
    }
    const a = Math.max(from + shift, 0), b = Math.min(to + shift, e.open ? cut : 1440);
    if (b <= a) continue;
    const gap = e.source === 'gap' || top(e.category) === 'не размечено';
    segs.push({ from: a, to: b, cls: toneClass(e.category, gap), title: e.title || '', category: e.category || '' });
    points += (worth.get(String(e.category || '').trim().toLowerCase()) || 0) * (Math.min(b, cut) - a) / 60;
  }
  segs.sort((x, y) => x.from - y.from);
  return { segs, points, nowMin };
}

const rub = (kop: number) => Math.round(Math.abs(kop) / 100).toLocaleString('ru-RU').replace(/\s/g, ' ');
/** Крупное — тысячами: «158 тыс» (плашка узкая, на сложенном телефоне — около 85 px). */
const thousands = (kop: number) => { const r = Math.abs(kop) / 100; return r >= 10000 ? Math.round(r / 1000).toLocaleString('ru-RU') + ' тыс' : rub(kop); };
const pts = (v: number) => { const r = Math.round(v); return r > 0 ? '+' + r : r < 0 ? '−' + -r : '0'; };
const dur = (m: number) => { const h = Math.floor(m / 60), mm = Math.round(m % 60); return h ? (mm ? `${h} ч ${mm} м` : `${h} ч`) : `${mm} м`; };

export function DayStrip() {
  useStore();
  const [d, setD] = useState<Day | null>(null);
  const [off, setOff] = useState(false);
  useEffect(() => {
    let live = true;
    const load = () => {
      if (document.hidden) return;
      api<Day>('/api/view/day').then((x) => { if (live) { setD(x); setOff(false); } }).catch((e) => {
        // нет права (не владелец) или нет архива — полосы нет; сеть пропала — оставляем последнюю
        if (live && e && e.status && e.status !== 0) setOff(true);
      });
    };
    load();
    const timer = setInterval(load, 60000);
    document.addEventListener('visibilitychange', load);
    return () => { live = false; clearInterval(timer); document.removeEventListener('visibilitychange', load); };
  }, []);
  if (off || !d || !d.tiles) return null;

  const t = d.tiles;
  const today = S.today || d.today;
  const doneToday = [...S.tasks.values()].filter((x) => x.status === 'done' && !x.goal && (x.completed_at || '').slice(0, 10) === today).length;
  const leftToday = dayList(today).length;
  const { segs, points, nowMin } = daySegments(d);
  const unmarked = segs.filter((s) => s.cls === 'gap').reduce((n, s) => n + s.to - s.from, 0);
  const kcalPct = t.kcal_goal ? Math.min(100, Math.round((t.kcal / t.kcal_goal) * 100)) : 0;
  const tiles = [
    { k: 'dela', icon: 'done', name: 'Дела', val: String(doneToday), sub: leftToday ? `из ${doneToday + leftToday}` : 'закрыто', href: '#/today',
      pct: doneToday + leftToday ? Math.round((doneToday / (doneToday + leftToday)) * 100) : null },
    { k: 'sport', icon: 'activity', name: 'Спорт', val: t.sport_min ? `${t.sport_min} м` : '—',
      sub: t.sport_min ? t.sport_name || 'тренировка' : t.sleep_h ? `сон ${String(t.sleep_h).replace('.', ',')} ч` : 'тренировок нет', href: '/#/day', pct: null },
    { k: 'eda', icon: 'food', name: 'Еда', val: t.kcal ? t.kcal.toLocaleString('ru-RU').replace(/\s/g, ' ') : '—',
      sub: t.kcal_goal ? `из ${t.kcal_goal.toLocaleString('ru-RU').replace(/\s/g, ' ')} ккал` : t.kcal ? 'ккал' : 'не записано', href: '/#/day',
      pct: t.kcal_goal ? kcalPct : null },
    { k: 'dengi', icon: 'ruble', name: 'Деньги', val: t.money_day_kop ? '−' + rub(t.money_day_kop) : '0',
      sub: t.money_month_kop ? `−${thousands(t.money_month_kop)}/мес` : 'сегодня', href: '/#/day', pct: null },
  ];
  const hours = [0, 6, 12, 18, 24];
  return (
    <section className="daystrip" aria-label="День: плашки и линия суток">
      <div className="ds-tiles">
        {tiles.map((x) => (
          <a key={x.k} className={'ds-tile ds-' + x.k} href={x.href}>
            <span className="ds-l"><Icon name={x.icon} size={14} sw={2} />{x.name}</span>
            <b className="ds-v">{x.val}</b>
            <span className="ds-s">{x.sub}</span>
            {x.pct !== null ? <i className="ds-bar"><i style={{ width: x.pct + '%' }} /></i> : null}
          </a>
        ))}
      </div>
      <a className="ds-day" href="/#/day" title="Открыть «Сегодня»: гора дня и вся лента">
        <span className="ds-dh">
          <b>День</b>
          <span>{unmarked >= 30 ? `не размечено ${dur(unmarked)}` : segs.length ? 'лента Засечки' : 'с утра ничего не записано'}</span>
          <em className={points >= 0 ? 'up' : 'down'}>{pts(points)}</em>
        </span>
        <span className="ds-rib">
          {segs.map((s, i) => (
            <i key={i} className={'ds-seg ' + s.cls} title={`${s.title || s.category} · ${dur(s.to - s.from)}`}
              style={{ left: (s.from / 14.4).toFixed(3) + '%', width: ((s.to - s.from) / 14.4).toFixed(3) + '%' }} />
          ))}
          {nowMin !== null ? <i className="ds-now" style={{ left: (nowMin / 14.4).toFixed(3) + '%' }} /> : null}
        </span>
        <span className="ds-ticks">{hours.map((h) => <span key={h}>{String(h).padStart(2, '0')}</span>)}</span>
      </a>
    </section>
  );
}
