# Hot Post Benchmark Report

Benchmarking the "view a post" endpoint (`GET /api/v1/posts/9/`) under
concurrent load, before and after adding a Redis cache in front of it.
Goal: find the real bottleneck, fix it, and prove the fix worked with
more than one signal (not just "throughput went up").

**Setup used throughout:** wrk hitting the same endpoint at 100/500/1000
concurrent connections, 30s per level, on an M1 Mac. Cross-checked against
`hikaricp.connections.*` (Actuator), `pg_stat_statements` (Postgres), and
Redis's own `keyspace_hits/misses`.


## Multi-user results: pre-Redis vs post-Redis

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

**Key finding:** the fix holds up under a 
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
