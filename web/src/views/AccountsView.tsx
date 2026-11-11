import { FormEvent, useState } from 'react';
import { Account, api, errorMessage } from '../api';
import { accountTypeLabel, categoryLabel, formatMoney, MERCHANT_CATEGORIES } from '../format';
import { navigate, useLoad } from '../hooks';
import { Icon } from '../icons';
import { Button, Card, EmptyState, Field, LoadingRows, Modal, Notice, PageHeader, Segmented, StatusBadge, useToast } from '../ui';

export default function AccountsView({ autoOpen = false }: { autoOpen?: boolean }) {
  const accounts = useLoad(() => api.accounts(), []);
  const [opening, setOpening] = useState(autoOpen);

  const list = accounts.data ?? [];
  const open = list.filter((a) => a.status !== 'CLOSED');
  const closed = list.filter((a) => a.status === 'CLOSED');
  const total = open.reduce((sum, a) => sum + a.balanceMinorUnits, 0);

  return (
    <>
      <PageHeader
        title="Accounts"
        description={accounts.data && open.length > 0
          ? `${formatMoney(total)} across ${open.length} ${open.length === 1 ? 'account' : 'accounts'}`
          : 'Wallets hold your money. Merchant accounts take payments.'}
        actions={<Button variant="primary" icon="plus" onClick={() => setOpening(true)}>Open account</Button>}
      />

      {accounts.error && <Notice tone="error">{accounts.error}</Notice>}
      {!accounts.data && !accounts.error && <Card flush><LoadingRows /></Card>}

      {accounts.data && list.length === 0 && (
        <Card flush>
          <EmptyState icon="wallet" title="You have no accounts yet"
                      action={<Button variant="primary" icon="plus" onClick={() => setOpening(true)}>Open account</Button>}>
            Open a wallet to hold money, or a merchant account to take payments.
          </EmptyState>
        </Card>
      )}

      <div className="account-grid">
        {open.map((account) => <AccountCard key={account.id} account={account} />)}
      </div>

      {closed.length > 0 && (
        <div style={{ marginTop: 32 }}>
          <h2 style={{ marginBottom: 12 }}>Closed</h2>
          <div className="account-grid">
            {closed.map((account) => <AccountCard key={account.id} account={account} />)}
          </div>
        </div>
      )}

      <OpenAccountModal open={opening} onClose={() => setOpening(false)}
                        onOpened={(account) => { setOpening(false); navigate(`/accounts/${account.id}`); }} />
    </>
  );
}

function AccountCard({ account }: { account: Account }) {
  return (
    <a className={`card account-card ${account.status === 'CLOSED' ? 'closed' : ''}`} href={`#/accounts/${account.id}`}>
      <div className="row between" style={{ flexWrap: 'nowrap' }}>
        <div className="party">
          <span className="txn-icon"><Icon name={account.type === 'MERCHANT' ? 'store' : 'wallet'} size={15} /></span>
          <div>
            <div className="truncate" style={{ fontWeight: 600 }}>{account.name}</div>
            <div className="muted" style={{ fontSize: 12.5 }}>
              {accountTypeLabel(account.type)}{account.category ? ` · ${categoryLabel(account.category)}` : ''}
            </div>
          </div>
        </div>
        {account.status !== 'ACTIVE' && <StatusBadge status={account.status} />}
      </div>
      <div className="balance">{formatMoney(account.balanceMinorUnits, account.currency)}</div>
      <div className="muted" style={{ fontSize: 12.5 }}>Available balance</div>
    </a>
  );
}

function OpenAccountModal({ open, onClose, onOpened }: {
  open: boolean; onClose: () => void; onOpened: (account: Account) => void;
}) {
  const toast = useToast();
  const [type, setType] = useState<'USER_WALLET' | 'MERCHANT'>('USER_WALLET');
  const [name, setName] = useState('');
  const [category, setCategory] = useState('OTHER');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const account = await api.openAccount(type, name.trim(), category);
      toast(`${account.name} is open`);
      setName('');
      onOpened(account);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} title="Open an account" onClose={onClose}
           description="It is free and ready to use straight away.">
      <form className="stack" onSubmit={submit}>
        <div className="field">
          <span className="label">What is it for?</span>
          <Segmented label="Account type" value={type} onChange={setType} options={[
            { value: 'USER_WALLET', label: 'Personal wallet' },
            { value: 'MERCHANT', label: 'Merchant account' },
          ]} />
          <span className="hint">
            {type === 'USER_WALLET'
              ? 'Hold money, send it to people and pay merchants.'
              : 'Take payments from customers. A 1% fee is taken from each payment.'}
          </span>
        </div>
        <Field label={type === 'MERCHANT' ? 'Business name' : 'Account name'}
               hint={type === 'MERCHANT' ? 'Customers see this name when they pay you.' : 'Only you see this name.'}>
          {(id) => <input id={id} className="input" value={name} onChange={(e) => setName(e.target.value)}
                          placeholder={type === 'MERCHANT' ? 'Corner Grocery' : 'Everyday'} required maxLength={120} />}
        </Field>
        {type === 'MERCHANT' && (
          <Field label="What do you sell?" hint="Lets your customers see their spending by category.">
            {(id) => (
              <select id={id} className="input" value={category} onChange={(e) => setCategory(e.target.value)}>
                {MERCHANT_CATEGORIES.map((c) => <option key={c} value={c}>{categoryLabel(c)}</option>)}
              </select>
            )}
          </Field>
        )}
        {error && <Notice tone="error">{error}</Notice>}
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" busy={busy} disabled={!name.trim()}>Open account</Button>
        </div>
      </form>
    </Modal>
  );
}
