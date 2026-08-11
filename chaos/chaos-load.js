// Light continuous money load used by chaos-test.sh: transfers + history
// reads for 120s while dependencies are being killed around it.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = 6;
const PASSWORD = 'chaos-test-password-123';
const json = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    transfers: { executor: 'constant-vus', vus: 6, duration: '120s', exec: 'transfers' },
    history: { executor: 'constant-vus', vus: 2, duration: '120s', exec: 'history' },
  },
  // Chaos run: we EXPECT elevated latency during failovers; what must hold
  // is that requests either succeed or fail cleanly (no hangs, asserted by
  // the invariant checks afterwards).
  thresholds: { checks: ['rate>0.95'] },
};

function auth(t) { return { headers: { ...json, Authorization: `Bearer ${t}` } }; }
function idem(t) {
  // Unique per call and safe in both setup() and VU context (no __ITER).
  return { headers: { ...json, Authorization: `Bearer ${t}`,
    'Idempotency-Key': `chaos-${Date.now()}-${Math.random().toString(36).slice(2)}` } };
}

export function setup() {
  const users = [];
  for (let i = 0; i < USERS; i++) {
    const email = `chaos-${Date.now()}-${i}@chaos.ledgerflow.io`;
    http.post(`${BASE}/api/v1/auth/register`, JSON.stringify({
      email, password: PASSWORD, fullName: `Chaos ${i}` }), { headers: json });
    const token = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
      email, password: PASSWORD }), { headers: json }).json('accessToken');
    const wallet = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
      type: 'USER_WALLET', currency: 'USD', name: 'chaos wallet' }), auth(token)).json('id');
    http.post(`${BASE}/api/v1/accounts/${wallet}/deposits`, JSON.stringify({
      amountMinorUnits: 10000000, currency: 'USD' }), idem(token));
    users.push({ token, wallet });
  }
  return { users };
}

export function transfers(data) {
  const from = data.users[__VU % USERS];
  const to = data.users[(__VU + 3) % USERS];
  const res = http.post(`${BASE}/api/v1/transfers`, JSON.stringify({
    sourceAccountId: from.wallet, destinationAccountId: to.wallet,
    amountMinorUnits: 7, currency: 'USD', description: 'chaos transfer',
  }), idem(from.token));
  check(res, { 'transfer completed or clean reject': (r) => r.status === 201 || r.status === 422 });
}

export function history(data) {
  const user = data.users[__VU % USERS];
  const res = http.get(`${BASE}/api/v1/accounts/${user.wallet}/transactions`, auth(user.token));
  check(res, { 'history 200': (r) => r.status === 200 });
}
