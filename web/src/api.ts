// Typed client for the LedgerFlow REST API.
//
// Every call that moves money takes an idempotency key from the caller.
// A screen creates one key per thing the person is trying to do and sends
// the same key again if they retry, so a double click or a flaky network
// can never move money twice.

export interface Session {
  token: string;
  userId: string;
  email: string;
  fullName: string;
  role: string;
}

export interface ApiError {
  status: number;
  code: string;
  message: string;
  details?: { field: string; message: string }[];
}

export interface Account {
  id: string;
  type: 'USER_WALLET' | 'MERCHANT';
  currency: string;
  status: 'ACTIVE' | 'FROZEN' | 'CLOSED';
  name: string;
  category: string | null;
  balanceMinorUnits: number;
  createdAt: string;
}

export interface Payee {
  accountId: string;
  type: string;
  status: string;
  currency: string;
  displayName: string;
  category: string | null;
  own: boolean;
}

export interface Recipient {
  id: string;
  accountId: string;
  nickname: string;
  accountType: string;
  accountStatus: string;
  currency: string;
  displayName: string;
  category: string | null;
  createdAt: string;
}

export type Direction = 'IN' | 'OUT' | 'INTERNAL';

export interface TransactionItem {
  id: string;
  type: string;
  status: string;
  amountMinorUnits: number;
  currency: string;
  sourceAccountId: string | null;
  destinationAccountId: string | null;
  description: string | null;
  createdAt: string;
  direction: Direction | null;
  counterparty: string | null;
  sourceLabel: string | null;
  destinationLabel: string | null;
}

export interface LedgerEntry {
  accountId: string | null;
  account: string;
  accountType: string;
  own: boolean;
  amountMinorUnits: number;
  direction: 'CREDIT' | 'DEBIT';
}

export interface TransactionEntries {
  transaction: TransactionItem;
  entries: LedgerEntry[];
  sumMinorUnits: number;
}

