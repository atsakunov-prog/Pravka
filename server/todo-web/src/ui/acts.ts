// Частые правки дела с тостом и «Отменить» — тексты Todoist (toastText.ts), откат — прежние значения (store.setTask).
import { placeName } from '../lib.ts';
import { S, setTask } from '../store.ts';
import { T } from '../toastText.ts';
import type { Task } from '../types.ts';
import type { DueVal, Place } from './pickers.tsx';

export const setDue = (t: Task, v: Partial<DueVal>) => setTask(t, v as Record<string, unknown>, T.due(v.due_date ?? null, S.today)).catch(() => {});
export const setDeadline = (t: Task, d: string | null) => setTask(t, { deadline: d }, T.deadline(d, S.today)).catch(() => {});
export const setPrio = (t: Task, p: number) => setTask(t, { priority: p }, T.priority(p)).catch(() => {});
export const setPlace = (t: Task, p: Partial<Place> & { project_id: string | null }) =>
  setTask(t, { project_id: p.project_id, deal_id: p.deal_id ?? null, section_id: p.section_id ?? null }, T.moved(placeName(p))).catch(() => {});
