// Язык фильтров Todoist: «(today | overdue) & #Альфа», «p1 & !@звонок», «7 days, no date». Русские слова — как в
// русском Todoist («сегодня», «просрочено», «без даты», «срок до: пт»), плюс наше: «сейчас», «жду», «повестка», «+Иван».
// Запятая — несколько списков в одном виде, каждый своим блоком.

import { addDays, diffDays } from './dates.ts';
import { parseWhen, type Ctx } from './quick.ts';
import type { Task } from './types.ts';

export type Pred = (t: Task) => boolean;

export interface FilterCtx extends Ctx {
  projectOf: (name: string, withChildren: boolean) => ((t: Task) => boolean) | null;
  sectionOf: (name: string) => ((t: Task) => boolean) | null;
  personOf: (name: string) => ((t: Task) => boolean) | null;
  me: string;
}

export class FilterError extends Error {}

/** Списки запроса: «a, b» — два блока. */
export function splitLists(q: string): string[] {
  const out: string[] = [];
  let depth = 0, cur = '';
  for (let i = 0; i < q.length; i++) {
    const ch = q[i];
    if (ch === '\\' && i + 1 < q.length) {
      cur += ch + q[++i];
      continue;
    }
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) {
      out.push(cur.trim());
      cur = '';
      continue;
    }
    cur += ch;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

type Tok = { t: '(' | ')' | '&' | '|' | '!' } | { t: 'atom'; s: string };

function lex(q: string): Tok[] {
  const out: Tok[] = [];
  let cur = '';
  const flush = () => {
    const s = cur.trim();
    if (s) out.push({ t: 'atom', s });
    cur = '';
  };
  for (let i = 0; i < q.length; i++) {
    const ch = q[i];
    if (ch === '\\' && i + 1 < q.length) {
      cur += q[++i];
      continue;
    }
    if (ch === '(' || ch === ')' || ch === '&' || ch === '|') {
      flush();
      out.push({ t: ch });
      continue;
    }
    // «!» — «не», если стоит перед словом и в начале или после оператора/пробела
    if (ch === '!' && !cur.trim()) {
      out.push({ t: '!' });
      continue;
    }
    cur += ch;
  }
  flush();
  return out;
}

export function compile(q: string, ctx: FilterCtx): Pred {
  const toks = lex(q);
  let i = 0;
  const peek = () => toks[i];
  function or(): Pred {
    let a = and();
    while (peek()?.t === '|') {
      i++;
      const b = and(), x = a;
      a = (t) => x(t) || b(t);
    }
    return a;
  }
  function and(): Pred {
    let a = not();
    while (peek()?.t === '&') {
      i++;
      const b = not(), x = a;
      a = (t) => x(t) && b(t);
    }
    return a;
  }
  function not(): Pred {
    if (peek()?.t === '!') {
      i++;
      const a = not();
      return (t) => !a(t);
    }
    return prim();
  }
  function prim(): Pred {
    const x = toks[i++];
    if (!x) throw new FilterError('запрос оборвался');
    if (x.t === '(') {
      const a = or();
      if (toks[i++]?.t !== ')') throw new FilterError('не закрыта скобка');
      return a;
    }
    if (x.t === 'atom') return atom(x.s, ctx);
    throw new FilterError('лишний знак «' + x.t + '»');
  }
  const p = or();
  if (i < toks.length) throw new FilterError('не понял хвост запроса');
  return p;
}

const wild = (pat: string): RegExp =>
  new RegExp('^' + pat.toLowerCase().replace(/ё/g, 'е').replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$');
const low = (s: string) => s.toLowerCase().replace(/ё/g, 'е');

function dateOf(s: string, ctx: FilterCtx): string {
  const w = parseWhen(s, ctx);
  if (!w || !w.due_date) throw new FilterError('не понял дату «' + s + '»');
  return w.due_date;
}

function atom(raw: string, ctx: FilterCtx): Pred {
  const s = low(raw.trim());
  const today = ctx.today;
  const due = (t: Task) => t.due_date;
  // Ключевые слова
  if (/^(today|сегодня)$/.test(s)) return (t) => due(t) === today || t.deadline === today;
  if (/^(tomorrow|завтра)$/.test(s)) return (t) => due(t) === addDays(today, 1);
  if (/^(yesterday|вчера)$/.test(s)) return (t) => due(t) === addDays(today, -1);
  if (/^(overdue|over due|od|просрочено|просроченные|просроченное)$/.test(s)) return (t) => !!due(t) && due(t)! < today;
  if (/^(no date|без даты|без срока)$/.test(s)) return (t) => !due(t);
  if (/^(no time|без времени)$/.test(s)) return (t) => !t.due_time;
  if (/^(no deadline|без дедлайна)$/.test(s)) return (t) => !t.deadline;
  if (/^(recurring|повторяющиеся|повторяющееся)$/.test(s)) return (t) => !!t.recur;
  if (/^(subtask|подзадача|подзадачи)$/.test(s)) return (t) => !!t.why_id && !t.why_goal;
  if (/^(no labels|без меток)$/.test(s)) return (t) => !(t.labels || []).length;
  if (/^(no priority|без приоритета|p4)$/.test(s)) return (t) => (t.priority ?? 4) === 4;
  if (/^(view all|all|все|всё)$/.test(s)) return () => true;
  if (/^(now|сейчас)$/.test(s)) return (t) => t.focus_on === today;
  if (/^(жду|waiting)$/.test(s)) return (t) => t.ball === 'waiting';
  if (/^(повестка|agenda)$/.test(s)) return (t) => t.ball === 'agenda';
  if (/^(мое|моё|mine|assigned to: me|assigned to: я)$/.test(s)) return (t) => t.owner_id === ctx.me;
  let m = s.match(/^p([1-4])$/);
  if (m) return (t) => (t.priority ?? 4) === +m![1];
  // N days / следующие N дней / -N days
  m = s.match(/^(?:next |следующие )?(-?\d{1,3}) (days?|дн(я|ей|ь)?)$/);
  if (m) {
    // «7 дней» — сегодня и шесть следующих; «-3 дня» — три прошедших (без сегодня).
    const n = +m[1];
    const a = n < 0 ? addDays(today, n) : today, b = n < 0 ? addDays(today, -1) : addDays(today, Math.max(0, n - 1));
    return (t) => !!due(t) && due(t)! >= a && due(t)! <= b;
  }
  // Даты с двоеточием
  m = s.match(/^(date|срок|due|deadline|дедлайн|created|создано)( before| after| до| после)?:\s*(.+)$/);
  if (m) {
    const field = m[1], rel = (m[2] || '').trim(), d = dateOf(m[3], ctx);
    const get = (t: Task): string | null | undefined =>
      field === 'deadline' || field === 'дедлайн' ? t.deadline : field === 'created' || field === 'создано' ? t.created_at?.slice(0, 10) : t.due_date;
    if (rel === 'before' || rel === 'до') return (t) => !!get(t) && get(t)! < d;
    if (rel === 'after' || rel === 'после') return (t) => !!get(t) && get(t)! > d;
    return (t) => get(t) === d;
  }
  m = s.match(/^(search|поиск):\s*(.+)$/);
  if (m) {
    const ws = m[2].split(/\s+/).filter(Boolean);
    return (t) => ws.every((w) => low(t.title + ' ' + (t.notes || '')).includes(w));
  }
  // Метки, проекты, разделы, люди
  if (s.startsWith('@') || s.startsWith('%')) {
    const re = wild(s.slice(1));
    return (t) => (t.labels || []).some((l) => re.test(low(l)));
  }
  if (s.startsWith('##') || s.startsWith('#')) {
    const deep = s.startsWith('##');
    const name = raw.trim().slice(deep ? 2 : 1);
    const p = ctx.projectOf(name, deep);
    if (!p) throw new FilterError('нет проекта «' + name + '»');
    return p;
  }
  if (s.startsWith('/')) {
    if (s === '!/*' || s === '/*') return (t) => !!t.section_id;
    const p = ctx.sectionOf(raw.trim().slice(1));
    if (!p) throw new FilterError('нет раздела «' + raw.trim().slice(1) + '»');
    return p;
  }
  if (s.startsWith('+')) {
    const p = ctx.personOf(raw.trim().slice(1));
    if (!p) throw new FilterError('нет человека «' + raw.trim().slice(1) + '»');
    return p;
  }
  // Голая дата: «пт», «25 окт», «завтра»
  const w = parseWhen(raw.trim(), ctx);
  if (w && w.due_date) return (t) => due(t) === w.due_date;
  throw new FilterError('не понял «' + raw.trim() + '»');
}

/** Блоки фильтра: по одному на каждый список через запятую, с заголовком — самим запросом. */
export function runFilter(q: string, tasks: Task[], ctx: FilterCtx): { title: string; tasks: Task[] }[] {
  return splitLists(q).map((part) => {
    const p = compile(part, ctx);
    return { title: part, tasks: tasks.filter(p) };
  });
}

export const daysTo = (d: string, today: string) => diffDays(d, today);
