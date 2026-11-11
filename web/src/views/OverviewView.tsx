import { useState } from 'react';
import { api, Overview, Session, TransactionItem } from '../api';
import { CashFlowChart, CashFlowTable, SpendingBars, Sparkline } from '../charts';
import { describe, TransactionDrawer, TransactionParty } from '../components';
import { accountTypeLabel, formatMoney, formatSigned, formatTime, dayLabel } from '../format';
import { useLoad } from '../hooks';
import { Icon } from '../icons';
import { Amount, Button, Card, EmptyState, LoadingRows, Notice, PageHeader, Segmented, StatusBadge } from '../ui';

type Range = '7' | '30' | '90';

const RANGE_WORDS: Record<Range, string> = { '7': 'previous 7 days', '30': 'previous 30 days', '90': 'previous 90 days' };

export default function OverviewView({ session }: { session: Session }) {
  const [range, setRange] = useState<Range>('30');
  const [showTable, setShowTable] = useState(false);
  const [openTransaction, setOpenTransaction] = useState<string | null>(null);

  const overview = useLoad(() => api.overview(Number(range)), [range]);
  const recent = useLoad(() => api.transactions({}, null, 6), []);

  const data = overview.data;
  const firstName = session.fullName.trim().split(/\s+/)[0] || 'there';

  if (overview.error && !data) {
    return (
      <>
        <PageHeader title="Overview" />
        <Notice tone="error">{overview.error} <button className="link-button" onClick={overview.reload}>Try again</button></Notice>
      </>
    );
  }

  if (data && data.accounts.length === 0) {
    return <Welcome name={firstName} />;
  }

  return (
    <div className="stack-lg">
      <PageHeader
        title={`Hello, ${firstName}`}
        description="Your balances, what came in and what went out."
        actions={
          <Segmented label="Period" value={range} onChange={setRange} options={[
            { value: '7', label: '7 days' }, { value: '30', label: '30 days' }, { value: '90', label: '90 days' },
          ]} />
        }
      />

      <div className={`kpis ${overview.loading && data ? 'is-refreshing' : ''}`}>
        {data ? <Kpis data={data} range={range} /> : (
          <>
            <div className="card kpi hero"><LoadingRows rows={2} /></div>
            <div className="card kpi"><LoadingRows rows={2} /></div>
            <div className="card kpi"><LoadingRows rows={2} /></div>
            <div className="card kpi"><LoadingRows rows={2} /></div>
          </>
        )}
      </div>

      <div className="cols-main">
        <Card
          title="Cash flow"
          description={data ? `Money in and out per day, last ${data.days} days` : undefined}
          flush={showTable}
          actions={
            <div className="row">
              {!showTable && (
                <div className="legend hide-narrow">
                  <span><i style={{ background: 'var(--chart-in)' }} />Money in</span>
                  <span><i style={{ background: 'var(--chart-out)' }} />Money out</span>
                </div>
              )}
              <Button size="small" variant="ghost" icon={showTable ? 'chart' : 'table'}
                      onClick={() => setShowTable(!showTable)}>
                {showTable ? 'Chart' : 'Table'}
              </Button>
            </div>
          }>
          {!data ? <LoadingRows rows={5} />
            : showTable ? <CashFlowTable days={data.daily} currency={data.currency} />
            : <div className={overview.loading ? 'is-refreshing' : ''}>
                <CashFlowChart days={data.daily} currency={data.currency} />
              </div>}
        </Card>

        <Card title="Where money went" description={data ? `Last ${data.days} days` : undefined}>
          {!data ? <LoadingRows />
            : data.spending.length === 0
              ? <p className="muted">Nothing has left your accounts in this period.</p>
              : <SpendingBars slices={data.spending} currency={data.currency} />}
        </Card>
      </div>

      <div className="cols-2">
        <Card title="Accounts" flush actions={<a href="#/accounts">View all</a>}>
          {!data ? <LoadingRows /> : (
            <div className="account-list">
              {data.accounts.filter((a) => a.status !== 'CLOSED').map((account) => (
                <a key={account.id} className="account-line" href={`#/accounts/${account.id}`}>
                  <span className="txn-icon"><Icon name={account.type === 'MERCHANT' ? 'store' : 'wallet'} size={15} /></span>
                  <span className="grow">
                    <span className="primary truncate" style={{ display: 'block', fontWeight: 500 }}>{account.name}</span>
                    <span className="muted" style={{ fontSize: 12.5 }}>{accountTypeLabel(account.type)}</span>
                  </span>
                  {account.status === 'FROZEN' && <StatusBadge status="FROZEN" />}
                  <span className="balance">{formatMoney(account.balanceMinorUnits, data.currency)}</span>
                </a>
              ))}
            </div>
          )}
        </Card>

        <Card title="Recent transactions" flush actions={<a href="#/transactions">View all</a>}>
          {!recent.data ? <LoadingRows />
            : recent.data.items.length === 0
              ? <EmptyState icon="list" title="No transactions yet">Add money to a wallet to get started.</EmptyState>
              : (
                <table className="table">
                  <tbody>
                    {recent.data.items.map((t) => <RecentRow key={t.id} transaction={t} onOpen={setOpenTransaction} />)}
                  </tbody>
                </table>
              )}
        </Card>
      </div>

      <TransactionDrawer transactionId={openTransaction} onClose={() => setOpenTransaction(null)} />
    </div>
  );
}

