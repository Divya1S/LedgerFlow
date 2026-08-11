import { FormEvent, useCallback, useEffect, useState } from 'react';
import { Account, api, formatMoney, Page, StatementLine } from '../api';

interface AccountWithBalance extends Account {
  balanceMinorUnits?: number;
}

export default function AccountsView() {
  const [accounts, setAccounts] = useState<AccountWithBalance[]>([]);
  const [selected, setSelected] = useState<AccountWithBalance | null>(null);
  const [statement, setStatement] = useState<StatementLine[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [newType, setNewType] = useState('USER_WALLET');

  const reload = useCallback(async () => {
    const { data } = await api.accounts();
    const withBalances = await Promise.all(data.map(async (account) => {
      const { data: balance } = await api.balance(account.id);
      return { ...account, balanceMinorUnits: balance.balanceMinorUnits };
    }));
    setAccounts(withBalances);
  }, []);

  useEffect(() => { reload().catch(console.error); }, [reload]);

  async function open(account: AccountWithBalance) {
    setSelected(account);
    const { data } = await api.statement(account.id);
    setStatement(data.items);
    setNextCursor(data.nextCursor);
  }

  async function loadMore() {
    if (!selected || !nextCursor) return;
    const { data }: { data: Page<StatementLine> } = await api.statement(selected.id, nextCursor);
    setStatement((current) => [...current, ...data.items]);
    setNextCursor(data.nextCursor);
  }

  async function createAccount(event: FormEvent) {
    event.preventDefault();
    await api.openAccount(newType, newName);
    setNewName('');
    await reload();
  }

  return (
    <>
      <div className="panel">
        <h2>Your accounts</h2>
        <div className="grid">
          {accounts.map((account) => (
            <div key={account.id} className="card" onClick={() => open(account)}>
              <div className="name">{account.name} <span className={`chip ${account.status}`}>{account.type}</span></div>
              <div className="amount">{formatMoney(account.balanceMinorUnits ?? 0)}</div>
              <div className="meta">{account.id}</div>
            </div>
          ))}
        </div>
        <form className="stack" style={{ marginTop: 16 }} onSubmit={createAccount}>
          <div className="row">
            <input placeholder="Account name" value={newName}
                   onChange={(e) => setNewName(e.target.value)} required maxLength={120} />
            <select value={newType} onChange={(e) => setNewType(e.target.value)}>
              <option value="USER_WALLET">Wallet</option>
              <option value="MERCHANT">Merchant</option>
            </select>
            <button className="primary">Open account</button>
          </div>
        </form>
      </div>

      {selected && (
        <div className="panel">
          <h2>Statement: {selected.name} <span className="muted">(running balance)</span></h2>
          <table>
            <thead>
              <tr><th>When</th><th>Direction</th><th>Amount</th><th>Balance after</th><th>Transaction</th></tr>
            </thead>
            <tbody>
              {statement.map((line) => (
                <tr key={line.entryId}>
                  <td>{new Date(line.createdAt).toLocaleString()}</td>
                  <td><span className={line.amountMinorUnits > 0 ? 'pos' : 'neg'}>{line.direction}</span></td>
                  <td className={line.amountMinorUnits > 0 ? 'pos' : 'neg'}>
                    {formatMoney(line.amountMinorUnits)}
                  </td>
                  <td>{formatMoney(line.balanceAfterMinorUnits)}</td>
                  <td className="mono-small">{line.transactionId.slice(0, 8)}</td>
                </tr>
              ))}
              {statement.length === 0 && (
                <tr><td colSpan={5} className="muted">No entries yet</td></tr>
              )}
            </tbody>
          </table>
          {nextCursor && (
            <button className="secondary" style={{ marginTop: 10 }} onClick={loadMore}>
              Load more
            </button>
          )}
        </div>
      )}
    </>
  );
}
