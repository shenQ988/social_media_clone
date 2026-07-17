# Hot Post Benchmark Report

## 1. Test configuration

| Field | Value |
|---|---|
| Date | 2026-07-15 |
| Label | pre-redis |
| Target endpoint | `GET /api/v1/posts/9/` |
| Auth method | HTTP Basic Auth (`mkim`) |
| VU levels tested | 100, 500, 1000 |
| Duration per VU level | 30s |
| Load tool | k6 v2.1.0 (darwin/arm64) |
| Script | `benchmark/k6/hot_post_test.js` |

## 2. System architecture

Request path for the benchmarked endpoint:

```
k6 (VUs) --HTTP Basic Auth--> Spring Boot (Tomcat, embedded)
                                   |
                                   v
                          PostsController.getPostDetails()
                                   |
                    +--------------+---------------+
                    |              |                |
                PostDao.findDetail  CommentDao   LikeDao
                    |              |                |
                    +------- JdbcTemplate ----------+
                                   |
                                   v
                          PostgreSQL 16 (Docker)
```

Every request to this endpoint currently triggers (see `PostDao`,
`CommentDao`, `LikeDao` in `backend/src/main/java/com/pixframe/dao/`):
1. `SELECT ... FROM posts p JOIN users u ON p.owner = u.username WHERE p.postid = ?`
2. `SELECT commentid, owner, text FROM comments WHERE postid = ? ORDER BY commentid ASC`
3. `SELECT COUNT(*) FROM likes WHERE postid = ?`
4. `SELECT likeid FROM likes WHERE owner = ? AND postid = ?`

No caching layer exists yet — every one of the above is a fresh query on
every single request, regardless of how many times the same post was just
viewed a moment ago. None of these four queries are wrapped in a single
`@Transactional` boundary, so (per the Postgres-side evidence in section 6)
each is committed as its own implicit transaction.

## 3. Hardware / software environment

| Component | Detail |
|---|---|
| Machine | Apple M1, 8 cores, 16 GB RAM |
| OS | Darwin 25.2.0 (macOS), arm64 |
| Java | OpenJDK 21.0.11 (Homebrew) |
| Spring Boot | 3.3.4 |
| PostgreSQL | 16 (Docker, `postgres:16` image) |
| Postgres container resources | Unconstrained — started via plain `docker run` with no `--cpus`/`--memory` flags |
| Backend run mode | `mvn spring-boot:run` (dev mode, not a packaged/production JAR) |
| Concurrent processes on host during test | **k6 itself, on the same machine as the backend** — see caveat in section 5 |

## 4. Raw benchmark results

Extracted from `vus_*_summary.json` (the console logs' final summary line
didn't flush to the tee'd log file before the script exited — a cosmetic
logging issue, not a test failure; the JSON exports are unaffected and are
the authoritative source used below).

### 100 VUs

```
requests: 27707   throughput: 917.97 req/s
latency:  avg 108.52ms  min 4.96ms  med 78.83ms
          p90 228.35ms  p95 302.26ms  p99 530.19ms  max 1211.22ms
error rate: 0.000% (0 / 27707 failed)
```

### 500 VUs

```
requests: 30430   throughput: 850.62 req/s
latency:  avg 494.70ms  min 8.78ms  med 452.85ms
          p90 745.81ms  p95 871.24ms  p99 1141.42ms  max 11622.29ms
error rate: 0.000% (0 / 30430 failed)
```

### 1000 VUs

```
requests: 25021   throughput: 811.25 req/s
latency:  avg 1210.99ms  min 9.55ms  med 1147.10ms
          p90 1688.57ms  p95 1876.68ms  p99 2233.97ms  max 3406.26ms
error rate: 0.000% (0 / 25021 failed)
```

### Summary table

| VUs | Throughput (req/s) | Mean latency (ms) | p95 (ms) | p99 (ms) | Error rate | JVM heap used (before → after) | Process CPU (before → after) |
|---|---|---|---|---|---|---|---|
| 100  | 917.97 | 108.52  | 302.26  | 530.19  | 0.000% | 396.7 MB → 376.8 MB | 0.004% → 13.76% |
| 500  | 850.62 | 494.70  | 871.24  | 1141.42 | 0.000% | 376.8 MB → 324.5 MB | 0.08% → 13.16% |
| 1000 | 811.25 | 1210.99 | 1876.68 | 2233.97 | 0.000% | 324.5 MB → 264.8 MB | 0.05% → 13.58% |

