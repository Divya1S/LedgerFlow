// Thin typed client over the LedgerFlow REST API. Money-writing calls
// attach a fresh Idempotency-Key per logical operation; callers can pass
// the same key again to demonstrate replay.

export interface Session {
  token: string;
  userId: string;
  email: string;
}

export interface ApiError {
  status: number;
  code: string;
  message: string;
}

export interface Account {
  id: string;
  type: string;
  currency: string;
  status: string;
  name: string;
  createdAt: string;
}

export interface Balance {
  accountId: string;
  balanceMinorUnits: number;
  currency: string;
  asOf: string;
}

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
}

export interface StatementLine {
  entryId: string;
  transactionId: string;
  amountMinorUnits: number;
  direction: string;
  balanceAfterMinorUnits: number;
  currency: string;
  createdAt: string;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
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

export class LedgerFlowApi {
  private token: string | null = null;

  setToken(token: string | null) {
    this.token = token;
  }

  private async request<T>(method: string, path: string, body?: unknown,
                           idempotencyKey?: string): Promise<{ data: T; replayed: boolean }> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.token) headers['Authorization'] = `Bearer ${this.token}`;
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    const data = text ? JSON.parse(text) : null;
    if (!response.ok) {
      throw {
        status: response.status,
        code: data?.code ?? 'UNKNOWN',
        message: data?.message ?? `HTTP ${response.status}`,
      } as ApiError;
    }
    return { data: data as T, replayed: response.headers.get('Idempotency-Replayed') === 'true' };
  }

  async register(email: string, password: string, fullName: string) {
    await this.request('POST', '/api/v1/auth/register', { email, password, fullName });
  }

  async login(email: string, password: string): Promise<Session> {
    const { data } = await this.request<{ accessToken: string; userId: string }>(
      'POST', '/api/v1/auth/login', { email, password });
    this.token = data.accessToken;
    return { token: data.accessToken, userId: data.userId, email };
  }

  accounts() {
    return this.request<Account[]>('GET', '/api/v1/accounts');
  }

  openAccount(type: string, name: string) {
    return this.request<Account>('POST', '/api/v1/accounts', { type, currency: 'USD', name });
  }

  balance(accountId: string) {
    return this.request<Balance>('GET', `/api/v1/accounts/${accountId}/balance`);
  }

  statement(accountId: string, cursor?: string | null) {
    const query = cursor ? `?cursor=${encodeURIComponent(cursor)}&limit=20` : '?limit=20';
    return this.request<Page<StatementLine>>('GET', `/api/v1/accounts/${accountId}/statement${query}`);
  }

  history(cursor?: string | null) {
    const query = cursor ? `?cursor=${encodeURIComponent(cursor)}&limit=20` : '?limit=20';
    return this.request<Page<TransactionItem>>('GET', `/api/v1/transactions${query}`);
  }

  deposit(accountId: string, amountMinorUnits: number, key: string) {
    return this.request('POST', `/api/v1/accounts/${accountId}/deposits`,
      { amountMinorUnits, currency: 'USD' }, key);
  }

  withdraw(accountId: string, amountMinorUnits: number, key: string) {
    return this.request('POST', `/api/v1/accounts/${accountId}/withdrawals`,
      { amountMinorUnits, currency: 'USD' }, key);
  }

  transfer(sourceAccountId: string, destinationAccountId: string,
           amountMinorUnits: number, description: string, key: string) {
    return this.request('POST', '/api/v1/transfers',
      { sourceAccountId, destinationAccountId, amountMinorUnits, currency: 'USD', description }, key);
  }

  pay(sourceAccountId: string, destinationAccountId: string,
      amountMinorUnits: number, description: string, key: string) {
    return this.request<PaymentResult>('POST', '/api/v1/payments',
      { sourceAccountId, destinationAccountId, amountMinorUnits, currency: 'USD', description }, key);
  }

  refund(paymentId: string, key: string) {
    return this.request('POST', `/api/v1/payments/${paymentId}/refunds`, {}, key);
  }

  fraudAssessment(paymentId: string) {
    return this.request<FraudAssessment>('GET', `/api/v1/payments/${paymentId}/fraud-assessment`);
  }
}

export const api = new LedgerFlowApi();

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export function formatMoney(minorUnits: number, currency = 'USD'): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency })
    .format(minorUnits / 100);
}
