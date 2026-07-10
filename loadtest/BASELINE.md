# Viral Post Load Test — Pre-Upgrade Baseline

Simulates a single post going viral: 10,000 GET requests against one
`/api/v1/posts/<postid>/` (the "viral" post), at concurrency 100, run
against the local Flask dev server.

Raw result: [`results/baseline_pre_upgrade_1783697178.json`](results/baseline_pre_upgrade_1783697178.json)

## Setup

```bash
source env/bin/activate
PIXFRAME_QUERY_DEBUG=1 flask --app pixframe run --host 127.0.0.1 --port 8000 &
python3 loadtest/viral_post_test.py --requests 10000 --concurrency 100 \
  --postid 9 --label baseline_pre_upgrade
```

`PIXFRAME_QUERY_DEBUG=1` turns on a per-request SQL query counter
(`pixframe/model.py`, exposed via the `X-DB-Query-Count` response header)
so the test can report real query counts instead of estimates.

## Results (2026-07-10)

| Metric | Value |
|---|---|
| Total requests | 10,000 |
| Concurrency | 100 |
| Wall time | 10.5 s |
| **Throughput** | **952 req/sec** |
| Latency (min / median / p95 / p99 / max) | 10 / 104 / 127 / 144 / 174 ms |
| Errors | 0 (0%) |
| Server CPU (avg / peak) | 166% / 177% (multi-core dev machine) |
| **DB queries per request** | **4** (constant, min=max=4) |

## What this tells us

- **No errors at 10k concurrent-ish requests** — the dev server holds up
  functionally, this is a throughput/latency ceiling problem, not a
  correctness problem.
- **4 SQL queries per single-post view**, every time, regardless of load —
  from `get_post_details` in `pixframe/api/posts.py`: one query for the
  post+owner join, one for comments, one for the like count, one for
  "did I like this." At 10k views that's 40,000 queries for one post.
  This is the fan-out we flagged earlier as a design weakness; a viral
  post is exactly the scenario that turns it into a real bottleneck,
  since query count scales linearly with view count instead of being
  cached/amortized.
- **p99 latency (144ms) is ~40% higher than median (104ms)** — the tail
  is where users actually notice slowness; that gap is a candidate to
  shrink with caching (e.g. cache the like count / comment list for N
  seconds) rather than recomputing on every single request.
- CPU is already elevated (166% avg) at just 100 concurrent connections
  on a single dev machine — this won't scale further without either more
  hardware or fewer queries per request.

## Suggested upgrade path (to compare the next baseline against)

1. Cache `numLikes`/comment list per post for a short TTL (e.g. Redis or
   even an in-process cache) so a viral post doesn't re-run the same 3
   aggregate queries per viewer.
2. Batch the like-count + like-status + comment queries into fewer
   round trips (or one query with `LEFT JOIN`s) instead of 3 separate
   `SELECT`s per request.
3. Re-run `viral_post_test.py` with the same parameters after each
   change and diff the JSON reports to quantify the improvement.
