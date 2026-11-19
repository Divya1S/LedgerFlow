import { useState } from 'react';
import { api, errorMessage, HeldTransfer, newIdempotencyKey } from '../api';
import { arrivalText, formatDateTime, formatMoney, reasonText } from '../format';
import { useLoad } from '../hooks';
import { Icon } from '../icons';
import { Button, Card, ConfirmDialog, EmptyState, LoadingRows, Notice, PageHeader, StatusBadge, useToast } from '../ui';

/**
 * Transfers on hold: the ones you sent that can still be cancelled, money
 * on its way to you, and what happened to earlier ones.
 */
export default function HeldView() {
  const toast = useToast();
  const holds = useLoad(() => api.heldTransfers(), []);
  const [cancelling, setCancelling] = useState<HeldTransfer | null>(null);
  // One request ID per cancel attempt, reused if the click is retried.
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function cancel() {
    if (!cancelling) return;
    setBusy(true);
    setError(null);
    try {
      await api.cancelHeldTransfer(cancelling.id, key);
      toast(`Cancelled. ${formatMoney(cancelling.amountMinorUnits)} is back in ${cancelling.accountName}.`);
      setCancelling(null);
      setKey(newIdempotencyKey());
      holds.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  const outgoing = holds.data?.outgoing ?? [];
  const waiting = outgoing.filter((h) => h.status === 'HELD');
  const settled = outgoing.filter((h) => h.status !== 'HELD');
  const incoming = holds.data?.incoming ?? [];

  return (
    <div className="stack-lg">
      <PageHeader
        title="Held transfers"
        description="A first, larger payment to someone new waits here for an hour before it is delivered (a day if there are warning signs about the recipient). You can cancel it until then. It is delivered automatically; nobody has to approve it."
      />

      {holds.error && <Notice tone="error">{holds.error}</Notice>}
      {!holds.data && !holds.error && <Card flush><LoadingRows /></Card>}

      {holds.data && waiting.length === 0 && incoming.length === 0 && settled.length === 0 && (
        <Card flush>
          <EmptyState icon="shield" title="Nothing on hold"
                      action={<a className="btn" href="#/send">Send money</a>}>
            Most transfers are instant. Only a first payment of $200 or more to someone you have never
            paid is held, so a mistake or a scam can still be stopped.
          </EmptyState>
        </Card>
      )}

      {waiting.length > 0 && (
        <div className="stack">
          <h2>You sent, still on hold</h2>
          {waiting.map((h) => (
            <Card key={h.id}>
              <div className="stack">
                <div className="row between">
                  <div>
                    <div style={{ fontSize: 20, fontWeight: 600, letterSpacing: '-0.01em' }}>
                      {formatMoney(h.amountMinorUnits, h.currency)}{' '}
                      <span className="muted" style={{ fontSize: 14, fontWeight: 400 }}>to {h.counterparty}</span>
                    </div>
                    <div className="muted" style={{ fontSize: 13 }}>
                      From {h.accountName}{h.description ? ` · ${h.description}` : ''} {'·'} sent {formatDateTime(h.createdAt)}
                    </div>
                  </div>
                  <StatusBadge status="HELD" />
                </div>
                <dl className="facts left">
                  <dt>Arrives</dt><dd><strong>{arrivalText(h.releaseAt)}</strong>, unless you cancel</dd>
                  <dt>Why it is held</dt>
                  <dd>{h.reasons.map((reason) => <div key={reason}>{reasonText(reason)}</div>)}</dd>
                </dl>
                <div className="row between">
                  <span className="muted" style={{ fontSize: 13 }}>
                    <Icon name="shield" size={14} /> Not sure about this payment? Cancelling returns all of it at once.
                  </span>
                  <Button variant="danger" onClick={() => { setError(null); setCancelling(h); }}>Cancel transfer</Button>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {incoming.length > 0 && (
        <Card flush title="On its way to you" description="Delivered automatically at the time shown">
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>From</th><th>Arrives</th><th className="right">Amount</th></tr></thead>
              <tbody>
                {incoming.map((h) => (
                  <tr key={h.id}>
                    <td>
                      <div className="primary">{h.counterparty}</div>
                      <div className="secondary">Into {h.accountName}{h.description ? ` · ${h.description}` : ''}</div>
                    </td>
                    <td className="nowrap">{arrivalText(h.releaseAt)}</td>
                    <td className="amount">{formatMoney(h.amountMinorUnits, h.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {settled.length > 0 && (
        <Card flush title="Earlier">
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>To</th><th>Result</th><th className="right">Amount</th></tr></thead>
              <tbody>
                {settled.map((h) => (
                  <tr key={h.id}>
                    <td>
                      <div className="primary">{h.counterparty}</div>
                      <div className="secondary">From {h.accountName}{h.description ? ` · ${h.description}` : ''}</div>
                    </td>
                    <td>
                      <StatusBadge status={h.status} />
                      {h.resolvedAt && <div className="secondary">{formatDateTime(h.resolvedAt)}</div>}
                    </td>
                    <td className="amount">{formatMoney(h.amountMinorUnits, h.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <ConfirmDialog open={cancelling !== null} title="Cancel this transfer?" confirmLabel="Cancel transfer" danger
                     busy={busy} error={error} onClose={() => setCancelling(null)} onConfirm={cancel}>
        {cancelling && `${formatMoney(cancelling.amountMinorUnits, cancelling.currency)} goes back to ${cancelling.accountName} straight away and ${cancelling.counterparty} receives nothing. If you still want to pay them, you can send it again.`}
      </ConfirmDialog>
    </div>
  );
}
