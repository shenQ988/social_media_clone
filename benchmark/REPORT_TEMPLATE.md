# Hot Post Benchmark Report

> Copy this file to `benchmark/results/<label>/REPORT.md` (e.g.
> `benchmark/results/pre-redis/REPORT.md`) and fill in each section after
> running `./benchmark/run_benchmark.sh <label> <duration>`. Run it once
> before adding Redis and once after, using the same duration both times,
> so the two reports are a direct apples-to-apples diff.

## 1. Test configuration

| Field | Value |
|---|---|
| Date | `<yyyy-mm-dd>` |
| Label | `pre-redis` / `post-redis` |
| Target endpoint | `GET /api/v1/posts/{postid}/` (postid = `<n>`) |
| Auth method | HTTP Basic Auth (`<username>`) |
| VU levels tested | 100, 500, 1000 |
| Duration per VU level | `<e.g. 30s>` |
| Load tool | k6 `<version>` (`k6 version`) |
| Script | `benchmark/k6/hot_post_test.js` |

## 2. System architecture

<!--
Brief description of what's actually being hit by this benchmark, so a
reader doesn't have to go spelunking through the repo to understand the
request path. Suggested to keep roughly as-is unless the architecture
changes between the pre- and post-Redis runs.
-->

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

No caching layer exists yet -- every one of the above is a fresh query on
every single request, regardless of how many times the same post was just
viewed a moment ago. <!-- Update this paragraph once Redis is added, describing what moved behind the cache. -->

## 3. Hardware / software environment

| Component | Detail |
|---|---|
| Machine | `<e.g. MacBook Air M-series, 8-core, 16GB>` |
| OS | `<uname -a output>` |
| Java | `<java -version>` |
| Spring Boot | 3.3.4 |
| PostgreSQL | 16 (Docker, `postgres:16` image) |
| Postgres container resources | `<docker inspect / --cpus / --memory limits, or "unlimited (default)">` |
| Backend run mode | `mvn spring-boot:run` (dev mode, not a packaged/production JAR) |
| Concurrent processes on host during test | `<note anything else running that could skew CPU numbers>` |

## 4. Raw benchmark results

<!-- Paste the console summary (from vus_*_console.log) or a table built
from vus_*_summary.json for each VU level. -->

### 100 VUs

```
<paste benchmark/results/<label>/vus_100_console.log summary block here>
```

### 500 VUs

```
<paste benchmark/results/<label>/vus_500_console.log summary block here>
```

### 1000 VUs

```
<paste benchmark/results/<label>/vus_1000_console.log summary block here>
```

### Summary table

| VUs | Throughput (req/s) | Mean latency (ms) | p95 (ms) | p99 (ms) | Error rate | JVM heap used (before -> after) | Process CPU (before -> after) |
|---|---|---|---|---|---|---|---|
| 100 | | | | | | | |
| 500 | | | | | | | |
| 1000 | | | | | | | |

## 5. Performance analysis

<!--
Questions to answer here, not just numbers to restate:
- Does throughput scale roughly linearly from 100->500->1000 VUs, or does
  it plateau (or regress)? At what VU level does it plateau?
- How does p99 latency change relative to p50/mean as VUs increase? A
  growing gap between p50 and p99 usually means queueing is starting
  somewhere (Tomcat thread pool, HikariCP connection pool, Postgres
  connection limit) rather than raw per-request work getting slower.
- Did the error rate stay at 0% at every VU level, or did it start
  climbing at some point? If Tomcat/HikariCP exhausts its pool under load,
  you'd expect to see this appear as connection timeouts/errors before
  you see it as pure latency growth.
-->

## 6. Bottleneck analysis

<!--
Use the metrics snapshots (vus_*_metrics_before.txt / _after.txt) to reason
about *where* time is going, not just that it's slow:
- HikariCP connections.active / .pending: if pending > 0 during the run,
  requests are queueing for a DB connection -- the pool size
  (default HikariCP pool is 10) is a likely bottleneck before the query
  logic itself is.
- Postgres pg_stat_database delta (tup_returned / tup_fetched): compare
  this across VU levels -- does query volume scale linearly with VUs, or
  does something (e.g. connection pool queueing) cap it below linear?
- Process CPU usage: pinned near 100% (of one core) suggests CPU-bound
  work (JSON serialization, request handling overhead); low CPU with high
  latency suggests you're bottlenecked on I/O wait (DB round trips) instead.
- The 4 sequential queries per request (see architecture section above)
  are the concrete target Redis is meant to remove or reduce -- name which
  of the 4 is most likely to dominate (typically the comments/likes
  lookups on a genuinely "hot" post with many comments/likes, since those
  scale with post popularity while the post-detail lookup itself doesn't).
-->

## 7. Future comparison with Redis caching

<!--
Fill this in on the *pre-redis* report as a prediction, then fill in the
matching section of the *post-redis* report with what actually happened,
and diff the two "Summary table"s from section 4 side by side.
-->

**Planned caching strategy:** `<e.g. cache the post-detail JSON response
keyed by postid with a short TTL (e.g. 30-60s); invalidate on new
comment/like/delete>`

**Predicted impact:**
- Query count per request: 4 -> `<expected, e.g. 0 on cache hit, 4 on cache miss>`
- Expected throughput change: `<e.g. "should remove the DB round-trip
  entirely on cache hits, so throughput should be bound by Tomcat/JSON
  serialization instead of Postgres">`
- Expected latency change: `<e.g. "p99 should tighten toward p50, since
  cache hits should have near-constant latency regardless of load, whereas
  DB-bound requests show more variance under contention">`

**Actual result (fill in after the post-redis run):**

| Metric | Pre-Redis | Post-Redis | Delta |
|---|---|---|---|
| Throughput @ 1000 VUs (req/s) | | | |
| p99 @ 1000 VUs (ms) | | | |
| DB queries per request | 4 | | |
| Process CPU @ 1000 VUs | | | |
