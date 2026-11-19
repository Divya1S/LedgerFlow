import { FormEvent, useState } from 'react';
import { Account, api, errorMessage, MoneyRequest, newIdempotencyKey } from '../api';
import { AccountSelect } from '../components';
import { arrivalText, formatDate, formatMoney, parseAmount } from '../format';
import { navigate, useLoad } from '../hooks';
import { Icon } from '../icons';
import {
  AmountField, Button, Card, CopyId, EmptyState, Field, LoadingRows, Modal, Notice, PageHeader, StatusBadge, useToast,
} from '../ui';

function requestLink(id: string): string {
  return `${window.location.origin}${window.location.pathname}#/pay/${id}`;
}

export default function RequestsView() {
  const toast = useToast();
  const requests = useLoad(() => api.moneyRequests(), []);
  const accounts = useLoad(() => api.accounts(), []);
  const [creating, setCreating] = useState(false);
  const [sharing, setSharing] = useState<MoneyRequest | null>(null);

  const receiving = (accounts.data ?? []).filter((a) => a.status !== 'CLOSED');

  async function cancel(request: MoneyRequest) {
    try {
      await api.cancelMoneyRequest(request.id);
      toast('Request cancelled');
      requests.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  }

  return (
    <div className="stack-lg">
      <PageHeader
        title="Money requests"
        description="Ask someone to pay you. Share the link and the money lands in your account when they pay."
        actions={<Button variant="primary" icon="plus" disabled={receiving.length === 0}
                         onClick={() => setCreating(true)}>Request money</Button>}
      />

      {requests.error && <Notice tone="error">{requests.error}</Notice>}

      <Card flush>
        {!requests.data && !requests.error && <LoadingRows />}
        {requests.data && requests.data.length === 0 && (
          <EmptyState icon="receive" title="No requests yet"
                      action={receiving.length > 0
                        ? <Button onClick={() => setCreating(true)}>Request money</Button>
                        : <a className="btn" href="#/accounts?open=1">Open an account first</a>}>
            Splitting a bill or collecting for a gift? Create a request and share its link.
          </EmptyState>
        )}
        {requests.data && requests.data.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>For</th><th>Status</th><th className="right">Amount</th><th /></tr></thead>
              <tbody>
                {requests.data.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <div className="primary">{r.note ?? 'Money request'}</div>
                      <div className="secondary">
                        Created {formatDate(r.createdAt)}
                        {r.status === 'PENDING' ? ` · expires ${formatDate(r.expiresAt)}` : ''}
                      </div>
                    </td>
                    <td>
                      <StatusBadge status={r.status} label={r.status === 'PENDING' ? 'Waiting to be paid' : undefined} />
                      {r.status === 'PAID' && r.payerName && <div className="secondary">by {r.payerName}</div>}
                    </td>
                    <td className="amount">{formatMoney(r.amountMinorUnits, r.currency)}</td>
                    <td className="right nowrap">
                      {r.status === 'PENDING' && (
                        <>
                          <Button size="small" variant="ghost" icon="link" onClick={() => setSharing(r)}>Share</Button>
                          <Button size="small" variant="ghost" onClick={() => cancel(r)}>Cancel</Button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <CreateRequestModal open={creating} accounts={receiving} onClose={() => setCreating(false)}
                          onCreated={(request) => { setCreating(false); setSharing(request); requests.reload(); }} />

      <Modal open={sharing !== null} title="Share this request" onClose={() => setSharing(null)}
             description={sharing ? `${formatMoney(sharing.amountMinorUnits)}${sharing.note ? ` for ${sharing.note}` : ''}` : undefined}
             footer={<Button variant="primary" onClick={() => setSharing(null)}>Done</Button>}>
        {sharing && (
          <div className="stack">
            <p className="muted">
              Send this link to the person who should pay. They sign in, choose an account and confirm.
              It can be paid once.
            </p>
            <CopyId value={requestLink(sharing.id)} label="Copy link" />
          </div>
        )}
      </Modal>
    </div>
  );
}

function CreateRequestModal({ open, accounts, onClose, onCreated }: {
  open: boolean; accounts: Account[]; onClose: () => void; onCreated: (request: MoneyRequest) => void;
}) {
  const [accountId, setAccountId] = useState('');
  const [amountText, setAmountText] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);

  const destination = accountId || accounts[0]?.id || '';
  const amount = parseAmount(amountText);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setTouched(true);
    if (amount === null || !destination) return;
    setBusy(true);
    setError(null);
    try {
      const request = await api.createMoneyRequest(destination, amount, note.trim());
      setAmountText('');
      setNote('');
      setTouched(false);
      onCreated(request);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} title="Request money" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <AmountField value={amountText} onChange={setAmountText}
                     error={touched && amount === null ? 'Enter an amount, for example 40 or 40.50.' : null} />
        <Field label="What is it for?" hint="Optional. Shown to the person paying.">
          {(id) => <input id={id} className="input" value={note} maxLength={280}
                          placeholder="Dinner on Friday" onChange={(e) => setNote(e.target.value)} />}
        </Field>
        <AccountSelect label="Receive into" accounts={accounts} value={destination}
                       onChange={setAccountId} showBalance={false} />
        {error && <Notice tone="error">{error}</Notice>}
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" busy={busy}>Create request</Button>
        </div>
      </form>
    </Modal>
  );
}

/** The page a shared request link opens: who is asking, how much, and a way to pay. */
export function PayRequestView({ requestId }: { requestId: string }) {
  const request = useLoad(() => api.moneyRequest(requestId), [requestId]);
  const accounts = useLoad(() => api.accounts(), []);
  const [accountId, setAccountId] = useState('');
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [paid, setPaid] = useState(false);
  const [heldUntil, setHeldUntil] = useState<string | null>(null);

  const payable = (accounts.data ?? []).filter((a) => a.status === 'ACTIVE');
  const source = accountId || payable[0]?.id || '';
  const r = request.data;

  async function pay() {
    if (!source) return;
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.payMoneyRequest(requestId, source, key);
      setHeldUntil(data.heldUntil ?? null);
      setPaid(true);
      request.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  if (request.error && !r) {
    return (
      <>
        <PageHeader title="Money request" back={{ label: 'Requests', href: '#/requests' }} />
        <Notice tone="error">{request.error}</Notice>
      </>
    );
  }
  if (!r) return <Card flush><LoadingRows /></Card>;

  return (
    <>
      <PageHeader title="Money request" back={{ label: 'Requests', href: '#/requests' }} />
      <div className="cols-form">
        <Card>
          <div className="stack">
            <div>
              <div className="muted">{r.mine ? 'You asked for' : `${r.requesterName} is asking for`}</div>
              <div style={{ fontSize: 32, fontWeight: 600, letterSpacing: '-0.02em' }}>
                {formatMoney(r.amountMinorUnits, r.currency)}
              </div>
              {r.note && <div style={{ marginTop: 4 }}>{r.note}</div>}
            </div>

            {paid || r.status === 'PAID' ? (
              <>
                {paid && heldUntil ? (
                  <Notice tone="warn">
                    <strong>Paid, and on hold.</strong> This is your first payment to {r.requesterName}, so it
                    arrives {arrivalText(heldUntil)}. You can cancel it before then
                    under <a href="#/held">Held transfers</a>.
                  </Notice>
                ) : (
                  <Notice tone="success">
                    <strong>Paid.</strong> {paid ? `${formatMoney(r.amountMinorUnits, r.currency)} was sent to ${r.requesterName}.`
                      : 'This request has been paid.'}
                  </Notice>
                )}
                <div className="row"><Button onClick={() => navigate('/transactions')}>View transactions</Button></div>
              </>
            ) : r.status !== 'PENDING' ? (
              <Notice tone="warn">
                This request {r.status === 'EXPIRED' ? 'has expired' : 'was cancelled'} and can no longer be paid.
              </Notice>
            ) : r.mine ? (
              <>
                <Notice tone="info">This is your own request. Share the link with the person who should pay.</Notice>
                <CopyId value={requestLink(r.id)} label="Copy link" />
              </>
            ) : payable.length === 0 ? (
              <Notice tone="warn">
                You need an active wallet with money in it to pay this. <a href="#/accounts?open=1">Open an account</a>
              </Notice>
            ) : (
              <>
                <AccountSelect label="Pay from" accounts={payable} value={source} onChange={setAccountId} />
                {error && <Notice tone="error">{error}</Notice>}
                <Button variant="primary" size="large" busy={busy} onClick={pay}>
                  <Icon name="send" size={16} /> Pay {formatMoney(r.amountMinorUnits, r.currency)}
                </Button>
              </>
            )}
          </div>
        </Card>
      </div>
    </>
  );
}
