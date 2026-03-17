import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    baseline_throughput: {
      executor: 'ramping-arrival-rate',
      startRate: 500,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
      stages: [
        { target: 2000, duration: '10s' },   // Warmup
        { target: 5000, duration: '20s' },    // Step 1
        { target: 10000, duration: '20s' },   // Step 2 — target sustainable load
        { target: 15000, duration: '20s' },   // Step 3 — push toward ceiling
        { target: 20000, duration: '20s' },   // Step 4 — past ceiling, expect degradation
        { target: 0, duration: '5s' },        // Ramp down
      ],
    },
  },
  thresholds: {
    // Calibrated from measured data (see LOAD_TEST_FINDINGS.md)
    http_req_duration: ['p(95)<20', 'p(99)<80'],
    http_req_failed: ['rate<0.001'],
  },
};

export default function () {
  const res = http.get('http://localhost:8080/ping');
  check(res, {
    'is status 200': (r) => r.status === 200,
  });
}