export interface StatementLine {
  entryId: string;
  transactionId: string;
  amountMinorUnits: number;
  direction: string;
  balanceAfterMinorUnits: number;
  currency: string;
  createdAt: string;
  type: string;
  description: string | null;
  counterparty: string | null;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface TransactionFilters {
  type?: string;
  direction?: string;
  accountId?: string;
  from?: string;
  to?: string;
  q?: string;
}

export interface OverviewDay {
  date: string;
  moneyInMinorUnits: number;
  moneyOutMinorUnits: number;
  closingBalanceMinorUnits: number;
}

export interface Totals {
  moneyInMinorUnits: number;
  moneyOutMinorUnits: number;
  netMinorUnits: number;
}

export interface Overview {
  currency: string;
  days: number;
  from: string;
  to: string;
  totalBalanceMinorUnits: number;
  accounts: { id: string; name: string; type: string; status: string; category: string | null; balanceMinorUnits: number }[];
  current: Totals;
  previous: Totals;
  daily: OverviewDay[];
  spending: { category: string; amountMinorUnits: number; transactions: number }[];
}

export interface MovementResult {
  transactionId: string;
  type: string;
  status: string;
  amountMinorUnits: number;
  currency: string;
  createdAt: string;
}

export interface PaymentResult {
  paymentId: string;
  transactionId: string;
  status: string;
  amountMinorUnits: number;
  feeMinorUnits: number;
  refundedMinorUnits: number;
  currency: string;
}

export interface PaymentListItem extends PaymentResult {
  description: string | null;
  payerName: string;
  merchantName: string;
  merchantCategory: string | null;
  role: 'PAYER' | 'MERCHANT';
  fraudVerdict: string | null;
  disputeId: string | null;
  disputeStatus: string | null;
  createdAt: string;
}

export interface FraudAssessment {
  verdict: string;
  score: number;
  ruleHits: string[];
  aiAssessment: {
    risk_level: string;
    summary: string;
    key_factors: string[];
    recommended_action: string;
  } | null;
  aiModel: string | null;
  aiAssessedAt: string | null;
}

export interface Dispute {
  disputeId: string;
  paymentId: string;
  status: 'OPEN' | 'UNDER_REVIEW' | 'RESOLVED_CUSTOMER' | 'RESOLVED_MERCHANT';
  reason: string;
  merchantResponse: string | null;
  resolutionNote: string | null;
  amountMinorUnits: number;
  refundedMinorUnits: number;
  currency: string;
  payerName: string;
  merchantName: string;
  role: 'CUSTOMER' | 'MERCHANT' | 'ADMIN';
  canDecide: boolean;
  createdAt: string;
  resolvedAt: string | null;
}

export interface Schedule {
  id: string;
  sourceAccountId: string;
  destinationAccountId: string;
  sourceLabel: string;
  destinationLabel: string;
  amountMinorUnits: number;
  currency: string;
  description: string | null;
  frequency: 'ONCE' | 'WEEKLY' | 'MONTHLY';
  status: 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  nextRunAt: string | null;
  runsCompleted: number;
  lastRunAt: string | null;
  lastOutcome: string | null;
  createdAt: string;
}

export interface MoneyRequest {
  id: string;
  requesterName: string;
  amountMinorUnits: number;
  currency: string;
  note: string | null;
  status: 'PENDING' | 'PAID' | 'CANCELLED' | 'EXPIRED';
  mine: boolean;
  payerName: string | null;
  transactionId: string | null;
  expiresAt: string;
  paidAt: string | null;
  createdAt: string;
}

export interface NotificationItem {
  id: string;
  type: string;
  title: string;
  body: string;
  read: boolean;
  createdAt: string;
}

export interface Inbox {
  items: NotificationItem[];
  unreadCount: number;
}

export interface ActivityItem {
  id: string;
  action: string;
  summary: string;
  category: 'SECURITY' | 'ACCOUNT' | 'MONEY';
  resourceType: string;
  resourceId: string;
  note: string | null;
  occurredAt: string;
}

export interface Profile {
  id: string;
  email: string;
  fullName: string;
  role: string;
  createdAt: string;
}

export interface Reply<T> {
  data: T;
  /** True when the server recognised the idempotency key and returned the stored result. */
  replayed: boolean;
}

export function isApiError(value: unknown): value is ApiError {
  return typeof value === 'object' && value !== null && 'code' in value && 'status' in value;
}

// The server's messages are precise but written for logs (they include
// account ids). These are the same facts in words for the person reading.
const FRIENDLY: Record<string, string> = {
  INSUFFICIENT_FUNDS: 'There is not enough money in that account.',
  ACCOUNT_FROZEN: 'That account is frozen. Unfreeze it before sending money from it.',
  ACCOUNT_NOT_ACTIVE: 'One of the accounts is closed and cannot be used.',
  ACCOUNT_NOT_FOUND: 'We could not find that account. Check the account ID.',
  ACCOUNT_NOT_EMPTY: 'Move the remaining balance out before closing this account.',
  SAME_ACCOUNT: 'Choose two different accounts.',
  NOT_A_MERCHANT: 'That account is a personal wallet. Use Send money to pay a person.',
  CURRENCY_MISMATCH: 'Both accounts need to hold the same currency.',
  BAD_CREDENTIALS: 'That email and password do not match.',
  EMAIL_TAKEN: 'There is already a profile with this email. Sign in instead.',
  WRONG_PASSWORD: 'The current password is not correct.',
  RATE_LIMITED: 'That was a lot of requests in a short time. Wait a minute and try again.',
  RECIPIENT_EXISTS: 'This account is already in your recipients.',
  OWN_ACCOUNT: 'Your own accounts are always available, so there is no need to save them.',
  UNAUTHENTICATED: 'Your session has ended. Sign in again.',
  PAYMENT_NOT_REFUNDABLE: 'This payment has already been refunded in full.',
  IDEMPOTENCY_KEY_REUSED: 'This request was already sent with different details. Start again.',
  REQUEST_NOT_FOUND: 'This request link is not valid. Ask the sender for a new one.',
  REQUEST_ALREADY_PAID: 'This request has already been paid.',
  REQUEST_CANCELLED: 'This request was cancelled and can no longer be paid.',
  REQUEST_EXPIRED: 'This request has expired. Ask the sender for a new one.',
  DISPUTE_ALREADY_OPEN: 'This payment already has an open dispute.',
  START_IN_PAST: 'Choose today or a later date.',
  NETWORK: 'We could not reach LedgerFlow. Check your connection and try again.',
};

/** A sentence a person can act on, for any failure. */
export function errorMessage(error: unknown): string {
  if (isApiError(error)) {
    if (FRIENDLY[error.code]) return FRIENDLY[error.code];
    if (error.code === 'VALIDATION_FAILED' && error.details?.length) {
      return error.details.map((d) => `${humanize(d.field)} ${d.message}`).join('. ') + '.';
    }
    return error.message;
  }
  return 'Something went wrong. Try again.';
}

function humanize(field: string): string {
  const words = field.replace(/([A-Z])/g, ' $1').replace('Minor Units', '').trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

type Query = Record<string, string | number | undefined | null>;

function withQuery(path: string, query: Query): string {
  const parts = Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
  return parts.length ? `${path}?${parts.join('&')}` : path;
}

export const timeZone: string = (() => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
})();

export class LedgerFlowApi {
  private token: string | null = null;
  private onUnauthorized: (() => void) | null = null;

