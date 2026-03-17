#!/usr/bin/env bash
#
# collect_metrics.sh — Capture Prometheus metrics before/after a k6 test run,
# then snapshot Grafana panels as PNGs.
#
# Usage:
#   bash tests/collect_metrics.sh <test_name>
#
# Examples:
#   bash tests/collect_metrics.sh baseline
#   bash tests/collect_metrics.sh thundering_herd
#   bash tests/collect_metrics.sh soak
#
# Prerequisites:
#   - docker-compose stack running (app, redis, prometheus, grafana, renderer)
#   - k6 installed
#   - Grafana dashboard provisioned with a known UID

set -euo pipefail

PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
GRAFANA_USER="${GRAFANA_USER:-admin}"
GRAFANA_PASS="${GRAFANA_PASS:-admin}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SNAPSHOT_DIR="${SCRIPT_DIR}/snapshots"

# --- Argument parsing ---

TEST_NAME="${1:?Usage: $0 <test_name>}"
TEST_FILE="${SCRIPT_DIR}/${TEST_NAME}.js"

if [[ ! -f "$TEST_FILE" ]]; then
  echo "ERROR: Test file not found: $TEST_FILE"
  exit 1
fi

# --- Helpers ---

query_prometheus() {
  local query="$1"
  curl -s --fail-with-body "${PROMETHEUS_URL}/api/v1/query" \
    --data-urlencode "query=${query}" \
    | python3 -c "
import sys, json
data = json.load(sys.stdin)
results = data.get('data', {}).get('result', [])
if results:
    print(results[0]['value'][1])
else:
    print('N/A')
" 2>/dev/null || echo "N/A"
}

fmt_bytes() {
  local bytes="$1"
  if [[ "$bytes" == "N/A" ]]; then
    echo "N/A"
    return
  fi
  python3 -c "
b = float('$bytes')
for unit in ['B', 'KB', 'MB', 'GB']:
    if abs(b) < 1024.0:
        print(f'{b:.2f} {unit}')
        break
    b /= 1024.0
else:
    print(f'{b:.2f} TB')
" 2>/dev/null || echo "${bytes} B"
}

fmt_duration() {
  local seconds="$1"
  if [[ "$seconds" == "N/A" ]]; then
    echo "N/A"
    return
  fi
  python3 -c "
s = float('$seconds')
if s < 0.001:
    print(f'{s*1_000_000:.1f}us')
elif s < 1:
    print(f'{s*1000:.2f}ms')
else:
    print(f'{s:.3f}s')
" 2>/dev/null || echo "${seconds}s"
}

delta() {
  local start="$1" end="$2"
  if [[ "$start" == "N/A" || "$end" == "N/A" ]]; then
    echo "N/A"
    return
  fi
  python3 -c "print(float('$end') - float('$start'))" 2>/dev/null || echo "N/A"
}

# --- Capture metrics snapshot ---

capture_metrics() {
  local label="$1"
  echo "--- Capturing $label metrics ---"

  local jvm_heap redis_mem redis_keys gc_pause_max

  jvm_heap=$(query_prometheus 'sum(jvm_memory_used_bytes{area="heap",application="guardian-server"})')
  redis_mem=$(query_prometheus 'redis_memory_used_bytes')
  redis_keys=$(query_prometheus 'redis_db_keys{db="db0"}')
  gc_pause_max=$(query_prometheus 'max(jvm_gc_pause_seconds_max{application="guardian-server"})')

  echo "JVM_HEAP=${jvm_heap}"
  echo "REDIS_MEM=${redis_mem}"
  echo "REDIS_KEYS=${redis_keys}"
  echo "GC_PAUSE_MAX=${gc_pause_max}"

  # Export for later use
  eval "export ${label}_JVM_HEAP=${jvm_heap}"
  eval "export ${label}_REDIS_MEM=${redis_mem}"
  eval "export ${label}_REDIS_KEYS=${redis_keys}"
  eval "export ${label}_GC_PAUSE_MAX=${gc_pause_max}"
}

# --- Capture Grafana panel snapshots ---

capture_snapshots() {
  local test_name="$1"
  local snapshot_subdir="${SNAPSHOT_DIR}/${test_name}"
  mkdir -p "$snapshot_subdir"

  # Find the first dashboard UID
  local dashboard_uid
  dashboard_uid=$(curl -s -u "${GRAFANA_USER}:${GRAFANA_PASS}" \
    "${GRAFANA_URL}/api/search?type=dash-db&limit=1" \
    | python3 -c "import sys,json; ds=json.load(sys.stdin); print(ds[0]['uid'] if ds else '')" 2>/dev/null || true)

  if [[ -z "$dashboard_uid" ]]; then
    echo "WARN: No Grafana dashboard found — skipping panel snapshots."
    return 0
  fi

  local slug
  slug=$(curl -s -u "${GRAFANA_USER}:${GRAFANA_PASS}" \
    "${GRAFANA_URL}/api/dashboards/uid/${dashboard_uid}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['meta']['slug'])" 2>/dev/null || true)

  if [[ -z "$slug" ]]; then
    echo "WARN: Could not resolve dashboard slug — skipping panel snapshots."
    return 0
  fi

  # Panel IDs and names to capture (bash 3.2 compatible — no associative arrays)
  local panel_ids="1 2 3 4 5 6 7 8"
  local panel_names="http_rps http_latency_percentiles tomcat_threads jvm_heap_memory gc_pause_time guardian_ratelimit_logic_duration guardian_ratelimit_decisions redis_memory_and_keys"

  echo "Capturing Grafana panel snapshots for dashboard: ${dashboard_uid}/${slug}"

  local i=1
  for panel_name in $panel_names; do
    local output_file="${snapshot_subdir}/${panel_name}.png"
    local render_url="${GRAFANA_URL}/render/d-solo/${dashboard_uid}/${slug}?panelId=${i}&width=1000&height=500&from=now-5m&to=now"

    if curl -s -f -o "$output_file" -u "${GRAFANA_USER}:${GRAFANA_PASS}" "$render_url"; then
      echo "  Saved: ${output_file}"
    else
      echo "  WARN: Failed to render panel ${i} (${panel_name}) — renderer may not be running"
    fi
    i=$((i + 1))
  done
}

