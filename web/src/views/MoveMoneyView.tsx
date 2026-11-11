import { FormEvent, useEffect, useMemo, useState } from 'react';
import { Account, api, errorMessage, newIdempotencyKey, Recipient } from '../api';
import { AccountSelect, PayeeChoice, PayeePicker, TransactionDrawer } from '../components';
import { formatDate, formatMoney, parseAmount, todayIso } from '../format';
import { navigate, useLoad } from '../hooks';
import { Icon } from '../icons';
import { AmountField, Button, Card, CopyId, EmptyState, Field, LoadingRows, Notice, PageHeader, Tabs, useToast } from '../ui';

type Tab = 'send' | 'add' | 'withdraw';
type When = 'NOW' | 'ONCE' | 'WEEKLY' | 'MONTHLY';

const WHEN_LABEL: Record<When, string> = {
  NOW: 'Now', ONCE: 'On a date', WEEKLY: 'Every week', MONTHLY: 'Every month',
};

export default function MoveMoneyView({ query }: { query: URLSearchParams }) {
  const initialTab = (['send', 'add', 'withdraw'] as Tab[]).includes(query.get('tab') as Tab)
    ? (query.get('tab') as Tab) : 'send';
  const [tab, setTab] = useState<Tab>(initialTab);
  const accounts = useLoad(() => api.accounts(), []);
  const recipients = useLoad(() => api.recipients(), []);

  const usable = (accounts.data ?? []).filter((a) => a.status !== 'CLOSED');

  return (
    <>
      <PageHeader title="Send and add money" description="Move money between your accounts, to other people, or to and from your bank." />
      <Tabs value={tab} onChange={setTab} options={[
        { value: 'send', label: 'Send' }, { value: 'add', label: 'Add money' }, { value: 'withdraw', label: 'Withdraw' },
      ]} />

      {accounts.error && <Notice tone="error">{accounts.error}</Notice>}
      {!accounts.data && !accounts.error && <Card flush><LoadingRows /></Card>}

      {accounts.data && usable.length === 0 && (
        <Card flush>
          <EmptyState icon="wallet" title="Open an account first"
                      action={<a className="btn primary" href="#/accounts?open=1">Open account</a>}>
            You need a wallet before you can move money.
          </EmptyState>
        </Card>
      )}

      {accounts.data && usable.length > 0 && (
        tab === 'send'
          ? <SendForm accounts={usable} recipients={recipients.data ?? []}
                      presetFrom={query.get('from')} presetTo={query.get('to')}
                      onMoved={() => { accounts.reload(); recipients.reload(); }} />
          : <BankForm key={tab} kind={tab} accounts={usable} presetAccount={query.get('from')}
                      onMoved={accounts.reload} />
      )}
    </>
  );
}

// --- send --------------------------------------------------------------

interface SendDone {
  kind: 'sent' | 'scheduled';
  amount: number;
  to: PayeeChoice;
  transactionId?: string;
  startDate?: string;
  when: When;
  key: string;
}

