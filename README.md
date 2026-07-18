# Pixframe

Pixframe (https://pixframe-backend.onrender.com) is a full-stack, Instagram-style photo-sharing app: users sign up,
post images, follow each other, and like/comment on posts. The frontend is
a React single-page app (React Router, infinite-scroll feed) talking to a
versioned JSON REST API (`/api/v1/...`) backed by Spring Boot, PostgreSQL,
and a Redis cache layer. The project started as a hybrid server-rendered
app and was rewritten in stages — first into a full SPA, then the backend
was migrated from Flask/SQLite to Spring Boot/PostgreSQL, and finally a
Redis caching layer was added after load testing confirmed a real
connection-pool bottleneck on the "view a post" endpoint. Every one of
those changes is backed by before/after benchmark data, not just claimed —
see `benchmark/results/pre-redis/REPORT.md` for the full investigation.

## Architecture

```
React SPA (browser)
  - React Router + fetch(); session cookie, or HTTP Basic Auth for
    non-browser API clients
  |
  |  HTTP: JSON over /api/v1/..., or a static SPA shell for any other path
  v
Spring Boot backend (:8000)
  |
  |-- Controller (one per resource: Posts, Comments, Likes, Users, ...)
  |     |
  |     |-- AuthUtil -- checks session, falls back to HTTP Basic Auth
  |     |               (verifies credentials against the DB)
  |     |
  |     '-- PostDetailCache -- cache-aside, GET /posts/{id} only
  |           |
  |           |-- HIT  --> Redis (post-detail:{postid}:{logname},
  |           |            60s TTL + a per-post tracking set for
  |           |            bulk invalidation on writes)
  |           |
  |           '-- MISS --> DAO layer (JdbcTemplate, hand-written SQL,
  |                        no ORM)
  |                          |
  v                          v
(response)              PostgreSQL 16 (HikariCP connection pool)
```

- **Frontend** (`pixframe/js/`): React 19 + React Router, built with
  webpack/Babel into `pixframe/static/js/bundle.js`. Every page is a
  client-routed React component; the backend serves one static HTML shell
  (`pixframe/templates/index.html`) for any non-API path so deep links and
  hard refreshes work.
- **Backend** (`backend/`): Spring Boot 3 (Java 21), organized as
  `controller/` (HTTP layer, one class per resource) → `dao/`
  (`JdbcTemplate`-based, one method per SQL query, no ORM) → PostgreSQL.
  `util/AuthUtil` centralizes authentication (session cookie, with an
  HTTP Basic Auth fallback for non-browser API clients);
  `cache/PostDetailCache` sits in front of the single most-requested
  endpoint (`GET /api/v1/posts/{postid}/`) as a cache-aside layer over
  Redis.
- **Data**: PostgreSQL for durable state (users, posts, comments, likes,
  follows), local disk for uploaded images, Redis for the post-detail
  cache only (nothing is *only* in Redis — it's always safe to flush).

## Setup

**Dependencies:** Java 21, Maven, Node.js 24+, Docker (for Postgres and
Redis), `psql` client.

**1. Install project dependencies:**
```bash
./bin/pixframeinstall     # npm ci + mvn dependency:go-offline
```

**2. Start Postgres and Redis** (Docker containers, not managed by this
repo's scripts):
```bash
docker run --name pixframe-pg -e POSTGRES_USER=pixframe \
  -e POSTGRES_PASSWORD=pixframe -e POSTGRES_DB=pixframe \
  -p 5433:5432 -d postgres:16

docker run --name pixframe-redis -p 6379:6379 -d redis:7
```
(Postgres is mapped to host port **5433**, not the default 5432 — pick a
different `-p` mapping and update `DATABASE_URL`/`spring.datasource.url`
below if 5433 is already taken on your machine.)

**3. Create and seed the database:**
```bash
./bin/pixframedb create
```

**4. Run the app** (builds the frontend in watch mode + starts the
backend on port 8000):
```bash
./bin/pixframerun
```
Open **http://localhost:8000**.

**Environment variables** (all optional — sensible defaults are baked into
`backend/src/main/resources/application.properties` and the `bin/`
scripts):

| Variable | Default | Used by |
|---|---|---|
| `DATABASE_URL` | `postgresql://pixframe:pixframe@localhost:5433/pixframe` | `bin/pixframedb`, `bin/pixframerun`, `benchmark/run_benchmark*.sh` |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | same as above, Spring Boot's own binding | overriding the backend's DB connection directly (e.g. running an old/alternate build against a different Postgres) |

Other commands:
- `./bin/pixframedb reset` — wipe and reseed the database.
- `./bin/pixframedb destroy` — drop everything, no reseed.
- `./bin/pixframetest` — backend tests (`mvn test`) + frontend lint
  (`eslint`/`prettier`).

## Sample data / seed script

`./bin/pixframedb create` (or `reset`) loads `sql/schema.sql` (DDL) and
`sql/data.sql` (seed rows) via `psql`, then copies the sample images from
`sql/uploads/` into the app's upload directory. This gives you:

- **4 user accounts**, all with password `password123`:
  `mkim`, `dsingh`, `lchen`, `rortiz`
- **12 posts** spread across those users, with comments and likes already
  populated so the feed, a user's profile, and a single post's detail view
  all have real content to look at immediately.
- `./bin/pixframedb random` additionally generates 100 placeholder posts
  (solid-gradient images) if you want more feed content to scroll through.
- `./benchmark/setup_test_users.sh [pool_size]` creates a separate pool of
  throwaway accounts (`loadtest001`, `loadtest002`, ...) — not for normal
  app use, but for load-testing scenarios that need many distinct
  authenticated users instead of one account hammering the same endpoint
  (see `benchmark/results/pre-redis/REPORT.md` section 9 for why that
  distinction mattered).

## Deploying to Render

The repo is set up to deploy as a single Docker image (backend serves the
built frontend itself — same origin, no CORS to configure) via
`render.yaml`, a Render Blueprint. In Render's current dashboard,
Blueprints live in their own left-sidebar section (not under the old
"+ New" resource menu) — connect this repo there, point it at `render.yaml`
at the repo root, and review the proposed plan before deploying.

This provisions:
- **`pixframe-backend`** — built from the root `Dockerfile` (multi-stage:
  Node builds the frontend, Maven builds the backend jar, a slim JRE image
  runs it). `docker-entrypoint.sh` assembles `SPRING_DATASOURCE_URL` from
  Render's discrete Postgres fields (`PGHOST`/`PGPORT`/`PGDATABASE`) at
  startup, since Spring needs a `jdbc:postgresql://...` URL, not Render's
  own `postgres://...` connection string.
- **`pixframe-pg`** — managed Postgres.
- **`pixframe-redis`** — managed Key Value (Redis) instance.

All of the env vars the app needs (`SPRING_DATASOURCE_*`,
`SPRING_DATA_REDIS_*`, `PORT`) are wired automatically by the Blueprint —
nothing to fill in by hand for a first deploy.

**Seeding runs from the entrypoint, not a Pre-Deploy Command.** Render's
free tier doesn't support the dashboard's Pre-Deploy Command feature, so
`docker-entrypoint.sh` runs the same idempotent guard `bin/pixframedb
create` uses (check for an existing `users` table; seed only if absent)
on every container boot instead of as a separate deploy hook. Functionally
identical, just relocated so it works on the free plan — if the app is
later moved to a paid plan, moving this back into a `preDeployCommand` is
a reasonable but optional cleanup.

**Known trade-off: uploads don't persist.** Render's web service disk is
ephemeral — anything written to it (including images uploaded through the
app after deploy) is wiped on the next restart or redeploy. The sample
images from `sql/uploads/` are baked into the Docker image at build time,
so the seeded posts always have working images, but any *new* upload made
against the deployed instance will disappear on the next redeploy. Fixing
this properly would mean moving upload storage to something like S3-
compatible object storage — a bigger change than "deploy the app as-is,"
so it's accepted and documented here rather than solved.

**Also accepted as-is:** `/actuator/metrics` and `/actuator/prometheus`
are publicly reachable on the deployed instance, same as in local dev —
no secrets are exposed, but JVM/HikariCP internals are visible to anyone
who requests them. Locking this down would need Spring Security scoped
to `/actuator/**`, which risks diverging from what
`benchmark/run_benchmark_wrk.sh` already expects locally.

## Key design decisions and trade-offs

**Full SPA over server-rendered pages.** The app was originally a hybrid
(some pages server-rendered, only the feed was React). It was rewritten
into a full React Router SPA specifically so the entire frontend could be
decoupled from the backend's implementation — which is what later made a
full backend language migration (see below) possible without touching a
single line of frontend code, since the only contract between them is
HTTP (URLs, status codes, JSON shapes).

**`JdbcTemplate` over Spring Data JPA/Hibernate.** The backend never uses
an ORM. Every DAO method is a hand-written SQL query. This was a
deliberate choice during the Flask→Spring Boot migration: the goal was
behavioral parity with the original app, and an ORM's auto-generated SQL
is a black box relative to that goal — it can silently change query
patterns (batching, lazy-loading, N+1 shapes) in ways that are hard to
diff against a known-good baseline. The cost is more boilerplate per
query; the benefit is that every DAO method is a near-literal
transliteration of a specific, auditable SQL statement.

**No Spring Security.** Authentication is a small, explicit `AuthUtil`
class (session-or-HTTP-Basic-Auth check) called directly by each
controller, rather than Spring Security's filter-chain model. Spring
Security brings its own opinions about CSRF, session handling, and login
flows that don't map cleanly onto this app's simple session-dict-plus-
Basic-Auth-fallback model — using it would have meant fighting the
framework's defaults rather than porting existing, working behavior.

**PostgreSQL over staying on SQLite.** A backend rewrite could have kept
the same embedded SQLite database with zero migration risk. Postgres was
chosen anyway (a deliberate scope decision, not a default), which meant
solving a real cross-cutting problem: existing password hashes
(`sha512$salt$hash`, computed by Python's `hashlib`) had to keep working
under a completely different language's hashing call
(`java.security.MessageDigest`). This was verified, not assumed — a
seed user's original hash logs in successfully against the Java backend.

**Redis cache keyed on `(postid, logname)`, not `postid` alone.** The
cached endpoint's response includes per-viewer fields (`lognameLikesThis`,
each comment's `lognameOwnsThis`) that differ depending on who's asking.
An earlier design sketch called for caching by `postid` only — that would
have leaked one user's like/comment-ownership status to a different user
viewing the same post. The fix trades some cache efficiency (100 distinct
viewers of the same "hot" post populate 100 separate cache entries instead
of sharing one) for straightforward correctness, verified directly:
Redis's own `keyspace_hits`/`keyspace_misses` counters and Postgres's
`pg_stat_statements` were cross-checked against each other to confirm the
cache behaves identically under both single-user and genuinely
multi-user simulated load (see `REPORT.md` sections 9-10).

**Every performance claim in this project is benchmark-backed, and the
benchmarks were interrogated, not just run once and accepted.** The
Redis layer wasn't added speculatively — it was added after `wrk`/`k6`
load testing against the "view a post" endpoint confirmed HikariCP's
connection pool saturating (`pending` connections observed directly via
Actuator, not inferred) under load. After adding the cache, the same
benchmark was re-run — twice, in fact, once after realizing the first
comparison had an uncontrolled variable (single fixed test user, meaning
the cache was only ever exercised with one key) — and a `git worktree`
checkout of the pre-Redis commit was used to get a true apples-to-apples
comparison rather than trusting numbers from two different code versions
run in two different sessions. The full trail, including the dead ends,
is in `benchmark/results/pre-redis/REPORT.md`.
