#!/bin/bash
#
# run_benchmark_wrk.sh
#
# Same hot-post benchmark as run_benchmark.sh, but using wrk instead of k6.
# wrk is a single C binary (epoll event loop, no per-connection JS VM), so
# it uses far less CPU than k6 to generate the same load -- meaningfully
# reducing (though not eliminating) the "load generator competes with the
# backend for CPU" confound when both run on the same machine. See the
# discussion in benchmark/results/pre-redis/REPORT.md sections 5-6 for why
# that confound matters here.
#
# wrk's concurrency model: -t (threads) and -c (connections). Each
# connection behaves like one of k6's VUs (loops firing requests as fast as
# the server responds). Threads are capped at 8 (this machine's core count)
# regardless of connection count, since more threads than cores doesn't
# generate more load, just more scheduling overhead in the generator itself.
#
# Usage:
#   ./benchmark/run_benchmark_wrk.sh [label] [duration]
#
#   label     Subdirectory under benchmark/results/ to save output into
#             (shared with run_benchmark.sh's k6 results -- files are
#             prefixed wrk_ so both tools' output can coexist for the same
#             experiment). Defaults to "pre-redis".
#   duration  wrk -d value (e.g. 30s, 1m). Default 30s.
#
# Example:
#   ./benchmark/run_benchmark_wrk.sh pre-redis 30s
#   ./benchmark/run_benchmark_wrk.sh post-redis 30s

set -Eeuo pipefail

LABEL="${1:-pre-redis}"
DURATION="${2:-30s}"
BASE_URL="${BASE_URL:-http://localhost:8000}"
POSTID="${POSTID:-9}"
DATABASE_URL="${DATABASE_URL:-postgresql://pixframe:pixframe@localhost:5432/pixframe}"
THREADS="${THREADS:-8}"

RESULTS_DIR="benchmark/results/${LABEL}"
mkdir -p "$RESULTS_DIR"

echo "=== Hot-post benchmark sweep (wrk): label=${LABEL} duration=${DURATION} ==="

if ! curl -sf -o /dev/null "${BASE_URL}/actuator/health"; then
    echo "Error: backend not reachable at ${BASE_URL}/actuator/health"
    echo "Start it first: ./bin/pixframerun"
    exit 1
fi

# Same lightweight Actuator + Postgres snapshot as run_benchmark.sh.
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

        echo "# Server-side request latency (unaffected by load generator's own CPU usage)"
        curl -s "${BASE_URL}/actuator/metrics/http.server.requests?tag=uri:/api/v1/posts/{postid}/" || true
        echo

        echo "# Postgres pg_stat_database snapshot"
        psql "$DATABASE_URL" -c \
            "SELECT datname, numbackends, xact_commit, xact_rollback, \
                    tup_returned, tup_fetched FROM pg_stat_database \
             WHERE datname = 'pixframe';" || true
    } > "$out_file"
}

for CONNS in 100 500 1000; do
    echo ""
    echo "--- connections=${CONNS} ---"

    snapshot_metrics "${RESULTS_DIR}/wrk_conns_${CONNS}_metrics_before.txt"

    POSTID="$POSTID" USERNAME="${USERNAME:-mkim}" PASSWORD="${PASSWORD:-password123}" \
    wrk -t"$THREADS" -c"$CONNS" -d"$DURATION" \
        -s benchmark/wrk/hot_post.lua \
        "${BASE_URL}/api/v1/posts/${POSTID}/" \
        | tee "${RESULTS_DIR}/wrk_conns_${CONNS}_console.log"

    snapshot_metrics "${RESULTS_DIR}/wrk_conns_${CONNS}_metrics_after.txt"

    echo "Saved: ${RESULTS_DIR}/wrk_conns_${CONNS}_console.log"

    sleep 5
done

echo ""
echo "=== Sweep complete. Results in ${RESULTS_DIR}/ (wrk_* files) ==="
