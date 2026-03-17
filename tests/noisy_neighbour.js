import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const userBLatency = new Trend('user_b_latency');
const userBAllowed = new Counter('user_b_allowed');

// Isolation test: a malicious user flooding the system must not degrade
// a legitimate user's latency or cause them to be incorrectly rate-limited.
// User A: 3,000 RPS on one key (will be rate-limited after token bucket drains)
// User B: 5 RPS on a different key (should never be rate-limited)
export const options = {
  scenarios: {
    malicious_actor: {
      executor: 'constant-arrival-rate',
      rate: 3000,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 300,
      exec: 'userA',
    },
    legitimate_user: {
      executor: 'constant-arrival-rate',
      rate: 5,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 10,
      exec: 'userB',
    },
  },
  thresholds: {
    // User B must never be rate-limited (5 RPS * 30s = 150 requests)
    'user_b_allowed': ['count>=145'],     // allow small margin for timing
    'user_b_latency': ['p(95)<20'],       // p95 latency must stay under 20ms
  },
};

export function userA() {
  http.get('http://localhost:8080/limit-token-bucket?user=malicious_user_A');
}

export function userB() {
  const res = http.get('http://localhost:8080/limit-token-bucket?user=legit_user_B');

  userBLatency.add(res.timings.duration);
  if (res.status === 200) userBAllowed.add(1);

  check(res, {
    'User B gets 200 OK': (r) => r.status === 200,
  });
}
