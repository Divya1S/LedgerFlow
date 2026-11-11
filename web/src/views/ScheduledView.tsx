import { useState } from 'react';
import { api, errorMessage, Schedule } from '../api';
import { formatDate, formatDateTime, formatMoney } from '../format';
import { useLoad } from '../hooks';
import { Icon } from '../icons';
import { Button, Card, ConfirmDialog, EmptyState, LoadingRows, Notice, PageHeader, StatusBadge, useToast } from '../ui';

const FREQUENCY: Record<Schedule['frequency'], string> = { ONCE: 'One time', WEEKLY: 'Every week', MONTHLY: 'Every month' };

const OUTCOME: Record<string, string> = {
  INSUFFICIENT_FUNDS: 'Skipped: not enough money in the account',
  ACCOUNT_FROZEN: 'Skipped: the account was frozen',
  ACCOUNT_NOT_ACTIVE: 'Skipped: an account was closed',
  ERROR: 'Delayed: it will be tried again shortly',
};

export default function ScheduledView() {
  const toast = useToast();
  const schedules = useLoad(() => api.schedules(), []);
  const [cancelling, setCancelling] = useState<Schedule | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function act(schedule: Schedule, action: 'pause' | 'resume' | 'cancel', done: string) {
    setBusy(true);
    setError(null);
    try {
      await api.scheduleAction(schedule.id, action);
      toast(done);
      setCancelling(null);
      schedules.reload();
    } catch (e) {
      if (action === 'cancel') setError(errorMessage(e));
      else toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  }

  const list = schedules.data ?? [];
  const upcoming = list.filter((s) => s.status === 'ACTIVE' || s.status === 'PAUSED');
  const past = list.filter((s) => s.status !== 'ACTIVE' && s.status !== 'PAUSED');

  return (
    <div className="stack-lg">
      <PageHeader
        title="Scheduled transfers"
        description="Transfers set to run on a date or on a repeat. Each one runs exactly once per date."
        actions={<a className="btn primary" href="#/send"><Icon name="plus" size={16} /> Schedule a transfer</a>}
      />

      {schedules.error && <Notice tone="error">{schedules.error}</Notice>}

      <Card flush title="Upcoming">
        {!schedules.data && !schedules.error && <LoadingRows />}
        {schedules.data && upcoming.length === 0 && (
          <EmptyState icon="calendar" title="Nothing scheduled"
                      action={<a className="btn" href="#/send">Schedule a transfer</a>}>
            Set up rent, savings or an allowance once and let it run. Choose a date or a repeat under
            When on the Send screen.
          </EmptyState>
        )}
        {upcoming.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr><th>To</th><th>Repeats</th><th>Next run</th><th className="right">Amount</th><th /></tr>
              </thead>
              <tbody>
                {upcoming.map((s) => (
                  <tr key={s.id}>
                    <td>
                      <div className="primary">{s.destinationLabel}</div>
                      <div className="secondary">
                        From {s.sourceLabel}{s.description ? ` · ${s.description}` : ''}
                      </div>
                      {s.lastOutcome && s.lastOutcome !== 'COMPLETED' && (
                        <div className="secondary" style={{ color: 'var(--warn)' }}>
                          Last run: {OUTCOME[s.lastOutcome] ?? 'Skipped'}
                        </div>
                      )}
                    </td>
                    <td className="nowrap">{FREQUENCY[s.frequency]}</td>
                    <td className="nowrap">
                      {s.status === 'PAUSED' ? <StatusBadge status="PAUSED" />
                        : s.nextRunAt ? formatDate(s.nextRunAt) : ''}
                      {s.runsCompleted > 0 && (
                        <div className="secondary">{s.runsCompleted} sent so far</div>
                      )}
                    </td>
                    <td className="amount">{formatMoney(s.amountMinorUnits, s.currency)}</td>
                    <td className="right nowrap">
                      {s.status === 'ACTIVE'
                        ? <Button size="small" variant="ghost" icon="pause" disabled={busy}
                                  onClick={() => act(s, 'pause', 'Schedule paused')}>Pause</Button>
                        : <Button size="small" variant="ghost" icon="play" disabled={busy}
                                  onClick={() => act(s, 'resume', 'Schedule resumed')}>Resume</Button>}
                      <Button size="small" variant="ghost" onClick={() => { setError(null); setCancelling(s); }}>
                        Cancel
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {past.length > 0 && (
        <Card flush title="Finished">
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>To</th><th>Repeats</th><th>Result</th><th className="right">Amount</th></tr></thead>
              <tbody>
                {past.map((s) => (
                  <tr key={s.id}>
                    <td>
                      <div className="primary">{s.destinationLabel}</div>
                      <div className="secondary">From {s.sourceLabel}</div>
                    </td>
                    <td className="nowrap">{FREQUENCY[s.frequency]}</td>
                    <td>
                      <StatusBadge status={s.status} label={s.status === 'COMPLETED' ? 'Sent' : undefined} />
                      <div className="secondary">
                        {s.status === 'FAILED' ? (OUTCOME[s.lastOutcome ?? ''] ?? 'It was refused')
                          : s.lastRunAt ? formatDateTime(s.lastRunAt)
                          : s.runsCompleted > 0 ? `${s.runsCompleted} sent before it was cancelled` : 'Never ran'}
                      </div>
                    </td>
                    <td className="amount">{formatMoney(s.amountMinorUnits, s.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <ConfirmDialog open={cancelling !== null} title="Cancel this schedule?" confirmLabel="Cancel schedule" danger
                     busy={busy} error={error} onClose={() => setCancelling(null)}
                     onConfirm={() => cancelling && act(cancelling, 'cancel', 'Schedule cancelled')}>
        {cancelling && `No more transfers of ${formatMoney(cancelling.amountMinorUnits)} will be sent to ${cancelling.destinationLabel}. Transfers already sent are not affected.`}
      </ConfirmDialog>
    </div>
  );
}
