import { useEffect, useState } from 'react';
import {
  Account, api, errorMessage, Payee, Recipient, StatementLine, TransactionEntries, TransactionItem,
} from './api';
import { accountTypeLabel, arrivalText, formatDateTime, formatMoney, formatSigned, typeLabel } from './format';
import { Icon, IconName } from './icons';
import { Amount, Button, CopyId, Drawer, Field, Notice, StatusBadge } from './ui';

// --- describing a transaction ------------------------------------------

export interface TransactionWords {
  icon: IconName;
  /** Who or what was on the other side. */
  title: string;
  /** What kind of movement it was. */
  kind: string;
  /** Amount as it affected the viewer: positive in, negative out, zero for internal moves. */
  signedAmount: number;
}

/** Turns a transaction's type and direction into the words and sign a person expects. */
export function describe(t: TransactionItem): TransactionWords {
  const other = t.counterparty ?? 'Unknown';
  const sign = t.direction === 'IN' ? 1 : t.direction === 'OUT' ? -1 : 0;
  const signedAmount = sign * t.amountMinorUnits;
  if (t.direction === 'INTERNAL') {
    return {
      icon: 'repeat', title: `${t.sourceLabel} to ${t.destinationLabel}`,
      kind: 'Between your accounts', signedAmount,
    };
  }
  switch (t.type) {
    case 'DEPOSIT':
      return { icon: 'bank', title: 'Money added', kind: 'Deposit from your bank', signedAmount };
    case 'WITHDRAWAL':
      return { icon: 'bank', title: 'Withdrawal', kind: 'Sent to your bank', signedAmount };
    case 'PAYMENT':
      return { icon: 'store', title: other, kind: t.direction === 'IN' ? 'Payment received' : 'Payment', signedAmount };
    case 'REFUND':
      return { icon: 'receive', title: other, kind: t.direction === 'IN' ? 'Refund' : 'Refund given', signedAmount };
    case 'HOLD': {
      // A transfer that waited on hold. What it is called depends on where it stands now.
      const kind = t.hold?.status === 'HELD' ? 'Transfer on hold'
        : t.hold?.status === 'CANCELLED' ? 'Transfer cancelled'
        : t.hold?.status === 'RETURNED' ? 'Transfer could not be delivered'
        : 'Transfer sent';
      return { icon: 'send', title: other, kind, signedAmount };
    }
    case 'HOLD_RETURN':
      return { icon: 'receive', title: 'Money returned', kind: 'From a transfer that did not go through', signedAmount };
    default:
      return {
        icon: t.direction === 'IN' ? 'receive' : 'send', title: other,
        kind: t.direction === 'IN' ? 'Transfer received' : 'Transfer sent', signedAmount,
      };
  }
}

export function TransactionParty({ transaction }: { transaction: TransactionItem }) {
  const words = describe(transaction);
  return (
    <div className="party">
      <span className={`txn-icon ${words.signedAmount > 0 ? 'in' : ''}`}><Icon name={words.icon} size={15} /></span>
      <div>
        <div className="primary truncate">{words.title}</div>
        <div className="secondary truncate">
          {words.kind}{transaction.description ? ` · ${transaction.description}` : ''}
        </div>
      </div>
    </div>
  );
}

/** The same shape for a statement line, which is one account's side of a transaction. */
export function StatementParty({ line }: { line: StatementLine }) {
  const incoming = line.amountMinorUnits > 0;
  const icon: IconName = line.type === 'PAYMENT' ? 'store'
    : line.type === 'DEPOSIT' || line.type === 'WITHDRAWAL' ? 'bank'
    : incoming ? 'receive' : 'send';
  return (
    <div className="party">
      <span className={`txn-icon ${incoming ? 'in' : ''}`}><Icon name={icon} size={15} /></span>
      <div>
        <div className="primary truncate">
          {line.type === 'HOLD_RETURN' ? 'Money returned' : line.counterparty ?? typeLabel(line.type)}
        </div>
        <div className="secondary truncate">
          {typeLabel(line.type)}{line.description ? ` · ${line.description}` : ''}
        </div>
      </div>
    </div>
  );
}

// --- transaction detail ------------------------------------------------

/**
 * Everything about one transaction, including the ledger entries behind
 * it. The entries are the product's promise made visible: every movement
 * is recorded on both sides and the two sides always add up to zero.
 */
