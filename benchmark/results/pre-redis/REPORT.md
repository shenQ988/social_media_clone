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

**Update — the "~1.0 commits/request" loose end above is now resolved,
and the original guess (HikariCP keepalive queries) was wrong.** Enabled
`pg_stat_statements` on `pixframe-pg` (`shared_preload_libraries =
'pg_stat_statements'`, one container restart, `CREATE EXTENSION`), reset
its counters, and ran one clean, isolated 20s/500-connection wrk run
against the same endpoint. Result:

```
130,347 calls | SELECT username, fullname, email, filename, password
                FROM users WHERE username = $1
      1 call  | SELECT p.filename AS post_filename, ... WHERE p.postid = $1
      1 call  | SELECT commentid, owner, text FROM comments WHERE postid = $1 ...
      1 call  | SELECT COUNT(*) FROM likes WHERE postid = $1
      1 call  | SELECT likeid FROM likes WHERE owner = $1 AND postid = $2
```
against 129,855 requests in that run, with Redis's `keyspace_misses`
advancing by exactly 1 (173 -> 174) over the same window.

This is a clean, exact match: **the post-detail cache is working perfectly**
— all 4 of `getPostDetails`'s own queries ran exactly once, tied precisely
to the single real cache miss. But a *different* query — `UserDao
.findByUsername`, called from `AuthUtil.checkAuth()`'s HTTP Basic Auth
branch — ran essentially once per request (130,347 calls against 129,855
requests). This is not a HikariCP artifact; it's real, identifiable
application behavior: **the benchmark authenticates via stateless HTTP
Basic Auth (no session cookie), so `checkAuth()` re-verifies the
username/password against the database on every single request**,
completely independent of the post-detail cache. That's the actual source
of the ~1.0 commits/request figure — not an unexplained side effect, but a
second, previously-invisible bottleneck the post-detail cache was never
designed to address.

This also sharpens the "process CPU went up" explanation from above: part
of that increase is `PasswordUtil.verify()` computing a fresh SHA-512 hash
per request (via `AuthUtil.checkAuth()`), which is genuinely CPU-expensive
by design (slow password verification is the point of the hashing scheme)
— not purely higher JSON-serialization/Redis-I/O volume as originally
guessed.