# --- Main ---

echo "============================================="
echo " Guardian Load Test: ${TEST_NAME}"
echo " $(date)"
echo "============================================="
echo ""

# 1. Capture pre-test metrics
capture_metrics "PRE"
echo ""

# 2. Run k6 test
echo "--- Running k6 test: ${TEST_FILE} ---"
echo ""
k6 run "$TEST_FILE" || true
K6_EXIT=${PIPESTATUS[0]:-$?}
echo ""

# 3. Brief pause for metrics to propagate to Prometheus
sleep 5

# 4. Capture post-test metrics
capture_metrics "POST"
echo ""

# 5. Compute deltas and print report
echo "============================================="
echo " Metrics Report: ${TEST_NAME}"
echo "============================================="
echo ""

JVM_DELTA=$(delta "$PRE_JVM_HEAP" "$POST_JVM_HEAP")
REDIS_MEM_DELTA=$(delta "$PRE_REDIS_MEM" "$POST_REDIS_MEM")
REDIS_KEYS_DELTA=$(delta "$PRE_REDIS_KEYS" "$POST_REDIS_KEYS")

echo "JVM Heap Used:"
echo "  Start:  $(fmt_bytes "$PRE_JVM_HEAP")"
echo "  End:    $(fmt_bytes "$POST_JVM_HEAP")"
echo "  Delta:  $(fmt_bytes "$JVM_DELTA")"
echo ""

echo "Redis Memory Used:"
echo "  Start:  $(fmt_bytes "$PRE_REDIS_MEM")"
echo "  End:    $(fmt_bytes "$POST_REDIS_MEM")"
echo "  Delta:  $(fmt_bytes "$REDIS_MEM_DELTA")"
echo ""

echo "Redis Key Count (db0):"
echo "  Start:  ${PRE_REDIS_KEYS}"
echo "  End:    ${POST_REDIS_KEYS}"
echo "  Delta:  ${REDIS_KEYS_DELTA}"
echo ""

echo "GC Pause Max:"
echo "  Start:  $(fmt_duration "$PRE_GC_PAUSE_MAX")"
echo "  End:    $(fmt_duration "$POST_GC_PAUSE_MAX")"
echo ""

# Guardian rate limit logic histogram (post-test snapshot)
echo "Guardian Rate Limit Logic Duration (from Prometheus):"
LOGIC_P50=$(query_prometheus 'histogram_quantile(0.5, sum(rate(guardian_ratelimit_logic_seconds_bucket{application="guardian-server"}[5m])) by (le))')
LOGIC_P95=$(query_prometheus 'histogram_quantile(0.95, sum(rate(guardian_ratelimit_logic_seconds_bucket{application="guardian-server"}[5m])) by (le))')
LOGIC_P99=$(query_prometheus 'histogram_quantile(0.99, sum(rate(guardian_ratelimit_logic_seconds_bucket{application="guardian-server"}[5m])) by (le))')
echo "  p50:  $(fmt_duration "$LOGIC_P50")"
echo "  p95:  $(fmt_duration "$LOGIC_P95")"
echo "  p99:  $(fmt_duration "$LOGIC_P99")"
echo ""

# Redis command latency
REDIS_CMD_P99=$(query_prometheus 'histogram_quantile(0.99, sum(rate(redis_commands_duration_seconds_bucket[5m])) by (le))')
echo "Redis Command Latency p99: $(fmt_duration "$REDIS_CMD_P99")"
echo ""

# CPU peak
CPU_PEAK=$(query_prometheus 'max_over_time(process_cpu_usage{application="guardian-server"}[5m])')
if [[ "$CPU_PEAK" != "N/A" ]]; then
  CPU_PCT=$(python3 -c "print(f'{float(\"$CPU_PEAK\") * 100:.1f}%')" 2>/dev/null || echo "${CPU_PEAK}")
  echo "CPU Peak (5m): ${CPU_PCT}"
else
  echo "CPU Peak (5m): N/A"
fi
echo ""

echo "============================================="
echo ""

# 6. Capture Grafana panel snapshots
capture_snapshots "$TEST_NAME"
echo ""

if [[ $K6_EXIT -ne 0 ]]; then
  echo "WARNING: k6 test exited with code $K6_EXIT (threshold violations)"
fi

exit $K6_EXIT
