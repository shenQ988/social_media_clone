# Pixframe

Pixframe (https://pixframe-backend.onrender.com) is a full-stack, Instagram-style photo-sharing app: users sign up, post images, follow each other, and like/comment on posts. The frontend is a React single-page app (React Router, infinite-scroll feed) talking to a versioned JSON REST API (/api/v1/...) backed by Spring Boot, PostgreSQL, and a Redis cache layer. The project started as a hybrid server-rendered app and was rewritten in stages — first into a full SPA, then the backend was migrated from Flask/SQLite to Spring Boot/PostgreSQL, and finally a Redis caching layer was added after load testing confirmed a real connection-pool bottleneck on the "view a post" endpoint. Every one of those changes is backed by before/after benchmark data, not just claimed — see benchmark/results/pre-redis/REPORT.md for the full investigation


## Architecture

```
React SPA (Browser)
    │
    ├── React Router (client-side routing)
    ├── fetch() → /api/v1/...
    └── Session Cookie / HTTP Basic Auth
            │
            ▼
Spring Boot REST API
    │
    ├── Authentication
    │      └── Session → HTTP Basic fallback
    │
    ├── Controllers
    │
    ├── PostDetailCache (cache-aside)
    │      │
    │      ├── Cache Hit
    │      │      ▼
    │      │    Redis
    │      │
    │      └── Cache Miss
    │             ▼
    └──────────── DAO (JdbcTemplate)
                   │
                HikariCP (default = 10)
                   │
              PostgreSQL 16
```

**Frontend (`pixframe/js/`)** — React 19 with React Router, bundled with webpack/Babel into a production JavaScript bundle. Navigation is handled entirely through client-side routing; the backend serves a single HTML shell (`pixframe/templates/index.html`) for all non-API routes so deep links and browser refreshes resolve correctly.

**Backend (`backend/`)** — Spring Boot 3 (Java 21) exposing a versioned REST API. Requests flow through resource-specific controllers into `JdbcTemplate`-based DAOs with hand-written SQL (no ORM) backed by PostgreSQL. `AuthUtil` centralizes authentication using session cookies, with HTTP Basic Authentication supported for non-browser API clients. `PostDetailCache` implements a cache-aside Redis layer for the performance-critical `GET /api/v1/posts/{postid}/` endpoint.

**Data** — PostgreSQL stores all durable application state (users, posts, comments, likes, and follows), while uploaded images are stored on local disk. Redis is used exclusively as a cache for post-detail responses; it contains no authoritative data, so the cache can be safely flushed or rebuilt at any time.

## Setup

### Prerequisites

* Java 21
* Maven
* Node.js 24+
* Docker
* `psql` (optional, for inspecting the PostgreSQL database)

### 1. Install project dependencies

Run the setup script to install frontend dependencies and pre-download Maven dependencies:

```bash
./bin/pixframeinstall
```

This script performs:

```bash
npm ci
mvn dependency:go-offline
```

### 2. Start PostgreSQL and Redis

The application expects PostgreSQL and Redis to be running locally. The following commands start both services in Docker containers:

```bash
docker run --name pixframe-pg \
  -e POSTGRES_USER=pixframe \
  -e POSTGRES_PASSWORD=pixframe \
  -e POSTGRES_DB=pixframe \
  -p 5433:5432 \
  -d postgres:16

docker run --name pixframe-redis \
  -p 6379:6379 \
  -d redis:7
```

> **Note:** PostgreSQL is mapped to host port **5433** to avoid conflicts with existing local installations. If you use a different port, update `DATABASE_URL` (or `spring.datasource.url`) accordingly.

### 3. Configure environment variables

Configure the application to connect to your PostgreSQL and Redis instances:

```text
DATABASE_URL=jdbc:postgresql://localhost:5433/pixframe
DATABASE_USERNAME=pixframe
DATABASE_PASSWORD=pixframe

REDIS_HOST=localhost
REDIS_PORT=6379
```

### 4. Start the application

Start the Spring Boot backend:

```bash
mvn spring-boot:run
```

In a separate terminal, build and watch the React frontend:

```bash
npm run dev
```

The application will be available at:

* **Frontend:** http://localhost:8000/
* **REST API:** http://localhost:8000/api/v1/


### Environment Variables

Most users do **not** need to configure any environment variables. Reasonable defaults are provided in `backend/src/main/resources/application.properties` and used by the helper scripts in `bin/`.

| Variable                     | Default                                                  | Purpose                                                                                |
| ---------------------------- | -------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| `DATABASE_URL`               | `postgresql://pixframe:pixframe@localhost:5433/pixframe` | Used by helper scripts (`bin/pixframedb`, `bin/pixframerun`) and benchmarking scripts. |
| `SPRING_DATASOURCE_URL`      | Derived from `DATABASE_URL`                              | Overrides the PostgreSQL connection used by Spring Boot.                               |
| `SPRING_DATASOURCE_USERNAME` | `pixframe`                                               | PostgreSQL username.                                                                   |
| `SPRING_DATASOURCE_PASSWORD` | `pixframe`                                               | PostgreSQL password.                                                                   |

These variables only need to be changed if you are connecting to a different PostgreSQL instance or using non-default credentials.

### Helper Scripts

The repository includes several helper scripts for common development tasks:

| Command                    | Description                                                                                            |
| -------------------------- | ------------------------------------------------------------------------------------------------------ |
| `./bin/pixframedb reset`   | Recreate the database schema and reseed the sample data.                                               |
| `./bin/pixframedb destroy` | Drop the database schema without reseeding.                                                            |
| `./bin/pixframerun`        | Start the Spring Boot backend using the project's default configuration.                               |
| `./bin/pixframetest`       | Run the backend test suite (`mvn test`) and frontend formatting/lint checks (`eslint` and `prettier`). |


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

Pixframe is deployed as a **single Docker container** that serves both the React frontend and the Spring Boot backend from the same origin. This eliminates the need for a separate frontend deployment or CORS configuration.

Deployment is managed through **Render Blueprints** using the repository's `render.yaml` configuration.

The Blueprint provisions three services:

| Service              | Purpose                                                                                                                                 |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| **pixframe-backend** | Multi-stage Docker image that builds the React frontend, packages the Spring Boot application, and serves both from a single container. |
| **pixframe-pg**      | Managed PostgreSQL database.                                                                                                            |
| **pixframe-redis**   | Managed Redis instance used for the post-detail cache.                                                                                  |

During container startup, `docker-entrypoint.sh` constructs Spring Boot's JDBC connection URL from Render's PostgreSQL environment variables and performs an idempotent database initialization. If the schema has not yet been created, it executes the same setup process as `./bin/pixframedb create`; otherwise startup proceeds normally without modifying existing data.

The Blueprint automatically configures the required environment variables (`SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_*`, and `PORT`), so no manual configuration is required for a standard deployment.


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