  setToken(token: string | null) {
    this.token = token;
  }

  /** Called when a signed-in request comes back 401, so the app can return to sign-in. */
  setUnauthorizedHandler(handler: (() => void) | null) {
    this.onUnauthorized = handler;
  }

  private async send(method: string, path: string, body?: unknown, idempotencyKey?: string): Promise<Response> {
    const headers: Record<string, string> = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (this.token) headers['Authorization'] = `Bearer ${this.token}`;
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

    let response: Response;
    try {
      response = await fetch(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      throw { status: 0, code: 'NETWORK', message: FRIENDLY.NETWORK } satisfies ApiError;
    }
    if (!response.ok) {
      const text = await response.text();
      let parsed: Partial<ApiError> | null = null;
      try {
        parsed = text ? JSON.parse(text) : null;
      } catch {
        parsed = null;
      }
      const error: ApiError = {
        status: response.status,
        code: parsed?.code ?? 'UNKNOWN',
        message: parsed?.message ?? `The server answered with status ${response.status}.`,
        details: parsed?.details,
      };
      if (response.status === 401 && this.token && this.onUnauthorized) this.onUnauthorized();
      throw error;
    }
    return response;
  }

  private async request<T>(method: string, path: string, body?: unknown, idempotencyKey?: string): Promise<Reply<T>> {
    const response = await this.send(method, path, body, idempotencyKey);
    const text = await response.text();
    return {
      data: (text ? JSON.parse(text) : null) as T,
      replayed: response.headers.get('Idempotency-Replayed') === 'true',
    };
  }

  private async get<T>(path: string, query: Query = {}): Promise<T> {
    return (await this.request<T>('GET', withQuery(path, query))).data;
  }

  // --- sign in ---------------------------------------------------------

  async register(email: string, password: string, fullName: string) {
    await this.request('POST', '/api/v1/auth/register', { email, password, fullName });
  }

  async login(email: string, password: string): Promise<Session> {
    const { data } = await this.request<{
      accessToken: string; userId: string; email: string; fullName: string; role: string;
    }>('POST', '/api/v1/auth/login', { email, password });
    this.token = data.accessToken;
    return {
      token: data.accessToken, userId: data.userId,
      email: data.email ?? email, fullName: data.fullName ?? email, role: data.role ?? 'USER',
    };
  }

  // --- profile ---------------------------------------------------------

  me() {
    return this.get<Profile>('/api/v1/me');
  }

  async updateProfile(fullName: string) {
    return (await this.request<Profile>('PATCH', '/api/v1/me', { fullName })).data;
  }

  async changePassword(currentPassword: string, newPassword: string) {
    await this.request('POST', '/api/v1/me/password', { currentPassword, newPassword });
  }

  // --- accounts --------------------------------------------------------

  accounts() {
    return this.get<Account[]>('/api/v1/accounts');
  }

  account(id: string) {
    return this.get<Account>(`/api/v1/accounts/${id}`);
  }

  async openAccount(type: string, name: string, category?: string) {
    const body: Record<string, string> = { type, currency: 'USD', name };
    if (type === 'MERCHANT' && category) body.category = category;
    return (await this.request<Account>('POST', '/api/v1/accounts', body)).data;
  }

  async renameAccount(id: string, name: string) {
    return (await this.request<Account>('PATCH', `/api/v1/accounts/${id}`, { name })).data;
  }

  async freezeAccount(id: string) {
    return (await this.request<Account>('POST', `/api/v1/accounts/${id}/freeze`, {})).data;
  }

  async unfreezeAccount(id: string) {
    return (await this.request<Account>('POST', `/api/v1/accounts/${id}/unfreeze`, {})).data;
  }

  async closeAccount(id: string) {
    return (await this.request<Account>('DELETE', `/api/v1/accounts/${id}`)).data;
  }

  lookupPayee(accountId: string) {
    return this.get<Payee>(`/api/v1/accounts/lookup/${accountId}`);
  }

  statement(accountId: string, cursor?: string | null) {
    return this.get<Page<StatementLine>>(`/api/v1/accounts/${accountId}/statement`, { cursor, limit: 25 });
  }

  /** Downloads a statement as a CSV file and returns it with the server's file name. */
  async exportStatement(accountId: string, from: string, to: string): Promise<{ blob: Blob; fileName: string }> {
    const response = await this.send('GET',
      withQuery(`/api/v1/accounts/${accountId}/statement/export`, { from, to, zone: timeZone }));
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const match = /filename="?([^";]+)"?/.exec(disposition);
    return { blob: await response.blob(), fileName: match?.[1] ?? `statement-${from}-to-${to}.csv` };
  }

