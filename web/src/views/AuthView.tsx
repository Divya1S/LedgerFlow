import { FormEvent, useState } from 'react';
import { api, errorMessage, isApiError, Session } from '../api';
import { Icon, Logo } from '../icons';
import { Button, Field, Notice } from '../ui';

export default function AuthView({ onLogin, notice }: { onLogin: (s: Session) => void; notice: string | null }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const registering = mode === 'register';
  const passwordTooShort = registering && password.length > 0 && password.length < 12;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (passwordTooShort) return;
    setError(null);
    setBusy(true);
    try {
      if (registering) await api.register(email.trim(), password, fullName.trim());
      onLogin(await api.login(email.trim(), password));
    } catch (e) {
      // After a successful registration a failed sign-in should not look
      // like the registration failed.
      setError(isApiError(e) && e.code === 'EMAIL_TAKEN'
        ? 'There is already a profile with this email. Sign in instead.'
        : errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  function switchMode() {
    setMode(registering ? 'login' : 'register');
    setError(null);
  }

  return (
    <div className="auth">
      <div className="auth-form">
        <div className="inner">
          <div className="brand" style={{ padding: 0 }}><Logo size={30} /> LedgerFlow</div>
          <h1>{registering ? 'Create your profile' : 'Sign in'}</h1>
          <p className="muted" style={{ marginBottom: 24 }}>
            {registering
              ? 'It takes a minute. You can open wallets and merchant accounts afterwards.'
              : 'Welcome back. Enter your details to continue.'}
          </p>

          <form className="stack" onSubmit={submit} noValidate={false}>
            {notice && <Notice tone="info">{notice}</Notice>}
            {registering && (
              <Field label="Full name" hint="Shown to people you send money to.">
                {(id) => <input id={id} className="input" value={fullName} autoComplete="name"
                                onChange={(e) => setFullName(e.target.value)} required maxLength={200} />}
              </Field>
            )}
            <Field label="Email">
              {(id) => <input id={id} className="input" type="email" value={email} autoComplete="email"
                              onChange={(e) => setEmail(e.target.value)} required />}
            </Field>
            <Field label="Password"
                   hint={registering ? 'At least 12 characters. A short sentence works well.' : undefined}
                   error={passwordTooShort ? `${12 - password.length} more characters needed.` : null}>
              {(id) => <input id={id} className={`input ${passwordTooShort ? 'invalid' : ''}`} type="password"
                              value={password} autoComplete={registering ? 'new-password' : 'current-password'}
                              onChange={(e) => setPassword(e.target.value)} required />}
            </Field>
            {error && <Notice tone="error">{error}</Notice>}
            <Button type="submit" variant="primary" size="large" block busy={busy}>
              {registering ? 'Create profile' : 'Sign in'}
            </Button>
          </form>

          <p className="muted" style={{ marginTop: 20 }}>
            {registering ? 'Already have a profile? ' : 'New to LedgerFlow? '}
            <button type="button" className="link-button" onClick={switchMode}>
              {registering ? 'Sign in' : 'Create a profile'}
            </button>
          </p>
        </div>
      </div>

      <aside className="auth-aside">
        <h2>Money that always adds up.</h2>
        <ul>
          <li>
            <Icon name="scale" size={20} />
            <span><b>Every cent is accounted for</b>
              Each movement is written to both sides of a ledger, and the two sides must match before it is saved.</span>
          </li>
          <li>
            <Icon name="shield" size={20} />
            <span><b>Safe to retry</b>
              A double click or a dropped connection never sends the same money twice.</span>
          </li>
          <li>
            <Icon name="chart" size={20} />
            <span><b>See where it goes</b>
              Balances, cash flow and spending by category, straight from the same records.</span>
          </li>
        </ul>
      </aside>
    </div>
  );
}
