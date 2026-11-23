// Перетаскивание пальцем (23.11.2026, владелец: «доделаем календарь и перетаскивание»). Перетаскивание в /t написано на
// HTML5 drag-and-drop (dragstart / dragover / drop — строки, карточки дерева, дни, сетка календаря, панель), а телефон его
// не даёт: палец листает. Здесь — одна прослойка на весь веб: долгое нажатие (350 мс, палец не сдвинулся) на том, что
// тянется мышью ([draggable=true]), поднимает его, дальше палец рождает те же события drag* на элементе под собой — и все
// цели, написанные для мыши, принимают и палец без второй копии кода. Сдвинул палец раньше — это прокрутка или свайп
// строки (выполнить / срок), прослойка молчит.
// Сверху: копия под пальцем, лёгкая вибрация, автопрокрутка у края списка, «пружина» — задержал палец на вкладке с
// data-spring (нижняя панель: «Круг», «Обзор») — вид открывается, и бросить можно уже туда.
// Без React; ставится один раз (install) из App.

import { setDragId } from './ui/TaskList.tsx';

const HOLD = 350; // мс до подъёма
const SLOP = 10; // пкс: сдвинул раньше — не перетаскивание
const EDGE = 64; // пкс от края списка — автопрокрутка
const SPRING = 650; // мс на вкладке с data-spring — открыть
const LIFT = 28; // пкс: копия чуть выше пальца — видно, куда ляжет (метка часа на сетке, черта в списке)

interface St {
  phase: 'wait' | 'drag';
  src: HTMLElement;
  tgt: EventTarget; // узел, куда браузер шлёт касания этого пальца (слушаем на нём — см. onStart)
  x: number;
  y: number;
  lx: number;
  ly: number;
  timer: number;
  dt: DataTransfer | null;
  ghost: HTMLElement | null;
  over: Element | null;
  accepted: boolean;
  raf: number;
  spring: Element | null;
  springT: number;
}

let st: St | null = null;
let swallowUntil = 0;

/** Идёт ли сейчас перетаскивание пальцем: свайп строки в это время молчит. */
export const touchDragging = () => st?.phase === 'drag';

function fire(type: string, el: Element, x: number, y: number, dt: DataTransfer | null, related: Element | null = null): DragEvent {
  const ev = new DragEvent(type, { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, dataTransfer: dt, relatedTarget: related });
  el.dispatchEvent(ev);
  return ev;
}

function scroller(el: Element | null): HTMLElement {
  for (let n = el as HTMLElement | null; n && n !== document.body; n = n.parentElement) {
    const cs = getComputedStyle(n);
    if (/(auto|scroll)/.test(cs.overflowY) && n.scrollHeight > n.clientHeight + 1) return n;
  }
  return (document.scrollingElement as HTMLElement) || document.documentElement;
}

function spring(el: Element | null) {
  if (!st) return;
  const a = el ? el.closest('[data-spring]') : null;
  if (a === st.spring) return;
  if (st.spring) st.spring.classList.remove('springing');
  clearTimeout(st.springT);
  st.spring = a;
  if (!a) return;
  a.classList.add('springing');
  st.springT = window.setTimeout(() => {
    if (st && st.spring === a) {
      a.classList.remove('springing');
      const to = a.getAttribute('data-spring') || '';
      if (to && location.hash !== to) location.hash = to;
    }
  }, SPRING);
}

function hover(x: number, y: number) {
  if (!st || st.phase !== 'drag') return;
  st.lx = x;
  st.ly = y;
  if (st.ghost) st.ghost.style.transform = `translate(${x - st.x}px, ${y - st.y - LIFT}px) scale(1.03)`;
  const el = document.elementFromPoint(x, y);
  if (el !== st.over) {
    if (st.over) fire('dragleave', st.over, x, y, st.dt, el);
    if (el) fire('dragenter', el, x, y, st.dt, st.over);
    st.over = el;
    spring(el);
  }
  st.accepted = el ? fire('dragover', el, x, y, st.dt).defaultPrevented : false;
}

/** Автопрокрутка, пока палец у края списка (и когда палец стоит). */
function loop() {
  if (!st || st.phase !== 'drag') return;
  const sc = scroller(st.over);
  const r = sc === document.scrollingElement || sc === document.documentElement
    ? { top: 0, bottom: innerHeight } : sc.getBoundingClientRect();
  const top = Math.max(r.top, 0), bottom = Math.min(r.bottom, innerHeight);
  let v = 0;
  if (st.ly < top + EDGE) v = -Math.ceil((top + EDGE - st.ly) / 5);
  else if (st.ly > bottom - EDGE) v = Math.ceil((st.ly - (bottom - EDGE)) / 5);
  if (v) {
    const was = sc.scrollTop;
    sc.scrollTop = was + v;
    if (sc.scrollTop !== was) hover(st.lx, st.ly);
  }
  st.raf = requestAnimationFrame(loop);
}

