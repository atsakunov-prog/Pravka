// Значки линией 24×24 в весе Todoist (свои пути, не их набор): цвет — от текста.
import type { CSSProperties, ReactElement } from 'react';

const P: Record<string, string> = {
  plus: 'M12 5v14M5 12h14',
  search: 'M10.5 4a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13zM15.5 15.5 20 20',
  inbox: 'M4 13.5V18a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4.5M4 13.5 6.2 6.3A1.8 1.8 0 0 1 7.9 5h8.2a1.8 1.8 0 0 1 1.7 1.3L20 13.5M4 13.5h4.3a1 1 0 0 1 .9.6 3 3 0 0 0 5.6 0 1 1 0 0 1 .9-.6H20',
  upcoming: 'M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM4 9.5h16M8 3.5V6M16 3.5V6M8 13h.01M12 13h.01M16 13h.01M8 16.5h.01M12 16.5h.01',
  filters: 'M5 5h5v5H5zM14 5h5v5h-5zM5 14h5v5H5zM14 16.5h5M16.5 14v5',
  bolt: 'M13 3 5 13.5h6l-1 7.5 8-10.5h-6l1-7.5z',
  bell: 'M6.5 16.5V11a5.5 5.5 0 0 1 11 0v5.5l1.5 2H5l1.5-2zM10 20.5a2 2 0 0 0 4 0',
  sidebar: 'M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5zM9.5 4.5v15',
  hash: 'M9.5 4.5 7.5 19.5M16.5 4.5l-2 15M5 9h15M4 15h15',
  chevR: 'm10 7 5 5-5 5',
  chevD: 'm7 10 5 5 5-5',
  chevL: 'm14 7-5 5 5 5',
  chevU: 'm7 14 5-5 5 5',
  more: 'M6 12h.01M12 12h.01M18 12h.01',
  pencil: 'm14.5 5.5 4 4L9 19H5v-4l9.5-9.5zM13 7l4 4',
  calendar: 'M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM4 9.5h16M8 3.5V6M16 3.5V6',
  comment: 'M5 5.5h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-7l-4.5 3.5V16.5H5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z',
  flag: 'M6 20.5V4.5M6 5h10.5l-2 3.5 2 3.5H6',
  tag: 'M4.5 12.3V5.5a1 1 0 0 1 1-1h6.8a1 1 0 0 1 .7.3l6.7 6.7a1 1 0 0 1 0 1.4l-6.8 6.8a1 1 0 0 1-1.4 0l-6.7-6.7a1 1 0 0 1-.3-.7zM8.5 8.5h.01',
  alarm: 'M12 7.5V12l2.5 2.5M12 20a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15zM4.5 5.5 7 3.5M19.5 5.5 17 3.5',
  x: 'M6.5 6.5l11 11M17.5 6.5l-11 11',
  check: 'M5.5 12.5 10 17l8.5-9',
  checkSm: 'M7 12.3l3.2 3.2L17 8.7',
  arrowUp: 'M12 19V5M6 11l6-6 6 6',
  arrowDown: 'M12 5v14M6 13l6 6 6-6',
  spark: 'M12 3.5c.6 3.9 2.6 5.9 6.5 6.5-3.9.6-5.9 2.6-6.5 6.5-.6-3.9-2.6-5.9-6.5-6.5 3.9-.6 5.9-2.6 6.5-6.5zM18.5 15c.3 1.6 1 2.3 2.5 2.5-1.5.3-2.2 1-2.5 2.5-.3-1.5-1-2.2-2.5-2.5 1.5-.2 2.2-.9 2.5-2.5z',
  user: 'M12 12a3.8 3.8 0 1 0 0-7.6 3.8 3.8 0 0 0 0 7.6zM5 20c.8-3.6 3.6-5.5 7-5.5s6.2 1.9 7 5.5',
  users: 'M9 11.5a3.3 3.3 0 1 0 0-6.6 3.3 3.3 0 0 0 0 6.6zM3.5 19c.7-3.2 2.9-4.9 5.5-4.9s4.8 1.7 5.5 4.9M15.5 5a3.2 3.2 0 0 1 0 6.3M17 14.3c1.9.4 3.2 1.9 3.6 4.7',
  deal: 'M8 7V5.5A1.5 1.5 0 0 1 9.5 4h5A1.5 1.5 0 0 1 16 5.5V7M4.5 7h15a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1zM3.5 12.5h17',
  target: 'M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17zM12 16.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zM12 12.5h.01',
  tree: 'M12 4v5M12 9H6.5v4M12 9h5.5v4M12 9v4M4.5 13h4v4h-4zM10 13h4v4h-4zM15.5 13h4v4h-4z',
  repeat: 'M17 4.5 19.5 7 17 9.5M4.5 12V10a3 3 0 0 1 3-3h12M7 19.5 4.5 17 7 14.5M19.5 12v2a3 3 0 0 1-3 3h-12',
  deadline: 'M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17zM12 7.5V12h3.5',
  grip: 'M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01',
  trash: 'M5 7h14M10 7V5h4v2M7 7l1 12.5h8L17 7M10.5 10.5v6M13.5 10.5v6',
  link: 'M10 14a3.5 3.5 0 0 0 5 0l3-3a3.5 3.5 0 0 0-5-5l-1 1M14 10a3.5 3.5 0 0 0-5 0l-3 3a3.5 3.5 0 0 0 5 5l1-1',
  sun: 'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4',
  moon: 'M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z',
  gear: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 13.5l1.3 1-2 3.4-1.5-.6a7 7 0 0 1-2 1.2L15 20.5h-4l-.3-2a7 7 0 0 1-2-1.2l-1.5.6-2-3.4 1.3-1a7 7 0 0 1 0-3l-1.3-1 2-3.4 1.5.6a7 7 0 0 1 2-1.2L11 3.5h4l.3 2a7 7 0 0 1 2 1.2l1.5-.6 2 3.4-1.3 1a7 7 0 0 1 0 3z',
  funnel: 'M4 5h16l-6 7.5v5L10 20v-7.5L4 5z',
  mic: 'M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3zM6 11a6 6 0 0 0 12 0M12 17v3.5',
  send: 'M12 19V5M6 11l6-6 6 6',
  board: 'M5 4.5h4a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1zM15 4.5h4a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z',
  list: 'M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01',
  sliders: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4',
  done: 'M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17zM8.5 12.3l2.4 2.4 4.6-5',
  door: 'M14 4.5h4a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5h-4M10 16l4-4-4-4M14 12H4.5',
  sub: 'M7 4.5v9a2 2 0 0 0 2 2h9M15 12.5l3 3-3 3',
  home: 'M3.5 10.5 12 3.5l8.5 7M5.5 9v10.5h5v-6h3v6h5V9',
  copy: 'M9 9h10v10H9zM5 15V5h10',
  sort: 'M8 5v14M4.5 15.5 8 19l3.5-3.5M16 19V5M12.5 8.5 16 5l3.5 3.5',
  archive: 'M4 5h16v4H4zM5.5 9v9.5a1 1 0 0 0 1 1h11a1 1 0 0 0 1-1V9M10 13h4',
  menu: 'M4 7h16M4 12h16M4 17h16',
  stop: 'M8 8h8v8H8z',
  star: 'M12 4.5l2.3 4.7 5.2.8-3.8 3.7.9 5.2-4.6-2.4-4.6 2.4.9-5.2-3.8-3.7 5.2-.8z',
};

