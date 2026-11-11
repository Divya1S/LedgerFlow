import { FormEvent, useState } from 'react';
import { api, errorMessage, Session } from '../api';
import { formatDate, initials } from '../format';
import { useLoad } from '../hooks';
import { Button, Card, Field, Notice, PageHeader, useToast } from '../ui';

export default function SettingsView({ session, onNameChange, onSignOut }: {
  session: Session; onNameChange: (name: string) => void; onSignOut: () => void;
}) {
  const toast = useToast();
  const profile = useLoad(() => api.me(), []);

  const [name, setName] = useState(session.fullName);
  const [savingName, setSavingName] = useState(false);
  const [nameError, setNameError] = useState<string | null>(null);

  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const [savingPassword, setSavingPassword] = useState(false);
  const [passwordError, setPasswordError] = useState<string | null>(null);

  async function saveName(event: FormEvent) {
    event.preventDefault();
    setSavingName(true);
    setNameError(null);
    try {
      const updated = await api.updateProfile(name.trim());
      onNameChange(updated.fullName);
      toast('Name updated');
    } catch (e) {
      setNameError(errorMessage(e));
    } finally {
      setSavingName(false);
    }
  }

  const tooShort = next.length > 0 && next.length < 12;
  const mismatch = again.length > 0 && again !== next;

  async function savePassword(event: FormEvent) {
    event.preventDefault();
    if (tooShort || next !== again) return;
    setSavingPassword(true);
    setPasswordError(null);
    try {
      await api.changePassword(current, next);
      setCurrent('');
      setNext('');
      setAgain('');
      toast('Password changed');
    } catch (e) {
      setPasswordError(errorMessage(e));
    } finally {
      setSavingPassword(false);
    }
  }

  return (
    <div className="stack-lg">
      <PageHeader title="Settings" description="Your profile and how you sign in." />

      <div className="cols-2">
        <Card title="Profile">
          <form className="stack" onSubmit={saveName}>
            <div className="row" style={{ gap: 14 }}>
              <span className="avatar large">{initials(name || session.fullName)}</span>
              <div>
                <div style={{ fontWeight: 600 }}>{session.fullName}</div>
                <div className="muted" style={{ fontSize: 13 }}>
                  {session.email}
                  {profile.data ? ` · Member since ${formatDate(profile.data.createdAt)}` : ''}
                </div>
              </div>
            </div>
            <Field label="Full name" hint="Shown to people you send money to and merchants you pay." error={nameError}>
              {(id) => <input id={id} className="input" value={name} maxLength={200} required
                              autoComplete="name" onChange={(e) => setName(e.target.value)} />}
            </Field>
            <Field label="Email" hint="Your email is how you sign in and cannot be changed here.">
              {(id) => <input id={id} className="input" value={session.email} disabled readOnly />}
            </Field>
            <div>
              <Button type="submit" variant="primary" busy={savingName}
                      disabled={!name.trim() || name.trim() === session.fullName}>Save changes</Button>
            </div>
          </form>
        </Card>

        <div className="stack">
          <Card title="Password">
            <form className="stack" onSubmit={savePassword}>
              <Field label="Current password">
                {(id) => <input id={id} className="input" type="password" value={current} required
                                autoComplete="current-password" onChange={(e) => setCurrent(e.target.value)} />}
              </Field>
              <Field label="New password" hint="At least 12 characters."
                     error={tooShort ? `${12 - next.length} more characters needed.` : null}>
                {(id) => <input id={id} className={`input ${tooShort ? 'invalid' : ''}`} type="password" value={next}
                                required autoComplete="new-password" onChange={(e) => setNext(e.target.value)} />}
              </Field>
              <Field label="Repeat new password" error={mismatch ? 'The two passwords do not match.' : null}>
                {(id) => <input id={id} className={`input ${mismatch ? 'invalid' : ''}`} type="password" value={again}
                                required autoComplete="new-password" onChange={(e) => setAgain(e.target.value)} />}
              </Field>
              {passwordError && <Notice tone="error">{passwordError}</Notice>}
              <div>
                <Button type="submit" variant="primary" busy={savingPassword}
                        disabled={!current || !next || tooShort || next !== again}>Change password</Button>
              </div>
            </form>
          </Card>

          <Card title="This session">
            <div className="stack">
              <p className="muted">
                You stay signed in on this browser tab for up to an hour. Closing the tab signs you out.
              </p>
              <div className="row">
                <Button icon="logout" onClick={onSignOut}>Sign out</Button>
                <a className="btn ghost" href="#/activity">Review recent activity</a>
              </div>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
