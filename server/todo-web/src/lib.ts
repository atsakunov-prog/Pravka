// Мелочи для всех экранов: палитра проектов, адрес страницы (#/…), имена.

import { useEffect, useState } from 'react';
import { S } from './store.ts';
import type { Id, Project, Task } from './types.ts';

/** Палитра Todoist (20 цветов по имени): цвет точки и «#» в светлой теме. */
export const PALETTE: Record<string, [string, string]> = {
  berry_red: ['#b8255f', 'Ягодный'], red: ['#dc4c3e', 'Красный'], orange: ['#f48318', 'Оранжевый'], yellow: ['#fecf05', 'Жёлтый'],
  olive_green: ['#aeb83a', 'Оливковый'], lime_green: ['#7ecc48', 'Лаймовый'], green: ['#369307', 'Зелёный'],
  mint_green: ['#52ccb8', 'Мятный'], teal: ['#148fad', 'Бирюзовый'], sky_blue: ['#3ab9e2', 'Небесный'],
  light_blue: ['#96c3eb', 'Светло-синий'], blue: ['#2a67e2', 'Синий'], grape: ['#692ec2', 'Виноградный'],
  violet: ['#ac30cc', 'Фиолетовый'], lavender: ['#eb96c8', 'Лавандовый'], magenta: ['#e05095', 'Пурпурный'],
  salmon: ['#c9766f', 'Лососевый'], charcoal: ['#808080', 'Угольный'], grey: ['#999999', 'Серый'], taupe: ['#ccae96', 'Серо-коричневый'],
};
const AUTO = ['blue', 'green', 'orange', 'grape', 'teal', 'berry_red', 'olive_green', 'sky_blue', 'magenta', 'taupe', 'mint_green', 'violet'];

/** Цвет проекта: свой — из палитры; не задан — стабильный по id (чтобы клиенты не были все серыми). */
export function projectColor(p: Project | undefined | null): string {
  if (!p) return PALETTE.charcoal[0];
  if (p.color && PALETTE[p.color]) return PALETTE[p.color][0];
  let h = 0;
  for (const ch of p.id) h = (h * 31 + ch.charCodeAt(0)) | 0;
  return PALETTE[AUTO[Math.abs(h) % AUTO.length]][0];
}
export function labelColor(name: string): string {
  const l = S.labels.find((x) => x.name === name);
  if (l?.color && PALETTE[l.color]) return PALETTE[l.color][0];
  return 'var(--fg2)';
}

// ── Адрес ────────────────────────────────────────────────────────────────

export interface Route {
  view: string;
  id?: string;
  q?: string;
}

export function parseHash(h = location.hash): Route {
  const s = decodeURIComponent(h.replace(/^#\/?/, ''));
  const [view, ...rest] = s.split('/');
  const id = rest.join('/') || undefined;
  return { view: view || '', id };
}
export function useRoute(): Route {
  const [r, setR] = useState(parseHash());
  useEffect(() => {
    const on = () => setR(parseHash());
    addEventListener('hashchange', on);
    return () => removeEventListener('hashchange', on);
  }, []);
  return r;
}
export const go = (path: string) => {
  location.hash = '#/' + path;
};
/** Окно задачи поверх текущего вида: ?task=<id> в хеше не нужен — держим в памяти, адрес — для ссылки. */
export const taskLink = (t: Task) => `${location.origin}/t#/task/${t.num}`;

// ── Имена ────────────────────────────────────────────────────────────────

export const projectName = (id: Id | null | undefined) => (id ? S.projects.get(id)?.name || '…' : 'Входящие');
export const dealName = (id: Id | null | undefined) => (id ? S.deals.get(id)?.name || '…' : '');
export function personName(id: Id | null | undefined): string {
  if (!id) return '';
  const p = S.people.get(id);
  return p ? p.short || p.name : '';
}
/** Сделка без имени клиента в начале: «Альфа: фонды» в Альфе — «фонды». */
export function dealShort(id: Id): string {
  const d = S.deals.get(id);
  if (!d) return '';
  const p = S.projects.get(d.project_id);
  const n = d.name;
  if (p) {
    const first = p.name.split(/\s+/)[0];
    const m = n.match(new RegExp('^' + first.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '[^:]*:\\s*', 'i'));
    if (m) return n.slice(m[0].length) || n;
  }
  return n;
}
export const STAGE: Record<string, string> = { lead: 'лид', proposal: 'КП', mandate: 'мандат', active: 'в работе', closing: 'закрытие', archive: 'архив' };

export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((x) => x[0]).join('').toUpperCase();
}

/** Старый веб Дел — карточки CRM живут там (/#/p/<id>, /#/d/<id>, /#/h/<id>). */
export const crmUrl = (kind: 'p' | 'd' | 'h' | 'crm' | 'clients' | 'people', id?: Id) => '/#/' + kind + (id ? '/' + id : '');

export function cls(...xs: (string | false | null | undefined)[]): string {
  return xs.filter(Boolean).join(' ');
}

export const isMobile = () => matchMedia('(max-width: 750px)').matches;
export function useMobile(): boolean {
  const [m, setM] = useState(isMobile());
  useEffect(() => {
    const q = matchMedia('(max-width: 750px)');
    const on = () => setM(q.matches);
    q.addEventListener('change', on);
    return () => q.removeEventListener('change', on);
  }, []);
  return m;
}
