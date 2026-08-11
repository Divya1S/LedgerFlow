import { useState } from 'react';
import { api, Session } from './api';
import AuthView from './views/AuthView';
import AccountsView from './views/AccountsView';
import MoveMoneyView from './views/MoveMoneyView';
import PaymentsView from './views/PaymentsView';
import HistoryView from './views/HistoryView';

type Tab = 'accounts' | 'move' | 'payments' | 'history';

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [tab, setTab] = useState<Tab>('accounts');

  if (!session) {
    return (
      <>
        <header className="topbar">
          <h1>Ledger<span className="logo-accent">Flow</span></h1>
        </header>
        <AuthView onLogin={setSession} />
      </>
    );
  }

  return (
    <>
      <header className="topbar">
        <h1>Ledger<span className="logo-accent">Flow</span></h1>
        <div className="row">
          <span className="session">{session.email}</span>
          <button className="secondary" onClick={() => { api.setToken(null); setSession(null); }}>
            Sign out
          </button>
        </div>
      </header>
      <nav className="tabs">
        {(['accounts', 'move', 'payments', 'history'] as Tab[]).map((t) => (
          <button key={t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>
            {{ accounts: 'Accounts', move: 'Move Money', payments: 'Payments', history: 'History' }[t]}
          </button>
        ))}
      </nav>
      {tab === 'accounts' && <AccountsView />}
      {tab === 'move' && <MoveMoneyView />}
      {tab === 'payments' && <PaymentsView />}
      {tab === 'history' && <HistoryView />}
    </>
  );
}
