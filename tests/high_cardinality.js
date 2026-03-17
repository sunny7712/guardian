import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const latency = new Trend('req_latency');

// High cardinality test: validates that Redis performance does not degrade
// as the number of unique rate-limit keys grows.
// Each VU uses a unique user key, creating thousands of distinct Redis entries.
// The latency at the end of the test should remain comparable to the start.
export const options = {
  scenarios: {
    cardinality_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: [
        { target: 500, duration: '10s' },    // Warm up
        { target: 2000, duration: '20s' },   // Ramp — keys accumulate
        { target: 2000, duration: '30s' },   // Hold — observe latency stability
        { target: 0, duration: '5s' },       // Ramp down
      ],
    },
  },
  thresholds: {
    // Latency must remain stable even with many unique keys in Redis
    'req_latency': ['p(95)<15'],
    http_req_failed: ['rate<0.01'],
  },
};

let counter = 0;

export default function () {
  // Each request uses a unique key to grow the Redis keyspace
  const uniqueUser = `user_${__VU}_${counter++}`;
  const res = http.get(`http://localhost:8080/limit-token-bucket?user=${uniqueUser}`);

  latency.add(res.timings.duration);

  check(res, {
    'got valid response': (r) => r.status === 200 || r.status === 429,
  });
}
