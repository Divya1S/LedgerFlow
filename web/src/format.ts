// Formatting and parsing for money and dates. Amounts travel as whole
// minor units (cents) everywhere; these helpers are the only place they
// become text, and the only place typed text becomes an amount.

const moneyFormats = new Map<string, Intl.NumberFormat>();

export function formatMoney(minorUnits: number, currency = 'USD'): string {
  let format = moneyFormats.get(currency);
  if (!format) {
    format = new Intl.NumberFormat('en-US', { style: 'currency', currency });
    moneyFormats.set(currency, format);
  }
  return format.format(minorUnits / 100);
}

/** "+$12.50" for money in, "−$12.50" for money out (a real minus sign, not a hyphen). */
export function formatSigned(minorUnits: number, currency = 'USD'): string {
  const text = formatMoney(Math.abs(minorUnits), currency);
  if (minorUnits > 0) return `+${text}`;
  if (minorUnits < 0) return `−${text}`;
  return text;
}

/** Short axis labels: $950, $1.2K, $3.4M. */
export function formatCompactMoney(minorUnits: number): string {
  const dollars = minorUnits / 100;
  const abs = Math.abs(dollars);
  if (abs >= 1_000_000) return `$${trim(dollars / 1_000_000)}M`;
  if (abs >= 10_000) return `$${Math.round(dollars / 1_000)}K`;
  if (abs >= 1_000) return `$${trim(dollars / 1_000)}K`;
  return `$${Math.round(dollars)}`;
}

function trim(value: number): string {
  return value.toFixed(1).replace(/\.0$/, '');
}

/**
 * Turns what a person typed ("1,250.5", "40") into minor units without
 * going through floating point. Returns null when it is not an amount.
 */
export function parseAmount(text: string): number | null {
  const cleaned = text.replace(/[$,\s]/g, '');
  const match = /^(\d{1,10})(?:\.(\d{1,2}))?$/.exec(cleaned);
  if (!match) return null;
  const cents = (match[2] ?? '').padEnd(2, '0');
  const value = Number(match[1]) * 100 + Number(cents);
  return value > 0 ? value : null;
}

const dateFormat = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
const shortDateFormat = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' });
const timeFormat = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' });

export function formatDate(iso: string): string {
  return dateFormat.format(new Date(iso));
}

export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  return `${dateFormat.format(date)}, ${timeFormat.format(date)}`;
}

export function formatTime(iso: string): string {
  return timeFormat.format(new Date(iso));
}

/** A calendar day ("2026-11-10") shown as "Nov 10", without time zone drift. */
export function formatDay(day: string): string {
  const [year, month, date] = day.split('-').map(Number);
  return shortDateFormat.format(new Date(year, month - 1, date));
}

/** "Today", "Yesterday", or the date. Used to group lists by day. */
export function dayLabel(iso: string): string {
  const date = new Date(iso);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (sameDay(date, today)) return 'Today';
  if (sameDay(date, yesterday)) return 'Yesterday';
  return dateFormat.format(date);
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

export function relativeTime(iso: string): string {
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 45) return 'Just now';
  if (seconds < 3600) return `${Math.max(1, Math.round(seconds / 60))} min ago`;
  if (seconds < 86_400) return `${Math.round(seconds / 3600)} hr ago`;
  if (seconds < 7 * 86_400) return `${Math.round(seconds / 86_400)} d ago`;
  return formatDate(iso);
}

/** Today's date as "YYYY-MM-DD" in the viewer's own time zone. */
export function todayIso(offsetDays = 0): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

export function titleCase(code: string): string {
  const words = code.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

const CATEGORY_LABELS: Record<string, string> = {
  GROCERIES: 'Groceries',
  DINING: 'Dining',
  SHOPPING: 'Shopping',
  TRAVEL: 'Travel',
  TRANSPORT: 'Transport',
  ENTERTAINMENT: 'Entertainment',
  UTILITIES: 'Utilities',
  HEALTH: 'Health',
  SERVICES: 'Services',
  OTHER: 'Other',
  TRANSFERS: 'Transfers to people',
  WITHDRAWALS: 'Withdrawals',
  REFUNDS: 'Refunds given',
  EVERYTHING_ELSE: 'Everything else',
};

export const MERCHANT_CATEGORIES = ['GROCERIES', 'DINING', 'SHOPPING', 'TRAVEL', 'TRANSPORT',
  'ENTERTAINMENT', 'UTILITIES', 'HEALTH', 'SERVICES', 'OTHER'];

export function categoryLabel(code: string | null): string {
  if (!code) return 'Other';
  return CATEGORY_LABELS[code] ?? titleCase(code);
}

const REASON_TEXT: Record<string, string> = {
  FIRST_PAYMENT: 'You have not sent money to them before.',
  RECIPIENT_ACCOUNT_NEW: 'Their account was opened in the last few days.',
  RECIPIENT_PAID_BY_STRANGERS: 'Several people who never paid them before have sent them money today.',
};

/** A hold reason code in plain words. */
export function reasonText(code: string): string {
  return REASON_TEXT[code] ?? titleCase(code);
}

/** "4 hours", "24 hours", "30 minutes". */
export function durationText(minutes: number): string {
  if (minutes % 60 === 0) {
    const hours = minutes / 60;
    return `${hours} ${hours === 1 ? 'hour' : 'hours'}`;
  }
  return `${minutes} minutes`;
}

/** "today at 6:10 PM", "tomorrow at 9:00 AM", or "Nov 21 at 9:00 AM". */
export function arrivalText(iso: string): string {
  const when = new Date(iso);
  const label = dayLabel(iso);
  const day = label === 'Today' ? 'today' : label === 'Yesterday' ? 'yesterday'
    : sameDay(when, new Date(Date.now() + 86_400_000)) ? 'tomorrow'
    : shortDateFormat.format(when);
  return `${day} at ${timeFormat.format(when)}`;
}

/** What a transaction type is called on screen. */
export function typeLabel(type: string): string {
  switch (type) {
    case 'HOLD': return 'Transfer';
    case 'HOLD_RELEASE': return 'Transfer';
    case 'HOLD_RETURN': return 'Transfer returned';
    default: return titleCase(type);
  }
}

export function accountTypeLabel(type: string): string {
  return type === 'MERCHANT' ? 'Merchant account' : 'Wallet';
}

/** "1f3a…9c2d": enough of an id to recognise it, short enough to read. */
export function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id;
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  const first = parts[0][0];
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase();
}
