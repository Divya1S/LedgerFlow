// A small set of line icons drawn on a 24px grid with one stroke weight,
// so they sit quietly next to text. Each is decorative: the label beside
// it carries the meaning, so they are hidden from screen readers.

const PATHS: Record<string, string[]> = {
  overview: ['M4 5h7v6H4z', 'M13 5h7v4h-7z', 'M13 11h7v8h-7z', 'M4 13h7v6H4z'],
  wallet: ['M4 7.5A2.5 2.5 0 0 1 6.5 5H18a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6.5A2.5 2.5 0 0 1 4 16.5z', 'M4 9h16', 'M15.5 14h1.5'],
  list: ['M8 7h12', 'M8 12h12', 'M8 17h12', 'M4 7h.01', 'M4 12h.01', 'M4 17h.01'],
  send: ['M7 17 17 7', 'M9 7h8v8'],
  receive: ['M17 7 7 17', 'M15 17H7V9'],
  calendar: ['M5 6.5h14a1 1 0 0 1 1 1V19a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7.5a1 1 0 0 1 1-1z', 'M4 10.5h16', 'M8 4v4', 'M16 4v4'],
  users: ['M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M3.5 19a5.5 5.5 0 0 1 11 0', 'M16 5.2a3 3 0 0 1 0 5.6', 'M17.5 14.2A5.5 5.5 0 0 1 20.5 19'],
  store: ['M4 10v9h16v-9', 'M3 6l1.5-2h15L21 6v2a2.5 2.5 0 0 1-5 0 2.5 2.5 0 0 1-5 0 2.5 2.5 0 0 1-5 0 2.5 2.5 0 0 1-3 2.4z', 'M10 19v-5h4v5'],
  scale: ['M12 4v16', 'M7 20h10', 'M5 7h14', 'M5 7l-2.5 6a2.7 2.7 0 0 0 5 0z', 'M19 7l-2.5 6a2.7 2.7 0 0 0 5 0z'],
  activity: ['M3 12h4l3-7 4 14 3-7h4'],
  settings: ['M5 7h9', 'M18 7h1', 'M5 12h2', 'M11 12h8', 'M5 17h7', 'M16 17h3', 'M16 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4z', 'M9 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z', 'M14 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z'],
  bell: ['M6 16V11a6 6 0 1 1 12 0v5l1.5 2h-15z', 'M10 20a2 2 0 0 0 4 0'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z', 'M20 20l-4-4'],
  download: ['M12 4v11', 'M7.5 11l4.5 4.5 4.5-4.5', 'M5 20h14'],
  plus: ['M12 5v14', 'M5 12h14'],
  check: ['M5 12.5l4.5 4.5L19 7.5'],
  close: ['M6 6l12 12', 'M18 6L6 18'],
  chevronRight: ['M9 6l6 6-6 6'],
  chevronDown: ['M6 9l6 6 6-6'],
  lock: ['M6 11h12v9H6z', 'M8.5 11V8a3.5 3.5 0 0 1 7 0v3'],
  unlock: ['M6 11h12v9H6z', 'M8.5 11V8a3.5 3.5 0 0 1 6.7-1.4'],
  copy: ['M9 9h10v11H9z', 'M5 15V4h10'],
  logout: ['M10 5H5v14h5', 'M14 8l4 4-4 4', 'M18 12H9'],
  info: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 11v5', 'M12 8h.01'],
  alert: ['M12 4l9 15.5H3z', 'M12 10v4', 'M12 16.8h.01'],
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
  pause: ['M9 6v12', 'M15 6v12'],
  play: ['M8 5.5v13l10-6.5z'],
  repeat: ['M17 3l3 3-3 3', 'M4 11V9a3 3 0 0 1 3-3h13', 'M7 21l-3-3 3-3', 'M20 13v2a3 3 0 0 1-3 3H4'],
  shield: ['M12 3l7.5 3v6c0 4.5-3.2 7.8-7.5 9-4.3-1.2-7.5-4.5-7.5-9V6z', 'M9 12l2.2 2.2L15 10.5'],
  link: ['M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1', 'M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1'],
  table: ['M4 6h16v13H4z', 'M4 10.5h16', 'M4 15h16', 'M10 6v13'],
  chart: ['M4 20V4', 'M4 20h16', 'M8 16v-5', 'M12 16V8', 'M16 16v-3'],
  bank: ['M3 9.5l9-5 9 5', 'M5 10v7', 'M9.5 10v7', 'M14.5 10v7', 'M19 10v7', 'M3 20h18'],
};

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  return (
    <svg className="icon" width={size} height={size} viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"
         aria-hidden="true" focusable="false">
      {PATHS[name].map((d, i) => <path key={i} d={d} />)}
    </svg>
  );
}

/** The LedgerFlow mark: two ledger lines that balance. */
export function Logo({ size = 26 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 28 28" aria-hidden="true" focusable="false">
      <rect width="28" height="28" rx="7" fill="var(--brand)" />
      <path d="M8 9.5h8.5M8 14h12M8 18.5h6" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" />
      <circle cx="20" cy="9.5" r="1.4" fill="#8fd3b6" />
    </svg>
  );
}
