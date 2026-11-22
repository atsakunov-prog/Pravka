import { createRoot } from 'react-dom/client';
import { inApp } from './api.ts';
import { applyLook, watchLook } from './look.ts';
import './styles.css';
import './glow.css';
import { App } from './ui/App.tsx';

// Тема — look.ts (16.11.2026: «Ночь» по умолчанию). KV.setTheme зовёт приложение Правки (ServiceWeb.themeScript), когда
// меняется тема телефона: здесь он ничего не меняет, иначе светлый телефон перебил бы ночь; функция есть — приложение
// страницу не перечитывает.
applyLook();
watchLook();
(globalThis as any).KV = {
  ...((globalThis as any).KV || {}),
  setTheme(_v: string) {
    /* тема телефона ночь не перебивает */
  },
};
if (inApp()) document.documentElement.classList.add('in-app');

// Каркас /t без сети (static/todo-sw.js, 19.10.2026): страница и сборка в кэше браузера — Дела открываются и без
// интернета, данные — из своей копии в IndexedDB (store.boot). Воркер отдаётся адресом /t?sw=1 (закрытый список путей
// сайта-посредника); встаёт только на https и 127.0.0.1 — на http по сети браузер его не пустит, и это не ошибка.
if ('serviceWorker' in navigator && location.pathname === '/t') {
  navigator.serviceWorker.register('/t?sw=1', { scope: '/t' }).catch(() => {});
}

createRoot(document.getElementById('root')!).render(<App />);