  // --- recipients ------------------------------------------------------

  recipients() {
    return this.get<Recipient[]>('/api/v1/recipients');
  }

  async addRecipient(accountId: string, nickname: string) {
    return (await this.request<Recipient>('POST', '/api/v1/recipients', { accountId, nickname })).data;
  }

  async removeRecipient(id: string) {
    await this.request('DELETE', `/api/v1/recipients/${id}`);
  }

  // --- transactions and insights ---------------------------------------

  transactions(filters: TransactionFilters = {}, cursor?: string | null, limit = 25) {
    return this.get<Page<TransactionItem>>('/api/v1/transactions',
      { ...filters, cursor, limit, zone: timeZone });
  }

  transactionEntries(id: string) {
    return this.get<TransactionEntries>(`/api/v1/transactions/${id}/entries`);
  }

  overview(days: number) {
    return this.get<Overview>('/api/v1/insights/overview', { days, zone: timeZone });
  }

  // --- moving money ----------------------------------------------------

  deposit(accountId: string, amountMinorUnits: number, description: string, key: string) {
    return this.request<MovementResult>('POST', `/api/v1/accounts/${accountId}/deposits`,
      { amountMinorUnits, currency: 'USD', description: description || undefined }, key);
  }

  withdraw(accountId: string, amountMinorUnits: number, description: string, key: string) {
    return this.request<MovementResult>('POST', `/api/v1/accounts/${accountId}/withdrawals`,
      { amountMinorUnits, currency: 'USD', description: description || undefined }, key);
  }

  transfer(sourceAccountId: string, destinationAccountId: string,
           amountMinorUnits: number, description: string, key: string) {
    return this.request<MovementResult>('POST', '/api/v1/transfers',
      { sourceAccountId, destinationAccountId, amountMinorUnits, currency: 'USD',
        description: description || undefined }, key);
  }

