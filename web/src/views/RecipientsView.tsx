import { FormEvent, useState } from 'react';
import { api, errorMessage, Payee, Recipient } from '../api';
import { accountTypeLabel, categoryLabel, initials } from '../format';
import { useLoad } from '../hooks';
import { Button, Card, ConfirmDialog, EmptyState, Field, LoadingRows, Modal, Notice, PageHeader, useToast } from '../ui';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function RecipientsView() {
  const toast = useToast();
  const recipients = useLoad(() => api.recipients(), []);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Recipient | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    if (!removing) return;
    setBusy(true);
    setError(null);
    try {
      await api.removeRecipient(removing.id);
      toast(`${removing.nickname} removed`);
      setRemoving(null);
      recipients.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Recipients"
        description="People and businesses you send money to. Save them once and pick them by name."
        actions={<Button variant="primary" icon="plus" onClick={() => setAdding(true)}>Add recipient</Button>}
      />

      {recipients.error && <Notice tone="error">{recipients.error}</Notice>}

      <Card flush>
        {!recipients.data && !recipients.error && <LoadingRows />}
        {recipients.data && recipients.data.length === 0 && (
          <EmptyState icon="users" title="No saved recipients"
                      action={<Button onClick={() => setAdding(true)}>Add recipient</Button>}>
            Ask someone for their account ID, check the name it belongs to, and save it here.
          </EmptyState>
        )}
        {recipients.data && recipients.data.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Name</th><th className="hide-narrow">Type</th><th /></tr></thead>
              <tbody>
                {recipients.data.map((r) => {
                  const closed = r.accountStatus === 'CLOSED';
                  const merchant = r.accountType === 'MERCHANT';
                  return (
                    <tr key={r.id}>
                      <td>
                        <div className="party">
                          <span className="avatar neutral" style={{ cursor: 'default' }}>{initials(r.nickname)}</span>
                          <div>
                            <div className="primary truncate">{r.nickname}</div>
                            <div className="secondary truncate">
                              {r.nickname !== r.displayName ? r.displayName : accountTypeLabel(r.accountType)}
                              {closed ? ' · account closed' : ''}
                            </div>
                          </div>
                        </div>
                      </td>
                      <td className="hide-narrow muted">
                        {merchant ? `Business · ${categoryLabel(r.category)}` : 'Person'}
                      </td>
                      <td className="right nowrap">
                        {!closed && (
                          <a className="btn small" href={merchant ? `#/payments?to=${r.accountId}` : `#/send?to=${r.accountId}`}>
                            {merchant ? 'Pay' : 'Send'}
                          </a>
                        )}{' '}
                        <Button size="small" variant="ghost" onClick={() => { setError(null); setRemoving(r); }}>Remove</Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <AddRecipientModal open={adding} onClose={() => setAdding(false)}
                         onAdded={() => { setAdding(false); recipients.reload(); }} />

      <ConfirmDialog open={removing !== null} title={`Remove ${removing?.nickname ?? ''}?`} confirmLabel="Remove" danger
                     busy={busy} error={error} onClose={() => setRemoving(null)} onConfirm={remove}>
        They are removed from your list only. Past transfers and scheduled transfers are not affected.
      </ConfirmDialog>
    </>
  );
}

function AddRecipientModal({ open, onClose, onAdded }: { open: boolean; onClose: () => void; onAdded: () => void }) {
  const toast = useToast();
  const [accountId, setAccountId] = useState('');
  const [nickname, setNickname] = useState('');
  const [payee, setPayee] = useState<Payee | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function reset() {
    setAccountId('');
    setNickname('');
    setPayee(null);
    setError(null);
  }

  async function check(event: FormEvent) {
    event.preventDefault();
    const id = accountId.trim();
    if (!UUID.test(id)) {
      setError('An account ID looks like 0198c2f4-7a1e-7c3b-9d42-5e6f7a8b9c0d.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const found = await api.lookupPayee(id);
      if (found.own) setError('That is one of your own accounts. They are always available when you send money.');
      else if (found.status === 'CLOSED') setError('That account is closed and cannot receive money.');
      else {
        setPayee(found);
        setNickname(found.displayName);
      }
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!payee) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await api.addRecipient(payee.accountId, nickname.trim());
      toast(`${saved.nickname} saved`);
      reset();
      onAdded();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  function close() {
    reset();
    onClose();
  }

  return (
    <Modal open={open} title="Add a recipient" onClose={close}
           description={payee ? 'Check this is who you mean, then save.' : 'Enter the account ID they gave you.'}>
      {!payee ? (
        <form className="stack" onSubmit={check}>
          <Field label="Account ID" error={error} hint="They can copy it from their account page.">
            {(id) => <input id={id} className={`input mono ${error ? 'invalid' : ''}`} value={accountId}
                            spellCheck={false} autoComplete="off" onChange={(e) => setAccountId(e.target.value)} />}
          </Field>
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <Button onClick={close}>Cancel</Button>
            <Button type="submit" variant="primary" busy={busy} disabled={!accountId.trim()}>Find account</Button>
          </div>
        </form>
      ) : (
        <form className="stack" onSubmit={save}>
          <Notice tone="success">
            <strong>{payee.displayName}</strong> {'·'} {accountTypeLabel(payee.type)}
            {payee.category ? ` · ${categoryLabel(payee.category)}` : ''}
          </Notice>
          <Field label="Save as" hint="A name that helps you recognise them. Only you see it.">
            {(id) => <input id={id} className="input" value={nickname} maxLength={80} required
                            onChange={(e) => setNickname(e.target.value)} />}
          </Field>
          {error && <Notice tone="error">{error}</Notice>}
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <Button onClick={() => { setPayee(null); setError(null); }}>Back</Button>
            <Button type="submit" variant="primary" busy={busy} disabled={!nickname.trim()}>Save recipient</Button>
          </div>
        </form>
      )}
    </Modal>
  );
}
