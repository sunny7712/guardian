import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const allowedReqs = new Counter('allowed_reqs');
const blockedReqs = new Counter('blocked_reqs');

export const options = {
    scenarios: {
        spike: {
            executor: 'per-vu-iterations',
            vus: 10000,
            iterations: 1,
            maxDuration: '5s'
        },
    },
    thresholds: {
        'allowed_reqs': ['count==5'],
        'blocked_reqs': ['count==95']
    },
};

export default function () {
    // Everyone hits the exact same key at the exact same time. On strict plan it is 5 requests per minute
  const url = 'http://localhost:8080/limit-sliding-window-counter?user=herd_target';
  const res = http.get(url);

  if (res.status === 200) allowedReqs.add(1);
  if (res.status === 429) blockedReqs.add(1);

  check(res, {
    'handled cleanly': (r) => r.status === 200 || r.status === 429,
  });
}