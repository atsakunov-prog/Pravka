// Новый веб Дел в виде Todoist (19.10.2026). Сборка — три файла с постоянными именами прямо в static пакета:
// todo.html, todo.js, todo.css. Постоянные имена — потому что сайт-посредник на Aeza пускает к Делам закрытый
// список путей (C:\Bot\Home\aeza\kovcheg.nginx): хеш в имени — новая правка списка на каждую сборку.
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  base: '/static/',
  build: {
    outDir: '../pravka_dela/static',
    emptyOutDir: false,
    target: 'es2022',
    cssCodeSplit: false,
    modulePreload: false,
    rollupOptions: {
      input: 'todo.html',
      output: {
        entryFileNames: 'todo.js',
        assetFileNames: (a) => (a.names?.[0]?.endsWith('.css') ? 'todo.css' : '[name][extname]'),
        inlineDynamicImports: true,
      },
    },
  },
});
