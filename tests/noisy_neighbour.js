import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const userBLatency = new Trend('user_b_latency');
const userBAllowed = new Counter('user_b_allowed');

export const options = {
  scenarios: {
    malicious_actor: {
      executor: 'constant-arrival-rate',
      rate: 3000, // User A spams 3,000 RPS
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 300,
      exec: 'userA', // Points to the userA function below
    },
    legitimate_user: {
      executor: 'constant-arrival-rate',
      rate: 5, // User B sends a gentle 5 RPS
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 10,
      exec: 'userB', // Points to the userB function below
    },
  },
  thresholds: {
    // User B should never be rate limited, and their latency must not degrade
    // just because User A is abusing the system.
    'user_b_allowed': ['count==150'], // 5 RPS * 30 seconds = 150
    'user_b_latency': ['p(95)<15'],   // User B's p95 latency must stay under 15ms
  },
};

export function userA() {
  const res = http.get('http://localhost:8080/limit-token-bucket?user=malicious_user_A');
}

export function userB() {
  const res = http.get('http://localhost:8080/limit-token-bucket?user=legit_user_B');

  userBLatency.add(res.timings.duration);
  if (res.status === 200) userBAllowed.add(1);

  check(res, {
    'User B gets 200 OK': (r) => r.status === 200,
  });
}