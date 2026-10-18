// Всплывающие меню: одно на экран (как у Todoist), открывается от кнопки и прилипает к ней, не вылезая за край.
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { emit, useStore } from '../store.ts';

interface PopState {
  anchor: DOMRect;
  render: (close: () => void) => ReactNode;
  cls?: string;
  onClose?: () => void;
}
let cur: PopState | null = null;

export function openPop(el: Element | DOMRect, render: (close: () => void) => ReactNode, opts: { cls?: string; onClose?: () => void } = {}) {
  const anchor = el instanceof Element ? el.getBoundingClientRect() : el;
  cur = { anchor, render, ...opts };
  emit();
}
export function closePop() {
  const was = cur;
  cur = null;
  emit();
  was?.onClose?.();
}
export const popOpen = () => !!cur;

export function PopHost() {
  useStore();
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);
  const st = cur;
  useLayoutEffect(() => {
    if (!st || !ref.current) {
      setPos(null);
      return;
    }
    const r = ref.current.getBoundingClientRect();
    const a = st.anchor;
    let left = Math.min(a.left, innerWidth - r.width - 8);
    left = Math.max(8, left);
    let top = a.bottom + 4;
    if (top + r.height > innerHeight - 8) top = Math.max(8, a.top - r.height - 4);
    setPos({ left, top });
  }, [st, st?.anchor]);
  if (!st) return null;
  return (
    <>
      <div className="pop-scrim" onMouseDown={closePop} />
      <div ref={ref} className={'pop ' + (st.cls || '')} style={pos ? { left: pos.left, top: pos.top } : { left: -9999, top: 0 }}
        onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); closePop(); } }}>
        {st.render(closePop)}
      </div>
    </>
  );
}
