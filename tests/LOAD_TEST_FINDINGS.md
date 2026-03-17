# Load Test Findings & Threshold Calibration

Benchmarked on Docker Compose stack (Spring Boot 3.4.2, JDK 21, Redis 8.6.0-alpine).
Environment: Docker for Mac, 4 CPU / 3GB memory limit on the app container.

## Infrastructure Profile

| Component | Config |
|---|---|
| Tomcat max threads | 200 |
| Tomcat max connections | 8192 |
| JVM GC | G1 (default) |
| JVM heap | -Xms512m -Xmx2g |
| Redis | Single instance, alpine, 1 CPU / 256MB |

## Baseline Capacity Test (`/ping`, no rate limiting)

Step-load test: 500 → 2k → 5k → 10k → 15k → 20k RPS over 95 seconds.

### Client-Side (k6)

| Metric | Value |
|---|---|
| Total requests | 868,137 |
| Avg sustained RPS | 9,138 |
| p50 latency | 0.28ms |
| p90 latency | 3.04ms |
| p95 latency | 15.52ms |
| Max latency | 330.43ms |
| Error rate | 0.00% |
| Dropped iterations | 14,362 (VU ceiling hit at ~18.5k target RPS) |

### Server-Side (Prometheus histogram)

| Bucket | Cumulative % |
|---|---|
| < 1ms | 99.91% |
| < 2ms | 99.96% |
| < 5ms | 99.98% |
| < 10ms | 99.99% |
| < 17ms | 100.00% |
| < 67ms | 100.00% |
| < 134ms | 100.00% (1 outlier) |

Server-side max recorded: 63.74ms.
The gap between server-side p99 (<1ms) and client-side p95 (15ms) is entirely Docker networking overhead under load.

### Resource Utilization at Each Step

| Target RPS | CPU | Connections | Observation |
|---|---|---|---|
| 500-2000 | 4-6% | 202 | Idle |
| 2000-5000 | 6-10% | 202 | Comfortable |
| 5000-10000 | 10-19% | 202-214 | Healthy |
| 10000-15000 | 25-50% | 223-609 | Inflection point: connections and CPU spike non-linearly |
| 15000-20000 | 37-45% | 925-977 | Saturated: hit 1000 VU cap, 14k dropped iterations |

### GC Impact

- G1 Evacuation Pause max: 19ms
- GC overhead: negligible at all load levels
- GC pauses are the primary contributor to server-side tail latency (p99.9+)

## Derived Thresholds

### Sustainable capacity: ~12,000-13,000 RPS

This is the last load level before connections and CPU spike non-linearly.

### Recommended test target: 10,000 RPS

80% of sustainable capacity, with headroom for variance between runs.

### Baseline thresholds (Docker environment)

```
p(95) < 20ms    (measured: 15.5ms, gives ~30% headroom)
p(99) < 80ms    (well under the 330ms max, accounts for GC + Docker spikes)
error rate < 0.1%
```

