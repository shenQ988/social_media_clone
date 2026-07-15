#!/bin/bash
#
# run_benchmark.sh
#
# Runs the hot-post k6 benchmark at 100, 500, and 1000 VUs and saves the
# results so a later run (e.g. after adding Redis caching) can be diffed
# against this one.
#
# Usage:
#   ./benchmark/run_benchmark.sh [label] [duration]
#
#   label     Subdirectory under benchmark/results/ to save this sweep's
#             output. Defaults to "pre-redis". Use e.g. "post-redis" for
#             the comparison run later -- same script, same VU levels,
#             different label, directly diffable.
#   duration  Duration per VU level (k6 format, e.g. 30s, 1m). Default 30s.
#
# Example:
#   ./benchmark/run_benchmark.sh pre-redis 30s
#   ./benchmark/run_benchmark.sh post-redis 30s

set -Eeuo pipefail

LABEL="${1:-pre-redis}"
DURATION="${2:-30s}"
BASE_URL="${BASE_URL:-http://localhost:8000}"
DATABASE_URL="${DATABASE_URL:-postgresql://pixframe:pixframe@localhost:5432/pixframe}"

RESULTS_DIR="benchmark/results/${LABEL}"
mkdir -p "$RESULTS_DIR"

echo "=== Hot-post benchmark sweep: label=${LABEL} duration=${DURATION} ==="

# Sanity check: backend must be up before we burn VU-seconds against a dead server.
if ! curl -sf -o /dev/null "${BASE_URL}/actuator/health"; then
    echo "Error: backend not reachable at ${BASE_URL}/actuator/health"
    echo "Start it first: ./bin/pixframerun"
    exit 1
fi

# Snapshot Actuator (JVM/CPU/connection-pool) + Postgres activity counters.
# These are cheap, always-available metrics -- no Prometheus/Grafana stack
# required to get a real before/after number. See benchmark/METRICS.md for
# the fuller Prometheus-based approach these are a lightweight stand-in for.
snapshot_metrics() {
    local out_file="$1"
    {
        echo "# Actuator metrics snapshot ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
        for metric in jvm.memory.used jvm.memory.max process.cpu.usage \
                      system.cpu.usage hikaricp.connections.active \
                      hikaricp.connections.pending; do
            echo "-- ${metric} --"
            curl -s "${BASE_URL}/actuator/metrics/${metric}" || true
            echo
        done

        echo "# Postgres pg_stat_database snapshot"
        psql "$DATABASE_URL" -c \
            "SELECT datname, numbackends, xact_commit, xact_rollback, \
                    tup_returned, tup_fetched FROM pg_stat_database \
             WHERE datname = 'pixframe';" || true
    } > "$out_file"
}

for VUS in 100 500 1000; do
    echo ""
    echo "--- VUs=${VUS} ---"

    snapshot_metrics "${RESULTS_DIR}/vus_${VUS}_metrics_before.txt"

    k6 run \
        --summary-export="${RESULTS_DIR}/vus_${VUS}_summary.json" \
        -e VUS="$VUS" -e DURATION="$DURATION" -e BASE_URL="$BASE_URL" \
        benchmark/k6/hot_post_test.js \
        | tee "${RESULTS_DIR}/vus_${VUS}_console.log"

    snapshot_metrics "${RESULTS_DIR}/vus_${VUS}_metrics_after.txt"

    echo "Saved: ${RESULTS_DIR}/vus_${VUS}_summary.json"

    # Brief cooldown so one VU level's tail latency doesn't bleed into the next.
    sleep 5
done

echo ""
echo "=== Sweep complete. Results in ${RESULTS_DIR}/ ==="
