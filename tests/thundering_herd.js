import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const allowedReqs = new Counter('allowed_reqs');
const blockedReqs = new Counter('blocked_reqs');

// Correctness test: validates Lua script atomicity under high concurrency.
// 500 VUs hit the exact same key simultaneously.
// The sliding window strict_plan allows 5 requests per 60s window.
// Expected: exactly 5 allowed, 495 blocked, zero leaks.
export const options = {
  scenarios: {
    spike: {
      executor: 'per-vu-iterations',
      vus: 500,
      iterations: 1,
      maxDuration: '10s',
    },
  },
  thresholds: {
    'allowed_reqs': ['count==5'],
    'blocked_reqs': ['count==495'],
  },
};

export default function () {
  const url = 'http://localhost:8080/limit-sliding-window-counter?user=herd_target';
  const res = http.get(url);

  if (res.status === 200) allowedReqs.add(1);
  if (res.status === 429) blockedReqs.add(1);

  check(res, {
    'handled cleanly': (r) => r.status === 200 || r.status === 429,
  });
}
