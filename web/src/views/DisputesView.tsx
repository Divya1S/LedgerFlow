import { useState } from 'react';
import { api, Dispute, errorMessage, newIdempotencyKey } from '../api';
import { formatDate, formatMoney } from '../format';
import { useLoad } from '../hooks';
import { Button, Card, EmptyState, Field, LoadingRows, Modal, Notice, PageHeader, StatusBadge, useToast } from '../ui';

const WHAT_NEXT: Record<Dispute['status'], (d: Dispute) => string> = {
  OPEN: (d) => d.role === 'CUSTOMER'
    ? `Waiting for ${d.merchantName} to respond.`
    : 'Refund the customer, or decline and LedgerFlow will review it.',
  UNDER_REVIEW: (d) => d.role === 'ADMIN'
    ? 'The merchant declined. This dispute needs a platform decision.'
    : 'The merchant declined. LedgerFlow is reviewing and will decide.',
  RESOLVED_CUSTOMER: () => 'Closed. The payment was refunded to the customer.',
  RESOLVED_MERCHANT: () => 'Closed after review. No refund was made.',
};

export default function DisputesView() {
  const toast = useToast();
  const disputes = useLoad(() => api.disputes(), []);
  const [deciding, setDeciding] = useState<{ dispute: Dispute; outcome: 'REFUND' | 'DECLINE' } | null>(null);
  const [note, setNote] = useState('');
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function start(dispute: Dispute, outcome: 'REFUND' | 'DECLINE') {
    setNote('');
    setError(null);
    setKey(newIdempotencyKey()); // one request ID per decision, reused if it is retried
    setDeciding({ dispute, outcome });
  }

  async function confirm() {
    if (!deciding) return;
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.decideDispute(deciding.dispute.disputeId, deciding.outcome, note.trim(), key);
      toast(data.status === 'RESOLVED_CUSTOMER' ? 'Refunded and closed'
        : data.status === 'UNDER_REVIEW' ? 'Declined. Sent to LedgerFlow for review.' : 'Dispute closed');
      setDeciding(null);
      disputes.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  const list = disputes.data ?? [];
  const open = list.filter((d) => d.status === 'OPEN' || d.status === 'UNDER_REVIEW');
  const closed = list.filter((d) => d.status !== 'OPEN' && d.status !== 'UNDER_REVIEW');
  const remaining = deciding ? deciding.dispute.amountMinorUnits - deciding.dispute.refundedMinorUnits : 0;

  return (
    <div className="stack-lg">
      <PageHeader
        title="Disputes"
        description="When a customer says a payment was wrong. The merchant can refund it, or LedgerFlow reviews and decides."
      />

      {disputes.error && <Notice tone="error">{disputes.error}</Notice>}
      {!disputes.data && !disputes.error && <Card flush><LoadingRows /></Card>}

      {disputes.data && list.length === 0 && (
        <Card flush>
          <EmptyState icon="scale" title="No disputes"
                      action={<a className="btn" href="#/payments">Go to payments</a>}>
            If something is wrong with a payment you made, open it under Payments and choose Report a problem.
          </EmptyState>
        </Card>
      )}

      {open.length > 0 && (
        <div className="stack">
          <h2>Open</h2>
          {open.map((d) => <DisputeCard key={d.disputeId} dispute={d} onDecide={start} />)}
        </div>
      )}
      {closed.length > 0 && (
        <div className="stack">
          <h2>Closed</h2>
          {closed.map((d) => <DisputeCard key={d.disputeId} dispute={d} onDecide={start} />)}
        </div>
      )}

      <Modal open={deciding !== null} onClose={() => setDeciding(null)}
             title={deciding?.outcome === 'REFUND' ? 'Refund the customer?' : 'Decline this dispute?'}
             footer={<>
               <Button onClick={() => setDeciding(null)}>Cancel</Button>
               <Button variant={deciding?.outcome === 'REFUND' ? 'primary' : 'danger-solid'} busy={busy} onClick={confirm}>
                 {deciding?.outcome === 'REFUND' ? `Refund ${formatMoney(remaining)}` : 'Decline'}
               </Button>
             </>}>
        {deciding && (
          <div className="stack">
            <p className="muted">
              {deciding.outcome === 'REFUND'
                ? `${formatMoney(remaining, deciding.dispute.currency)} goes back to ${deciding.dispute.payerName} from ${deciding.dispute.merchantName} and the dispute is closed. This cannot be undone.`
                : deciding.dispute.role === 'ADMIN'
                  ? 'The dispute is closed in the merchant\'s favour and no money moves.'
                  : 'No money moves now. LedgerFlow will review the dispute and make the final decision.'}
            </p>
            <Field label={deciding.outcome === 'REFUND' ? 'Note' : 'Your response'}
                   hint={deciding.outcome === 'REFUND' ? 'Optional.' : 'Explain why, for the reviewer and the customer.'}>
              {(id) => <textarea id={id} className="input" value={note} maxLength={1000}
                                 onChange={(e) => setNote(e.target.value)} />}
            </Field>
            {error && <Notice tone="error">{error}</Notice>}
          </div>
        )}
      </Modal>
    </div>
  );
}

function DisputeCard({ dispute: d, onDecide }: {
  dispute: Dispute; onDecide: (dispute: Dispute, outcome: 'REFUND' | 'DECLINE') => void;
}) {
  const merchantSide = d.role !== 'CUSTOMER';
  // A merchant who already declined can still change their mind and refund.
  const canDecline = d.canDecide && (d.status === 'OPEN' || d.role === 'ADMIN');
  return (
    <Card>
      <div className="stack">
        <div className="row between">
          <div>
            <div style={{ fontWeight: 600 }}>
              {formatMoney(d.amountMinorUnits, d.currency)}{' '}
              <span className="muted" style={{ fontWeight: 400 }}>
                {merchantSide ? `from ${d.payerName} to ${d.merchantName}` : `to ${d.merchantName}`}
              </span>
            </div>
            <div className="muted" style={{ fontSize: 12.5 }}>
              Opened {formatDate(d.createdAt)}
              {d.resolvedAt ? ` · closed ${formatDate(d.resolvedAt)}` : ''}
            </div>
          </div>
          <StatusBadge status={d.status} />
        </div>

        <dl className="facts left">
          <dt>{merchantSide ? 'Customer said' : 'You said'}</dt><dd>{d.reason}</dd>
          {d.merchantResponse && (<><dt>{merchantSide && d.role === 'MERCHANT' ? 'You replied' : 'Merchant replied'}</dt><dd>{d.merchantResponse}</dd></>)}
          {d.resolutionNote && (<><dt>Decision note</dt><dd>{d.resolutionNote}</dd></>)}
          {d.refundedMinorUnits > 0 && (<><dt>Refunded</dt><dd>{formatMoney(d.refundedMinorUnits, d.currency)}</dd></>)}
        </dl>

        <div className="row between">
          <span className="muted">{WHAT_NEXT[d.status](d)}</span>
          {d.canDecide && (
            <div className="row">
              {canDecline && <Button onClick={() => onDecide(d, 'DECLINE')}>Decline</Button>}
              <Button variant="primary" onClick={() => onDecide(d, 'REFUND')}>Refund customer</Button>
            </div>
          )}
        </div>
      </div>
    </Card>
  );
}
