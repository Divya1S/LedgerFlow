import { useEffect, useState } from 'react';
import { api, formatMoney, TransactionItem } from '../api';

export default function HistoryView() {
  const [items, setItems] = useState<TransactionItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);

  useEffect(() => {
    api.history().then(({ data }) => {
      setItems(data.items);
      setNextCursor(data.nextCursor);
    }).catch(console.error);
  }, []);

  async function loadMore() {
    if (!nextCursor) return;
    const { data } = await api.history(nextCursor);
    setItems((current) => [...current, ...data.items]);
    setNextCursor(data.nextCursor);
  }

  return (
    <div className="panel">
      <h2>Transaction history <span className="muted">(keyset paginated)</span></h2>
      <table>
        <thead>
          <tr><th>When</th><th>Type</th><th>Status</th><th>Amount</th><th>Description</th></tr>
        </thead>
        <tbody>
          {items.map((t) => (
            <tr key={t.id}>
              <td>{new Date(t.createdAt).toLocaleString()}</td>
              <td>{t.type}</td>
              <td><span className={`chip ${t.status}`}>{t.status}</span></td>
              <td>{formatMoney(t.amountMinorUnits)}</td>
              <td className="muted">{t.description ?? ''}</td>
            </tr>
          ))}
          {items.length === 0 && <tr><td colSpan={5} className="muted">No transactions yet</td></tr>}
        </tbody>
      </table>
      {nextCursor && (
        <button className="secondary" style={{ marginTop: 10 }} onClick={loadMore}>Load more</button>
      )}
    </div>
  );
}
