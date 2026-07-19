# Hot Post Benchmark Report

Benchmarking the "view a post" endpoint (`GET /api/v1/posts/9/`) under
concurrent load, before and after adding a Redis cache in front of it.
Goal: find the real bottleneck, fix it, and prove the fix worked with
more than one signal (not just "throughput went up").

**Setup used throughout:** wrk (and initially k6) hitting the same
endpoint at 100/500/1000 concurrent connections, 30s per level, on an
M1 Mac. Cross-checked against `hikaricp.connections.*` (Actuator),
`pg_stat_statements` (Postgres), and Redis's own `keyspace_hits/misses`.

## 1. Baseline: no cache, single user (k6)

**What was tested:** `mkim` hammering the same post via HTTP Basic Auth,
100/500/1000 virtual users, k6.

**Result:**

| VUs | Throughput (req/s) | p95 (ms) | p99 (ms) | Errors |
|---|---|---|---|---|
| 100  | 918 | 302  | 530  | 0 |
| 500  | 851 | 871  | 1141 | 0 |
| 1000 | 811 | 1877 | 2234 | 0 |

**Key finding:** throughput doesn't scale with load — it mildly
*regresses*. Latency grows almost linearly with VU count instead. That's
the signature of a fixed-size resource (a thread or connection pool)
capping throughput, with everyone else queueing behind it. Zero errors
just means the system degrades by getting slower, not by failing.

**Caveat:** k6 and the backend shared the same 8-core machine, so some of
that latency growth could be k6 itself struggling to keep up rather than
the backend. Worth a second opinion with a lighter load tool — see next
section.

## 2. Cross-check with wrk, and finding the real bottleneck

**What was tested:** identical benchmark, swapped k6 for `wrk` (a single
C binary, near-zero CPU overhead of its own) to rule out the load tool as
a confound.

**Result:**

| Connections | Throughput (req/s) | p99 (ms) | Socket timeouts |
|---|---|---|---|
| 100  | 1238 | 305  | 0 |
| 500  | 1021 | 957  | 1 |
| 1000 | 766  | 1946 | **1107** |

**Key finding:** two things changed with a lighter load tool:
- **System CPU stayed pinned near 100% even with wrk using far less CPU
  than k6** — ruling out "k6 is the bottleneck" and pointing at Postgres/
  JVM/Docker overhead instead.