function RecentRow({ transaction, onOpen }: { transaction: TransactionItem; onOpen: (id: string) => void }) {
  const words = describe(transaction);
  return (
    <tr className="clickable" tabIndex={0} onClick={() => onOpen(transaction.id)}
        onKeyDown={(e) => { if (e.key === 'Enter') onOpen(transaction.id); }}>
      <td><TransactionParty transaction={transaction} /></td>
      <td className="amount">
        <Amount minorUnits={words.signedAmount || transaction.amountMinorUnits}
                currency={transaction.currency} signed={words.signedAmount !== 0} />
        <div className="secondary" style={{ fontWeight: 400 }}>
          {dayLabel(transaction.createdAt)}, {formatTime(transaction.createdAt)}
        </div>
      </td>
    </tr>
  );
}

function Kpis({ data, range }: { data: Overview; range: Range }) {
  const startBalance = data.totalBalanceMinorUnits - data.current.netMinorUnits;
  const change = data.current.netMinorUnits;
  return (
    <>
      <div className="card kpi hero">
        <div>
          <div className="label">Total balance</div>
          <div className="value">{formatMoney(data.totalBalanceMinorUnits, data.currency)}</div>
          <div className="delta">
            {change === 0 ? 'No change' : (
              <span className={change > 0 ? 'up' : 'down'}>{formatSigned(change, data.currency)}</span>
            )}
            <span>in the last {data.days} days{startBalance !== 0 && change !== 0
              ? ` (${formatPercent(change, startBalance)})` : ''}</span>
          </div>
        </div>
        <Sparkline values={data.daily.map((d) => d.closingBalanceMinorUnits)}
                   label={`Balance over the last ${data.days} days`} />
      </div>
      <Kpi label="Money in" value={data.current.moneyInMinorUnits} previous={data.previous.moneyInMinorUnits}
           currency={data.currency} moreIsGood range={range} />
      <Kpi label="Money out" value={data.current.moneyOutMinorUnits} previous={data.previous.moneyOutMinorUnits}
           currency={data.currency} moreIsGood={false} range={range} />
      <div className="card kpi">
        <div className="label">Net</div>
        <div className="value">{formatSigned(data.current.netMinorUnits, data.currency)}</div>
        <div className="delta">Money in minus money out</div>
      </div>
    </>
  );
}

function formatPercent(change: number, base: number): string {
  const percent = (change / Math.abs(base)) * 100;
  const rounded = Math.abs(percent) >= 10 ? Math.round(percent) : Math.round(percent * 10) / 10;
  return `${rounded > 0 ? '+' : rounded < 0 ? '−' : ''}${Math.abs(rounded)}%`;
}

/** A headline number with how it compares to the period before it. */
function Kpi({ label, value, previous, currency, moreIsGood, range }: {
  label: string; value: number; previous: number; currency: string; moreIsGood: boolean; range: Range;
}) {
  let comparison;
  if (previous === 0) {
    comparison = <span>{value === 0 ? 'Nothing in this period' : `Nothing in the ${RANGE_WORDS[range]}`}</span>;
  } else {
    const change = value - previous;
    const good = change === 0 ? null : (change > 0) === moreIsGood;
    comparison = (
      <>
        <span className={good === null ? '' : good ? 'up' : 'down'}>{formatPercent(change, previous)}</span>
        <span>vs {RANGE_WORDS[range]}</span>
      </>
    );
  }
  return (
    <div className="card kpi">
      <div className="label">{label}</div>
      <div className="value">{formatMoney(value, currency)}</div>
      <div className="delta">{comparison}</div>
    </div>
  );
}

/** First visit: nothing to chart yet, so show the way in instead of empty tiles. */
function Welcome({ name }: { name: string }) {
  return (
    <>
      <PageHeader title={`Welcome, ${name}`} description="Three steps and you are up and running." />
      <div className="cols-form">
        <Card title="Get started">
          <ol className="steps">
            <li><span><b>Open a wallet.</b> It holds your money and has its own statement.</span></li>
            <li><span><b>Add money.</b> Move funds in from your bank.</span></li>
            <li><span><b>Send, pay or schedule.</b> To a friend, a merchant, or yourself next month.</span></li>
          </ol>
          <div style={{ marginTop: 20 }}>
            <a className="btn primary" href="#/accounts?open=1"><Icon name="plus" size={16} /> Open your first account</a>
          </div>
        </Card>
      </div>
    </>
  );
}
