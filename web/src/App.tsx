import { useCallback, useEffect, useState } from 'react';
import { api, Inbox, Session } from './api';
import { initials, relativeTime } from './format';
import { navigate, Route, useClickOutside, useEscape, useRoute } from './hooks';
import { Icon, IconName, Logo } from './icons';
import { Button, ToastProvider } from './ui';
import AuthView from './views/AuthView';
import OverviewView from './views/OverviewView';
import AccountsView from './views/AccountsView';
import AccountDetailView from './views/AccountDetailView';
import TransactionsView from './views/TransactionsView';
import MoveMoneyView from './views/MoveMoneyView';
import ScheduledView from './views/ScheduledView';
import RequestsView, { PayRequestView } from './views/RequestsView';
import RecipientsView from './views/RecipientsView';
import PaymentsView from './views/PaymentsView';
import DisputesView from './views/DisputesView';
import ActivityView from './views/ActivityView';
import SettingsView from './views/SettingsView';

const STORAGE_KEY = 'ledgerflow.session';

/** Restores a signed-in session after a page reload, unless its token has expired. */
function restoreSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    const payload = JSON.parse(atob(session.token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    if (typeof payload.exp === 'number' && payload.exp * 1000 < Date.now() + 5000) return null;
    return session;
  } catch {
    return null;
  }
}

interface NavLink { path: string; label: string; icon: IconName; }

const NAV: { heading?: string; links: NavLink[] }[] = [
  { links: [
    { path: '', label: 'Overview', icon: 'overview' },
    { path: 'accounts', label: 'Accounts', icon: 'wallet' },
    { path: 'transactions', label: 'Transactions', icon: 'list' },
  ] },
  { heading: 'Move money', links: [
    { path: 'send', label: 'Send and add money', icon: 'send' },
    { path: 'scheduled', label: 'Scheduled', icon: 'calendar' },
    { path: 'requests', label: 'Requests', icon: 'receive' },
    { path: 'recipients', label: 'Recipients', icon: 'users' },
  ] },
  { heading: 'Business', links: [
    { path: 'payments', label: 'Payments', icon: 'store' },
    { path: 'disputes', label: 'Disputes', icon: 'scale' },
  ] },
  { heading: 'You', links: [
    { path: 'activity', label: 'Activity', icon: 'activity' },
    { path: 'settings', label: 'Settings', icon: 'settings' },
  ] },
];

function isActive(route: Route, link: NavLink): boolean {
  const first = route.segments[0] ?? '';
  if (link.path === 'requests') return first === 'requests' || first === 'pay';
  return first === link.path;
}

export default function App() {
  const [session, setSession] = useState<Session | null>(() => {
    const restored = restoreSession();
    if (restored) api.setToken(restored.token);
    return restored;
  });
  const [notice, setNotice] = useState<string | null>(null);

  const signOut = useCallback((reason?: string) => {
    api.setToken(null);
    sessionStorage.removeItem(STORAGE_KEY);
    setSession(null);
    setNotice(reason ?? null);
  }, []);

  useEffect(() => {
    api.setUnauthorizedHandler(() => signOut('Your session ended. Sign in again to continue.'));
    return () => api.setUnauthorizedHandler(null);
  }, [signOut]);

  function signIn(next: Session) {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    setNotice(null);
    setSession(next);
  }

  function updateName(fullName: string) {
    if (!session) return;
    signIn({ ...session, fullName });
  }

  return (
    <ToastProvider>
      {session
        ? <Shell session={session} onSignOut={() => signOut()} onNameChange={updateName} />
        : <AuthView onLogin={signIn} notice={notice} />}
    </ToastProvider>
  );
}

