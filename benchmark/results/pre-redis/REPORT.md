# Hot Post Benchmark Report

Benchmarking the "view a post" endpoint (`GET /api/v1/posts/9/`) under
concurrent load, before and after adding a Redis cache in front of it.
Goal: find the real bottleneck, fix it, and prove the fix worked with
more than one signal (not just "throughput went up").

**Setup used throughout:** wrk hitting the same endpoint at 100/500/1000
concurrent connections, 30s per level, on an M1 Mac. Cross-checked against
`hikaricp.connections.*` (Actuator), `pg_stat_statements` (Postgres), and
Redis's own `keyspace_hits/misses`.

## 1. Why Redis got added

Early testing (single test user, k6 then wrk to rule out the load tool
itself as a confound) pointed at the connection pool: an Actuator
snapshot caught HikariCP maxed out (`active=10`, its configured limit;
`pending` up to 131 at 1000 connections) mid-load, and wrk exposed a
failure mode k6's looser timeouts had been hiding — 4.6% of requests at
1000 connections timing out at the socket level rather than just running
slow. `pg_stat_statements` confirmed why: every request fires 4 separate,
uncached DB queries for this endpoint's post/comments/likes data.
That's the case for caching this endpoint specifically.

## 2. Wait — was "one test user" actually testing "one viral post, many viewers"?

**The problem:** the cache is keyed on `(postid, logname)`, not `postid`
alone — the response includes per-viewer fields (`lognameLikesThis`,
`lognameOwnsThis`) that differ by who's asking, so caching by `postid`
alone would leak one user's like/comment status to a different viewer.
But that also means a benchmark using one fixed test account hits the
*same* cache key on every request, at every connection count. That's not
"many people viewing a viral post" — it's one connection hammering
refresh, and it never exercises eviction across multiple viewers' cached
copies.

**The fix:** `benchmark/setup_test_users.sh` creates 100 throwaway
accounts; `hot_post.lua` rotates Basic Auth credentials round-robin
across them instead of using one fixed header.

## 3. Multi-user results: pre-Redis vs post-Redis

To isolate the caching change specifically (not the script change too),
the pre-Redis numbers below are from the last commit before `PostDetailCache`
existed (`bb93511`, checked out into an isolated `git worktree`), run with
the *same* multi-user script as the post-Redis numbers.

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
| Redis hit rate | n/a | 99.68%-99.90% across levels |
| HikariCP pending @ 1000 conns | 131 | **0** |
| Socket timeouts @ 1000 conns | 1107 | **0** |

**Key finding:** the fix holds up under a genuine "many viewers, one hot
post" simulation, not just the easier single-user case. HikariCP pending
connections and socket timeouts both drop to exactly zero at every level,
and the cache-tracking set genuinely holds 100 members (one per active
viewer) instead of 1 — the first time multi-key invalidation was actually
exercised under load.

Two things reported as observed rather than smoothed over:
- **Improvement isn't a clean multiplier across load levels**
  (+134%/+221%/+157%) — no pattern forced onto it.
- **p99 at 100 connections is actually worse post-Redis** (199ms vs
  183ms) — the one metric where caching didn't help. Likely explanation:
  at low concurrency, post-Redis throughput is so much higher that the
  bottleneck shifts from queueing to raw request-handling/serialization
  capacity, where GC or scheduling jitter shows up in the tail. At 500
  and 1000 connections, where pre-Redis was still queueing-bound, p99
  improves dramatically (-72.5%, -55.2%) because that's exactly the delay
  the cache removes.

## 4. What's still uncached

`pg_stat_statements` at every connection level showed the 4 post-detail
queries' call counts matching the cache-miss count *exactly* — the cache
is correct, not just fast. But a different query, `SELECT ... FROM users
WHERE username = $1`, ran essentially once per request regardless of
cache hits or load level.

**Why:** `AuthUtil.checkAuth()` re-verifies HTTP Basic Auth credentials
against the DB on every request, since Basic Auth is stateless (no
session cookie) — completely independent of the post-detail cache. This
also explains part of why process CPU rose post-Redis: `PasswordUtil
.verify()` computes a fresh SHA-512 hash per request, and slow hashing is
the whole point of a password scheme. Caching the auth-check result
itself (same cache-aside pattern, short TTL) is the obvious next target
— not yet built.

**Bottom line:** the confirmed bottleneck (HikariCP pool saturation) is
fixed — verified three independent ways (pending-connections metric,
socket-timeout count, Redis hit-rate counters), not just by a throughput
number. The next concrete target, found along the way rather than
planned in advance, is the still-uncached per-request auth check.