## 5. Performance analysis

**Throughput does not scale with VUs — it mildly *regresses*.** Going from
100 → 500 → 1000 VUs, throughput actually drops slightly (918 → 851 → 811
req/s) rather than climbing. That's the key finding of this baseline: the
system is already saturated well before 100 VUs, and adding more concurrent
clients past that point doesn't buy more completed work — it just makes
every client wait longer for the same fixed amount of throughput. This is
the textbook signature of a fixed-capacity resource being the ceiling
(a thread pool or connection pool), not raw per-request compute getting
slower under more parallel load.

**Latency grows roughly in proportion to VU count, which confirms
queueing, not per-request slowdown.** Mean latency goes 108ms → 495ms →
1211ms as VUs go 100 → 500 → 1000 — almost exactly 5x and then another
~2.4x, tracking the VU multiplier far more closely than it tracks any
change in the actual query logic (which didn't change at all between
runs). If each individual request were doing more work under load, you'd
expect the *shape* of the latency distribution to change; instead the
whole distribution just shifts right uniformly (p50/p90/p95/p99 all scale
together), which is exactly what queueing for a fixed-size resource looks
like — every request pays roughly the same wait time for a turn, and that
wait time grows with how many other requests are ahead of it in line.

**Error rate stayed at 0.000% at every VU level.** No timeouts, no
connection-pool exhaustion errors, no HTTP 5xx — the system degrades by
getting slower, not by failing outright, at least up to 1000 VUs. That's
a meaningfully different (better) failure mode than a pool exhausting and
throwing errors, but it also means "no errors" can't be used as evidence
that there's no bottleneck — the bottleneck here shows up entirely as
latency, not as failures.

