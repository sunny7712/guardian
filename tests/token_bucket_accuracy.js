import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const allowedReqs = new Counter('allowed_reqs');
const blockedReqs = new Counter('blocked_reqs');
const latency = new Trend('req_latency');

// Token Bucket Accuracy Test
//
// Proves the token bucket algorithm allows the mathematically correct number
// of requests over time.
//
// Config (load_test_plan): bucketCapacity=100, refillRate=50 tokens/s
//
// Sends a steady 200 RPS for 30s against a single key (saturates the bucket).
// After the initial burst of 100 (bucket capacity), the limiter should allow
// ~50/s (refill rate).
//
// Expected allowed over 30s: 100 (initial burst) + 50 * 29 ≈ 1550 (±margin)
// Threshold: allowed_reqs count between 1450 and 1650.
export const options = {
  scenarios: {
    steady_load: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: {
    // Token bucket should allow ~1550 requests (100 burst + 50/s * 29s)
    'allowed_reqs': ['count>=1450', 'count<=1650'],
    // Latency should remain stable
    'req_latency': ['p(95)<20'],
    // 429s are expected (rate-limited), not actual failures
    // Only check for non-200/429 responses via the check below
  },
};

export default function () {
  // All requests use the same key to test a single bucket's accuracy
  const res = http.get('http://localhost:8080/limit-token-bucket?user=accuracy_test_user');

  latency.add(res.timings.duration);

  if (res.status === 200) allowedReqs.add(1);
  if (res.status === 429) blockedReqs.add(1);

  check(res, {
    'got valid response': (r) => r.status === 200 || r.status === 429,
  });
}