function Shell({ session, onSignOut, onNameChange }: {
  session: Session; onSignOut: () => void; onNameChange: (name: string) => void;
}) {
  const route = useRoute();
  const [menuOpen, setMenuOpen] = useState(false);
  const routeKey = route.segments.join('/');

  // Close the mobile menu whenever the page changes.
  useEffect(() => setMenuOpen(false), [routeKey]);

  return (
    <div className="shell">
      {menuOpen && <div className="scrim" onClick={() => setMenuOpen(false)} />}
      <aside className={`sidebar ${menuOpen ? 'open' : ''}`}>
        <a className="brand" href="#/"><Logo /> LedgerFlow</a>
        <nav className="nav" aria-label="Main">
          {NAV.map((group, i) => (
            <div key={i} style={{ display: 'contents' }}>
              {group.heading && <div className="nav-heading">{group.heading}</div>}
              {group.links.map((link) => (
                <a key={link.path} href={`#/${link.path}`}
                   className={`nav-item ${isActive(route, link) ? 'active' : ''}`}
                   aria-current={isActive(route, link) ? 'page' : undefined}>
                  <Icon name={link.icon} /> {link.label}
                </a>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">
          Every balance here is backed by ledger entries that add up to zero.
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <Button className="menu-button" variant="ghost" icon="menu" aria-label="Open menu"
                  onClick={() => setMenuOpen(true)} />
          <div className="spacer" />
          <Button variant="primary" size="small" icon="send" onClick={() => navigate('/send')}>Send money</Button>
          <NotificationBell />
          <UserMenu session={session} onSignOut={onSignOut} />
        </header>
        <main className="content">
          <Screen route={route} session={session} onNameChange={onNameChange} onSignOut={onSignOut} />
        </main>
      </div>
    </div>
  );
}

function Screen({ route, session, onNameChange, onSignOut }: {
  route: Route; session: Session; onNameChange: (name: string) => void; onSignOut: () => void;
}) {
  const [first = '', second] = route.segments;
  switch (first) {
    case '':
    case 'overview':
      return <OverviewView session={session} />;
    case 'accounts':
      return second ? <AccountDetailView key={second} accountId={second} />
        : <AccountsView autoOpen={route.query.get('open') === '1'} />;
    case 'transactions':
      return <TransactionsView query={route.query} />;
    case 'send':
      return <MoveMoneyView key={route.query.toString()} query={route.query} />;
    case 'scheduled':
      return <ScheduledView />;
    case 'requests':
      return <RequestsView />;
    case 'pay':
      return second ? <PayRequestView key={second} requestId={second} /> : <RequestsView />;
    case 'recipients':
      return <RecipientsView />;
    case 'payments':
      return <PaymentsView query={route.query} />;
    case 'disputes':
      return <DisputesView />;
    case 'activity':
      return <ActivityView />;
    case 'settings':
      return <SettingsView session={session} onNameChange={onNameChange} onSignOut={onSignOut} />;
    default:
      return (
        <div className="card">
          <div className="empty">
            <h3>This page does not exist</h3>
            <p>The link may be old or mistyped.</p>
            <a className="btn" href="#/">Go to overview</a>
          </div>
        </div>
      );
  }
}

function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [inbox, setInbox] = useState<Inbox | null>(null);
  const close = useCallback(() => setOpen(false), []);
  const ref = useClickOutside<HTMLDivElement>(open, close);
  useEscape(open, close);

  const refresh = useCallback(() => {
    api.inbox().then(setInbox).catch(() => undefined);
  }, []);

  // New notifications arrive a moment after the action that caused them,
  // so the count is refreshed quietly in the background.
  useEffect(() => {
    refresh();
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') refresh();
    }, 20_000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  async function markAll() {
    await api.markAllNotificationsRead().catch(() => undefined);
    refresh();
  }

  async function read(id: string) {
    await api.markNotificationRead(id).catch(() => undefined);
    refresh();
  }

  const unread = inbox?.unreadCount ?? 0;
  return (
    <div className="anchor" ref={ref}>
      <Button className="bell icon-only" variant="ghost" icon="bell" aria-expanded={open}
              aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
              onClick={() => { setOpen(!open); if (!open) refresh(); }}>
        {unread > 0 ? <span className="dot">{unread > 9 ? '9+' : unread}</span> : null}
      </Button>
      {open && (
        <div className="popover inbox" role="dialog" aria-label="Notifications">
          <div className="inbox-head">
            <h2 className="grow">Notifications</h2>
            {unread > 0 && <button className="link-button" onClick={markAll}>Mark all as read</button>}
          </div>
          <div className="inbox-list">
            {inbox && inbox.items.length === 0 && (
              <div className="empty" style={{ padding: '28px 20px' }}>
                <h3>Nothing yet</h3>
                <p>You will see payments, refunds and transfers you receive here.</p>
              </div>
            )}
            {inbox?.items.map((item) => (
              <button key={item.id} className={`inbox-item ${item.read ? '' : 'unread'}`}
                      onClick={() => { if (!item.read) read(item.id); }}>
                <span className="marker" />
                <span>
                  <span className="title" style={{ display: 'block' }}>{item.title}</span>
                  <span className="body" style={{ display: 'block' }}>{item.body}</span>
                  <span className="when" style={{ display: 'block' }}>{relativeTime(item.createdAt)}</span>
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function UserMenu({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  const ref = useClickOutside<HTMLDivElement>(open, close);
  useEscape(open, close);
  return (
    <div className="anchor" ref={ref}>
      <button className="avatar" aria-label="Your account" aria-expanded={open} onClick={() => setOpen(!open)}>
        {initials(session.fullName)}
      </button>
      {open && (
        <div className="popover menu">
          <div className="who">
            <div>{session.fullName}</div>
            <div>{session.email}</div>
          </div>
          <a href="#/settings" onClick={close}><Icon name="settings" size={16} /> Settings</a>
          <a href="#/activity" onClick={close}><Icon name="activity" size={16} /> Activity</a>
          <button onClick={onSignOut}><Icon name="logout" size={16} /> Sign out</button>
        </div>
      )}
    </div>
  );
}