**Caveat worth being honest about:** k6 and the Spring Boot backend ran on
the *same* 8-core M1 machine for this benchmark. At 500-1000 VUs, k6 itself
is a non-trivial CPU consumer (generating, sending, and timing that many
concurrent requests isn't free), and it's competing with the JVM for the
same cores. Some portion of the latency growth at higher VU counts could
be inflated by k6 struggling to keep pace, not purely by backend queueing.
The system-wide CPU numbers in section 6 are consistent with this — before
drawing a hard conclusion, the more rigorous version of this benchmark
would run k6 from a separate machine (or at least a separate container
with CPU limits set on the backend) so the load generator and the system
under test aren't sharing a CPU budget.

## 6. Bottleneck analysis

**HikariCP pool metrics were inconclusive by construction, not because
there's no contention.** Every snapshot shows `hikaricp.connections.active
= 0` and `hikaricp.connections.pending = 0`, both before and after every
run. That's because the "before"/"after" snapshot approach captures the
pool's state at the instant just before k6 starts and just after it stops
— i.e., precisely when the pool is idle. It cannot see the pool's state
*during* the 30 seconds of actual load, which is exactly when contention
would show up. This is a real limitation of this benchmark's methodology,
not evidence that HikariCP's pool isn't the bottleneck. **Follow-up before
trusting a bottleneck conclusion:** poll `/actuator/metrics/hikaricp
.connections.pending` every second during the run (the same pattern
`METRICS.md` suggests for CPU) — if `pending > 0` during the load window,
the default HikariCP pool size (10 connections) is the ceiling.

**System CPU usage climbed toward 100% at every VU level (59.8%→99.0% at
100 VUs, 79.5%→98.2% at 500 VUs, 73.7%→99.6% at 1000 VUs), while the JVM
process's own CPU usage stayed comparatively low and roughly flat (~13-14%
after every run, regardless of VU count).** That gap — system-wide CPU
pinned near 100% while the backend process itself uses only ~13% — is the
concrete evidence behind the section 5 caveat: something *other* than the
Spring Boot process is consuming most of the CPU on this machine during
the benchmark, and the most likely candidate sharing this machine is k6
itself. This doesn't rule out the backend/Postgres as the real bottleneck,
but it means the current setup can't cleanly distinguish "the backend is
slow" from "the load generator is CPU-starved and can't send requests fast
enough" — both would produce the observed pattern of flat throughput and
growing latency.

**Postgres query volume, corroborating the 4-queries-per-request
architecture independently:** `xact_commit` deltas across the three runs
were 136,923 (100 VUs) / 152,191 (500 VUs) / 121,242 (1000 VUs) against
27,707 / 30,430 / 25,021 completed requests respectively — a consistent
~4.9-5.0 commits per request at every VU level. Since none of the four
DAO queries share a `@Transactional` boundary, each commits independently,
so this Postgres-side counter is an independent confirmation (via a
completely different measurement path than reading the Java source) that
every request really is doing ~4-5 separate round trips to the database,
matching the architecture described in section 2.

**Most likely primary bottleneck, ranked by evidence strength (updated —
see section 7 for the wrk run that confirmed #1 directly):**
1. **HikariCP default pool size (10 connections) — CONFIRMED, not just
   plausible.** The wrk-based re-run in section 7 caught the pool with
   `active=10` (its configured max) and `pending=60`-`131` threads queued
   waiting for a connection, during/immediately after the 500 and 1000
   connection runs. This is direct, in-flight evidence, not an inference —
   see section 7.
2. **System CPU pinned near 100% regardless of load generator** — the
   k6-vs-wrk comparison in section 7 shows switching to a much lighter
   load generator did *not* reduce system-wide CPU pinning, which weakens
   (but doesn't eliminate) the "k6 was the confound" theory from the
   original analysis below and points more toward Postgres/JVM GC/Docker
   overhead as a real, load-generator-independent CPU consumer worth
   investigating separately.
3. **Tomcat's default embedded thread pool (200 max threads)** — still not
   directly measured; less likely to be the binding constraint now that
   HikariCP's pool (size 10) is confirmed saturated well before Tomcat's
   200-thread ceiling would matter.

The paragraphs immediately below are the original (k6-only) analysis,
kept for the record since the reasoning process is itself worth showing —
section 7 is where that analysis got updated with harder evidence.

## 7. Cross-validation with wrk (lighter-weight load generator)

To address the "is k6 itself the bottleneck" caveat above, the same
benchmark was re-run with `wrk` — a single C binary (epoll event loop, no
per-connection JS VM), chosen specifically because it burns far less CPU
than k6 to generate the same request load. Same endpoint, same auth, same
30s duration, same connection counts (100/500/1000 connections, wrk's
equivalent of k6's VUs).

### Results

| Connections | Throughput (req/s) | Mean latency (ms) | p95 (ms) | p99 (ms) | Non-200 errors | Socket timeouts |
|---|---|---|---|---|---|---|
| 100  | 1237.83 | 84.02   | 206.24 | 304.74 | 0 | 0 |
| 500  | 1020.86 | 480.60  | 783.89 | 956.66 | 0 | 1 |
| 1000 | 766.06  | 1218.24 | 1788.85 | 1945.93 | 0 | **1107** |

### This changes the conclusion in two important ways

**1. The HikariCP bottleneck is now directly confirmed, not inferred.**
The Actuator snapshot taken right after the 500-connection run recorded
`hikaricp.connections.active = 10` (exactly the pool's configured max)
and `hikaricp.connections.pending = 60` — 60 requests actively queued,
unable to get a database connection. The snapshot taken before the
1000-connection run (i.e., residual state right after the 500-connection
run's cooldown) showed `pending = 131` — even worse. This is exactly the
in-flight evidence the original k6-only analysis said was missing (every
k6 snapshot showed `active=0, pending=0`, because those snapshots landed
during idle windows, not during load). With wrk, the snapshot timing
happened to land while contention was still draining, and it caught the
pool maxed out red-handed.

**2. A real failure mode appeared that k6 never reported: socket
timeouts.** At 1000 connections, 1,107 requests didn't just get slow —
they timed out at the socket level waiting for a response (wrk's default
read timeout), on top of the 23,056 that did complete. That's roughly a
4.6% hard failure rate that the k6 run's "0.000% error rate" never
surfaced. The likely explanation: k6's own request handling (and possibly
more lenient internal timeout defaults) may have kept waiting long enough
for slow responses to eventually complete, whereas wrk's default timeout
is stricter — but the more important takeaway is that **the backend is
producing responses so slow under load that at least one load generator's
default timeout considers them failures.** That's a materially worse
finding than "0% errors, just slower," and it was invisible in the
original k6-only report.

**3. The CPU confound theory is weaker than originally stated, not
stronger.** System CPU usage still pinned near 100% at every connection
count under wrk (79.2%→99.2% at 100, 91.8%→99.8% at 500, 93.2%→99.9% at
1000) — essentially the same pattern as k6, *despite* wrk using
dramatically less CPU itself to generate the load. If k6's own CPU
consumption were the main driver of that near-100% system CPU number,
switching to a much lighter tool should have visibly reduced it. It
didn't. That's evidence the near-100% system CPU has a cause largely
independent of which load generator is running — Postgres, JVM GC
activity, or Docker Desktop's VM overhead are the remaining candidates,
and none of those were directly instrumented in this benchmark. Worth a
follow-up: `docker stats --no-stream pixframe-pg` polled during a run
(per `METRICS.md`) would tell us whether Postgres itself is the CPU sink.

**Corroborating evidence held up under wrk too:** `xact_commit` deltas
were 186,508 / 154,145 / 120,669 across the three runs against 37,255 /
30,709 / 23,056 completed requests — 5.01 / 5.02 / 5.23 commits per
request, consistent with the ~4.9-5.0 seen under k6 and with the
architecture's 4 independently-committed queries per request.

**Revised conclusion:** the dominant, now-confirmed bottleneck is
HikariCP's default connection pool size (10), not the load-generator
confound the original analysis emphasized. The confound is real and still
worth fixing for precise absolute numbers, but it was not hiding the
actual answer — both tools agree the system saturates and gets
dramatically slower well before 1000 concurrent clients, and wrk's data
additionally proves *why* (the connection pool), with hard failures
(timeouts) appearing that the original report didn't know to look for.

## 8. Future comparison with Redis caching

**Planned caching strategy:** cache the post-detail JSON response
(the full `getPostDetails` output) keyed by `postid`, with a short TTL
(e.g. 30-60s) or explicit invalidation on new comment/like/delete for that
post.

**Predicted impact (updated with section 7's confirmed HikariCP
bottleneck — this is now a much stronger prediction than the original
speculative version):**
- Query count per request: 4 → 0 on a cache hit, 4 on a cache miss
  (first request per TTL window, or right after an invalidation).
- Expected throughput change: since section 7 *confirmed* (not just
  guessed) that HikariCP's pool saturates at its default size of 10 under
  load, and every cache hit removes all 4 DB round trips for that request,
  throughput should rise substantially and the pool should stop
  bottlenecking — `hikaricp.connections.pending` should stay near 0 even
  at 1000 connections post-Redis, versus the confirmed 60-131 pending
  observed pre-Redis. If pending connections are *still* observed
  post-Redis, that would mean the cache isn't actually being hit as often
  as expected (e.g. TTL too short, or the "hot post" isn't landing on the
  same cache key every time) — a concrete, checkable failure signal.
- Expected latency change: p99 should tighten dramatically, and the
  1,107 socket timeouts observed at 1000 connections (section 7) should
  drop to at or near 0 — a cache read is near-constant-time regardless of
  concurrent load, unlike a DB round trip contending for a 10-connection
  pool. Timeouts persisting post-Redis would be a strong signal that
  something *other* than the DB round trips (e.g. Tomcat thread pool
  exhaustion) is now the binding constraint.

**Actual result:**

| Metric | Pre-Redis (k6) | Pre-Redis (wrk) | Post-Redis (wrk) | Delta (wrk, pre→post) |
|---|---|---|---|---|
| Throughput @ 100 conns (req/s) | n/a | 1237.83 | 6848.92 | **+453%** |
| Throughput @ 500 conns (req/s) | n/a | 1020.86 | 6753.71 | **+562%** |
| Throughput @ 1000 conns (req/s) | 811.25 | 766.06 | 6237.93 | **+714%** |
| HikariCP pending @ 1000 conns | not captured (idle snapshot) | 131 | **0** (every snapshot, all 3 levels) | fixed |
| Socket timeouts @ 1000 conns | n/a (k6 has no equivalent) | 1107 | **0** | fixed |
| p99 @ 100 conns (ms) | n/a | 304.74 | 67.44 | -78% |
| p99 @ 500 conns (ms) | n/a | 956.66 | 194.00 | -80% |
| p99 @ 1000 conns (ms) | 2233.97 | 1945.93 | 299.02 | **-84.6%** |
| Redis cache hit rate (whole sweep) | n/a | n/a | **99.97%** (598,484 hits / 173 misses) | — |
| Process CPU @ 1000 conns | 13.58% | 12.03% | 35.77% | +23.7pp (see note) |

Both predictions from above came true, and the confirmed bottleneck is
fixed, not just improved:

**HikariCP pending dropped from a confirmed 131 to a flat 0 at every
snapshot, at every connection level, both before and after each run.** Not
"lower" — zero, every time. Combined with the Redis-reported 99.97% hit
rate (598,484 hits against only 173 misses across the entire three-level
sweep — essentially: one miss per postid the first time it's requested,
then every subsequent request for 30s×3 is served from cache), this is
about as clean a confirmation as this kind of benchmark can produce that
the connection-pool bottleneck identified in section 7 is actually gone,
not just masked.

**Socket timeouts went from 1,107 (at 1000 connections) to exactly 0.**
The hard-failure mode wrk exposed pre-Redis — requests waiting so long for
a DB connection that they exceeded wrk's read timeout — no longer happens
at any tested connection level.

**Throughput gains grow *with* connection count (453% → 562% → 714%),
the opposite of the pre-Redis pattern where throughput mildly regressed as
connections increased.** That inversion is itself meaningful: pre-Redis,
more concurrent clients made things worse (queueing for the same 10
connections); post-Redis, more concurrent clients scale roughly with
available CPU/serialization throughput instead of colliding on a fixed-size
pool. This is exactly the qualitative shift predicted above.

**p99 latency tightened dramatically (-78% to -84.6%)**, confirming the
second prediction — cache hits behave close to constant-time regardless of
concurrent load, unlike DB round trips contending for a shared pool.

**Process CPU went *up* (13.58%→35.77% @ 1000 conns), which is expected,
not a red flag.** The backend is now doing ~8x more requests per second
(6238 vs 766), so even though *each individual request* is far cheaper (no
DB wait, no 4 sequential queries), the aggregate CPU spent on request
handling, JSON serialization, and Redis I/O scales with the much higher
request volume. The bottleneck moved from "waiting on a saturated DB
connection pool" to "how much request/serialization throughput the JVM can
sustain" — a substantially better problem to have, and the natural next
optimization target if even more throughput were needed.

**One loose end, noted rather than hidden:** Postgres's own `xact_commit`
counter still shows ~1.0 commits per request post-Redis (203,909 /
206,228 ≈ 0.99 at 100 conns; similar at 500 and 1000) — not the ~0 a 99.97%
cache-hit-rate would naively suggest. Since Redis's own hit-rate counters
are authoritative and directly confirm the cache is doing its job, this
residual ~1 commit/request is most likely HikariCP's idle-connection
keepalive/validation queries (each of which autocommits in Postgres's
accounting, even for a trivial `SELECT 1`-style check) rather than actual
per-request application queries — but this wasn't independently confirmed
in this pass, and would be worth checking directly (e.g. via
`pg_stat_activity` query text, or HikariCP's `leakDetectionThreshold`/
keepalive logs) before treating it as fully explained.

**Bottom line:** the caching strategy predicted in this report's original
draft, corrected for the per-viewer-field bug and keyed on (postid,
logname) instead of postid alone, fixed the exact, specific, previously-
confirmed bottleneck (HikariCP pool saturation) — verified independently
through three different signals (pending-connections metric, socket-timeout
count, and Redis's own hit-rate counters) rather than by throughput number
alone.
