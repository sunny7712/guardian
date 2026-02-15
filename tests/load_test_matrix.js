import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

// --- CONFIGURATION ---
const BASE_URL = 'http://localhost:8080/limit';

// SCENARIO SELECTION via Environment Variable
// Usage: k6 run -e SCENARIO=hot_key tests/load_test_matrix.js
const SCENARIO = __ENV.SCENARIO || 'baseline';

export const options = {
  scenarios: {
    // We define one scenario dynamically based on the ENV variable
    attack: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { target: 100, duration: '10s' }, // Ramp up
        { target: 500, duration: '30s' }, // Hold High Load
        { target: 0, duration: '5s' },    // Ramp down
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<5'], // 99% of requests must complete below 5ms
  },
};

export default function () {
  let user;
  let plan = 'load_test_plan';
  let quota = 'default';

  // --- LOGIC PER SCENARIO ---
  if (SCENARIO === 'hot_key') {
    // 1. Hot Key: Everyone hits the same user
    user = 'user_hot_1';

  } else if (SCENARIO === 'distributed') {
    // 2. Distributed: Random user every single request
    user = `user_${randomString(10)}`;

  } else if (SCENARIO === 'burst') {
    // 3. Burst: Toggle between two specific users to allow partial refills
    // (Simplistic burst implementation)
    user = (Math.random() > 0.5) ? 'user_A' : 'user_B';

  } else {
    // Baseline / Warmup
    user = 'user_baseline';
  }

  const url = `${BASE_URL}?user=${user}&plan=${plan}&quota=${quota}`;

  // Tag request with scenario for clearer filtering in K6 output
  const params = {
    tags: { scenario: SCENARIO },
  };

  const res = http.get(url, params);

  // Custom check: 429 is "Success" for a Rate Limiter, 200 is "Success".
  // 500 is the only real Failure.
  check(res, {
    'is handled (200 or 429)': (r) => r.status === 200 || r.status === 429,
  });
}