// «Что не так?» — строка записок о багах вверху (режим отладки Дел, debug.py; правило 28 карты Дел). Записка уходит
// операцией debug.add со снимком экрана ДАННЫМИ (вид, заголовок, строки по порядку, что в окне, выбранное, открытое окно
// задачи, разговор Claude) и последними действиями страницы. Статус у новой — «новое»: сразу в очередь разбора.
import { useState } from 'react';
import { uuid } from '../api.ts';
import { log, op1, S, toast, trail, useStore } from '../store.ts';
import { Icon } from './icons.tsx';
import { UI } from './state.ts';

function snapshot(): Record<string, unknown> {
  const main = document.querySelector('.main');
  const box = main?.getBoundingClientRect();
  const rows = [...document.querySelectorAll('.main [data-id]')].slice(0, 120).map((el) => {
    const id = (el as HTMLElement).dataset.id!;
    const t = S.tasks.get(id);
    const b = el.getBoundingClientRect();
    return {
      id, num: t?.num, title: t?.title || el.textContent?.slice(0, 120),
      in_view: !!box && b.top >= box.top - 4 && b.bottom <= Math.min(box.bottom, innerHeight) + 4,
      meta: el.querySelector('.tmeta')?.textContent?.slice(0, 160) || undefined,
    };
  });
  const open = UI.task ? S.tasks.get(UI.task) : null;
  return {
    v: 1, app: 'todo-web', route: location.hash || '#/now', kind: location.hash.replace(/^#\/?/, '').split('/')[0] || 'now',
    title: document.querySelector('.main .view-h')?.textContent || '',
    sub: document.querySelector('.main .view-sub')?.textContent || undefined,
    rows: rows.length ? rows : undefined,
    selected: UI.sel.size ? [...UI.sel].map((id) => ({ id, title: S.tasks.get(id)?.title })) : undefined,
    card: open ? { id: open.id, num: open.num, title: open.title, project: open.project_name, due: open.due_date, priority: open.priority } : undefined,
    claude: [...document.querySelectorAll('.claude-feed .turn')].slice(-3).map((x) => x.textContent?.slice(0, 400)),
    width: innerWidth,
  };
}

export function BugLine() {
  useStore();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  if (!S.debug?.on) return null;
  const send = async () => {
    const said = text.trim();
    if (!said || busy) return;
    setBusy(true);
    try {
      const r = await op1({ op: 'debug.add', note: { id: uuid(), text: said, source: 'web', version: 'todo-web', screen: location.hash || '#/now',
        snapshot: snapshot(), actions: trail.slice(-30) } });
      setText('');
      log('debug', said);
      toast(r.queued ? 'Записка с экраном уйдёт, когда появится сеть' : `Записка №${r.note?.num ?? '?'} — в очереди, с экраном`);
    } catch {
      /* тост ошибки уже показан */
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="bugline">
      <Icon name="bug" size={16} />
      <input value={text} placeholder="Что не так? — запишу с тем, что на экране" onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); send(); } if (e.key === 'Escape') (e.target as HTMLInputElement).blur(); }} />
      {S.debug.new ? <a href="/#/debug" target="_blank" rel="noreferrer" title="Записки в очереди">{S.debug.new} в очереди</a> : null}
    </div>
  );
}
