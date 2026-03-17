# Guardian: Distributed Rate Limiting Library

Guardian is an educational project built to learn how to implement a highly concurrent, resilient, and dynamically configurable distributed rate-limiting library for Spring Boot applications. Designed as a drop-in core library (`guardian-core`) with an accompanying test server, it provides robust API protection using Redis-backed atomicity, Aspect-Oriented Programming (AOP), and real-time configuration sync.

## Table of Contents

- [Architecture & Key Design Choices](#architecture--key-design-choices)
- [Algorithms Implemented](#algorithms-implemented)
- [Getting Started](#getting-started)
- [Usage Guide](#usage-guide)
- [Testing & Validation](#testing--validation)
- [Project Structure](#project-structure)
- [Pluggable Architecture & Extensibility](#pluggable-architecture--extensibility)

## Architecture & Key Design Choices

Guardian is built with a focus on high throughput, zero race conditions, and graceful degradation.

### Concurrency & Atomicity (The "Thundering Herd" Problem)

#### Redis Lua Scripts (Primary)

Rate-limiting logic (Token Bucket and Sliding Window) is pushed directly to Redis via embedded Lua scripts. This ensures **100% atomicity** during high-concurrency bursts without the overhead of distributed locks or optimistic locking loops.

#### Data Structures

The Token Bucket uses a Redis **HASH** to store available tokens and the last refill timestamp efficiently.

#### Fallback Storage Mechanisms

The project includes an educational implementation of:

- Redis `WATCH/MULTI/EXEC` transactional store
- `ConcurrentHashMap` based in-memory store for non-distributed testing

### Annotation-Driven AOP & SpEL Context

Rate limits are enforced non-intrusively via the `@GuardianRateLimit` annotation.

The annotation supports SpEL, allowing dynamic resolution of rate-limit keys directly from method parameters:

```
#user.id
#request.getRemoteAddr()
```

### Real-Time Dynamic Configuration

Rate limits often need to be adjusted during live incidents (for example a sudden traffic spike).

Guardian uses a **GuardianConfigScheduler** that polls Redis (`guardian:config:*`) for configuration updates.

Behavior:

1. If a valid JSON configuration is found
   → it atomically swaps the configuration reference in memory without requiring an application restart.

2. If the Redis configuration is malformed or missing
   → it safely falls back to the baseline YAML configuration.

### Resiliency & Failure Modes

If the Redis cluster becomes unavailable or a timeout occurs, Guardian intercepts the exception and evaluates the configured failure mode.

| Mode | Behavior | Priority |
|---|---|---|
| **OPEN** | Swallows the exception, allows the request to pass | Availability |
| **CLOSED** | Blocks the request | Strict quota enforcement |

### Observability

Integrated with **Micrometer**, Guardian emits metrics for every rate-limit evaluation.

Metrics are categorized by:

- algorithm
- plan
- quota
- outcome (allowed, blocked, error)

The project includes a fully configured `docker-compose.yml` stack containing:

- **Prometheus** for scraping metrics
- **Grafana** for visualization
- **cAdvisor** for container-level resource monitoring

![Grafana Dashboard](assets/img.png)

## Algorithms Implemented

### Token Bucket

A highly optimized algorithm suitable for general API rate limiting and allowing controlled bursts.

**Parameters**

- `bucketCapacity` (maximum burst)
- `refillRate` (tokens added per second)

**Storage** — Redis Hash

```
{
  t: current_tokens,
  r: last_refill_timestamp
}
```

### Sliding Window Counter

Provides smoother traffic distribution than fixed window algorithms by calculating a weighted estimate of traffic based on previous and current window limits.

**Parameters**

- `requestLimit` (maximum requests)
- `windowSizeInSeconds` (rolling time frame)

**Storage** — Redis keys for current and previous window boundaries with TTLs.

## Getting Started

### Prerequisites

- Java 21
- Docker
- Docker Compose

### Running the Full Stack

The project includes a **guardian-test-server** that implements the library.

Spin up the application stack with Docker Compose:

```bash
docker-compose up --build -d
```

Services:

| Service | URL | Notes |
|---|---|---|
| App | http://localhost:8080 | |
| Prometheus | http://localhost:9090 | |
| Grafana | http://localhost:3000 | User: `admin` / Pass: `admin` |
| cAdvisor | http://localhost:8081 | |

## Usage Guide

### Protecting an Endpoint

Apply the `@GuardianRateLimit` annotation to any Spring-managed bean method.

```java
@RestController
public class PaymentController {

    // Token Bucket (Default)
    @GetMapping("/api/v1/payments")
    @GuardianRateLimit(key = "#userId", plan = "pro_plan", quota = "read_limit")
    public ResponseEntity<String> getPayments(@RequestParam String userId) {
        return ResponseEntity.ok("Success");
    }

    // Sliding Window
    @PostMapping("/api/v1/payments")
    @GuardianRateLimit(
        algorithm = "slidingWindowCounterRateLimiter",
        key = "#paymentRequest.accountId",
        plan = "strict_plan"
    )
    public ResponseEntity<String> processPayment(@RequestBody PaymentRequest paymentRequest) {
        return ResponseEntity.ok("Processed");
    }
}
```

### Baseline Configuration (YAML)

Define rate-limit plans in `application.yml`.

```yaml
guardian:
  token-bucket:
    enabled: true
    failure-mode: open # 'open' or 'closed'
    plans:
      pro_plan:
        read_limit:
          bucketCapacity: 100
          refillRate: 50

  sliding-window-counter:
    enabled: true
    plans:
      strict_plan:
        default:
          requestLimit: 5
          windowSizeInSeconds: 60
```

### Exception Handling

When a limit is breached, a `RateLimitExceededException` is thrown.

The test server translates this exception into an HTTP response using `@ControllerAdvice`.

```
HTTP 429 Too Many Requests
```

## Testing & Validation

### Unit & Integration Tests

The `guardian-core` module is tested using **Testcontainers**, which spins up ephemeral Redis instances.

This ensures Lua scripts and concurrent logic are tested against real infrastructure.

```bash
./gradlew clean test
```

### Load Testing (k6)

A k6 test suite is included in the `tests/` directory to validate correctness and performance under load. See [`tests/LOAD_TEST_FINDINGS.md`](tests/LOAD_TEST_FINDINGS.md) for detailed benchmark results and threshold calibration.

| Test | Purpose |
|---|---|
| `baseline.js` | Throughput and latency regression guard (step-load up to 20k RPS) |
| `thundering_herd.js` | Validates Lua atomicity — exactly N requests allowed under concurrent contention |
| `noisy_neighbour.js` | Confirms per-key isolation — a malicious user cannot degrade a legitimate user |
| `high_cardinality.js` | Ensures Redis performance stability as unique key count grows |

```bash
k6 run tests/baseline.js
k6 run tests/thundering_herd.js
k6 run tests/noisy_neighbour.js
k6 run tests/high_cardinality.js
```

## Project Structure

```
guardian-core/              Core rate-limiting library
├── annotation/             @GuardianRateLimit annotation
├── aspect/                 AOP interceptor, SpEL evaluation
├── ratelimiter/            Algorithm implementations (Token Bucket, Sliding Window)
│   └── impl/
│       ├── tokenbucket/    Token Bucket limiter, config, store interfaces & impls
│       └── slidingwindowcounter/  Sliding Window limiter, config, store interfaces & impls
├── storage/                Storage backends (Redis Lua, Transactional, In-memory)
├── sync/                   Dynamic configuration scheduler and reloader interface
└── resources/scripts/      Redis Lua scripts

guardian-test-server/       Sample Spring Boot app demonstrating the library
monitoring/                 Prometheus configuration
tests/                      k6 load test scripts and findings
```

## Pluggable Architecture & Extensibility

Guardian follows the **Strategy Pattern** and the Open-Closed Principle. Core behaviors are decoupled through strict interfaces:

- `RateLimiter`
- `TokenBucketStore`
- `SlidingWindowStore`

### Bring Your Own Algorithm (BYOA)

Implement a custom algorithm (e.g. Leaky Bucket) by implementing the `RateLimiter` interface and registering it as a Spring bean:

```java
@Component("myCustomLimiter")
public class LeakyBucketRateLimiter implements RateLimiter {
    @Override
    public boolean allow(RateLimitRequest request) { /* ... */ }
}
```

### Bring Your Own Storage (BYOS)

Replace the persistence layer with Cassandra, PostgreSQL, or any other backend by implementing the appropriate **Store interface** (`TokenBucketStore` or `SlidingWindowStore`).

### Dynamic Routing

Custom implementations integrate with the existing AOP infrastructure automatically:

```java
@GuardianRateLimit(algorithm = "myCustomLimiter")
```

At runtime, Guardian dynamically resolves the bean and routes requests to the custom limiter without modifying the core library.
