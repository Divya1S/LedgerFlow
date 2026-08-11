import { FormEvent, useState } from 'react';
import { api, ApiError, Session } from '../api';

export default function AuthView({ onLogin }: { onLogin: (s: Session) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      if (mode === 'register') {
        await api.register(email, password, fullName);
      }
      onLogin(await api.login(email, password));
    } catch (e) {
      setError((e as ApiError).message ?? 'Something went wrong');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel" style={{ maxWidth: 460, margin: '40px auto' }}>
      <h2>{mode === 'login' ? 'Sign in' : 'Create your account'}</h2>
      <form className="stack" onSubmit={submit}>
        {mode === 'register' && (
          <label>
            Full name
            <input value={fullName} onChange={(e) => setFullName(e.target.value)} required maxLength={200} />
          </label>
        )}
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Password {mode === 'register' && <span className="muted">(12+ characters)</span>}
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                 required minLength={mode === 'register' ? 12 : 1} />
        </label>
        {error && <div className="notice error">{error}</div>}
        <div className="row">
          <button className="primary" disabled={busy}>
            {mode === 'login' ? 'Sign in' : 'Register and sign in'}
          </button>
          <button type="button" className="secondary"
                  onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
            {mode === 'login' ? 'New here? Register' : 'Have an account? Sign in'}
          </button>
        </div>
      </form>
    </div>
  );
}
