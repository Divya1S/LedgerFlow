import { FormEvent, useEffect, useState } from 'react';
import { Account, api, errorMessage, StatementLine } from '../api';
import { StatementParty, TransactionDrawer } from '../components';
import { accountTypeLabel, categoryLabel, dayLabel, formatDate, formatMoney, formatTime, todayIso } from '../format';
import { navigate, useLoad } from '../hooks';
import {
  Amount, Button, Card, ConfirmDialog, CopyId, EmptyState, Field, LoadingRows, Modal, Notice, PageHeader,
  StatusBadge, useToast,
} from '../ui';

export default function AccountDetailView({ accountId }: { accountId: string }) {
  const toast = useToast();
  const account = useLoad(() => api.account(accountId), [accountId]);
  const [lines, setLines] = useState<StatementLine[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [statementError, setStatementError] = useState<string | null>(null);
  const [openTransaction, setOpenTransaction] = useState<string | null>(null);
  const [dialog, setDialog] = useState<'rename' | 'freeze' | 'unfreeze' | 'close' | 'export' | null>(null);
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    setLines(null);
    api.statement(accountId)
      .then((page) => {
        if (!current) return;
        setLines(page.items);
        setNextCursor(page.nextCursor);
      })
      .catch((e) => { if (current) setStatementError(errorMessage(e)); });
    return () => { current = false; };
  }, [accountId]);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    try {
      const page = await api.statement(accountId, nextCursor);
      setLines((current) => [...(current ?? []), ...page.items]);
      setNextCursor(page.nextCursor);
    } catch (e) {
      setStatementError(errorMessage(e));
    } finally {
      setLoadingMore(false);
    }
  }

  function openDialog(next: typeof dialog) {
    setDialogError(null);
    setDialog(next);
  }

  async function run(action: () => Promise<Account>, done: string) {
    setBusy(true);
    setDialogError(null);
    try {
      await action();
      toast(done);
      setDialog(null);
      account.reload();
    } catch (e) {
      setDialogError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  if (account.error && !account.data) {
    return (
      <>
        <PageHeader title="Account" back={{ label: 'Accounts', href: '#/accounts' }} />
        <Notice tone="error">{account.error}</Notice>
      </>
    );
  }
  const a = account.data;
  if (!a) return <Card flush><LoadingRows /></Card>;

  const closed = a.status === 'CLOSED';
  const frozen = a.status === 'FROZEN';

  return (
    <div className="stack-lg">
      <PageHeader
        back={{ label: 'Accounts', href: '#/accounts' }}
        title={<span className="row">{a.name} {a.status !== 'ACTIVE' && <StatusBadge status={a.status} />}</span>}
        description={`${accountTypeLabel(a.type)}${a.category ? ` · ${categoryLabel(a.category)}` : ''} · Opened ${formatDate(a.createdAt)}`}
        actions={!closed && (
          <>
            <Button icon="plus" onClick={() => navigate(`/send?tab=add&from=${a.id}`)}>Add money</Button>
            <Button variant="primary" icon="send" disabled={frozen}
                    onClick={() => navigate(`/send?from=${a.id}`)}>Send</Button>
          </>
        )}
      />

      {frozen && (
        <Notice tone="info">
          <strong>This account is frozen.</strong> Money can still arrive, but nothing can be sent from it
          until you unfreeze it.
        </Notice>
      )}

      <div className="cols-main">
        <Card flush title="Statement"
              description="Every entry on this account, with the balance after each one"
              actions={<Button size="small" icon="download" onClick={() => openDialog('export')}>Export CSV</Button>}>
          {statementError && <div className="card-body"><Notice tone="error">{statementError}</Notice></div>}
          {!lines && !statementError && <LoadingRows rows={6} />}
          {lines && lines.length === 0 && (
            <EmptyState icon="list" title="No entries yet">
              Money you add, send or receive on this account will be listed here.
            </EmptyState>
          )}
          {lines && lines.length > 0 && (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr><th>Details</th><th className="hide-narrow">Date</th>
                    <th className="right">Amount</th><th className="right">Balance</th></tr>
                </thead>
                <tbody>
                  {lines.map((line) => (
                    <tr key={line.entryId} className="clickable" tabIndex={0}
                        onClick={() => setOpenTransaction(line.transactionId)}
                        onKeyDown={(e) => { if (e.key === 'Enter') setOpenTransaction(line.transactionId); }}>
                      <td><StatementParty line={line} /></td>
                      <td className="hide-narrow nowrap muted">{dayLabel(line.createdAt)}, {formatTime(line.createdAt)}</td>
                      <td className="amount"><Amount minorUnits={line.amountMinorUnits} currency={line.currency} signed /></td>
                      <td className="amount" style={{ fontWeight: 400 }}>
                        {formatMoney(line.balanceAfterMinorUnits, line.currency)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {nextCursor && (
            <div className="card-foot">
              <Button block variant="ghost" busy={loadingMore} onClick={loadMore}>Show earlier entries</Button>
            </div>
          )}
        </Card>

        <div className="stack">
          <Card>
            <div className="muted" style={{ fontSize: 13 }}>Available balance</div>
            <div style={{ fontSize: 30, fontWeight: 600, letterSpacing: '-0.02em', margin: '4px 0 16px' }}>
              {formatMoney(a.balanceMinorUnits, a.currency)}
            </div>
            <dl className="facts">
              <dt>Currency</dt><dd>{a.currency}</dd>
              <dt>Account ID</dt><dd><CopyId value={a.id} label="Copy account ID" /></dd>
            </dl>
            <p className="muted" style={{ fontSize: 12.5, marginTop: 12 }}>
              Share the account ID with someone so they can send you money.
            </p>
          </Card>

          {!closed && (
            <Card title="Manage">
              <div className="stack" style={{ gap: 8 }}>
                <Button block onClick={() => openDialog('rename')}>Rename</Button>
                {frozen
                  ? <Button block icon="unlock" onClick={() => openDialog('unfreeze')}>Unfreeze account</Button>
                  : <Button block icon="lock" onClick={() => openDialog('freeze')}>Freeze account</Button>}
                <Button block variant="danger" onClick={() => openDialog('close')}>Close account</Button>
              </div>
            </Card>
          )}
        </div>
      </div>

      <RenameModal open={dialog === 'rename'} account={a} busy={busy} error={dialogError}
                   onClose={() => setDialog(null)}
                   onSave={(name) => run(() => api.renameAccount(a.id, name), 'Account renamed')} />

      <ConfirmDialog open={dialog === 'freeze'} title={`Freeze ${a.name}?`} confirmLabel="Freeze account"
                     busy={busy} error={dialogError} onClose={() => setDialog(null)}
                     onConfirm={() => run(() => api.freezeAccount(a.id), 'Account frozen')}>
        Nothing can be sent or withdrawn from this account while it is frozen, including scheduled
        transfers. Money can still arrive. You can unfreeze it at any time.
      </ConfirmDialog>

      <ConfirmDialog open={dialog === 'unfreeze'} title={`Unfreeze ${a.name}?`} confirmLabel="Unfreeze account"
                     busy={busy} error={dialogError} onClose={() => setDialog(null)}
                     onConfirm={() => run(() => api.unfreezeAccount(a.id), 'Account unfrozen')}>
        Money can be sent from this account again straight away.
      </ConfirmDialog>

      <ConfirmDialog open={dialog === 'close'} title={`Close ${a.name}?`} confirmLabel="Close account" danger
                     busy={busy} error={dialogError} onClose={() => setDialog(null)}
                     onConfirm={() => run(() => api.closeAccount(a.id), 'Account closed')}>
        {a.balanceMinorUnits > 0
          ? `Move the remaining ${formatMoney(a.balanceMinorUnits, a.currency)} out first. An account can only be closed when it is empty.`
          : 'A closed account cannot be reopened. Its statement stays available.'}
      </ConfirmDialog>

      <ExportModal open={dialog === 'export'} accountId={a.id} onClose={() => setDialog(null)} />
      <TransactionDrawer transactionId={openTransaction} onClose={() => setOpenTransaction(null)} />
    </div>
  );
}

function RenameModal({ open, account, busy, error, onClose, onSave }: {
  open: boolean; account: Account; busy: boolean; error: string | null;
  onClose: () => void; onSave: (name: string) => void;
}) {
  const [name, setName] = useState(account.name);
  useEffect(() => { if (open) setName(account.name); }, [open, account.name]);

  function submit(event: FormEvent) {
    event.preventDefault();
    onSave(name.trim());
  }
  return (
    <Modal open={open} title="Rename account" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <Field label="Name">
          {(id) => <input id={id} className="input" value={name} onChange={(e) => setName(e.target.value)}
                          required maxLength={120} />}
        </Field>
        {error && <Notice tone="error">{error}</Notice>}
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" busy={busy} disabled={!name.trim()}>Save</Button>
        </div>
      </form>
    </Modal>
  );
}

function ExportModal({ open, accountId, onClose }: { open: boolean; accountId: string; onClose: () => void }) {
  const toast = useToast();
  const [from, setFrom] = useState(todayIso(-30));
  const [to, setTo] = useState(todayIso());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function download(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { blob, fileName } = await api.exportStatement(accountId, from, to);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      toast('Statement downloaded');
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} title="Export statement" onClose={onClose}
           description="A CSV file that opens in Excel, Numbers or Google Sheets.">
      <form className="stack" onSubmit={download}>
        <div className="row" style={{ flexWrap: 'nowrap', alignItems: 'flex-start' }}>
          <div className="grow">
            <Field label="From">
              {(id) => <input id={id} className="input" type="date" value={from} max={to}
                              onChange={(e) => setFrom(e.target.value)} required />}
            </Field>
          </div>
          <div className="grow">
            <Field label="To">
              {(id) => <input id={id} className="input" type="date" value={to} min={from} max={todayIso()}
                              onChange={(e) => setTo(e.target.value)} required />}
            </Field>
          </div>
        </div>
        <p className="muted" style={{ fontSize: 13 }}>
          Up to one year at a time. Entries are listed oldest first with the balance after each one.
        </p>
        {error && <Notice tone="error">{error}</Notice>}
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" icon="download" busy={busy}>Download</Button>
        </div>
      </form>
    </Modal>
  );
}