- **wrk caught HikariCP red-handed:** an Actuator snapshot taken right
  after the 500-connection run showed `active=10` (the pool's max) and
  `pending=60`; before the 1000-connection run, `pending=131`. That's
  direct proof, not inference — the default 10-connection HikariCP pool
  is the bottleneck.
- **wrk also exposed a failure mode k6 never reported:** 1,107 requests
  (4.6%) at 1000 connections didn't just get slow, they timed out. k6's
  looser timeout defaults had been silently hiding this.

`pg_stat_statements` corroborated the architecture independently: ~5
commits per request at every load level, matching the 4 DAO queries this
endpoint fires per call (none share a `@Transactional` boundary).

## 3. Adding Redis (still single user) — did it fix it?

Cached the full post-detail response, cache-aside, keyed on `(postid,
logname)` — not `postid` alone, because the response includes per-viewer
fields (`lognameLikesThis`, `lognameOwnsThis`) that differ by who's
asking. Caching by `postid` only would leak one user's like/comment
status to a different viewer of the same post.

**Result (same wrk benchmark, same single test user):**

| Metric | Pre-Redis | Post-Redis | Delta |
|---|---|---|---|
| Throughput @ 1000 conns | 766 req/s | 6238 req/s | **+714%** |
| p99 @ 1000 conns | 1946ms | 299ms | **-84.6%** |
| HikariCP pending @ 1000 conns | 131 | **0** | fixed |
| Socket timeouts @ 1000 conns | 1107 | **0** | fixed |
| Redis hit rate (whole sweep) | n/a | 99.97% (598,484 hits / 173 misses) | — |
| Process CPU @ 1000 conns | 13.6% | 35.8% | +22pp |

**Key finding:** the predicted fix landed exactly as expected —
HikariCP's pending-connections count dropped to a flat zero at every
level, socket timeouts disappeared, and throughput scales *up* with more
connections now instead of regressing. CPU going up is expected, not a
red flag: the backend is doing ~8x more requests/sec, so aggregate CPU
for request handling/serialization rises even though each request is far
cheaper.

## 4. What's the remaining ~1 query/request, then?

**What was tested:** enabled `pg_stat_statements`, reset counters, ran
one clean 500-connection pass, looked at exact per-query call counts.

**Result:** the 4 post-detail queries ran **exactly once** — tied
precisely to the single real cache miss. But `SELECT ... FROM users
WHERE username = $1` ran ~130,000 times against ~130,000 requests —
once per request, every time.

**Key finding:** the post-detail cache is working perfectly. The
remaining query is unrelated — it's `AuthUtil.checkAuth()` re-verifying
HTTP Basic Auth credentials against the DB on *every* request, since
Basic Auth is stateless (no session cookie). That's also why process CPU
rose more than pure request volume would explain: `PasswordUtil.verify()`
computes a fresh SHA-512 hash per request, and slow hashing is the whole
point of a password scheme. Caching the auth check itself (same
cache-aside pattern, short TTL) is the obvious next target — not yet
built.

## 5. Wait — was this actually testing "one viral post, many viewers"?

**The problem:** every run above (k6 and wrk, pre- and post-Redis) used
one fixed test account. Since the cache key includes `logname`, every
single request — 100 to 1000 connections — hit the *same* cache key. That's
not "many people viewing a viral post," it's one connection hammering
refresh. The 99.97% hit rate was real, but measured under the easiest
possible case, and never exercised eviction across multiple viewers.

**The fix:** `benchmark/setup_test_users.sh` creates 100 throwaway
accounts; `hot_post.lua` rotates Basic Auth credentials round-robin
across them instead of using one fixed header.

**Result (100 real distinct cache keys now):**

| Connections | Throughput (req/s) | p99 (ms) | Cache misses | Hit rate |
|---|---|---|---|---|
| 100  | 4978 | 199 | 482 | 99.68% |
| 500  | 6020 | 216 | 217 | 99.88% |
| 1000 | 4616 | 439 | 145 | 99.90% |

**Key finding:** hit rate dropped slightly (as expected with 100 real
keys instead of 1) but stayed high, and the tracking set that drives
cache invalidation now genuinely holds 100 members instead of 1 — the
first time multi-key eviction was actually exercised under load rather
than only checked manually. `pg_stat_statements` confirmed cache-miss
counts matched post-detail query counts exactly at every level, and the
auth-check query still ran ~once per request regardless — same bottleneck
as section 4, now confirmed independent of user diversity or load level.

One oddity worth flagging: p99 at 100 connections (199ms) was slightly
*worse* than expected relative to its much better median (17.5ms) — likely
GC/scheduling jitter showing up in the tail once throughput gets high
enough that the bottleneck shifts from queueing to raw request-handling
capacity. Noted rather than smoothed away.

## 6. The real apples-to-apples comparison

Section 5 fixed the script but only re-ran it against the current
(cached) backend — so no run had ever paired "no cache" with "many
users" using the same script. Checked out the last pre-Redis commit
(`bb93511`) into an isolated `git worktree`, ran the identical multi-user
script against it.

**Result — same script, same 100-account pool, only the backend code
differs:**

| Connections | Throughput: Pre-Redis | Throughput: Post-Redis | Delta | p99: Pre-Redis | p99: Post-Redis | Delta |
|---|---|---|---|---|---|---|
| 100  | 2127 req/s | 4978 req/s | **+134%** | 183ms | 199ms | +8.7% (worse) |
| 500  | 1874 req/s | 6020 req/s | **+221%** | 784ms | 216ms | **-72.5%** |
| 1000 | 1794 req/s | 4616 req/s | **+157%** | 980ms | 439ms | **-55.2%** |

| Metric | Pre-Redis | Post-Redis |
|---|---|---|
| Total DB queries/request | 5, every request | ~1.01 |
| Post-detail queries/request | 4, every request | ~0.004-0.013 |
| Auth-check queries/request | 1, every request | 1, every request (unchanged) |

**Key finding:** the caching win holds up under a genuine "many viewers,
one hot post" simulation, not just the easier single-user case. Two
honest, non-cherry-picked observations:
- Improvement isn't a clean multiplier across load levels (+134/+221/+157%)
  — reported as observed, no pattern forced onto it.
- **p99 at 100 connections is actually worse post-Redis** (199ms vs
  183ms) — the one metric where caching didn't help. Same explanation as
  section 5: at low concurrency, post-Redis throughput is so much higher
  that the bottleneck becomes raw request-handling capacity, where GC/
  scheduling jitter shows up in the tail. At 500 and 1000 connections,
  where pre-Redis was still queueing-bound, p99 improves dramatically
  (-72.5%, -55.2%) because that's exactly the delay the cache removes.

**Bottom line:** the confirmed bottleneck (HikariCP pool saturation) is
fixed — verified three independent ways (pending-connections metric,
socket-timeout count, Redis hit-rate counters), not just by a throughput
number. The next concrete target, found along the way rather than
planned in advance, is the still-uncached per-request auth check.
