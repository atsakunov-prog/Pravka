import { createRoot } from 'react-dom/client';
import { inApp } from './api.ts';
import './styles.css';
import { App } from './ui/App.tsx';

// Тема: своя (меню «я» → Тема) сильнее; нет своей — общая кука Ковчега kv-theme (её ставит и Правка для страниц внутри
// приложения). KV.setTheme — тот же вызов, что у общего слоя Ковчега: Правка зовёт его, когда меняется тема приложения.
function cookieTheme(): string | null {
  const m = document.cookie.match(/(?:^|;\s*)kv-theme=(light|dark)/);
  return m ? m[1] : null;
}
let own: string | null = null;
try {
  own = localStorage.getItem('todo.theme');
} catch {
  /* без хранилища */
}
const start = own && own !== 'system' ? own : cookieTheme();
if (start) document.documentElement.dataset.theme = start;
(globalThis as any).KV = {
  ...((globalThis as any).KV || {}),
  setTheme(v: string) {
    if (v === 'light' || v === 'dark') document.documentElement.dataset.theme = v;
  },
};
if (inApp()) document.documentElement.classList.add('in-app');

createRoot(document.getElementById('root')!).render(<App />);
