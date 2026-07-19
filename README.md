# Guardian

Distributed rate-limiting library for Spring Boot. Redis-backed Lua scripts for atomic evaluation, AOP annotations for non-intrusive enforcement, dynamic configuration sync at runtime.


## Table of Contents

<<<<<<< Updated upstream
- [How It Works](#how-it-works)
- [Algorithms](#algorithms)
- [Configuration](#configuration)
- [Usage](#usage)
- [Performance](#performance)
- [Getting Started](#getting-started)
- [Load Tests](#load-tests)
- [Extensibility](#extensibility)
- [Project Structure](#project-structure)
- [Observability](#observability)

## How It Works

1. Annotate a controller method with `@GuardianRateLimit`
2. AOP aspect intercepts the call, resolves the key via SpEL
3. Redis Lua script atomically evaluates the rate limit
4. Request is allowed or rejected with HTTP 429
=======
- [Architecture & Key Design Choices](#️-architecture--key-design-choices)
    - [Concurrency & Atomicity (The "Thundering Herd" Problem)](#1-concurrency--atomicity-the-thundering-herd-problem)
    - [Annotation-Driven AOP & SpEL Context](#2-annotation-driven-aop--spel-context)
    - [Real-Time Dynamic Configuration](#3-real-time-dynamic-configuration)
    - [Resiliency & Failure Modes](#4-resiliency--failure-modes)
    - [Deep Observability](#5-deep-observability)
- [Algorithms Implemented](#️-algorithms-implemented)
    - [Token Bucket](#1-token-bucket)
    - [Sliding Window Counter](#2-sliding-window-counter)
- [Getting Started](#-getting-started)
    - [Prerequisites](#prerequisites)
    - [Running the Full Stack](#running-the-full-stack)
- [Usage Guide](#-usage-guide)
    - [Protecting an Endpoint](#1-protecting-an-endpoint)
    - [Baseline Configuration (YAML)](#2-baseline-configuration-yaml)
    - [Exception Handling](#3-exception-handling)
- [Testing & Validation](#-testing--validation)
    - [Unit & Integration Tests](#unit--integration-tests)
    - [Load Testing (k6)](#load-testing-k6)
- [Project Structure](#-project-structure)
- [Pluggable Architecture & Extensibility (Open-Closed Principle)](#6-pluggable-architecture--extensibility-open-closed-principle)
    - [Bring Your Own Algorithm (BYOA)](#bring-your-own-algorithm-byoa)
    - [Bring Your Own Storage (BYOS)](#bring-your-own-storage-byos)
    - [Dynamic Routing](#dynamic-routing)
  
---

# Architecture & Key Design Choices

Guardian is built with a focus on high throughput, zero race conditions, and graceful degradation.

## 1. Concurrency & Atomicity (The "Thundering Herd" Problem)

### Redis Lua Scripts (Primary)

Rate-limiting logic (Token Bucket and Sliding Window) is pushed directly to Redis via embedded Lua scripts. This ensures **100% atomicity** during high-concurrency bursts without the overhead of distributed locks or optimistic locking loops.

### Data Structures

The Token Bucket uses a Redis **HASH** to store available tokens and the last refill timestamp efficiently.

### Fallback Storage Mechanisms

The project includes an educational implementation of:

- Redis `WATCH/MULTI/EXEC` transactional store
- `ConcurrentHashMap` based in-memory store for non-distributed testing

---

## 2. Annotation-Driven AOP & SpEL Context

Rate limits are enforced non-intrusively via the `@GuardianRateLimit` annotation.

### Spring Expression Language (SpEL)

The annotation supports SpEL, allowing dynamic resolution of rate-limit keys directly from:

- method parameters

Examples:

```
#user.id
#request.getRemoteAddr()
```

---

## 3. Real-Time Dynamic Configuration

Rate limits often need to be adjusted during live incidents (for example a sudden traffic spike).

Guardian uses a **GuardianConfigScheduler** that polls Redis (`guardian:config:*`) for configuration updates.

Behavior:

1. If a valid JSON configuration is found  
   → it atomically swaps the configuration reference in memory without requiring an application restart.

2. If the Redis configuration is malformed or missing  
   → it safely falls back to the baseline YAML configuration.

---

## 4. Resiliency & Failure Modes

### Fail-Open vs Fail-Closed

If the Redis cluster becomes unavailable or a timeout occurs, Guardian intercepts the exception and evaluates the configured failure mode.

**OPEN**

- Swallows the exception
- Allows the request to pass
- Prioritizes availability

**CLOSED**

- Blocks the request
- Prioritizes strict quota enforcement

---

## 5. Observability

Integrated with **Micrometer**, Guardian emits metrics for every rate-limit evaluation.

Metrics are categorized by:

- algorithm
- plan
- quota
- outcome (allowed, blocked, error)

| Metric Name | Type | Description |
| :--- | :--- | :--- |
| `guardian.ratelimit.logic` | Timer | Latency of the rate-limiting decision, tagged by `algorithm`, `plan`, `quota`, `result`. |
| `guardian.ratelimit.fallback` | Counter | Incremented when a failure mode (OPEN/CLOSED) is triggered, tagged by `plan`, `mode`. |
| `guardian.config.reload` | Counter | Tracks success/failure of dynamic configuration updates, tagged by `provider`, `status`. |
| `guardian.config.last_updated_timestamp` | Gauge | Epoch timestamp of the last successful config synchronization. |

The project includes a fully configured `docker-compose.yml` stack containing:

- **Prometheus** for scraping metrics
- **Grafana** for visualization
- **cAdvisor** for container-level resource monitoring
![img.png](assets/img.png)
---

# Algorithms Implemented

## 1. Token Bucket

A highly optimized algorithm suitable for general API rate limiting and allowing controlled bursts.

**Parameters**

- `bucketCapacity` (maximum burst)
- `refillRate` (tokens added per second)

**Storage**

Redis Hash

```
{ 
  t: current_tokens,
  r: last_refill_timestamp 
}
```

---

## 2. Sliding Window Counter

Provides smoother traffic distribution than fixed window algorithms by calculating a weighted estimate of traffic based on previous and current window limits.

**Parameters**

- `requestLimit` (maximum requests)
- `windowSizeInSeconds` (rolling time frame)

**Storage**

Redis keys for current and previous window boundaries with TTLs.

---

# Getting Started

## Prerequisites

- Java 21
- Docker
- Docker Compose

---

## Running the Full Stack

The project includes a **guardian-test-server** that implements the library.

Spin up the application stack with Docker Compose:

```bash
docker-compose up --build -d
```

Services:

- App: http://localhost:8080
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000
    - User: `admin`
    - Pass: `admin`
- cAdvisor: http://localhost:8081

---

# Usage Guide

## 1. Protecting an Endpoint

Apply the `@GuardianRateLimit` annotation to any Spring-managed bean method.
>>>>>>> Stashed changes

```java
@GetMapping("/api/v1/payments")
@GuardianRateLimit(key = "#userId", plan = "pro_plan", quota = "read_limit")
public ResponseEntity<String> getPayments(@RequestParam String userId) {
    return ResponseEntity.ok("Success");
}
```

## Algorithms

### Token Bucket

Allows controlled bursts up to `bucketCapacity`, then refills at `refillRate` tokens/second. Stored as a Redis HASH (`t: tokens`, `r: last_refill_timestamp`).

### Sliding Window Counter

Weighted estimate across previous and current time windows. Smoother than fixed windows. Parameters: `requestLimit` and `windowSizeInSeconds`.

## Configuration

```yaml
guardian:
  dynamic-config:
    refresh-rate: 60000       # polls Redis for config changes (ms)
  token-bucket:
    enabled: true
    failure-mode: open        # open = allow on Redis failure, closed = block
    plans:
      pro_plan:
        read_limit:
          bucketCapacity: 100
          refillRate: 50
  sliding-window-counter:
    enabled: true
    failure-mode: closed
    plans:
      strict_plan:
        default:
          requestLimit: 5
          windowSizeInSeconds: 60
```

Algorithms only load if `enabled: true`. The aspect itself only loads if at least one `RateLimiter` bean exists.

### Dynamic Configuration

`GuardianConfigScheduler` polls Redis keys (`guardian:config:*`) on a fixed interval. Valid JSON found in Redis atomically replaces the in-memory config. Malformed or missing keys fall back to the YAML baseline.

### Failure Modes

| Mode | On Redis failure | Use when |
|---|---|---|
| `open` | Allow the request | Availability matters most |
| `closed` | Block the request | Strict quota enforcement |

## Usage

```java
// Token Bucket (default algorithm)
@GuardianRateLimit(key = "#userId", plan = "pro_plan", quota = "read_limit")

// Sliding Window
@GuardianRateLimit(algorithm = "slidingWindowCounterRateLimiter", key = "#req.accountId", plan = "strict_plan")

// SpEL expressions for keys
@GuardianRateLimit(key = "#request.getRemoteAddr()")
```

When a limit is breached, `RateLimitExceededException` is thrown. Handle it with `@ControllerAdvice` to return HTTP 429.

## Performance

Benchmarked on Docker for Mac (4 CPU / 3GB app container, single Redis). Full data: [`tests/LOAD_TEST_FINDINGS.md`](tests/LOAD_TEST_FINDINGS.md).

**Rate limiter overhead** (AOP + SpEL + Redis Lua round-trip):

| p50 | p95 | p99 |
|---|---|---|
| ~520us | ~980us | ~5ms |

**Throughput:** ~12,000-13,000 sustainable RPS, 0% error rate at 10k RPS.

**Atomicity:** 500 concurrent VUs on one key — exactly 5 allowed, 495 blocked. Zero leaks.

**Token bucket accuracy:** 1,598 allowed over 30s at 200 RPS (expected ~1,550). Within 3% of theoretical.

**Memory stability:** 3-minute soak at 5,000 RPS. Redis grew 227 KB. JVM heap stable. p95 latency held at 2ms throughout.

### Soak Test Panels (5,000 RPS, 3 minutes)

Sustained throughput held at 5K req/s with no degradation:

![HTTP RPS](assets/soak_http_rps.png)

Rate limit decisions — allowed vs blocked under sustained load:

![Rate Limit Decisions](assets/soak_ratelimit_decisions.png)

Rate limiter logic overhead — p50 stays under 1ms, p99 spikes correlate with GC pauses:

![Rate Limit Logic Duration](assets/soak_ratelimit_logic_duration.png)

Redis memory and key count grow proportionally and plateau — no leak:

![Redis Memory & Keys](assets/soak_redis_memory.png)

## Getting Started

**Prerequisites:** Java 21, Docker, Docker Compose

```bash
# Build
./gradlew clean build -x test

# Run tests (requires Docker for Testcontainers)
./gradlew clean test

# Run the full stack
docker-compose up --build -d
```

| Service | URL |
|---|---|
| App | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |
| cAdvisor | http://localhost:8082 |

## Load Tests

k6 test suite in `tests/`. Run individually or with `collect_metrics.sh` for Prometheus metrics + Grafana panel snapshots.

<<<<<<< Updated upstream
| Test | What it proves |
|---|---|
| `baseline.js` | Throughput ceiling (step-load to 20k RPS) |
| `thundering_herd.js` | Lua atomicity under 500 concurrent VUs |
| `noisy_neighbour.js` | Per-key isolation (malicious user can't affect others) |
| `high_cardinality.js` | Redis latency stability as key count grows to 90k+ |
| `token_bucket_accuracy.js` | Refill rate correctness (measured vs mathematical) |
| `soak.js` | Memory leak detection (JVM + Redis, 3 minutes) |

```bash
# Run with full metrics collection and Grafana snapshots
bash tests/collect_metrics.sh thundering_herd
bash tests/collect_metrics.sh soak
```

## Extensibility
=======
```
tests/
├── baseline.js          # Raw throughput ceiling (unprotected endpoint)
├── thundering_herd.js   # Atomicity proof under extreme concurrency on one key
├── noisy_neighbour.js   # Isolation between a spamming key and a legitimate one
└── high_cardinality.js  # Planned: many distinct keys under load. Not yet implemented.
```

Run tests:

```bash
k6 run tests/baseline.js
k6 run -e SCENARIO=hot_key tests/thundering_herd.js
k6 run tests/noisy_neighbour.js
```

### Results

All runs below against the full `docker-compose` stack on local hardware. Not a substitute for testing against your own infra.

**Guardian decision-logic overhead** — `guardian.ratelimit.logic` (Prometheus, pure Java + Redis Lua round trip, excludes HTTP/Tomcat):

| Metric | Value |
| :--- | :--- |
| p95 | 0.98 ms |
| p99 | 17.1 ms (tail from GC / connection-pool contention under sustained load) |
| avg | 0.67 ms |

**Full HTTP path** (`/limit-token-bucket`, AOP + SpEL + Guardian + Redis, k6-measured):

| Scenario | Throughput | p95 | Failures |
| :--- | :--- | :--- | :--- |
| Unprotected baseline (`/ping`) | 11.8k req/s (target 15k — VU pool capped the last stage, so 15k isn't a proven ceiling) | 722 µs | 0% |
| Guardian-protected, high-cardinality keys | 2,428 req/s sustained | 1.7 ms | 0% |

**Atomicity — `thundering_herd.js`**: 8,191 concurrent requests against a single sliding-window key (`strict_plan`, limit 5/window). Result: **exactly 5 allowed**, the rest blocked — atomicity holds past 8k concurrent on one key. (1,809 of 10,000 VUs never completed within the 5s window — Tomcat thread pool saturation, not a rate-limiter issue.)

**Isolation — `noisy_neighbour.js`**: a spamming key at 3,000 req/s (98% correctly blocked) alongside a legitimate key at 5 req/s. The legitimate key got **exactly 150/150** allowed requests, p95 latency 1.28 ms — no cross-key latency bleed-through.

**Soak (4 min, 800 req/s, high-cardinality keys, 192,001 requests, 0% failures)**: G1 Old Gen heap 204.061 MB → 204.312 MB (+0.25 MB, within noise), 101 minor GCs, 0 full GCs. Redis grew from 85,003 → 277,004 keys — each pinned at the fixed 3600s TTL in `token_bucket_hash.lua` regardless of actual traffic. Four minutes is not a real soak; treat this as directional, not a leak-free guarantee.

**Fail-open / fail-closed**: not exercised in any load run above — Redis stayed healthy throughout. That path is verified only by mocked unit tests (`TokenBucketRateLimiterResiliencyTest`), not under live load.

---
>>>>>>> Stashed changes

Core interfaces follow the Strategy Pattern. Add custom algorithms or storage backends without modifying the library.

**Custom algorithm:**

```java
@Component("leakyBucketRateLimiter")
public class LeakyBucketRateLimiter implements RateLimiter {
    @Override
    public boolean allow(RateLimitRequest request) { /* ... */ }
}
```

**Custom storage:** Implement `TokenBucketStore` or `SlidingWindowStore` for any backend (Cassandra, PostgreSQL, etc).

**Use it:**

```java
@GuardianRateLimit(algorithm = "leakyBucketRateLimiter")
```

The AOP infrastructure resolves the bean by name at runtime.

## Project Structure

```
guardian-core/                 Core library (published as plain JAR)
├── annotation/                @GuardianRateLimit
├── aspect/                    AOP interceptor, SpEL evaluation
├── ratelimiter/impl/
│   ├── tokenbucket/           Token Bucket algorithm + stores
│   └── slidingwindowcounter/  Sliding Window algorithm + stores
├── sync/                      Dynamic config scheduler
└── resources/scripts/         Redis Lua scripts

guardian-test-server/          Sample app with test endpoints
monitoring/                    Prometheus + Grafana provisioning
tests/                         k6 load tests, metrics collection, snapshots
```

## Observability

Guardian emits Micrometer metrics for every rate-limit evaluation, tagged by algorithm, plan, quota, and result (allowed/blocked/error).

The `docker-compose.yml` stack includes Prometheus, Grafana (with provisioned dashboards), cAdvisor, Redis exporter, and a Grafana image renderer for automated panel snapshots.
