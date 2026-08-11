// LedgerFlow load test.
//
// Run (app must be up with a raised rate limit, see load/README.md):
//   k6 run --summary-export=load/results/summary.json load/ledgerflow-load.js
//
// Scenarios:
//   transfers    normal wallet-to-wallet transfers, distinct accounts
//   hot          many VUs draining ONE shared wallet: worst-case row contention
//   payments     wallet-to-merchant payments (fee path)
//   history      first-page history reads (Redis cache path)
//   accounts     register + login + open account (bcrypt-heavy)

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = 30;
const PASSWORD = 'load-test-password-123';

export const options = {
  scenarios: {
    transfers: { executor: 'constant-vus', vus: 20, duration: '60s', exec: 'transfers' },
    hot:       { executor: 'constant-vus', vus: 10, duration: '60s', exec: 'hotTransfers' },
    payments:  { executor: 'constant-vus', vus: 10, duration: '60s', exec: 'payments' },
    history:   { executor: 'constant-vus', vus: 10, duration: '60s', exec: 'history' },
    accounts:  { executor: 'constant-vus', vus: 3,  duration: '60s', exec: 'accounts' },
  },
  // Thresholds double as per-scenario latency breakdown in the summary.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:transfers}': ['p(95)<500'],
    'http_req_duration{scenario:hot}': ['p(95)<1000'],
    'http_req_duration{scenario:payments}': ['p(95)<500'],
    'http_req_duration{scenario:history}': ['p(95)<200'],
    'http_req_duration{scenario:accounts}': ['p(95)<2000'],
    checks: ['rate>0.99'],
  },
};

const json = { 'Content-Type': 'application/json' };

function auth(token) {
  return { headers: { ...json, Authorization: `Bearer ${token}` } };
}

function idem(token) {
  // Unique per call and safe in both setup() and VU context (no __ITER).
  return {
    headers: {
      ...json,
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': `k6-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    },
  };
}

export function setup() {
  const users = [];
  for (let i = 0; i < USERS; i++) {
    const email = `load-${Date.now()}-${i}@load.ledgerflow.io`;
    http.post(`${BASE}/api/v1/auth/register`, JSON.stringify({
      email, password: PASSWORD, fullName: `Load User ${i}`,
    }), { headers: json });
    const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
      email, password: PASSWORD,
    }), { headers: json });
    const token = login.json('accessToken');
    const wallet = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
      type: 'USER_WALLET', currency: 'USD', name: 'load wallet',
    }), auth(token)).json('id');
    http.post(`${BASE}/api/v1/accounts/${wallet}/deposits`, JSON.stringify({
      amountMinorUnits: 50000000, currency: 'USD',
    }), idem(token));
    users.push({ token, wallet });
  }

  // One merchant for the payments scenario.
  const merchantToken = users[0].token;
  const merchant = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
    type: 'MERCHANT', currency: 'USD', name: 'load merchant',
  }), auth(merchantToken)).json('id');

  // One heavily funded shared wallet for the hot-contention scenario.
  const hotOwner = users[1];
  const hotWallet = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
    type: 'USER_WALLET', currency: 'USD', name: 'hot wallet',
  }), auth(hotOwner.token)).json('id');
  http.post(`${BASE}/api/v1/accounts/${hotWallet}/deposits`, JSON.stringify({
    amountMinorUnits: 100000000, currency: 'USD',
  }), idem(hotOwner.token));

  return { users, merchant, hotWallet, hotToken: hotOwner.token };
}

export function transfers(data) {
  const from = data.users[__VU % USERS];
  const to = data.users[(__VU + 7) % USERS];
  const res = http.post(`${BASE}/api/v1/transfers`, JSON.stringify({
    sourceAccountId: from.wallet, destinationAccountId: to.wallet,
    amountMinorUnits: 5, currency: 'USD', description: 'load transfer',
  }), idem(from.token));
  check(res, { 'transfer 201': (r) => r.status === 201 });
}

export function hotTransfers(data) {
  const to = data.users[__VU % USERS];
  const res = http.post(`${BASE}/api/v1/transfers`, JSON.stringify({
    sourceAccountId: data.hotWallet, destinationAccountId: to.wallet,
    amountMinorUnits: 3, currency: 'USD', description: 'hot transfer',
  }), idem(data.hotToken));
  check(res, { 'hot transfer 201': (r) => r.status === 201 });
}

export function payments(data) {
  const payer = data.users[__VU % USERS];
  const res = http.post(`${BASE}/api/v1/payments`, JSON.stringify({
    sourceAccountId: payer.wallet, destinationAccountId: data.merchant,
    amountMinorUnits: 100, currency: 'USD', description: 'load payment',
  }), idem(payer.token));
  check(res, { 'payment 201': (r) => r.status === 201 });
}

export function history(data) {
  const user = data.users[__VU % USERS];
  const res = http.get(`${BASE}/api/v1/accounts/${user.wallet}/transactions`, auth(user.token));
  check(res, { 'history 200': (r) => r.status === 200 });
}

export function accounts() {
  const email = `acct-${__VU}-${__ITER}-${Date.now()}@load.ledgerflow.io`;
  const reg = http.post(`${BASE}/api/v1/auth/register`, JSON.stringify({
    email, password: PASSWORD, fullName: 'Account Load',
  }), { headers: json });
  const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    email, password: PASSWORD,
  }), { headers: json });
  const created = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
    type: 'USER_WALLET', currency: 'USD', name: 'w',
  }), auth(login.json('accessToken')));
  check(reg, { 'register 201': (r) => r.status === 201 });
  check(created, { 'account 201': (r) => r.status === 201 });
}
