// «Продуктивность» — окно как у Todoist (опись §12.5): цель дня и недели с сериями, столбики выполненного за 7 дней и
// 4 недели кусками цветов проектов, «N выполненных задач с …», настройки (цели, выходные, отпуск). Считает prod.ts.
import { useEffect, useState } from 'react';
import { api } from '../api.ts';
import { plural4, shortDate, WD_SHORT, weekday } from '../dates.ts';
import { projectColor } from '../lib.ts';
import { PROD_DEFAULT, prodSummary, type ProdDay, type ProdPrefs } from '../prod.ts';
import { S, saveTodo, todoSettings, toast, useStore } from '../store.ts';
import { Icon } from './icons.tsx';

const color = (pid: string) => (pid ? projectColor(S.projects.get(pid)) : 'var(--fg3)');

function Bars({ cols, label }: { cols: { key: string; parts: [string, number][] }[]; label: (k: string) => string }) {
  const max = Math.max(1, ...cols.map((c) => c.parts.reduce((a, [, n]) => a + n, 0)));
  return (
    <div className="prod-bars">
      {cols.map((c) => {
        const n = c.parts.reduce((a, [, x]) => a + x, 0);
        return (
          <div key={c.key} title={c.parts.map(([p, x]) => `${p ? S.projects.get(p)?.name || '…' : 'Входящие'}: ${x}`).join('\n')}>
            <span>{n || ''}</span>
            <div className="stack" style={{ height: Math.round((n / max) * 96) }}>
              {c.parts.map(([p, x]) => <i key={p} style={{ flex: x, background: color(p) }} />)}
            </div>
            <b>{label(c.key)}</b>
          </div>
        );
      })}
    </div>
  );
}

export function ProductivityDialog({ close }: { close: () => void }) {
  useStore();
  const [days, setDays] = useState<ProdDay[] | null>(null);
  const [edit, setEdit] = useState(false);
  const saved = ((todoSettings() as { prod?: ProdPrefs }).prod || {}) as ProdPrefs;
  const [p, setP] = useState<Required<ProdPrefs>>({ ...PROD_DEFAULT, ...saved });
  useEffect(() => {
    api<{ days: ProdDay[] }>('/api/view/productivity').then((v) => setDays(v.days)).catch(() => setDays([]));
  }, []);
  const s = days ? prodSummary(days, S.today, { ...PROD_DEFAULT, ...saved }) : null;
  const save = () => {
    saveTodo({ prod: p } as never).then(() => toast('Цели сохранены', () => saveTodo({ prod: saved } as never).catch(() => {}))).catch(() => {});
    setEdit(false);
  };
  const goal = (title: string, done: number, g: number, st: { current: number; best: number }, unit: [string, string, string]) => (
    <div className="prod-goal">
      <div className="prod-h">{title}</div>
      <div className="prod-num">{done}<span>/{g}</span></div>
      <div className="pstat-bar"><i style={{ width: Math.min(100, Math.round((100 * done) / Math.max(1, g))) + '%' }} /></div>
      <div className="pstat-sub">Серия: {plural4(st.current, ['{n} ' + unit[0], '{n} ' + unit[1], '{n} ' + unit[2], '{n} ' + unit[1]])} · лучшая {st.best}</div>
    </div>
  );
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
      <div className="dialog prod">
        <h3>Продуктивность<button className="icon-btn sm" title="Цели и выходные" onClick={() => setEdit(!edit)} style={{ marginLeft: 'auto' }}><Icon name="gear" size={16} /></button></h3>
        {!s ? <div className="mh">Считаю…</div> : (
          <div className="form">
            <div className="prod-goals">
              {goal('Цель на день', s.today.done, s.today.goal, s.dayStreak, ['день', 'дня', 'дней'])}
              {goal('Цель на неделю', s.week.done, s.week.goal, s.weekStreak, ['неделя', 'недели', 'недель'])}
            </div>
            <div className="prod-h">Последние 7 дней</div>
            <Bars cols={s.last7.map((d) => ({ key: d.day, parts: d.parts }))} label={(k) => WD_SHORT[weekday(k)]} />
            <div className="prod-h">Последние 4 недели</div>
            <Bars cols={s.last4w.map((w) => ({ key: w.week, parts: w.parts }))} label={(k) => shortDate(k, S.today)} />
            <div className="pstat-sub">{s.since ? plural4(s.total, ['{n} выполненная задача', '{n} выполненные задачи', '{n} выполненных задач', '{n} выполненные задачи']) + ' с ' + shortDate(s.since, S.today) : 'Пока ничего не выполнено'}</div>
            {edit ? (
              <div className="prod-edit">
                <label>Цель на день<input type="number" min={1} max={100} value={p.daily} onChange={(e) => setP({ ...p, daily: Math.max(1, +e.target.value || 1) })} /></label>
                <label>Цель на неделю<input type="number" min={1} max={500} value={p.weekly} onChange={(e) => setP({ ...p, weekly: Math.max(1, +e.target.value || 1) })} /></label>
                <div className="prod-h" style={{ fontSize: 12 }}>Выходные — не рвут серию</div>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  {WD_SHORT.map((w, i) => (
                    <label key={w} className="qs-check"><input type="checkbox" checked={p.days_off.includes(i + 1)}
                      onChange={(e) => setP({ ...p, days_off: e.target.checked ? [...p.days_off, i + 1].sort() : p.days_off.filter((x) => x !== i + 1) })} />{w}</label>
                  ))}
                </div>
                <label className="qs-check"><input type="checkbox" checked={p.vacation} onChange={(e) => setP({ ...p, vacation: e.target.checked })} />Режим отпуска — серии не прерываются</label>
              </div>
            ) : null}
          </div>
        )}
        <div className="foot">
          {edit ? <><button className="btn sec" onClick={() => setEdit(false)}>Отмена</button><button className="btn pri" onClick={save}>Сохранить</button></>
            : <button className="btn sec" onClick={close}>Закрыть</button>}
        </div>
      </div>
    </div>
  );
}
