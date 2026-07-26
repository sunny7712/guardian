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

## Token Bucket Accuracy Test

Validates that the token bucket algorithm allows the mathematically correct number of requests over time.

### Setup

- Config: `load_test_plan` → `bucketCapacity: 100`, `refillRate: 50` tokens/s
- Steady 200 RPS for 30s against a single key (saturates the bucket)
- Expected: 100 (initial burst) + 50/s * 29s = ~1,550 allowed requests

### Results

| Metric | Expected | Measured |
|---|---|---|
| Total requests sent | 6,000 | 6,000 |
| Allowed requests | 1,450–1,650 | **1,598** |
| Blocked requests | ~4,400–4,550 | 4,402 |
| p95 latency | < 20ms | 2.28ms |
| Error rate (non-200/429) | 0.00% | 0.00% |

The measured allowed count of 1,598 is within 3% of the theoretical 1,550, confirming the Lua-based token bucket refill logic is accurate.

### Rate Limiter Logic Overhead (Server-Side)

| Percentile | Value |
|---|---|
| p50 | 509us |
| p95 | 967us |
| p99 | 1.27ms |

## Soak Test — Memory Leak Detection

Sustained load test designed to detect memory leaks in the JVM and Redis under realistic traffic patterns.

### Setup

- 5,000 RPS for 3 minutes with 10,000 rotating keys
- Monitors: JVM heap, Redis memory, Redis key count, GC pauses, latency degradation

### Results

| Metric | Pass Criteria | Measured |
|---|---|---|
| Total requests | — | 898,959 (5,000 RPS sustained) |
| JVM heap (start → end) | — | 333 MB → 271 MB |
| JVM heap delta | Stable | **-63 MB** (GC reclaimed; no growth) |
| Redis memory (start → end) | Stabilizes | 1.79 MB → 2.01 MB (+227 KB) |
| Redis key count | Stabilizes | 0 → 1,606 (10k pool, keys with TTL) |
| GC pause max | No growing trend | 28ms |
| p95 latency | < 20ms | **1.96ms** |
| p99 latency | < 80ms | **30.3ms** |
| Error rate (non-200/429) | 0.00% | **0.00%** |
| CPU peak | — | 57% |

### Findings

- **No memory leak detected.** JVM heap delta was negative (GC reclaimed more than was allocated). No upward trend.
- **Redis memory bounded.** Only 227 KB growth over 3 minutes at 5,000 RPS. Keys with TTL are working correctly.
- **Latency stable.** p95 remained under 2ms throughout the run with no degradation over time.
- **GC pauses acceptable.** Max 28ms G1 pause, no growing trend.
- **CPU headroom.** 57% peak leaves room for traffic spikes.

### Rate Limiter Logic Overhead (Server-Side)

| Percentile | Value |
|---|---|
| p50 | 519us |
| p95 | 986us |
| p99 | 7.08ms |

### Why 5,000 RPS (not 10,000)?

Baseline tests show ~12-13K sustainable RPS for `/ping` (no Redis). However, the rate limiter adds a Redis Lua round-trip per request. At 10K RPS, the rate limiter p95 degrades to ~13ms (vs ~1ms at 5K). This indicates Redis single-thread contention becomes the bottleneck. 5K RPS represents the load level where the rate limiter performs healthily with sub-2ms p95.

## Redis Metrics Summary

Captured via `redis-exporter` → Prometheus. These metrics are collected automatically by `collect_metrics.sh`.

| Metric | Source | Purpose |
|---|---|---|
| `redis_memory_used_bytes` | redis-exporter | Track Redis memory stability under load |
| `redis_db_keys{db="db0"}` | redis-exporter | Verify key count matches expectations and TTLs work |
| `redis_commands_duration_seconds` | redis-exporter | Monitor Redis command latency |

## Rate Limiter Logic Overhead

Server-side overhead of the Guardian rate-limit evaluation, measured via the `guardian_ratelimit_logic_seconds` Micrometer histogram.

This metric captures the full AOP → SpEL → Redis Lua round-trip time.

| Percentile | Measured (high cardinality, 2k RPS) | Measured (soak, 5k RPS) |
|---|---|---|
| p50 | 517us | 519us |
| p95 | 982us | 986us |
| p99 | 4.76ms | 7.08ms |

The full rate-limit evaluation (AOP interception → SpEL key resolution → Redis Lua round-trip) completes in **under 1ms at p95**. The p99 tail is dominated by occasional GC pauses and Docker networking variance.

## Regression Verification — TTL Fixes (2026-07-26)

Two bugs were found and fixed in the Redis Lua scripts:

- `token_bucket_hash.lua`: `EXPIRE key 3600` was a flat hardcoded TTL, unrelated to the plan's actual refill rate. Fixed to derive TTL from `bucket_capacity / (refill_rate * 1000)` (seconds to fully refill from empty), floored at 60s.
- `sliding_window_counter.lua`: `EXPIRE current_key, window_size_ms * 2` passed milliseconds to a seconds-based Redis call — a 60s window got a ~33h TTL instead of ~2min. Fixed to use `window_size_sec * 2`.

Full k6 suite re-run against a fresh Docker build (native Linux Docker, not the Docker-for-Mac rig used above — absolute latencies aren't directly comparable, this run is a correctness/regression check, not a re-benchmark) to confirm no regressions:

| Test | Result | Verdict |
|---|---|---|
| `baseline.js` | p95 282µs, p99 1.36ms, 0% errors, 882,289 reqs | PASS |
| `thundering_herd.js` | exactly 5 allowed, 495 blocked | PASS |
| `noisy_neighbour.js` | User B 151/150 allowed, p95 0.56ms, no bleed-through from 3,000 RPS attacker | PASS |
| `high_cardinality.js` | p95 0.62ms, 0% errors, 92,999 unique keys | PASS |
| `token_bucket_accuracy.js` | 1,599 allowed (expected ~1,550, ±100 margin) — matches prior 1,598 baseline | PASS |
| `soak.js` (3min, 5,000 RPS) | p95 505µs, p99 1.7ms, 0% errors, 900,001 reqs | PASS |

**TTL fix specifically verified:** post-soak, Redis sat at 879 keys / 1.74MB. 65s later (past the new TTL window) it dropped to **0 keys / 1.60MB** — back to the exact pre-test baseline. Previously, keys sat at a flat 3600s TTL regardless of actual traffic shape; now they expire on schedule instead of accumulating.

## Collecting Metrics

All tests can be run with full metrics collection and Grafana panel snapshots:

```bash
bash tests/collect_metrics.sh <test_name>
```

This script:
1. Captures pre-test JVM heap, Redis memory, Redis key count, and GC pause max
2. Runs the k6 test
3. Captures post-test metrics and computes deltas
4. Queries `guardian_ratelimit_logic_seconds` percentiles from Prometheus
5. Captures Grafana panel PNGs to `tests/snapshots/<test_name>/`