function begin() {
  if (!st || st.phase !== 'wait') return;
  if (!st.src.isConnected) { forget(); return; }
  const dt = new DataTransfer();
  if (fire('dragstart', st.src, st.x, st.y, dt).defaultPrevented) { forget(); return; }
  st.phase = 'drag';
  st.dt = dt;
  try { navigator.vibrate?.(12); } catch { /* нет вибрации — и ладно */ }
  // Копия — компактная и у пальца: строка списка во всю ширину телефона закрыла бы вкладки внизу и цели рядом.
  const r = st.src.getBoundingClientRect();
  const w = Math.min(r.width, Math.max(160, innerWidth * 0.72));
  const ox = Math.min(st.x - r.left, w * 0.35);
  const oy = Math.min(st.y - r.top, 36);
  const g = st.src.cloneNode(true) as HTMLElement;
  g.removeAttribute('id');
  g.classList.add('tdrag-ghost');
  Object.assign(g.style, {
    position: 'fixed', left: st.x - ox + 'px', top: st.y - oy + 'px', width: w + 'px', height: Math.min(r.height, 120) + 'px', right: 'auto', bottom: 'auto',
    margin: '0', overflow: 'hidden', pointerEvents: 'none', zIndex: '9999', transform: `translateY(${-LIFT}px) scale(1.03)`,
  });
  document.body.appendChild(g);
  st.ghost = g;
  st.src.classList.add('tdrag-src');
  document.documentElement.classList.add('tdragging');
  hover(st.x, st.y);
  st.raf = requestAnimationFrame(loop);
}

/** Снять ожидание или перетаскивание: таймер, слушатели на узле касания. */
function unlisten(s: St) {
  s.tgt.removeEventListener('touchmove', onMove as EventListener);
  s.tgt.removeEventListener('touchend', onEnd as EventListener);
  s.tgt.removeEventListener('touchcancel', onCancel as EventListener);
}

function forget() {
  if (!st) return;
  clearTimeout(st.timer);
  unlisten(st);
  st = null;
}

function finish() {
  if (!st) return;
  unlisten(st);
  clearTimeout(st.timer);
  clearTimeout(st.springT);
  cancelAnimationFrame(st.raf);
  st.ghost?.remove();
  st.src.classList.remove('tdrag-src');
  st.spring?.classList.remove('springing');
  document.documentElement.classList.remove('tdragging');
  // Источник мог уйти из документа (вид перерисовался, «пружина» открыла другой): его dragend до React не дойдёт.
  setDragId(null);
  st = null;
}

function onStart(e: TouchEvent) {
  if (st) finish();
  if (e.touches.length !== 1) return;
  const t = e.target as Element | null;
  if (!t || !t.closest || t.closest('input, textarea, select, [contenteditable="true"], .calw-rs')) return;
  const src = t.closest('[draggable="true"]') as HTMLElement | null;
  if (!src) return;
  const p = e.touches[0];
  // Движение и отпускание слушаем на самом узле касания, а не на документе: браузер шлёт касания пальца туда, где он
  // начался, даже когда узел ушёл со страницы (пружина открыла другой вид, список перерисовался) — до документа такие
  // события уже не всплывают, и копия застыла бы у вкладки (поймано проверкой 23.11.2026).
  const tgt = e.target as EventTarget;
  st = { phase: 'wait', src, tgt, x: p.clientX, y: p.clientY, lx: p.clientX, ly: p.clientY, timer: window.setTimeout(begin, HOLD),
    dt: null, ghost: null, over: null, accepted: false, raf: 0, spring: null, springT: 0 };
  tgt.addEventListener('touchmove', onMove as EventListener, { passive: false });
  tgt.addEventListener('touchend', onEnd as EventListener, { passive: false });
  tgt.addEventListener('touchcancel', onCancel as EventListener);
}

function onMove(e: TouchEvent) {
  if (!st) return;
  const p = e.touches[0];
  if (!p) return;
  if (st.phase === 'wait') {
    if (Math.hypot(p.clientX - st.x, p.clientY - st.y) > SLOP) forget();
    return;
  }
  e.preventDefault(); // палец тащит, а не листает
  hover(p.clientX, p.clientY);
}

function onEnd(e: TouchEvent) {
  if (!st) return;
  if (st.phase === 'wait') { forget(); return; }
  e.preventDefault(); // без «клика» по строке после броска
  swallowUntil = Date.now() + 500;
  const { lx, ly, over, accepted, dt, src } = st;
  if (over && accepted) fire('drop', over, lx, ly, dt);
  else if (over) fire('dragleave', over, lx, ly, dt);
  if (src.isConnected) fire('dragend', src, lx, ly, dt);
  finish();
}

function onCancel() {
  if (!st) return;
  if (st.phase === 'drag') {
    if (st.over) fire('dragleave', st.over, st.lx, st.ly, st.dt);
    if (st.src.isConnected) fire('dragend', st.src, st.lx, st.ly, st.dt);
  }
  finish();
}

let installed = false;

/** Один раз на страницу. Без DataTransfer или DragEvent (очень старый браузер) — ничего: остаётся «Разложить» и меню. */
export function install() {
  if (installed || typeof DataTransfer === 'undefined' || typeof DragEvent === 'undefined') return;
  try { new DataTransfer(); } catch { return; }
  installed = true;
  document.addEventListener('touchstart', onStart, { passive: true, capture: true });
  // Долгое нажатие не открывает меню браузера и не выделяет текст, пока ждём подъёма или тащим.
  document.addEventListener('contextmenu', (e) => { if (st) e.preventDefault(); }, { capture: true });
  // Свой перетаскиватель браузера (Chrome на больших экранах умеет поднимать draggable сам) — не вдвоём с нашим.
  document.addEventListener('dragstart', (e) => { if (st && e.isTrusted) e.preventDefault(); }, { capture: true });
  document.addEventListener('click', (e) => { if (Date.now() < swallowUntil) { e.preventDefault(); e.stopPropagation(); } }, { capture: true });
}
