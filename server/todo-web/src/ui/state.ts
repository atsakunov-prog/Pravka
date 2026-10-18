// Состояние экрана (не данных): открытое окно задачи, быстрый ввод, поиск, выбранные строки, боковая панель.
import { emit } from '../store.ts';
import type { Id } from '../types.ts';

export interface QuickDefaults {
  project_id?: Id | null;
  deal_id?: Id | null;
  section_id?: Id | null;
  due_date?: string | null;
  focus_on?: string | null;
  why_id?: Id | null;
  labels?: string[];
  person_id?: Id | null;
  priority?: number;
}

export const UI = {
  task: null as Id | null, // окно задачи
  taskEdit: false, // сразу в правку названия
  taskComment: false, // сразу в комментарий
  quick: null as QuickDefaults | null, // окно быстрого ввода
  search: false,
  sel: new Set<Id>(),
  sideMobile: false,
  sideOff: (() => {
    try {
      return localStorage.getItem('todo.sideOff') === '1';
    } catch {
      return false;
    }
  })(),
  bell: false,
  claudeOpen: true,
  dialog: null as null | { kind: string; data?: any },
  focus: null as Id | null, // строка под клавиатурой (J/K, стрелки)
  dictate: false, // окно «Надиктовать задачи»
};

export function openTask(id: Id, edit = false, comment = false) {
  UI.task = id;
  UI.taskEdit = edit;
  UI.taskComment = comment;
  UI.sideMobile = false;
  emit();
}
export function closeTask() {
  UI.task = null;
  emit();
}
export function openQuick(d: QuickDefaults = {}) {
  UI.quick = d;
  emit();
}
export function closeQuick() {
  UI.quick = null;
  emit();
}
export function toggleSide() {
  if (matchMedia('(max-width: 750px)').matches) UI.sideMobile = !UI.sideMobile;
  else {
    UI.sideOff = !UI.sideOff;
    try {
      localStorage.setItem('todo.sideOff', UI.sideOff ? '1' : '0');
    } catch {
      /* без хранилища — до перезагрузки */
    }
  }
  emit();
}
export function openDictate(on = true) {
  UI.dictate = on;
  emit();
}
export function openDialog(kind: string, data?: any) {
  UI.dialog = { kind, data };
  emit();
}
export function closeDialog() {
  UI.dialog = null;
  emit();
}
