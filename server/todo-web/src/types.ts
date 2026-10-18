// Строки, как их отдаёт сервер Дел (/api/sync, tasks.v_tasks и таблицы crm). Поля — только те, что читает новый веб;
// остальное едет как есть (index signature), чтобы правка сервера не ломала сборку.

export type Id = string;

export interface Task {
  id: Id;
  num: number;
  title: string;
  notes: string | null;
  project_id: Id | null;
  deal_id: Id | null;
  owner_id: string;
  ball: 'mine' | 'waiting' | 'agenda';
  owed?: boolean;
  person_id: Id | null;
  requested_by: Id | null;
  waiting_since?: string | null;
  nudge_on?: string | null;
  due_date: string | null;
  due_time: string | null;
  estimate_min: number | null;
  focus_on: string | null;
  labels: string[];
  status: 'open' | 'done' | 'cancelled';
  source: string;
  source_ref: string | null;
  created_by: string;
  created_at: string;
  updated_at: string;
  completed_at: string | null;
  remind_at: string | null;
  remind_place: string | null;
  pos: number | null;
  kind?: 'task' | 'remind';
  // вид Todoist (dela_0036)
  priority?: number;
  deadline?: string | null;
  due_rule?: string | null;
  recur?: Recur | null;
  done_count?: number;
  section_id?: Id | null;
  // из v_tasks
  project_name?: string | null;
  project_kind?: string | null;
  section_name?: string | null;
  deal_name?: string | null;
  person_short?: string | null;
  person_name?: string | null;
  why_id?: Id | null;
  why_title?: string | null;
  why_goal?: boolean;
  goal?: boolean;
  rhythm?: string | null;
  no_steps?: boolean;
  directions?: string[];
  dirs?: string[];
  steps_open?: number;
  [k: string]: unknown;
}

export interface Recur {
  every: 'day' | 'workday' | 'week' | 'month' | 'year';
  n?: number;
  days?: number[];
  monthday?: number;
  from?: 'due' | 'done';
  until?: string;
}

export interface Project {
  id: Id;
  name: string;
  kind: 'client' | 'internal' | 'personal' | string;
  sphere: string;
  owner_id: string;
  archived_at: string | null;
  status?: string | null;
  color?: string | null;
  [k: string]: unknown;
}

export interface Deal {
  id: Id;
  project_id: Id;
  name: string;
  stage: string;
  outcome?: string | null;
  next_step?: string | null;
  [k: string]: unknown;
}

export interface Person {
  id: Id;
  name: string;
  short: string | null;
  org_id?: Id | null;
  user_id?: string | null;
  merged_into?: Id | null;
  [k: string]: unknown;
}

export interface Section {
  id: Id;
  project_id: Id;
  name: string;
  pos: number | null;
  collapsed: boolean;
  archived: boolean;
  [k: string]: unknown;
}

export interface Comment {
  id: Id;
  task_id: Id;
  author_id: string;
  body?: string;
  text?: string;
  created_at: string;
  deleted_at: string | null;
  [k: string]: unknown;
}

export interface Suggestion {
  id: Id;
  kind: string;
  status: string;
  source: string;
  for_user: string;
  task_id: Id | null;
  batch_ref: string | null;
  batch_title: string | null;
  quote: string | null;
  reason: string | null;
  payload: Record<string, any>;
  decided_at: string | null;
  seen_at: string | null;
  created_at: string;
  [k: string]: unknown;
}

export interface Me {
  user: string;
  name: string;
  role: string;
  settings: Record<string, any>;
  [k: string]: unknown;
}

export interface Direction {
  key: string;
  name?: string;
  title?: string;
  short?: string;
  pos?: number;
  [k: string]: unknown;
}

export interface Filter {
  id: string;
  name: string;
  query: string;
  color?: string;
  fav?: boolean;
}

export interface TodoSettings {
  fav?: string[];            // «p:<id>», «d:<id>», «l:<имя>», «f:<id>»
  filters?: Filter[];
  porder?: Id[];             // порядок проектов в боковой панели
  collapsed?: Record<string, boolean>;
  layout?: Record<string, 'list' | 'board'>;
}
