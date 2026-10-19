// «Быстрое добавление» — настройки строки ввода, как у Todoist: два списка «Показаны» и «Скрыты», чипы перетаскиваются
// между ними и внутри (порядок списка — порядок чипов), «Проект» скрыть нельзя; подписи у чипов; описание всегда открыто.
import { useState, type DragEvent } from 'react';
import { features, FEATURE_NAMES, type Feature, type QuickPrefs } from '../quickprefs.ts';
import { saveTodo, todoSettings, toast } from '../store.ts';
import { Icon } from './icons.tsx';

export function QuickSettingsDialog({ close }: { close: () => void }) {
  const was = (todoSettings() as { quick?: QuickPrefs }).quick || {};
  const [list, setList] = useState(features(was));
  const [labelsShown, setLabelsShown] = useState(was.labels_shown !== false);
  const [descShown, setDescShown] = useState(was.description_shown !== false);
  const [drag, setDrag] = useState<Feature | null>(null);
  // Положить чип name перед before (или в конец списка shown).
  const put = (name: Feature, shown: boolean, before: Feature | null) => {
    if (name === 'project' && !shown) return; // проект скрыть нельзя
    const rest = list.filter((f) => f.name !== name);
    let i = before ? rest.findIndex((f) => f.name === before) : -1;
    if (i < 0) i = shown ? rest.reduce((n, f, k) => (f.shown ? k + 1 : n), 0) : rest.length; // в конец своего списка
    rest.splice(i, 0, { name, shown });
    setList(rest);
  };
  const row = (f: { name: Feature; shown: boolean }) => (
    <div key={f.name} className="mi qs-row" draggable={f.name !== 'project'} onDragStart={() => setDrag(f.name)} onDragEnd={() => setDrag(null)}
      onDragOver={(e: DragEvent) => e.preventDefault()} onDrop={(e: DragEvent) => { e.preventDefault(); e.stopPropagation(); if (drag) put(drag, f.shown, f.name); setDrag(null); }}>
      <Icon name="grip" size={14} style={{ color: 'var(--fg3)', visibility: f.name === 'project' ? 'hidden' : 'visible' }} />
      <span style={{ flex: 1 }}>{FEATURE_NAMES[f.name]}</span>
      {f.name === 'project' ? <span className="r">всегда</span> : (
        <button className="icon-btn sm" title={f.shown ? 'Скрыть' : 'Показать'} onClick={() => put(f.name, !f.shown, null)}><Icon name={f.shown ? 'chevD' : 'chevU'} size={16} /></button>
      )}
    </div>
  );
  const zone = (shown: boolean) => (
    <div className="qs-zone" onDragOver={(e: DragEvent) => e.preventDefault()} onDrop={(e: DragEvent) => { e.preventDefault(); if (drag) put(drag, shown, null); setDrag(null); }}>
      {list.filter((f) => f.shown === shown).map(row)}
      {!list.some((f) => f.shown === shown) ? <div className="mh" style={{ fontWeight: 400 }}>Перетащите сюда</div> : null}
    </div>
  );
  const save = () => {
    const quick: QuickPrefs = { labels_shown: labelsShown, description_shown: descShown, features: list };
    saveTodo({ quick } as never).then(() => toast('Настройки быстрого добавления сохранены', () => saveTodo({ quick: was } as never).catch(() => {}))).catch(() => {});
    close();
  };
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
      <div className="dialog" style={{ width: 380 }}>
        <h3>Быстрое добавление</h3>
        <div className="form">
          <div className="dm-h" style={{ padding: 0 }}>Показаны</div>
          {zone(true)}
          <div className="dm-h" style={{ padding: 0 }}>Скрыты — появятся, только если у них есть значение</div>
          {zone(false)}
          <label className="qs-check"><input type="checkbox" checked={labelsShown} onChange={(e) => setLabelsShown(e.target.checked)} />Показывать подписи у чипов</label>
          <label className="qs-check"><input type="checkbox" checked={descShown} onChange={(e) => setDescShown(e.target.checked)} />Описание всегда открыто (иначе — по ↓)</label>
        </div>
        <div className="foot"><button className="btn sec" onClick={close}>Отмена</button><button className="btn pri" onClick={save}>Сохранить</button></div>
      </div>
    </div>
  );
}