export function TransactionDrawer({ transactionId, onClose }: { transactionId: string | null; onClose: () => void }) {
  const [detail, setDetail] = useState<TransactionEntries | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setDetail(null);
    setError(null);
    if (!transactionId) return;
    let current = true;
    api.transactionEntries(transactionId)
      .then((data) => { if (current) setDetail(data); })
      .catch((e) => { if (current) setError(errorMessage(e)); });
    return () => { current = false; };
  }, [transactionId]);

  const t = detail?.transaction;
  return (
    <Drawer open={transactionId !== null} title="Transaction" onClose={onClose}>
      {error && <Notice tone="error">{error}</Notice>}
      {!detail && !error && <div className="muted">Loading…</div>}
      {detail && t && (
        <>
          <div>
            <div style={{ fontSize: 28, fontWeight: 600, letterSpacing: '-0.02em' }}>
              {formatMoney(t.amountMinorUnits, t.currency)}
            </div>
            <div className="row" style={{ marginTop: 6 }}>
              <StatusBadge status={t.status} />
              <span className="muted">{typeLabel(t.type)}</span>
            </div>
          </div>

          {t.type.startsWith('HOLD') && (
            <Notice tone="info">
              {t.type === 'HOLD' && 'This was a first payment to someone new, so it waited on hold before delivery. While on hold the money sits in a LedgerFlow holding account, shown below.'}
              {t.type === 'HOLD_RELEASE' && `This transfer waited on hold as a safety check before it reached you${t.createdAt ? `, and arrived ${arrivalText(t.createdAt)}` : ''}.`}
              {t.type === 'HOLD_RETURN' && 'A transfer on hold was cancelled or could not be delivered, so the money came back from the holding account.'}
            </Notice>
          )}

          <dl className="facts">
            <dt>Date</dt><dd>{formatDateTime(t.createdAt)}</dd>
            {t.description && (<><dt>Description</dt><dd>{t.description}</dd></>)}
            <dt>Reference</dt><dd><CopyId value={t.id} label="Copy reference" /></dd>
          </dl>

          <div>
            <h3 style={{ marginBottom: 4 }}>Ledger entries</h3>
            <p className="muted" style={{ fontSize: 13, marginBottom: 12 }}>
              Every transaction is recorded on each account it touches. The entries always add up to zero.
            </p>
            <div className="card">
              <table className="table">
                <thead><tr><th>Account</th><th className="right">Amount</th></tr></thead>
                <tbody>
                  {detail.entries.map((entry, i) => (
                    <tr key={i}>
                      <td>
                        <div className="primary">
                          {entry.accountType === 'SYSTEM_FEES' ? 'Platform fee' : entry.account}
                        </div>
                        <div className="secondary">
                          {entry.own ? 'Your account'
                            : entry.accountType === 'SYSTEM_SUSPENSE' ? 'LedgerFlow holding account'
                            : entry.accountType === 'SYSTEM_FEES' ? 'Kept by LedgerFlow'
                            : entry.accountType === 'SYSTEM_CASH' ? 'Outside LedgerFlow'
                            : 'Other party'}
                        </div>
                      </td>
                      <td className="amount"><Amount minorUnits={entry.amountMinorUnits} currency={t.currency} signed /></td>
                    </tr>
                  ))}
                  <tr>
                    <td className="muted">Sum of entries</td>
                    <td className="amount">{formatSigned(detail.sumMinorUnits, t.currency)}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </Drawer>
  );
}

// --- choosing accounts and payees --------------------------------------

export function AccountSelect({ label, accounts, value, onChange, hint, showBalance = true }: {
  label: string; accounts: Account[]; value: string; onChange: (id: string) => void;
  hint?: string; showBalance?: boolean;
}) {
  return (
    <Field label={label} hint={hint}>
      {(id) => (
        <select id={id} className="input" value={value} onChange={(e) => onChange(e.target.value)}>
          {accounts.length === 0 && <option value="">No accounts available</option>}
          {accounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.name}
              {showBalance ? ` (${formatMoney(account.balanceMinorUnits, account.currency)})` : ''}
              {account.status === 'FROZEN' ? ', frozen' : ''}
            </option>
          ))}
        </select>
      )}
    </Field>
  );
}

export interface PayeeChoice {
  accountId: string;
  name: string;
  /** Typed in as a new account id, rather than picked from a list. */
  isNew: boolean;
}

const NEW = '__new__';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Picks who receives money: one of your own accounts, a saved recipient,
 * or someone new by account id. A new id is looked up first and the name
 * behind it is shown, so money is not sent to a mistyped account.
 */