function SendForm({ accounts, recipients, presetFrom, presetTo, onMoved }: {
  accounts: Account[]; recipients: Recipient[]; presetFrom: string | null; presetTo: string | null;
  onMoved: () => void;
}) {
  const toast = useToast();
  const sendable = accounts.filter((a) => a.status === 'ACTIVE');
  const [from, setFrom] = useState(() =>
    sendable.find((a) => a.id === presetFrom)?.id ?? sendable[0]?.id ?? '');
  const [to, setTo] = useState<PayeeChoice | null>(null);
  const [amountText, setAmountText] = useState('');
  const [description, setDescription] = useState('');
  const [when, setWhen] = useState<When>('NOW');
  const [startDate, setStartDate] = useState(todayIso(1));
  const [step, setStep] = useState<'form' | 'review' | 'done'>('form');
  // One key per attempt to send. It is created when the review opens and
  // reused if Confirm is pressed again, so a retry cannot send twice.
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);
  const [done, setDone] = useState<SendDone | null>(null);
  const [replayNote, setReplayNote] = useState<string | null>(null);
  const [openTransaction, setOpenTransaction] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  // Arriving from a recipient's "Send" button preselects them.
  useEffect(() => {
    if (!presetTo) return;
    const recipient = recipients.find((r) => r.accountId === presetTo);
    const own = accounts.find((a) => a.id === presetTo);
    if (recipient) setTo({ accountId: presetTo, name: recipient.nickname, isNew: false });
    else if (own) setTo({ accountId: presetTo, name: own.name, isNew: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [presetTo, recipients.length]);

  const source = accounts.find((a) => a.id === from);
  const amount = parseAmount(amountText);
  const amountError = useMemo(() => {
    if (!touched) return null;
    if (amount === null) return 'Enter an amount, for example 25 or 25.50.';
    if (when === 'NOW' && source && amount > source.balanceMinorUnits) {
      return `That is more than the ${formatMoney(source.balanceMinorUnits)} available in ${source.name}.`;
    }
    return null;
  }, [touched, amount, when, source]);

  function review(event: FormEvent) {
    event.preventDefault();
    setTouched(true);
    setError(null);
    if (!to) { setError('Choose who to send to.'); return; }
    if (amount === null) return;
    if (when === 'NOW' && source && amount > source.balanceMinorUnits) return;
    setKey(newIdempotencyKey());
    setStep('review');
  }

  async function confirm() {
    if (!to || amount === null) return;
    setBusy(true);
    setError(null);
    try {
      if (when === 'NOW') {
        const { data } = await api.transfer(from, to.accountId, amount, description.trim(), key);
        setDone({ kind: 'sent', amount, to, transactionId: data.transactionId, when, key });
      } else {
        await api.createSchedule({
          sourceAccountId: from, destinationAccountId: to.accountId, amountMinorUnits: amount,
          description: description.trim(), frequency: when, startDate,
        });
        setDone({ kind: 'scheduled', amount, to, startDate, when, key });
      }
      setStep('done');
      onMoved();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  /** Sends the identical request again, to show that the server will not repeat it. */
  async function replay() {
    if (!done || !to || amount === null) return;
    try {
      const { replayed } = await api.transfer(from, to.accountId, amount, description.trim(), done.key);
      setReplayNote(replayed
        ? 'LedgerFlow recognised this request and returned the first result. No money moved a second time.'
        : 'The request was processed.');
    } catch (e) {
      setReplayNote(errorMessage(e));
    }
  }

  async function saveRecipient() {
    if (!done) return;
    try {
      await api.addRecipient(done.to.accountId, done.to.name);
      setSaved(true);
      toast(`${done.to.name} saved to recipients`);
      onMoved();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  }

  function reset() {
    setStep('form');
    setAmountText('');
    setDescription('');
    setTouched(false);
    setDone(null);
    setReplayNote(null);
    setSaved(false);
    setError(null);
  }

  if (sendable.length === 0) {
    return (
      <Card flush>
        <EmptyState icon="lock" title="All your accounts are frozen"
                    action={<a className="btn" href="#/accounts">Go to accounts</a>}>
          Unfreeze an account to send money from it.
        </EmptyState>
      </Card>
    );
  }

  if (step === 'done' && done) {
    return (
      <div className="cols-form">
        <Card>
          <div className="receipt">
            <div className="tick"><Icon name="check" size={22} /></div>
            <div className="big">{formatMoney(done.amount)}</div>
            <p className="muted" style={{ marginTop: 4 }}>
              {done.kind === 'sent'
                ? `Sent to ${done.to.name}`
                : `Scheduled to ${done.to.name}, ${describeWhen(done.when, done.startDate ?? '').toLowerCase()}`}
            </p>
          </div>
          <div className="stack" style={{ marginTop: 20 }}>
            {done.kind === 'sent' && done.to.isNew && !saved && (
              <Notice tone="info">
                Send to {done.to.name} again?{' '}
                <button className="link-button" onClick={saveRecipient}>Save as a recipient</button>
              </Notice>
            )}
            <div className="row">
              <Button variant="primary" onClick={reset}>Send another</Button>
              {done.transactionId && <Button onClick={() => setOpenTransaction(done.transactionId ?? null)}>View transaction</Button>}
              {done.kind === 'scheduled' && <Button onClick={() => navigate('/scheduled')}>View scheduled transfers</Button>}
            </div>
            {done.kind === 'sent' && (
              <details className="advanced">
                <summary>How this request is protected</summary>
                <div className="stack" style={{ gap: 10 }}>
                  <p className="muted" style={{ fontSize: 13 }}>
                    This transfer carried a one-time request ID. If the same request reaches LedgerFlow
                    again, because of a double click or a lost connection, it returns the first result
                    instead of sending the money twice.
                  </p>
                  <CopyId value={done.key} label="Copy request ID" />
                  <div><Button size="small" onClick={replay}>Send the same request again</Button></div>
                  {replayNote && <Notice tone="success">{replayNote}</Notice>}
                </div>
              </details>
            )}
          </div>
        </Card>
        <TransactionDrawer transactionId={openTransaction} onClose={() => setOpenTransaction(null)} />
      </div>
    );
  }

  if (step === 'review' && to && amount !== null) {
    return (
      <div className="cols-form">
        <Card title="Check the details">
          <div className="stack">
            <div style={{ fontSize: 30, fontWeight: 600, letterSpacing: '-0.02em' }}>{formatMoney(amount)}</div>
            <dl className="facts">
              <dt>From</dt><dd>{source?.name}</dd>
              <dt>To</dt><dd>{to.name}</dd>
              <dt>When</dt><dd>{describeWhen(when, startDate)}</dd>
              {description.trim() && (<><dt>Note</dt><dd>{description.trim()}</dd></>)}
              <dt>Fee</dt><dd>None</dd>
            </dl>
            {when !== 'NOW' && (
              <Notice tone="info">
                The money stays in {source?.name} until each transfer runs. If there is not enough on the
                day, that transfer is skipped and you are told.
              </Notice>
            )}
            {error && <Notice tone="error">{error}</Notice>}
            <div className="row">
              <Button onClick={() => { setStep('form'); setError(null); }} disabled={busy}>Back</Button>
              <Button variant="primary" busy={busy} onClick={confirm}>
                {when === 'NOW' ? `Send ${formatMoney(amount)}` : 'Schedule transfer'}
              </Button>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  return (
    <div className="cols-form">
      <Card>
        <form className="stack" onSubmit={review}>
          <AccountSelect label="From" accounts={sendable} value={from} onChange={setFrom} />
          <PayeePicker label="To" ownAccounts={accounts} recipients={recipients} excludeAccountId={from}
                       value={to} onChange={setTo} />
          <AmountField value={amountText} onChange={setAmountText} error={amountError} />
          <Field label="Note" hint="Optional. The recipient sees it.">
            {(id) => <input id={id} className="input" value={description} maxLength={200}
                            placeholder="Dinner, rent, thank you" onChange={(e) => setDescription(e.target.value)} />}
          </Field>
          <Field label="When">
            {(id) => (
              <select id={id} className="input" value={when} onChange={(e) => setWhen(e.target.value as When)}>
                {(Object.keys(WHEN_LABEL) as When[]).map((w) => <option key={w} value={w}>{WHEN_LABEL[w]}</option>)}
              </select>
            )}
          </Field>
          {when !== 'NOW' && (
            <Field label={when === 'ONCE' ? 'Date' : 'First transfer on'}>
              {(id) => <input id={id} className="input" type="date" value={startDate} min={todayIso()}
                              max={todayIso(365)} onChange={(e) => setStartDate(e.target.value)} required />}
            </Field>
          )}
          {error && <Notice tone="error">{error}</Notice>}
          <Button type="submit" variant="primary" size="large">Review</Button>
        </form>
      </Card>
      <aside className="stack">
        <Card title="Good to know">
          <ul className="points">
            <li><span><b>Free and instant.</b> Transfers between LedgerFlow accounts arrive straight away.</span></li>
            <li><span><b>Check the name.</b> New recipients are shown by name before you send.</span></li>
            <li><span><b>Paying a business?</b> Use <a href="#/payments">Payments</a> so you can ask for a refund or open a dispute.</span></li>
          </ul>
        </Card>
      </aside>
    </div>
  );
}

function describeWhen(when: When, startDate: string): string {
  const date = startDate ? formatDate(`${startDate}T12:00:00`) : '';
  switch (when) {
    case 'NOW': return 'Now';
    case 'ONCE': return `On ${date}`;
    case 'WEEKLY': return `Every week from ${date}`;
    case 'MONTHLY': return `Every month from ${date}`;
  }
}

// --- add money and withdraw --------------------------------------------

function BankForm({ kind, accounts, presetAccount, onMoved }: {
  kind: 'add' | 'withdraw'; accounts: Account[]; presetAccount: string | null; onMoved: () => void;
}) {
  const adding = kind === 'add';
  const eligible = adding ? accounts : accounts.filter((a) => a.status === 'ACTIVE');
  const [accountId, setAccountId] = useState(() =>
    eligible.find((a) => a.id === presetAccount)?.id ?? eligible[0]?.id ?? '');
  const [amountText, setAmountText] = useState('');
  const [touched, setTouched] = useState(false);
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<{ amount: number; account: string } | null>(null);

  const account = accounts.find((a) => a.id === accountId);
  const amount = parseAmount(amountText);
  const amountError = !touched ? null
    : amount === null ? 'Enter an amount, for example 100 or 100.50.'
    : !adding && account && amount > account.balanceMinorUnits
      ? `That is more than the ${formatMoney(account.balanceMinorUnits)} available.`
      : null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setTouched(true);
    if (amount === null || !account) return;
    if (!adding && amount > account.balanceMinorUnits) return;
    setBusy(true);
    setError(null);
    try {
      if (adding) await api.deposit(accountId, amount, '', key);
      else await api.withdraw(accountId, amount, '', key);
      setDone({ amount, account: account.name });
      onMoved();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setDone(null);
    setAmountText('');
    setTouched(false);
    setKey(newIdempotencyKey()); // a new attempt gets a new request ID
  }

  if (eligible.length === 0) {
    return (
      <Card flush>
        <EmptyState icon="lock" title="No account to withdraw from">Frozen accounts cannot send money out.</EmptyState>
      </Card>
    );
  }

  if (done) {
    return (
      <div className="cols-form">
        <Card>
          <div className="receipt">
            <div className="tick"><Icon name="check" size={22} /></div>
            <div className="big">{formatMoney(done.amount)}</div>
            <p className="muted" style={{ marginTop: 4 }}>
              {adding ? `Added to ${done.account}` : `Withdrawn from ${done.account}`}
            </p>
          </div>
          <div className="row" style={{ marginTop: 20 }}>
            <Button variant="primary" onClick={reset}>{adding ? 'Add more' : 'Withdraw more'}</Button>
            <Button onClick={() => navigate(`/accounts/${accountId}`)}>View account</Button>
          </div>
        </Card>
      </div>
    );
  }

  return (
    <div className="cols-form">
      <Card>
        <form className="stack" onSubmit={submit}>
          <AccountSelect label={adding ? 'Add to' : 'Withdraw from'} accounts={eligible}
                         value={accountId} onChange={setAccountId} />
          <AmountField value={amountText} onChange={setAmountText} error={amountError} />
          {error && <Notice tone="error">{error}</Notice>}
          <Button type="submit" variant="primary" size="large" busy={busy}>
            {adding ? 'Add money' : 'Withdraw'}
          </Button>
        </form>
      </Card>
      <aside>
        <Notice tone="info">
          {adding
            ? 'This is a demo environment. Money is added instantly and no real bank account is charged.'
            : 'This is a demo environment. Withdrawals leave your balance instantly and no real bank is credited.'}
        </Notice>
      </aside>
    </div>
  );
}
