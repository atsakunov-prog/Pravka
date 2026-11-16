// Тема /t (16.11.2026 — «Ночь» по умолчанию, общий вид Ковчега версии 3, вариант Б холста «Ковчег ночью»). Своя (меню
// «я» → Тема, todo.look) сильнее; нет своей — общая кука Ковчега kv-look («light», «auto» или пусто = ночь); нет и её —
// ночь. Прежние todo.theme и kv-theme не читаем нарочно: в них мог остаться давний выбор «светлая», а kv-theme ещё и
// ставит приложение Правки по теме телефона.

const dark = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: dark)') : null;

/** Выбор: 'dark' (ночь), 'light' или 'system' (как в системе). */
export function lookChoice(): string {
  let own: string | null = null;
  try {
    own = localStorage.getItem('todo.look');
  } catch {
    /* без хранилища */
  }
  if (own === 'light' || own === 'dark' || own === 'system') return own;
  const m = document.cookie.match(/(?:^|;\s*)kv-look=(light|auto)/);
  return m ? (m[1] === 'auto' ? 'system' : 'light') : 'dark';
}

/** <html data-theme> — всегда, даже у ночи: стили смотрят только на него. */
export function applyLook(): void {
  const c = lookChoice();
  const t = c === 'system' ? (dark && !dark.matches ? 'light' : 'dark') : c;
  document.documentElement.dataset.theme = t;
  // Полоса браузера (Chrome на телефоне) — в цвет страницы: иначе над ночной /t висит светлая шапка (16.11.2026).
  // Остальной Ковчег делает то же в kovcheg.js; в приложении Правки полоса своя, там это ни на что не влияет.
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', t === 'light' ? '#fcfaf8' : '#12110f');
}

export function watchLook(): void {
  dark?.addEventListener?.('change', () => {
    if (lookChoice() === 'system') applyLook();
  });
}
