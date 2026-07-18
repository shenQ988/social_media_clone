# Collecting Backend Metrics During a Benchmark

`run_benchmark.sh` already captures k6's own numbers (throughput, mean/p95/p99
latency, error rate) plus a lightweight before/after snapshot of JVM and
Postgres counters via `curl`/`psql` -- no extra services required. This file
covers each metric the benchmark cares about: what the lightweight snapshot
already gives you, and the fuller Prometheus/Grafana setup for continuous
time-series if you want graphs instead of two-point snapshots.

## What's already wired up (zero extra setup)

| Metric | How it's collected | Where |
|---|---|---|
| Request throughput (req/s) | k6's `http_reqs` counter | `benchmark/results/<label>/vus_*_summary.json` |
| Mean / p95 / p99 latency | k6's `http_req_duration` trend | same file |
| Error rate | k6's `http_req_failed` + custom `hot_post_non_200_rate` | same file |
| JVM heap used/max | Actuator `/actuator/metrics/jvm.memory.used` | `vus_*_metrics_before.txt` / `_after.txt` |
| Process CPU usage | Actuator `/actuator/metrics/process.cpu.usage` | same |
| System CPU usage | Actuator `/actuator/metrics/system.cpu.usage` | same |
| HikariCP pool (active/pending connections) | Actuator `/actuator/metrics/hikaricp.connections.*` | same |
| Postgres query activity (proxy) | `pg_stat_database` counters (`xact_commit`, `tup_returned`, `tup_fetched`) | same |

The before/after snapshot approach has an obvious limitation: it tells you the
*delta* over the whole run, not the *peak* or the shape of CPU/memory over
time. That's the gap Prometheus + Grafana fills below.

## Average vs. peak CPU utilization

- **Backend (Java) process**: `/actuator/metrics/process.cpu.usage` returns
  the CPU usage of the JVM process at the instant you scrape it (0.0-1.0,
  where 1.0 = 100% of one core). A single before/after snapshot only tells
  you two points in time. For a real average/peak *during* the run, either:
  - Poll it every second while k6 runs (`while true; do curl -s
    .../process.cpu.usage; sleep 1; done > cpu_samples.txt`) and compute
    avg/max after (the same polling pattern the project's earlier,
    now-retired Flask-era load-test tool used via Python's `psutil`), or
  - Let Prometheus scrape it on an interval automatically and read
    avg/max straight off a Grafana graph -- no manual polling loop needed.
- **Postgres container**: since it runs in Docker
  (`docker run ... postgres:16`), `docker stats --no-stream pixframe-pg`
  gives CPU% and memory directly from the container's cgroup, no
  instrumentation needed at all. Run it in a polling loop the same way for
  a time series, or scrape it via `cadvisor` if you want it in Prometheus
  alongside the JVM metrics.

## Memory usage / JVM heap usage

`/actuator/metrics/jvm.memory.used` (optionally tagged `?tag=area:heap` to
isolate heap from non-heap/metaspace) is the direct answer -- Micrometer's
JVM memory binder, auto-registered the moment `spring-boot-starter-actuator`
is on the classpath (no extra config beyond what's already in
`application.properties`). `jvm.memory.max` alongside it tells you how close
to the configured heap ceiling the run pushed things.

## Postgres query count

Two options, in order of effort:

1. **Cheap proxy, already wired up**: `pg_stat_database`'s `tup_returned` /
   `tup_fetched` counters, snapshotted before/after by `run_benchmark.sh`.
   The delta is a reasonable stand-in for "how much query work happened,"
   without needing any Postgres config change.
2. **Exact per-statement counts**: Postgres's `pg_stat_statements` extension
   gives you actual call counts and total time *per distinct query*. It
   requires `shared_preload_libraries = 'pg_stat_statements'` in
   `postgresql.conf`, which means restarting the container with that flag
   set -- not a runtime-enable-able extension. If you want this level of
   detail: recreate the container with
   `docker run ... postgres:16 -c shared_preload_libraries=pg_stat_statements`,
   then `CREATE EXTENSION pg_stat_statements;` once, then query
   `pg_stat_statements` directly for exact counts per query shape. Worth
   doing before the Redis comparison run specifically, since "how many times
   did we hit `SELECT ... FROM posts WHERE postid = ?`" is the number Redis
   should visibly shrink.

## Postgres CPU utilization

`docker stats --no-stream pixframe-pg` (or without `--no-stream`, streamed
continuously into a log file during the k6 run) is the simplest path since
Postgres is already containerized -- no extension, no extra exporter needed
for a basic number. For it to show up in Grafana next to the JVM metrics,
you'd add `cadvisor` (a container-metrics exporter) to the Prometheus scrape
config below.

## Recommended tooling for the full picture: Actuator + Micrometer + Prometheus + Grafana

This is genuinely the easiest production-style path for a Spring Boot app,
and most of it is already done:

1. **Spring Boot Actuator** -- already added (`spring-boot-starter-actuator`
   in `backend/pom.xml`). Exposes `/actuator/health`, `/actuator/metrics/*`,
   and `/actuator/prometheus`.
2. **Micrometer** -- Actuator's metrics facade; already auto-registers JVM
   memory/GC/threads, process CPU, HikariCP pool stats, and per-endpoint
   HTTP request latency histograms (the `management.metrics.distribution
   .percentiles-histogram.http.server.requests=true` line in
   `application.properties` is what makes p95/p99 available as real
   Prometheus histogram buckets, not just k6's client-side numbers).
3. **`micrometer-registry-prometheus`** -- already added to `pom.xml`. This
   is what turns Micrometer's internal metrics into the Prometheus text
   format served at `/actuator/prometheus`.
4. **Prometheus** -- not yet running; a ready-to-use config is in
   `benchmark/prometheus/`. It's a separate process that scrapes
   `/actuator/prometheus` on an interval (default 5s) and stores the time
   series, so instead of two snapshot points you get a full graph of CPU/
   memory/latency across the whole benchmark run.
5. **Grafana** -- a dashboard on top of Prometheus's stored data.
   Grafana.com has a pre-built "JVM (Micrometer)" dashboard (ID `4701`)
   that works out of the box against this exact stack (Actuator +
   Micrometer + Prometheus), so you don't need to hand-build panels.

### Bringing it up

```bash
cd benchmark/prometheus
docker compose up -d
```

This starts Prometheus (`localhost:9090`) pre-configured to scrape the
backend's `/actuator/prometheus` every 5 seconds, and Grafana
(`localhost:3000`, default login `admin`/`admin`) with Prometheus already
added as a data source. Import dashboard ID `4701` from grafana.com for
instant JVM graphs, or build a panel querying
`rate(http_server_requests_seconds_count{uri="/api/v1/posts/{postid}/"}[1m])`
for endpoint-specific throughput during a benchmark window.

Run `run_benchmark.sh` while this stack is up and you'll have both: k6's
client-side numbers in `benchmark/results/`, and the full server-side
time-series in Grafana to correlate against (e.g. "did CPU spike exactly
when p99 latency spiked, or did it stay flat while Postgres connection
wait time grew instead").
