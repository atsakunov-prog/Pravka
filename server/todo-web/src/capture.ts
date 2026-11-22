// Строка быстрого ввода поверх разбора quick.ts (23.11.2026): «~направление» дерева жизни и «напомни …». Отдельно от
// Editor.tsx — чтобы проверялось тестом без React (capture.test.ts).
import { dirByWord, krugOn } from './krug.ts';
import type { Parsed, TokKind } from './quick.ts';

/** «~Здоровье» — направление дерева жизни (23.11.2026): своё слово строки поверх разбора quick.ts — вырезается из
 *  названия и подсвечивается, как остальное распознанное. Цель — выбором из подсказки «~» (у целей длинные имена). */
export function withDir(p: Parsed, text: string): Parsed & { dir?: string } {
  if (!krugOn()) return p;
  const m = /(^|\s)~([^\s~#@%+/]+)/u.exec(text);
  const k = m ? dirByWord(m[2]) : null;
  if (!m || !k) return p;
  const start = m.index + m[1].length, end = start + 1 + m[2].length;
  const word = text.slice(start, end);
  return { ...p, title: p.title.replace(word, '').replace(/\s{2,}/g, ' ').trim(), dir: k,
    tokens: [...p.tokens, { start, end, kind: 'dir' as TokKind }].sort((a, b) => a.start - b.start) };
}

/** «Напомни завтра в 9 позвонить врачу» (23.11.2026; владелец: «если нужно что-то запомнить, я записываю в календарь, а
 *  это ужас»): «напомни» в начале — слово уходит из названия, срок строки становится и напоминанием ботом Ковчега; без
 *  времени — в 9 утра того дня. Без срока — обычная задача (напомнить некогда). Явное «!…» строки — главнее. */
export function withRemind<P extends Parsed>(p: P, text: string): P {
  const m = /^\s*(напомни(?:ть)?(?:\s+мне)?)(?=\s|$)/iu.exec(text);
  if (!m) return p;
  const start = text.indexOf(m[1]), end = start + m[1].length;
  const title = p.title.replace(/^\s*напомни(?:ть)?(?:\s+мне)?\s*/iu, '');
  const out = { ...p, title: title ? title[0].toUpperCase() + title.slice(1) : title,
    tokens: [...p.tokens, { start, end, kind: 'remind' as TokKind }].sort((a, b) => a.start - b.start) };
  if (!p.remind_when && !p.remind_place && p.due_date) out.remind_when = { date: p.due_date, time: p.due_time || '09:00' };
  return out;
}
