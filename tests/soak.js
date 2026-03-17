import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const allowedReqs = new Counter('allowed_reqs');
const blockedReqs = new Counter('blocked_reqs');
const latency = new Trend('req_latency');

// Soak Test — Memory Leak Detection
//
// Runs sustained load (2000 RPS for 3 minutes) with rotating keys to stress
// both JVM heap and Redis memory. After the test completes, use
// collect_metrics.sh to verify:
//   - JVM heap usage stabilizes (no unbounded growth)
//   - Redis memory stabilizes (keys expire via TTL)
//   - Redis key count stabilizes
//   - GC pauses don't grow over time
//   - Latency doesn't degrade over the run
//
// Uses rotating keys (10,000 unique users) to create realistic keyspace churn.
export const options = {
  scenarios: {
    sustained_load: {
      executor: 'constant-arrival-rate',
      rate: 2000,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 200,
      maxVUs: 500,
    },
  },
  thresholds: {
    // Latency must not degrade over the run
    'req_latency': ['p(95)<20', 'p(99)<80'],
    // 429s are expected (rate-limited), not actual failures
    // Error detection is via the check below
  },
};

let counter = 0;
const KEY_POOL_SIZE = 10000;

export default function () {
  // Rotate through a pool of keys to simulate realistic traffic patterns
  const userId = `soak_user_${(counter++) % KEY_POOL_SIZE}`;
  const res = http.get(`http://localhost:8080/limit-token-bucket?user=${userId}`);

  latency.add(res.timings.duration);

  if (res.status === 200) allowedReqs.add(1);
  if (res.status === 429) blockedReqs.add(1);

  check(res, {
    'got valid response': (r) => r.status === 200 || r.status === 429,
  });
}
