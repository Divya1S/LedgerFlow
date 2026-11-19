import {
  ButtonHTMLAttributes, createContext, ReactNode, useCallback, useContext, useEffect, useId, useRef, useState,
} from 'react';
import { Icon, IconName } from './icons';
import { formatMoney, formatSigned } from './format';
import { useEscape } from './hooks';

// --- buttons -----------------------------------------------------------

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'default' | 'primary' | 'danger' | 'danger-solid' | 'ghost';
  size?: 'small' | 'medium' | 'large';
  block?: boolean;
  /** Shows a spinner and blocks clicks while work is in flight. */
  busy?: boolean;
  icon?: IconName;
}

export function Button({ variant = 'default', size = 'medium', block, busy, icon, children,
                         className, disabled, type = 'button', ...rest }: ButtonProps) {
  const classes = ['btn', variant !== 'default' && variant, size !== 'medium' && size,
    block && 'block', !children && 'icon-only', className].filter(Boolean).join(' ');
  return (
    <button type={type} className={classes} disabled={disabled || busy} aria-busy={busy || undefined} {...rest}>
      {busy ? <span className="spinner" /> : icon && <Icon name={icon} size={16} />}
      {children}
    </button>
  );
}

// --- structure ---------------------------------------------------------

export function PageHeader({ title, description, actions, back }: {
  title: ReactNode; description?: ReactNode; actions?: ReactNode; back?: { label: string; href: string };
}) {
  return (
    <div className="page-head">
      <div className="titles">
        {back && <a className="crumb" href={back.href}>{back.label} <Icon name="chevronRight" size={12} /></a>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </div>
  );
}

export function Card({ title, description, actions, children, flush, className }: {
  title?: ReactNode; description?: ReactNode; actions?: ReactNode; children: ReactNode;
  /** Content runs edge to edge (tables, lists). */
  flush?: boolean; className?: string;
}) {
  return (
    <section className={`card ${className ?? ''}`}>
      {(title || actions) && (
        <header className="card-head">
          <div className="grow">
            {title && <h2>{title}</h2>}
            {description && <p>{description}</p>}
          </div>
          {actions}
        </header>
      )}
      {flush ? children : <div className="card-body">{children}</div>}
    </section>
  );
}

export function EmptyState({ icon, title, children, action }: {
  icon: IconName; title: string; children?: ReactNode; action?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="mark"><Icon name={icon} size={20} /></div>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function Notice({ tone = 'info', children }: { tone?: 'info' | 'error' | 'success' | 'warn'; children: ReactNode }) {
  const icon: IconName = tone === 'success' ? 'check' : tone === 'info' ? 'info' : 'alert';
  return (
    <div className={`notice ${tone === 'info' ? '' : tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      <Icon name={icon} size={16} />
      <div>{children}</div>
    </div>
  );
}

/** Placeholder rows shown the first time a list loads. */
export function LoadingRows({ rows = 4 }: { rows?: number }) {
  return (
    <div className="card-body stack" aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton" style={{ width: `${88 - i * 13}%` }} />
      ))}
    </div>
  );
}

// --- forms -------------------------------------------------------------

export function Field({ label, hint, error, children }: {
  label: string; hint?: ReactNode; error?: string | null; children: (id: string) => ReactNode;
}) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children(id)}
      {error ? <span className="error">{error}</span> : hint && <span className="hint">{hint}</span>}
    </div>
  );
}

export function AmountField({ label = 'Amount', value, onChange, hint, error }: {
  label?: string; value: string; onChange: (value: string) => void; hint?: ReactNode; error?: string | null;
}) {
  return (
    <Field label={label} hint={hint} error={error}>
      {(id) => (
        <div className="amount-input">
          <span className="unit" aria-hidden="true">$</span>
          <input id={id} className={`input ${error ? 'invalid' : ''}`} inputMode="decimal" autoComplete="off"
                 placeholder="0.00" value={value} onChange={(e) => onChange(e.target.value)} />
        </div>
      )}
    </Field>
  );
}

export function Segmented<T extends string>({ value, onChange, options, label }: {
  value: T; onChange: (value: T) => void; options: { value: T; label: string }[]; label: string;
}) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map((option) => (
        <button key={option.value} type="button" aria-pressed={value === option.value}
                onClick={() => onChange(option.value)}>
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function Tabs<T extends string>({ value, onChange, options }: {
  value: T; onChange: (value: T) => void; options: { value: T; label: string }[];
}) {
  return (
    <div className="tabs" role="tablist">
      {options.map((option) => (
        <button key={option.value} type="button" role="tab" aria-selected={value === option.value}
                onClick={() => onChange(option.value)}>
          {option.label}
        </button>
      ))}
    </div>
  );
}

// --- status and money --------------------------------------------------

type Tone = 'good' | 'warn' | 'bad' | 'info' | 'neutral';

const STATUS: Record<string, { tone: Tone; label: string }> = {
  ACTIVE: { tone: 'good', label: 'Active' },
  FROZEN: { tone: 'info', label: 'Frozen' },
  CLOSED: { tone: 'neutral', label: 'Closed' },
  COMPLETED: { tone: 'good', label: 'Completed' },
  HELD: { tone: 'warn', label: 'On hold' },
  RELEASED: { tone: 'good', label: 'Delivered' },
  RETURNED: { tone: 'neutral', label: 'Returned to sender' },
  PENDING: { tone: 'warn', label: 'Pending' },
  FAILED: { tone: 'bad', label: 'Failed' },
  REVERSED: { tone: 'neutral', label: 'Reversed' },
  REFUNDED: { tone: 'neutral', label: 'Refunded' },
  PARTIALLY_REFUNDED: { tone: 'warn', label: 'Partly refunded' },
  PAID: { tone: 'good', label: 'Paid' },
  CANCELLED: { tone: 'neutral', label: 'Cancelled' },
  EXPIRED: { tone: 'neutral', label: 'Expired' },
  PAUSED: { tone: 'warn', label: 'Paused' },
  OPEN: { tone: 'warn', label: 'Open' },
  UNDER_REVIEW: { tone: 'info', label: 'Under review' },
  RESOLVED_CUSTOMER: { tone: 'good', label: 'Refunded to customer' },
  RESOLVED_MERCHANT: { tone: 'neutral', label: 'Closed, no refund' },
  APPROVED: { tone: 'good', label: 'Passed' },
  REVIEW: { tone: 'warn', label: 'Needs review' },
  REJECTED: { tone: 'bad', label: 'High risk' },
  LOW: { tone: 'good', label: 'Low risk' },
  MEDIUM: { tone: 'warn', label: 'Medium risk' },
  HIGH: { tone: 'bad', label: 'High risk' },
  CRITICAL: { tone: 'bad', label: 'Critical risk' },
};

/** A status as a labelled pill. The words carry the meaning; colour only supports them. */
export function StatusBadge({ status, label }: { status: string; label?: string }) {
  const known = STATUS[status] ?? { tone: 'neutral' as Tone, label: status };
  return <span className={`badge ${known.tone === 'neutral' ? '' : known.tone}`}>{label ?? known.label}</span>;
}

export function Tag({ children }: { children: ReactNode }) {
  return <span className="badge plain">{children}</span>;
}

/**
 * A money amount. With `signed`, money in is green with a plus and money
 * out is plain ink with a minus: the sign says the direction, so colour is
 * never the only cue.
 */
export function Amount({ minorUnits, currency = 'USD', signed }: {
  minorUnits: number; currency?: string; signed?: boolean;
}) {
  if (!signed) return <span className="num">{formatMoney(minorUnits, currency)}</span>;
  return (
    <span className="num" style={minorUnits > 0 ? { color: 'var(--positive)' } : undefined}>
      {formatSigned(minorUnits, currency)}
    </span>
  );
}

export function CopyId({ value, label = 'Copy' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard blocked: the id is still visible to select by hand */
    }
  }
  return (
    <span className="copy-id">
      <span className="truncate">{value}</span>
      <button type="button" onClick={copy} aria-label={copied ? 'Copied' : label} title={copied ? 'Copied' : label}>
        <Icon name={copied ? 'check' : 'copy'} size={14} />
      </button>
    </span>
  );
}

// --- overlays ----------------------------------------------------------

function useDialogFocus(open: boolean) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    const first = ref.current?.querySelector<HTMLElement>('input, select, textarea, button:not([data-close])');
    (first ?? ref.current)?.focus();
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = '';
      previous?.focus?.();
    };
  }, [open]);
  return ref;
}

export function Modal({ open, title, description, onClose, children, footer }: {
  open: boolean; title: string; description?: ReactNode; onClose: () => void;
  children?: ReactNode; footer?: ReactNode;
}) {
  const titleId = useId();
  const ref = useDialogFocus(open);
  useEscape(open, onClose);
  if (!open) return null;
  return (
    <div className="backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={ref} tabIndex={-1}>
        <div className="modal-head">
          <div className="grow">
            <h2 id={titleId}>{title}</h2>
            {description && <p>{description}</p>}
          </div>
          <Button variant="ghost" size="small" icon="close" aria-label="Close" data-close onClick={onClose} />
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

export function Drawer({ open, title, onClose, children }: {
  open: boolean; title: string; onClose: () => void; children: ReactNode;
}) {
  const titleId = useId();
  const ref = useDialogFocus(open);
  useEscape(open, onClose);
  if (!open) return null;
  return (
    <div className="backdrop drawer-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="drawer" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={ref} tabIndex={-1}>
        <div className="drawer-head">
          <h2 id={titleId} className="grow">{title}</h2>
          <Button variant="ghost" size="small" icon="close" aria-label="Close" data-close onClick={onClose} />
        </div>
        <div className="drawer-body">{children}</div>
      </div>
    </div>
  );
}

/** Asks before something that cannot be undone, or that moves money. */
export function ConfirmDialog({ open, title, children, confirmLabel, danger, busy, error, onConfirm, onClose }: {
  open: boolean; title: string; children?: ReactNode; confirmLabel: string; danger?: boolean;
  busy?: boolean; error?: string | null; onConfirm: () => void; onClose: () => void;
}) {
  return (
    <Modal open={open} title={title} onClose={onClose}
           footer={<>
             <Button onClick={onClose}>Cancel</Button>
             <Button variant={danger ? 'danger-solid' : 'primary'} busy={busy} onClick={onConfirm}>{confirmLabel}</Button>
           </>}>
      <div className="stack">
        {children && <div className="muted">{children}</div>}
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

// --- toasts ------------------------------------------------------------

interface Toast { id: number; text: string; tone: 'ok' | 'error'; }

const ToastContext = createContext<(text: string, tone?: 'ok' | 'error') => void>(() => undefined);

export function useToast() {
  return useContext(ToastContext);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);
  const push = useCallback((text: string, tone: 'ok' | 'error' = 'ok') => {
    const id = nextId.current++;
    setToasts((current) => [...current, { id, text, tone }]);
    window.setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), 4200);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast ${toast.tone === 'error' ? 'error' : ''}`}>
            <Icon name={toast.tone === 'error' ? 'alert' : 'check'} size={16} />
            {toast.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
