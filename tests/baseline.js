import http from 'k6/http';
import { check } from 'k6';


export const options = {
  scenarios: {
    baseline_throughput: {
      executor: 'ramping-arrival-rate',
      startRate: 1000,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
      stages: [
        { target: 15000, duration: '20s' }, // Ramp up
        { target: 15000, duration: '30s' }, // Hold High Load
        { target: 0, duration: '5s' },    // Ramp down
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<5'], // 99% of requests must complete below 5ms
  },
};

export default function () {
  const res = http.get('http://localhost:8080/ping');
  check(res, {
    'is status 200': (r) => r.status === 200,
  });
}