  // --- scheduled transfers ---------------------------------------------

  schedules() {
    return this.get<Schedule[]>('/api/v1/scheduled-transfers');
  }

  async createSchedule(input: {
    sourceAccountId: string; destinationAccountId: string; amountMinorUnits: number;
    description: string; frequency: string; startDate: string;
  }) {
    return (await this.request<Schedule>('POST', '/api/v1/scheduled-transfers',
      { ...input, description: input.description || undefined, currency: 'USD', zone: timeZone })).data;
  }

  async scheduleAction(id: string, action: 'pause' | 'resume' | 'cancel') {
    return (await this.request<Schedule>('POST', `/api/v1/scheduled-transfers/${id}/${action}`, {})).data;
  }

  // --- money requests --------------------------------------------------

  moneyRequests() {
    return this.get<MoneyRequest[]>('/api/v1/payment-requests');
  }

  moneyRequest(id: string) {
    return this.get<MoneyRequest>(`/api/v1/payment-requests/${id}`);
  }

  async createMoneyRequest(destinationAccountId: string, amountMinorUnits: number, note: string) {
    return (await this.request<MoneyRequest>('POST', '/api/v1/payment-requests',
      { destinationAccountId, amountMinorUnits, currency: 'USD', note: note || undefined })).data;
  }

  async cancelMoneyRequest(id: string) {
    return (await this.request<MoneyRequest>('POST', `/api/v1/payment-requests/${id}/cancel`, {})).data;
  }

  payMoneyRequest(id: string, sourceAccountId: string, key: string) {
    return this.request<MoneyRequest>('POST', `/api/v1/payment-requests/${id}/pay`, { sourceAccountId }, key);
  }

  // --- payments and disputes -------------------------------------------

  pay(sourceAccountId: string, destinationAccountId: string,
      amountMinorUnits: number, description: string, key: string) {
    return this.request<PaymentResult>('POST', '/api/v1/payments',
      { sourceAccountId, destinationAccountId, amountMinorUnits, currency: 'USD',
        description: description || undefined }, key);
  }

  payments(role: 'PAYER' | 'MERCHANT', cursor?: string | null) {
    return this.get<Page<PaymentListItem>>('/api/v1/payments', { role, cursor, limit: 25 });
  }

  refund(paymentId: string, amountMinorUnits: number | null, reason: string, key: string) {
    const body: Record<string, unknown> = {};
    if (amountMinorUnits !== null) body.amountMinorUnits = amountMinorUnits;
    if (reason) body.reason = reason;
    return this.request('POST', `/api/v1/payments/${paymentId}/refunds`, body, key);
  }

  fraudAssessment(paymentId: string) {
    return this.get<FraudAssessment>(`/api/v1/payments/${paymentId}/fraud-assessment`);
  }

  disputes() {
    return this.get<Dispute[]>('/api/v1/disputes');
  }

  async openDispute(paymentId: string, reason: string) {
    return (await this.request<Dispute>('POST', `/api/v1/payments/${paymentId}/disputes`, { reason })).data;
  }

  decideDispute(disputeId: string, outcome: 'REFUND' | 'DECLINE', note: string, key: string) {
    return this.request<Dispute>('POST', `/api/v1/disputes/${disputeId}/decision`,
      { outcome, note: note || undefined }, key);
  }

  // --- inbox and activity ----------------------------------------------

  inbox() {
    return this.get<Inbox>('/api/v1/notifications', { limit: 20 });
  }

  async markNotificationRead(id: string) {
    await this.request('POST', `/api/v1/notifications/${id}/read`, {});
  }

  async markAllNotificationsRead() {
    await this.request('POST', '/api/v1/notifications/read-all', {});
  }

  activity(cursor?: string | null) {
    return this.get<Page<ActivityItem>>('/api/v1/activity', { cursor, limit: 30 });
  }
}

export const api = new LedgerFlowApi();

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