export function PayeePicker({ label, ownAccounts, recipients, excludeAccountId, merchantsOnly, value, onChange }: {
  label: string; ownAccounts: Account[]; recipients: Recipient[]; excludeAccountId?: string;
  /** Only show business accounts (for paying a merchant). */
  merchantsOnly?: boolean; value: PayeeChoice | null; onChange: (choice: PayeeChoice | null) => void;
}) {
  const [mode, setMode] = useState<string>(value && !value.isNew ? value.accountId : '');
  const [typedId, setTypedId] = useState('');
  const [checking, setChecking] = useState(false);
  const [found, setFound] = useState<Payee | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);

  const own = ownAccounts.filter((a) => a.status !== 'CLOSED' && a.id !== excludeAccountId
    && (!merchantsOnly || a.type === 'MERCHANT'));
  const saved = recipients.filter((r) => r.accountStatus !== 'CLOSED'
    && (!merchantsOnly || r.accountType === 'MERCHANT'));

  // Keep the select in step when the parent presets or clears the choice.
  useEffect(() => {
    if (value === null && mode !== NEW) setMode('');
    else if (value && !value.isNew && value.accountId !== mode) setMode(value.accountId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  // If the chosen account becomes the source, it can no longer be the destination.
  useEffect(() => {
    if (value && value.accountId === excludeAccountId) {
      setMode('');
      onChange(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [excludeAccountId]);

  function choose(next: string) {
    setMode(next);
    setFound(null);
    setLookupError(null);
    if (next === '' || next === NEW) {
      onChange(null);
      return;
    }
    const account = own.find((a) => a.id === next);
    const recipient = saved.find((r) => r.accountId === next);
    onChange({ accountId: next, name: account?.name ?? recipient?.nickname ?? 'Recipient', isNew: false });
  }

  async function check() {
    const id = typedId.trim();
    setFound(null);
    onChange(null);
    if (!UUID.test(id)) {
      setLookupError('An account ID looks like 0198c2f4-7a1e-7c3b-9d42-5e6f7a8b9c0d.');
      return;
    }
    setChecking(true);
    setLookupError(null);
    try {
      const payee = await api.lookupPayee(id);
      if (payee.status === 'CLOSED') {
        setLookupError('That account is closed and cannot receive money.');
      } else if (merchantsOnly && payee.type !== 'MERCHANT') {
        setLookupError(`${payee.displayName} is a personal wallet. Use Send money to pay a person.`);
      } else if (payee.accountId === excludeAccountId) {
        setLookupError('That is the account you are sending from.');
      } else {
        setFound(payee);
        onChange({ accountId: payee.accountId, name: payee.displayName, isNew: !payee.own });
      }
    } catch (e) {
      setLookupError(errorMessage(e));
    } finally {
      setChecking(false);
    }
  }

  return (
    <div className="stack" style={{ gap: 10 }}>
      <Field label={label}>
        {(id) => (
          <select id={id} className="input" value={mode} onChange={(e) => choose(e.target.value)}>
            <option value="">Choose…</option>
            {own.length > 0 && (
              <optgroup label="Your accounts">
                {own.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </optgroup>
            )}
            {saved.length > 0 && (
              <optgroup label="Saved recipients">
                {saved.map((r) => (
                  <option key={r.id} value={r.accountId}>
                    {r.nickname}{r.nickname !== r.displayName ? ` (${r.displayName})` : ''}
                  </option>
                ))}
              </optgroup>
            )}
            <option value={NEW}>Someone new, by account ID…</option>
          </select>
        )}
      </Field>

      {mode === NEW && (
        <Field label="Account ID" error={lookupError}
               hint="Ask the person for the ID shown on their account page.">
          {(id) => (
            <div className="row" style={{ flexWrap: 'nowrap' }}>
              <input id={id} className={`input mono ${lookupError ? 'invalid' : ''}`} value={typedId}
                     spellCheck={false} autoComplete="off"
                     onChange={(e) => { setTypedId(e.target.value); setFound(null); onChange(null); }}
                     onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); check(); } }} />
              <Button onClick={check} busy={checking}>Check</Button>
            </div>
          )}
        </Field>
      )}

      {mode === NEW && found && (
        <Notice tone="success">
          <strong>{found.displayName}</strong> {'·'} {accountTypeLabel(found.type)}.
          {' '}Make sure this is who you mean before you continue.
        </Notice>
      )}
    </div>
  );
}
