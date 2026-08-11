import { FormEvent, useEffect, useState } from 'react';
import { Account, api, ApiError, formatMoney, newIdempotencyKey } from '../api';

type Operation = 'deposit' | 'withdraw' | 'transfer';

export default function MoveMoneyView() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [operation, setOperation] = useState<Operation>('deposit');
  const [source, setSource] = useState('');
  const [destination, setDestination] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [idemKey, setIdemKey] = useState(newIdempotencyKey());
  const [result, setResult] = useState<{ kind: 'ok' | 'error' | 'replay'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.accounts().then(({ data }) => {
      setAccounts(data);
      if (data.length > 0) {
        setSource(data[0].id);
        setDestination(data[data.length - 1].id);
      }
    }).catch(console.error);
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setResult(null);
    setBusy(true);
    const minorUnits = Math.round(parseFloat(amount) * 100);
    try {
      let replayed = false;
      if (operation === 'deposit') {
        replayed = (await api.deposit(source, minorUnits, idemKey)).replayed;
      } else if (operation === 'withdraw') {
        replayed = (await api.withdraw(source, minorUnits, idemKey)).replayed;
      } else {
        replayed = (await api.transfer(source, destination, minorUnits, description, idemKey)).replayed;
      }
      setResult(replayed
        ? {
            kind: 'replay',
            text: 'Idempotency-Replayed: true. The server recognized this key and returned the stored result instead of moving money twice.',
          }
        : { kind: 'ok', text: `${formatMoney(minorUnits)} ${operation} completed.` });
    } catch (e) {
      const error = e as ApiError;
      setResult({ kind: 'error', text: `${error.code}: ${error.message}` });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel">
      <h2>Move money</h2>
      <form className="stack" onSubmit={submit}>
        <label>
          Operation
          <select value={operation} onChange={(e) => setOperation(e.target.value as Operation)}>
            <option value="deposit">Deposit (external to wallet)</option>
            <option value="withdraw">Withdraw (wallet to external)</option>
            <option value="transfer">Transfer (wallet to wallet)</option>
          </select>
        </label>
        <label>
          {operation === 'deposit' ? 'Into account' : 'From account'}
          <select value={source} onChange={(e) => setSource(e.target.value)}>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.type})</option>)}
          </select>
        </label>
        {operation === 'transfer' && (
          <label>
            To account id <span className="muted">(any active account, paste an id)</span>
            <input value={destination} onChange={(e) => setDestination(e.target.value)} required />
          </label>
        )}
        <label>
          Amount (USD)
          <input type="number" min="0.01" step="0.01" value={amount}
                 onChange={(e) => setAmount(e.target.value)} required />
        </label>
        {operation === 'transfer' && (
          <label>
            Description
            <input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} />
          </label>
        )}
        <label>
          Idempotency-Key <span className="muted">(submit twice with the same key to see replay)</span>
          <div className="row">
            <input value={idemKey} onChange={(e) => setIdemKey(e.target.value)} style={{ flex: 1 }} />
            <button type="button" className="secondary" onClick={() => setIdemKey(newIdempotencyKey())}>
              New key
            </button>
          </div>
        </label>
        <button className="primary" disabled={busy}>Submit</button>
        {result && <div className={`notice ${result.kind === 'ok' ? 'ok' : result.kind}`}>{result.text}</div>}
      </form>
    </div>
  );
}
