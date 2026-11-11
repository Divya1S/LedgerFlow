import { useEffect, useMemo, useState } from 'react';
import { ActivityItem, api, errorMessage } from '../api';
import { dayLabel, formatTime } from '../format';
import { Icon, IconName } from '../icons';
import { Button, Card, EmptyState, LoadingRows, Notice, PageHeader, Segmented } from '../ui';

type Filter = 'ALL' | ActivityItem['category'];

const ICONS: Record<ActivityItem['category'], IconName> = { SECURITY: 'shield', ACCOUNT: 'wallet', MONEY: 'send' };

export default function ActivityView() {
  const [items, setItems] = useState<ActivityItem[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    api.activity()
      .then((page) => {
        if (!current) return;
        setItems(page.items);
        setNextCursor(page.nextCursor);
      })
      .catch((e) => { if (current) setError(errorMessage(e)); });
    return () => { current = false; };
  }, []);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    try {
      const page = await api.activity(nextCursor);
      setItems((current) => [...(current ?? []), ...page.items]);
      setNextCursor(page.nextCursor);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoadingMore(false);
    }
  }

  const groups = useMemo(() => {
    const result: { day: string; rows: ActivityItem[] }[] = [];
    for (const item of items ?? []) {
      if (filter !== 'ALL' && item.category !== filter) continue;
      const day = dayLabel(item.occurredAt);
      const last = result[result.length - 1];
      if (last && last.day === day) last.rows.push(item);
      else result.push({ day, rows: [item] });
    }
    return result;
  }, [items, filter]);

  return (
    <>
      <PageHeader
        title="Activity"
        description="A record of every sign-in and every change made with your profile. It cannot be edited or deleted."
        actions={
          <Segmented label="Show" value={filter} onChange={setFilter} options={[
            { value: 'ALL', label: 'All' }, { value: 'SECURITY', label: 'Security' },
            { value: 'ACCOUNT', label: 'Accounts' }, { value: 'MONEY', label: 'Money' },
          ]} />
        }
      />

      {error && <Notice tone="error">{error}</Notice>}

      <Card flush>
        {!items && !error && <LoadingRows rows={6} />}
        {items && groups.length === 0 && (
          <EmptyState icon="activity" title="Nothing to show">
            {filter === 'ALL' ? 'Your activity will appear here.' : 'No activity of this kind in the entries loaded so far.'}
          </EmptyState>
        )}
        {groups.map((group) => (
          <div key={group.day}>
            <div className="day-head">{group.day}</div>
            <div className="timeline">
              {group.rows.map((item) => (
                <div className="timeline-item" key={item.id}>
                  <span className="txn-icon"><Icon name={ICONS[item.category]} size={15} /></span>
                  <div className="grow">
                    <div style={{ fontWeight: 500 }}>{item.summary}</div>
                    {item.note && <div className="muted" style={{ fontSize: 12.5 }}>{item.note}</div>}
                  </div>
                  <span className="muted nowrap">{formatTime(item.occurredAt)}</span>
                </div>
              ))}
            </div>
          </div>
        ))}
        {nextCursor && (
          <div className="card-foot">
            <Button block variant="ghost" busy={loadingMore} onClick={loadMore}>Show earlier activity</Button>
          </div>
        )}
      </Card>

      <p className="muted" style={{ marginTop: 16, fontSize: 13 }}>
        See something you did not do? <a href="#/settings">Change your password</a> and freeze the affected account.
      </p>
    </>
  );
}
