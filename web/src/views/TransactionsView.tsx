import { Fragment, useEffect, useMemo, useState } from 'react';
import { api, errorMessage, TransactionFilters, TransactionItem } from '../api';
import { describe, TransactionDrawer, TransactionParty } from '../components';
import { dayLabel, formatTime } from '../format';
import { useLoad } from '../hooks';
import { Icon } from '../icons';
import { Amount, Button, Card, EmptyState, LoadingRows, Notice, PageHeader } from '../ui';

const FILTER_KEYS = ['q', 'type', 'direction', 'accountId', 'from', 'to'] as const;

function filtersFromQuery(query: URLSearchParams): TransactionFilters {
  const filters: TransactionFilters = {};
  for (const key of FILTER_KEYS) {
    const value = query.get(key);
    if (value) filters[key] = value;
  }
  // Older links used ?account=
  if (!filters.accountId && query.get('account')) filters.accountId = query.get('account') as string;
  return filters;
}

export default function TransactionsView({ query }: { query: URLSearchParams }) {
  const [filters, setFilters] = useState<TransactionFilters>(() => filtersFromQuery(query));
  const [search, setSearch] = useState(filters.q ?? '');
  const [items, setItems] = useState<TransactionItem[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openTransaction, setOpenTransaction] = useState<string | null>(null);
  const accounts = useLoad(() => api.accounts(), []);

  const filterKey = JSON.stringify(filters);
  const active = Object.keys(filters).length > 0;

  // Typing searches after a short pause instead of on every keystroke.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setFilters((current) => {
        const q = search.trim();
        if ((current.q ?? '') === q) return current;
        const next = { ...current };
        if (q) next.q = q; else delete next.q;
        return next;
      });
    }, 300);
    return () => window.clearTimeout(timer);
  }, [search]);

  useEffect(() => {
    // Keep the filters in the address bar so a filtered view can be bookmarked or shared.
    const params = new URLSearchParams();
    for (const key of FILTER_KEYS) {
      const value = filters[key];
      if (value) params.set(key, value);
    }
    const text = params.toString();
    window.history.replaceState(null, '', `#/transactions${text ? `?${text}` : ''}`);

    let current = true;
    setLoading(true);
    api.transactions(filters)
      .then((page) => {
        if (!current) return;
        setItems(page.items);
        setNextCursor(page.nextCursor);
        setError(null);
      })
      .catch((e) => { if (current) setError(errorMessage(e)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey]);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    try {
      const page = await api.transactions(filters, nextCursor);
      setItems((current) => [...(current ?? []), ...page.items]);
      setNextCursor(page.nextCursor);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoadingMore(false);
    }
  }

  function setFilter(key: keyof TransactionFilters, value: string) {
    setFilters((current) => {
      const next = { ...current };
      if (value) next[key] = value; else delete next[key];
      return next;
    });
  }

  function clear() {
    setSearch('');
    setFilters({});
  }

  // Rows grouped under a date heading, in the order the server returned them.
  const groups = useMemo(() => {
    const result: { day: string; rows: TransactionItem[] }[] = [];
    for (const item of items ?? []) {
      const day = dayLabel(item.createdAt);
      const last = result[result.length - 1];
      if (last && last.day === day) last.rows.push(item);
      else result.push({ day, rows: [item] });
    }
    return result;
  }, [items]);

  return (
    <>
      <PageHeader title="Transactions" description="Everything that moved in or out of your accounts." />

      <Card flush>
        <div className="card-head">
          <div className="filters grow">
            <div className="search">
              <Icon name="search" size={15} />
              <input className="input" type="search" placeholder="Search descriptions" value={search}
                     aria-label="Search descriptions" onChange={(e) => setSearch(e.target.value)} />
            </div>
            <select className="input" aria-label="Type" value={filters.type ?? ''}
                    onChange={(e) => setFilter('type', e.target.value)}>
              <option value="">All types</option>
              <option value="TRANSFER">Transfers</option>
              <option value="PAYMENT">Payments</option>
              <option value="DEPOSIT">Money added</option>
              <option value="WITHDRAWAL">Withdrawals</option>
              <option value="REFUND">Refunds</option>
            </select>
            <select className="input" aria-label="Direction" value={filters.direction ?? ''}
                    onChange={(e) => setFilter('direction', e.target.value)}>
              <option value="">In and out</option>
              <option value="IN">Money in</option>
              <option value="OUT">Money out</option>
            </select>
            <select className="input" aria-label="Account" value={filters.accountId ?? ''}
                    onChange={(e) => setFilter('accountId', e.target.value)}>
              <option value="">All accounts</option>
              {(accounts.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
            <input className="input" type="date" aria-label="From date" value={filters.from ?? ''}
                   max={filters.to} onChange={(e) => setFilter('from', e.target.value)} />
            <span className="muted">to</span>
            <input className="input" type="date" aria-label="To date" value={filters.to ?? ''}
                   min={filters.from} onChange={(e) => setFilter('to', e.target.value)} />
            {active && <Button variant="ghost" size="small" onClick={clear}>Clear</Button>}
          </div>
        </div>

        {error && <div className="card-body"><Notice tone="error">{error}</Notice></div>}
        {!items && !error && <LoadingRows rows={6} />}

        {items && items.length === 0 && (
          active
            ? <EmptyState icon="search" title="No transactions match these filters"
                          action={<Button onClick={clear}>Clear filters</Button>}>
                Try a wider date range or a different search.
              </EmptyState>
            : <EmptyState icon="list" title="No transactions yet"
                          action={<a className="btn primary" href="#/send?tab=add">Add money</a>}>
                Add money to a wallet and it will show up here.
              </EmptyState>
        )}

        {items && items.length > 0 && (
          <div className={`table-wrap ${loading ? 'is-refreshing' : ''}`}>
            <table className="table">
              <thead>
                <tr><th>Details</th><th className="hide-narrow">Time</th><th className="right">Amount</th></tr>
              </thead>
              <tbody>
                {groups.map((group) => (
                  <Fragment key={group.day}>
                    <tr className="day"><td colSpan={3}>{group.day}</td></tr>
                    {group.rows.map((t) => {
                      const words = describe(t);
                      return (
                        <tr key={t.id} className="clickable" tabIndex={0}
                            onClick={() => setOpenTransaction(t.id)}
                            onKeyDown={(e) => { if (e.key === 'Enter') setOpenTransaction(t.id); }}>
                          <td><TransactionParty transaction={t} /></td>
                          <td className="hide-narrow muted nowrap">{formatTime(t.createdAt)}</td>
                          <td className="amount">
                            <Amount minorUnits={words.signedAmount || t.amountMinorUnits}
                                    currency={t.currency} signed={words.signedAmount !== 0} />
                          </td>
                        </tr>
                      );
                    })}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {nextCursor && (
          <div className="card-foot">
            <Button block variant="ghost" busy={loadingMore} onClick={loadMore}>Show earlier transactions</Button>
          </div>
        )}
      </Card>

      <TransactionDrawer transactionId={openTransaction} onClose={() => setOpenTransaction(null)} />
    </>
  );
}
