// Справочник для разбора быстрого ввода и фильтров — из памяти веба.
import { nowHm } from '../dates.ts';
import type { FilterCtx } from '../filter.ts';
import type { Ctx, Named } from '../quick.ts';
import { S } from '../store.ts';
import type { Task } from '../types.ts';

export function quickCtx(): Ctx {
  const projects: Named[] = [];
  for (const p of S.projects.values()) if (!p.archived_at) projects.push({ id: p.id, name: p.name });
  for (const d of S.deals.values()) if (d.stage !== 'archive' && !d.outcome) projects.push({ id: d.id, name: d.name, deal: true, project_id: d.project_id });
  return {
    today: S.today,
    now: nowHm(),
    projects,
    sections: [...S.sections.values()].filter((s) => !s.archived).map((s) => ({ id: s.id, name: s.name, project_id: s.project_id })),
    labels: S.labels.map((l) => l.name),
    people: [...S.people.values()].filter((p) => !p.merged_into).map((p) => ({ id: p.id, name: p.name, short: p.short })),
  };
}

const low = (s: string) => s.toLowerCase().replace(/ё/g, 'е').trim();
const re = (pat: string) => new RegExp('^' + low(pat).replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$');

export function filterCtx(): FilterCtx {
  return {
    ...quickCtx(),
    me: S.me?.user || '',
    projectOf: (name, deep) => {
      const r = re(name);
      if (low(name) === 'входящие' || low(name) === 'inbox') return (t: Task) => !t.project_id;
      const ps = [...S.projects.values()].filter((p) => r.test(low(p.name)));
      const ds = [...S.deals.values()].filter((d) => r.test(low(d.name)));
      if (!ps.length && !ds.length) return null;
      const pid = new Set(ps.map((p) => p.id)), did = new Set(ds.map((d) => d.id));
      return (t: Task) => (!!t.deal_id && did.has(t.deal_id)) || (!!t.project_id && pid.has(t.project_id) && (deep || !t.deal_id));
    },
    sectionOf: (name) => {
      const r = re(name);
      const ids = new Set([...S.sections.values()].filter((s) => r.test(low(s.name))).map((s) => s.id));
      return ids.size ? (t: Task) => !!t.section_id && ids.has(t.section_id) : null;
    },
    personOf: (name) => {
      const r = re(name.includes('*') ? name : name + '*');
      const ids = new Set([...S.people.values()].filter((p) => r.test(low(p.short || '')) || r.test(low(p.name))).map((p) => p.id));
      return ids.size ? (t: Task) => (!!t.person_id && ids.has(t.person_id)) || (!!t.requested_by && ids.has(t.requested_by)) : null;
    },
  };
}
