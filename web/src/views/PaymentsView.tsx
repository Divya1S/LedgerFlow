import { FormEvent, useEffect, useState } from 'react';
import {
  Account, api, errorMessage, FraudAssessment, isApiError, newIdempotencyKey, PaymentListItem, Recipient,
} from '../api';
import { AccountSelect, PayeeChoice, PayeePicker } from '../components';
import { categoryLabel, dayLabel, formatDateTime, formatMoney, formatTime, parseAmount, titleCase } from '../format';
import { navigate, useLoad } from '../hooks';
import {
  AmountField, Button, Card, CopyId, Drawer, EmptyState, Field, LoadingRows, Modal, Notice, PageHeader,
  StatusBadge, Tabs, useToast,
} from '../ui';

type Role = 'PAYER' | 'MERCHANT';

const RULES: Record<string, string> = {
  LARGE_AMOUNT: 'Larger than usual amount',
  VERY_LARGE_AMOUNT: 'Very large amount',
  HIGH_VELOCITY: 'Many payments from this customer in a few minutes',
  FAILED_STREAK: 'Several failed payments from this customer today',
};

export default function PaymentsView({ query }: { query: URLSearchParams }) {
  const [role, setRole] = useState<Role>('PAYER');
  const [paying, setPaying] = useState(query.get('to') !== null);
  const [selected, setSelected] = useState<PaymentListItem | null>(null);
  const [extra, setExtra] = useState<PaymentListItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const payments = useLoad(async () => {
    const page = await api.payments(role);
    setExtra([]);
    setNextCursor(page.nextCursor);
    return page.items;
  }, [role]);
  const accounts = useLoad(() => api.accounts(), []);
  const recipients = useLoad(() => api.recipients(), []);

  const items = payments.data ? [...payments.data, ...extra] : null;
  const hasMerchantAccount = (accounts.data ?? []).some((a) => a.type === 'MERCHANT' && a.status !== 'CLOSED');

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    try {
      const page = await api.payments(role, nextCursor);
      setExtra((current) => [...current, ...page.items]);
      setNextCursor(page.nextCursor);
    } finally {
      setLoadingMore(false);
    }
  }

  function refresh() {
    payments.reload();
    accounts.reload();
  }

  return (
    <>
      <PageHeader
        title="Payments"
        description="Pay a business from your wallet, or see what your own business has been paid."
        actions={<Button variant="primary" icon="store" onClick={() => setPaying(true)}>Pay a merchant</Button>}
      />

      <Tabs value={role} onChange={setRole} options={[
        { value: 'PAYER', label: 'Payments you made' }, { value: 'MERCHANT', label: 'Payments you received' },
      ]} />

      {payments.error && <Notice tone="error">{payments.error}</Notice>}

      <Card flush>
        {!items && !payments.error && <LoadingRows />}
        {items && items.length === 0 && (
          role === 'PAYER'
            ? <EmptyState icon="store" title="You have not paid a merchant yet"
                          action={<Button onClick={() => setPaying(true)}>Pay a merchant</Button>}>
                Payments to businesses are listed here, with refunds and disputes.
              </EmptyState>
            : <EmptyState icon="store" title="No payments received"
                          action={hasMerchantAccount ? undefined
                            : <a className="btn" href="#/accounts?open=1">Open a merchant account</a>}>
                {hasMerchantAccount
                  ? 'Share your merchant account ID with customers. Their payments will appear here.'
                  : 'Open a merchant account to take payments from customers.'}
              </EmptyState>
        )}
        {items && items.length > 0 && (
          <div className={`table-wrap ${payments.loading ? 'is-refreshing' : ''}`}>
            <table className="table">
              <thead>
                <tr>
                  <th>{role === 'PAYER' ? 'Merchant' : 'Customer'}</th>
                  <th className="hide-narrow">Date</th>
                  <th>Status</th>
                  <th className="right">Amount</th>
                </tr>
              </thead>
              <tbody>
                {items.map((p) => (
                  <tr key={p.paymentId} className="clickable" tabIndex={0} onClick={() => setSelected(p)}
                      onKeyDown={(e) => { if (e.key === 'Enter') setSelected(p); }}>
                    <td>
                      <div className="primary truncate">{role === 'PAYER' ? p.merchantName : p.payerName}</div>
                      <div className="secondary truncate">
                        {role === 'PAYER' ? categoryLabel(p.merchantCategory) : `To ${p.merchantName}`}
                        {/* A description that only repeats the category adds nothing. */}
                        {p.description && p.description.toLowerCase() !== categoryLabel(p.merchantCategory).toLowerCase()
                          ? ` · ${p.description}` : ''}
                      </div>
                    </td>
                    <td className="hide-narrow muted nowrap">{dayLabel(p.createdAt)}, {formatTime(p.createdAt)}</td>
                    <td>
                      <div className="row" style={{ gap: 6 }}>
                        <StatusBadge status={p.status} />
                        {p.disputeStatus && (p.disputeStatus === 'OPEN' || p.disputeStatus === 'UNDER_REVIEW') && (
                          <StatusBadge status={p.disputeStatus} label="Disputed" />
                        )}
                        {p.fraudVerdict && p.fraudVerdict !== 'APPROVED' && <StatusBadge status={p.fraudVerdict} />}
                      </div>
                    </td>
                    <td className="amount">{formatMoney(p.amountMinorUnits, p.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {nextCursor && (
          <div className="card-foot">
            <Button block variant="ghost" busy={loadingMore} onClick={loadMore}>Show earlier payments</Button>
          </div>
        )}
      </Card>

      <PayModal open={paying} accounts={accounts.data ?? []} recipients={recipients.data ?? []}
                presetTo={query.get('to')} onClose={() => setPaying(false)}
                onPaid={() => { setPaying(false); setRole('PAYER'); refresh(); }} />

      <PaymentDrawer payment={selected} onClose={() => setSelected(null)}
                     onChanged={() => { setSelected(null); refresh(); }} />
    </>
  );
}

// --- pay a merchant ----------------------------------------------------

function PayModal({ open, accounts, recipients, presetTo, onClose, onPaid }: {
  open: boolean; accounts: Account[]; recipients: Recipient[]; presetTo: string | null;
  onClose: () => void; onPaid: () => void;
}) {
  const toast = useToast();
  const wallets = accounts.filter((a) => a.type === 'USER_WALLET' && a.status === 'ACTIVE');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState<PayeeChoice | null>(null);
  const [amountText, setAmountText] = useState('');
  const [description, setDescription] = useState('');
  const [key, setKey] = useState(newIdempotencyKey);
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const source = wallets.find((a) => a.id === from) ?? wallets[0];
  const amount = parseAmount(amountText);

  useEffect(() => {
    if (!open || !presetTo) return;
    const recipient = recipients.find((r) => r.accountId === presetTo);
    if (recipient) setTo({ accountId: presetTo, name: recipient.nickname, isNew: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, presetTo, recipients.length]);

  const amountError = !touched ? null
    : amount === null ? 'Enter an amount, for example 25 or 25.50.'
    : source && amount > source.balanceMinorUnits
      ? `That is more than the ${formatMoney(source.balanceMinorUnits)} available in ${source.name}.` : null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setTouched(true);
    setError(null);
    if (!to) { setError('Choose which merchant to pay.'); return; }
    if (amount === null || !source || amount > source.balanceMinorUnits) return;
    setBusy(true);
    try {
      await api.pay(source.id, to.accountId, amount, description.trim(), key);
      toast(`Paid ${formatMoney(amount)} to ${to.name}`);
      setAmountText('');
      setDescription('');
      setTouched(false);
      setKey(newIdempotencyKey());
      onPaid();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} title="Pay a merchant" onClose={onClose}
           description="The merchant receives the payment straight away. You can dispute it later if something is wrong.">
      {wallets.length === 0 ? (
        <div className="stack">
          <Notice tone="warn">You need an active wallet to pay from.</Notice>
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <a className="btn primary" href="#/accounts?open=1">Open a wallet</a>
          </div>
        </div>
      ) : (
        <form className="stack" onSubmit={submit}>
          <AccountSelect label="Pay from" accounts={wallets} value={source?.id ?? ''} onChange={setFrom} />
          <PayeePicker label="Merchant" ownAccounts={accounts} recipients={recipients} merchantsOnly
                       value={to} onChange={setTo} />
          <AmountField value={amountText} onChange={setAmountText} error={amountError} />
          <Field label="What is it for?" hint="Optional. The merchant sees it.">
            {(id) => <input id={id} className="input" value={description} maxLength={200}
                            placeholder="Order 1042" onChange={(e) => setDescription(e.target.value)} />}
          </Field>
          {error && <Notice tone="error">{error}</Notice>}
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <Button onClick={onClose}>Cancel</Button>
            <Button type="submit" variant="primary" busy={busy}>
              {amount ? `Pay ${formatMoney(amount)}` : 'Pay'}
            </Button>
          </div>
        </form>
      )}
    </Modal>
  );
}

// --- one payment -------------------------------------------------------

function PaymentDrawer({ payment, onClose, onChanged }: {
  payment: PaymentListItem | null; onClose: () => void; onChanged: () => void;
}) {
  const toast = useToast();
  const [mode, setMode] = useState<'view' | 'refund' | 'dispute'>('view');
  const [amountText, setAmountText] = useState('');
  const [reason, setReason] = useState('');
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setMode('view');
    setAmountText('');
    setReason('');
    setError(null);
    setKey(newIdempotencyKey());
  }, [payment?.paymentId]);

  if (!payment) return <Drawer open={false} title="Payment" onClose={onClose}>{null}</Drawer>;

  const merchant = payment.role === 'MERCHANT';
  const refundable = payment.amountMinorUnits - payment.refundedMinorUnits;
  const canRefund = merchant && refundable > 0 && (payment.status === 'COMPLETED' || payment.status === 'PARTIALLY_REFUNDED');
  const disputeActive = payment.disputeStatus === 'OPEN' || payment.disputeStatus === 'UNDER_REVIEW';
  const canDispute = !merchant && !disputeActive && refundable > 0
    && (payment.status === 'COMPLETED' || payment.status === 'PARTIALLY_REFUNDED');

  async function refund(event: FormEvent) {
    event.preventDefault();
    if (!payment) return;
    const amount = amountText.trim() === '' ? refundable : parseAmount(amountText);
    if (amount === null || amount > refundable) {
      setError(`Enter an amount up to ${formatMoney(refundable, payment.currency)}.`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.refund(payment.paymentId, amount, reason.trim(), key);
      toast(`Refunded ${formatMoney(amount, payment.currency)} to ${payment.payerName}`);
      onChanged();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function dispute(event: FormEvent) {
    event.preventDefault();
    if (!payment) return;
    setBusy(true);
    setError(null);
    try {
      await api.openDispute(payment.paymentId, reason.trim());
      toast('Dispute opened. The merchant has been told.');
      onChanged();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Drawer open title="Payment" onClose={onClose}>
      <div>
        <div style={{ fontSize: 28, fontWeight: 600, letterSpacing: '-0.02em' }}>
          {formatMoney(payment.amountMinorUnits, payment.currency)}
        </div>
        <div className="row" style={{ marginTop: 6 }}>
          <StatusBadge status={payment.status} />
          {payment.disputeStatus && <StatusBadge status={payment.disputeStatus}
                                                 label={disputeActive ? 'Disputed' : undefined} />}
        </div>
      </div>

      <dl className="facts">
        <dt>{merchant ? 'From' : 'To'}</dt><dd>{merchant ? payment.payerName : payment.merchantName}</dd>
        {merchant && (<><dt>Paid to</dt><dd>{payment.merchantName}</dd></>)}
        <dt>Date</dt><dd>{formatDateTime(payment.createdAt)}</dd>
        {payment.description && (<><dt>For</dt><dd>{payment.description}</dd></>)}
        {merchant && (
          <>
            <dt>Fee (1%)</dt><dd>{formatMoney(payment.feeMinorUnits, payment.currency)}</dd>
            <dt>You received</dt>
            <dd>{formatMoney(payment.amountMinorUnits - payment.feeMinorUnits, payment.currency)}</dd>
          </>
        )}
        {payment.refundedMinorUnits > 0 && (
          <><dt>Refunded</dt><dd>{formatMoney(payment.refundedMinorUnits, payment.currency)}</dd></>
        )}
        <dt>Reference</dt><dd><CopyId value={payment.paymentId} label="Copy reference" /></dd>
      </dl>

      {merchant && <FraudPanel paymentId={payment.paymentId} />}

      {disputeActive && (
        <Notice tone="warn">
          This payment is being disputed. <a href="#/disputes" onClick={onClose}>Open disputes</a>
        </Notice>
      )}

      {mode === 'view' && (canRefund || canDispute) && (
        <div className="row">
          {canRefund && <Button onClick={() => setMode('refund')}>Refund this payment</Button>}
          {canDispute && <Button onClick={() => setMode('dispute')}>Report a problem</Button>}
        </div>
      )}

      {mode === 'refund' && (
        <form className="stack" onSubmit={refund}>
          <h3>Refund</h3>
          <AmountField label="Amount to refund" value={amountText} onChange={setAmountText}
                       hint={`Leave empty to refund the full ${formatMoney(refundable, payment.currency)}. The fee is returned in proportion.`} />
          <Field label="Reason" hint="Optional. Kept with the refund for your records.">
            {(id) => <input id={id} className="input" value={reason} maxLength={200}
                            onChange={(e) => setReason(e.target.value)} />}
          </Field>
          {error && <Notice tone="error">{error}</Notice>}
          <div className="row">
            <Button onClick={() => { setMode('view'); setError(null); }}>Cancel</Button>
            <Button type="submit" variant="primary" busy={busy}>Refund</Button>
          </div>
        </form>
      )}

      {mode === 'dispute' && (
        <form className="stack" onSubmit={dispute}>
          <h3>Report a problem</h3>
          <p className="muted" style={{ fontSize: 13 }}>
            Tell {payment.merchantName} what went wrong. They can refund you, or LedgerFlow reviews
            the dispute if they disagree.
          </p>
          <Field label="What went wrong?">
            {(id) => <textarea id={id} className="input" value={reason} maxLength={1000} required
                               placeholder="The order never arrived" onChange={(e) => setReason(e.target.value)} />}
          </Field>
          {error && <Notice tone="error">{error}</Notice>}
          <div className="row">
            <Button onClick={() => { setMode('view'); setError(null); }}>Cancel</Button>
            <Button type="submit" variant="primary" busy={busy} disabled={!reason.trim()}>Open dispute</Button>
          </div>
        </form>
      )}

      {!merchant && mode === 'view' && !canDispute && !disputeActive && payment.status === 'REFUNDED' && (
        <p className="muted">This payment was refunded in full.</p>
      )}
      {merchant && mode === 'view' && (
        <div><Button variant="ghost" size="small" onClick={() => { onClose(); navigate('/disputes'); }}>View disputes</Button></div>
      )}
    </Drawer>
  );
}

/**
 * The fraud check for a payment the merchant received. The check runs a
 * moment after the payment, so this polls briefly until the result (and,
 * for flagged payments, the analyst's write-up) is there.
 */
function FraudPanel({ paymentId }: { paymentId: string }) {
  const [assessment, setAssessment] = useState<FraudAssessment | null>(null);
  const [waiting, setWaiting] = useState(true);

  useEffect(() => {
    let current = true;
    let attempts = 0;
    let timer = 0;
    setAssessment(null);
    setWaiting(true);

    async function poll() {
      attempts++;
      try {
        const result = await api.fraudAssessment(paymentId);
        if (!current) return;
        setAssessment(result);
        // Flagged payments get a written assessment a little later.
        const moreComing = result.verdict !== 'APPROVED' && !result.aiAssessment && attempts < 8;
        setWaiting(moreComing);
        if (moreComing) timer = window.setTimeout(poll, 2500);
      } catch (e) {
        if (!current) return;
        if (isApiError(e) && e.code === 'ASSESSMENT_NOT_FOUND' && attempts < 8) {
          timer = window.setTimeout(poll, 1500);
        } else {
          setWaiting(false);
        }
      }
    }
    poll();
    return () => { current = false; window.clearTimeout(timer); };
  }, [paymentId]);

  return (
    <div>
      <h3 style={{ marginBottom: 8 }}>Fraud check</h3>
      {!assessment && (
        <p className="muted">{waiting ? 'Checking this payment…' : 'No result is available for this payment.'}</p>
      )}
      {assessment && (
        <div className="stack" style={{ gap: 12 }}>
          <div className="row">
            <StatusBadge status={assessment.verdict} />
            <span className="muted">Risk score {assessment.score} of 100</span>
          </div>
          {assessment.ruleHits.length > 0 && (
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {assessment.ruleHits.map((rule) => <li key={rule}>{RULES[rule] ?? rule}</li>)}
            </ul>
          )}
          {assessment.verdict === 'APPROVED' && (
            <p className="muted" style={{ fontSize: 13 }}>Nothing unusual was found.</p>
          )}
          {assessment.aiAssessment && (
            <div className="card">
              <div className="card-body stack" style={{ gap: 10 }}>
                <div className="row between">
                  <h3>Analyst notes</h3>
                  <StatusBadge status={assessment.aiAssessment.risk_level} />
                </div>
                <p>{assessment.aiAssessment.summary}</p>
                {assessment.aiAssessment.key_factors.length > 0 && (
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {assessment.aiAssessment.key_factors.map((factor, i) => (
                      // Some models answer with codes (LARGE_AMOUNT); show them as words.
                      <li key={i}>{/^[A-Z0-9_]+$/.test(factor) ? (RULES[factor] ?? titleCase(factor)) : factor}</li>
                    ))}
                  </ul>
                )}
                <p><strong>Suggested next step:</strong> {assessment.aiAssessment.recommended_action}</p>
                <p className="muted" style={{ fontSize: 12.5 }}>
                  Written by an AI model{assessment.aiModel ? ` (${assessment.aiModel})` : ''} from this
                  payment's history. It is advice for a person to weigh. It cannot move or block money.
                </p>
              </div>
            </div>
          )}
          {assessment.verdict !== 'APPROVED' && !assessment.aiAssessment && (
            <p className="muted" style={{ fontSize: 13 }}>
              {waiting ? 'An analyst write-up is being prepared…'
                : 'No analyst write-up is available. The AI analyst may be switched off on this server.'}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