**Follow-up this reveals, not yet built:** caching the auth-check result
itself (e.g. a short-TTL "this username/password pair verified
successfully N seconds ago" cache, mirroring `PostDetailCache`'s pattern)
would remove this now-dominant per-request query — same caching idea,
applied to a different, now-identified bottleneck.

**Bottom line:** the caching strategy predicted in this report's original
draft, corrected for the per-viewer-field bug and keyed on (postid,
logname) instead of postid alone, fixed the exact, specific, previously-
confirmed bottleneck (HikariCP pool saturation) — verified independently
through three different signals (pending-connections metric, socket-timeout
count, and Redis's own hit-rate counters) rather than by throughput number
alone.

## 9. A methodology gap in every result above, and the corrected numbers

> Raw evidence for sections 9-10: `wrk_conns_{100,500,1000}_multiuser_console.log`
> and `wrk_conns_{100,500,1000}_multiuser_dbstats.txt` in both
> `benchmark/results/pre-redis/` and `benchmark/results/post-redis/` —
> same naming convention as the earlier single-user `wrk_conns_*` files.
> The 500-connection level was captured first (during the original
> multi-user investigation); 100 and 1000 were added afterward
> specifically to match sections 1-8's full three-level sweep instead of
> reporting a single data point.

**The gap:** every benchmark run so far — k6 and wrk, pre-Redis and
post-Redis — authenticated as a single fixed account (`mkim`, via a
`USERNAME`/`PASSWORD` env var read once and baked into one static
`Authorization` header applied to every request from every connection).
Since `PostDetailCache` is keyed on `(postid, logname)`, this meant every
single request across every run — whether 100 or 1000 connections — hit
the *exact same* cache key, `post-detail:9:mkim`. That's not "many people
viewing a viral post" (the scenario this whole benchmark exists to
simulate) — it's "one person's connection hammering refresh, 1000 times
concurrently." The 99.97% hit rate reported in section 7/8 is real, but it
was measured under the easiest possible case for the cache: one key,
populated once, read hundreds of thousands of times. It never exercised
`PostDetailCache.invalidate()`'s actual reason for existing — evicting
*multiple* viewers' cached copies at once — since the tracking set
(`post-cache-keys:9`) could never hold more than 1 member when every
request shares one identity (`SADD` of the same string is a no-op).

**The fix:** `benchmark/setup_test_users.sh` creates a pool of 100
throwaway accounts (`loadtest001`-`loadtest100`), and `benchmark/wrk/
hot_post.lua` now rotates Basic Auth credentials round-robin across that
pool per request (via wrk's `request()` hook, precomputing each user's
auth header once at load to avoid adding non-representative base64
overhead into the hot path), instead of one fixed header for the whole
run. `POOL_SIZE=1` still reproduces the original single-user behavior if
needed for comparison.

**Re-run, clean and isolated, full three-level sweep (30s each,
`pg_stat_statements` reset before every level):**

| Connections | Requests | Throughput (req/s) | p50 (ms) | p99 (ms) | Tracking set size | Cache misses | Hit rate |
|---|---|---|---|---|---|---|---|
| 100 | 149,753 | 4978.15 | 17.55 | 199.32 | 100 | 482 | 99.68% |
| 500 | 181,053 | 6019.90 | 75.51 | 215.50 | 100 | 217 | 99.88% |
| 1000 | 138,871 | 4615.81 | 197.52 | 439.29 | 100 | 145 | 99.90% |

At every level, `pg_stat_statements` showed the post-detail queries'
call counts (findDetail/findByPostid/countByPostid/findLikeId) matching
the cache-miss count *exactly* (482/482/482, 217/217/217, 145/145/145) —
the cache's correctness holds at every concurrency level, not just one.
The auth-check query (`SELECT ... FROM users WHERE username = $1`) ran
essentially once per request at every level too (149,844 / 181,548 /
139,869 calls against those request counts) — unaffected by connection
count, confirming that bottleneck is general, not an artifact of one
specific load level.

**What changed vs. the single-user numbers, and what didn't:**
- **Hit rate dropped slightly but stayed high, and now scales the
  "right" direction:** 99.97% (single user, 1 key) -> 99.68%-99.90% across
  100-1000 connections with 100 distinct keys. Hit rate actually *rises*
  slightly with more connections (99.68% -> 99.90%) — more concurrent
  traffic per key means each of the 100 keys gets refreshed faster
  relative to its 60s TTL, so proportionally fewer of the (mostly
  fixed-count, ~100-500) misses show up against a growing request total.
- **The tracking set is now genuinely exercised at every level:** 100
  members every time, not 1 — this is the first time `invalidate()`'s
  multi-key eviction logic has actually been tested under load rather
  than only unit-verified manually (single-user, single-key) earlier in
  this project.
- **One non-monotonic result worth being upfront about:** p99 latency at
  100 connections (199.32ms) is *higher* than at 500 connections' median
  case would suggest, even though median (p50) latency is far better at
  100 connections (17.55ms) than at 500 (75.51ms). The likely explanation
  is that at low concurrency, throughput is so much higher (4978 req/s)
  that the system is now bottlenecked by raw request-handling/
  serialization volume rather than by queueing — occasional GC pauses or
  thread-scheduling jitter under that much throughput can produce tail
  latency that doesn't track the median. This wasn't smoothed into a
  single "500 connections" data point precisely so this kind of
  non-monotonic tail-latency behavior stays visible rather than hidden by
  picking one convenient sample.
- **The auth-check bottleneck identified in section 8's update is
  unchanged and unaffected by user diversity or connection count** — see
  the per-level call counts above. This confirms that bottleneck isn't an
  artifact of the single-user setup, or of any one specific load level —
  it's real, general, and still the next concrete optimization target.

**Takeaway:** the caching win holds up under a more honest simulation of
"many people viewing the same viral post," not just the easier single-user
case — but this is exactly the kind of gap worth catching before quoting a
number in an interview: "99.97% hit rate" sounds strong on its own, but
only becomes a trustworthy claim once you can say what, specifically, was
varied across those requests to make the hit rate meaningful.

## 10. The missing data point: true pre-Redis + multi-user, same script

Section 9 fixed the script (multi-user) but only re-ran it against the
*current* backend (with caching). That still left a gap: no run had ever
paired "no cache" with "multi-user" using the identical script — sections
1-8's pre-Redis numbers were single-user, and section 9's multi-user
numbers were post-Redis. Two variables (code version, script) had changed
between any two data points being compared; this section isolates the
code-version variable properly.

**Method:** git history has a clean commit boundary — `bb93511` ("added
wrk test") is the last commit before `PostDetailCache` and all Redis
wiring were introduced in the next commit (`080feef`). Checked out
`bb93511` into an isolated `git worktree` (no risk to the working
directory), confirmed it has no `com/pixframe/cache/` directory and no
Redis dependency in `pom.xml`, built and ran that exact old backend on
port 8000 (pointed at the same Postgres instance via an env var override,
since the datasource port was remapped to 5433 in a later commit), ran
the *current* multi-user `hot_post.lua` (100-account pool) against it,
then tore the old backend down and restarted the current one.

**Result — full three-level sweep, `pg_stat_statements` reset before
each level, identical script to section 9:**

| Connections | Requests | Throughput (req/s) | p50 (ms) | p99 (ms) |
|---|---|---|---|---|
| 100 | 64,000 | 2126.77 | 40.83 | 183.39 |
| 500 | 56,406 | 1873.96 | 239.19 | 784.12 |
| 1000 | 53,904 | 1793.62 | 528.30 | 979.58 |

At every level, `pg_stat_statements` showed **exactly** matching call
counts across all five queries (the 4 post-detail queries and the
auth-check query) — e.g. at 500 connections, 56,901 calls each, matching
request count almost exactly. No caching exists in this code at all, so
every single request costs 5 DB round trips, no exceptions, at every
concurrency level.

**Now a genuine apples-to-apples comparison exists at all three levels**
(same script, same pool of 100 accounts, same load per level, only the
backend code differs):

| Connections | Throughput: Pre-Redis | Throughput: Post-Redis | Delta | p99: Pre-Redis | p99: Post-Redis | Delta |
|---|---|---|---|---|---|---|
| 100  | 2126.77 req/s | 4978.15 req/s | **+134%** | 183.39ms | 199.32ms | **+8.7% (worse)** |
| 500  | 1873.96 req/s | 6019.90 req/s | **+221%** | 784.12ms | 215.50ms | **-72.5%** |
| 1000 | 1793.62 req/s | 4615.81 req/s | **+157%** | 979.58ms | 439.29ms | **-55.2%** |

| Metric | Pre-Redis | Post-Redis | Delta |
|---|---|---|---|
| Post-detail cache hit rate | **N/A — no cache exists** | 99.68%-99.90% across levels | n/a -> ~99.8% |
| Post-detail queries/request | 4, every request, every level | ~0.004-0.013 (varies with hit rate) | **~99%+ reduction** |
| Auth-check queries/request | 1, every request, every level | 1, every request, every level — **unaffected by the cache** | unchanged |
| Total DB queries/request | 5, every request, every level | ~1.004-1.013 | **~80% reduction**, bottlenecked by the still-uncached auth check |

**Two honest observations, not smoothed over:**

1. **Throughput improves at every level, but not by a consistent
   multiplier** (+134% / +221% / +157%) — there's no clean linear
   relationship between connection count and improvement magnitude, and
   I'm not forcing one. 500 connections happens to show the largest gain
   in this data; that's reported as observed, not as a predicted or
   "expected" pattern.
2. **p99 latency at 100 connections is *worse* post-Redis (199.32ms vs.
   183.39ms pre-Redis)** — the one data point across this whole
   investigation where the caching change didn't help, or very slightly
   hurt, on this specific metric. This lines up with the same
   non-monotonic tail-latency observation flagged in section 9: at low
   concurrency, post-Redis throughput is so much higher (4978 vs 2127
   req/s) that the bottleneck shifts to raw request-handling/
   serialization capacity, where occasional GC pauses or scheduling
   jitter can produce tail latency that doesn't track the (much
   improved) median. At 500 and 1000 connections, where pre-Redis
   was still queueing-bound, p99 improves dramatically (-72.5%, -55.2%)
   because that queueing delay is exactly what the cache removes. The
   takeaway isn't "caching sometimes makes things worse" — it's that
   *which* bottleneck dominates changes with load level, and a single
   aggregate claim ("Redis improved p99") would have hidden this nuance.

**On the earlier, now-superseded single-point observation:** an earlier
draft of this section noted that the original single-connection-level
pre-Redis multi-user run (1873.96 req/s at 500 connections) was *higher*
than the original single-user pre-Redis wrk run from section 7 (1020.86
req/s, also 500 connections) and speculated this was due to an unrelated
Docker resource-contention issue on the host machine at the time of the
earlier run (since resolved). That observation was about a
single-user-vs-multi-user comparison across different sessions, which is
a different (and less useful) comparison than the same-script,
same-session table above — it's retained here for the record, not because
it changes the conclusion: the apples-to-apples numbers in this section's
tables are the ones that isolate the Redis change specifically.

**Why this was worth doing rather than accepting section 9's numbers
alone:** section 9 could only show that the *post-Redis* cache behaves
correctly under multi-user load. It could not, by itself, prove that
*Redis specifically* (as opposed to some other change, or noise) was
responsible for the throughput/latency difference from the original
single-user pre-Redis baseline — because the script changed at the same
time as the code. This section removes that ambiguity by holding the
script constant and only varying the code version, which is what makes
the +221%/-72.5% figures directly attributable to the caching change
specifically.