export function Icon({ name, size = 24, style, sw = 1.5, title }: { name: string; size?: number; style?: CSSProperties; sw?: number; title?: string }): ReactElement {
  const fat = name === 'more' || name === 'grip';
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={fat ? 2.6 : sw}
      strokeLinecap="round" strokeLinejoin="round" style={style} aria-hidden={title ? undefined : true}>
      {title ? <title>{title}</title> : null}
      <path d={P[name] || P.more} />
    </svg>
  );
}

/** «Сегодня» в боковой панели — календарь с числом, как у Todoist. */
export function TodayIcon({ day, size = 24 }: { day: number; size?: number }): ReactElement {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" aria-hidden>
      <path d="M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM4 9.5h16" />
      <text x="12" y="17.6" textAnchor="middle" fontSize="8" fontWeight="700" fill="currentColor" stroke="none" fontFamily="system-ui">{day}</text>
    </svg>
  );
}

/** Флажок приоритета: залитый цветом p1–p3, контур — p4. */
export function PrioFlag({ p, size = 18 }: { p: number; size?: number }): ReactElement {
  const color = p === 1 ? 'var(--p1)' : p === 2 ? 'var(--p2)' : p === 3 ? 'var(--p3)' : 'var(--fg2)';
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={p < 4 ? color : 'none'} stroke={color} strokeWidth={1.5} strokeLinejoin="round" aria-hidden>
      <path d="M6 20.5V4.5" fill="none" />
      <path d="M6 5h10.5l-2 3.5 2 3.5H6" />
    </svg>
  );
}
