# Backend Migration: Flask/SQLite → Spring Boot/PostgreSQL

A log of the engineering decisions, trade-offs, and bugs encountered while
replacing the Python/Flask backend with a Java/Spring Boot backend on
PostgreSQL, written up as I'd walk an interviewer through it.

## The ask, and why it's not a trivial "rewrite the routes" job

The constraint was: **don't change behavior**. The React frontend
(`pixframe/js/`) was already fully decoupled from the backend — it only
knows about `/api/v1/...` JSON endpoints, `/uploads/<file>`, and a
session cookie. That's what made a full language swap tractable at all:
as long as the HTTP contract stayed identical, the frontend would never
know the backend changed underneath it. So the real engineering problem
wasn't "translate Python to Java" — it was "prove the contract didn't
move," which shaped almost every decision below.

## Decision 1: JdbcTemplate over JPA/Hibernate

The Flask app never used an ORM — every query was hand-written SQL
against `sqlite3`. Two ways to port that to Spring:

- **Spring Data JPA/Hibernate**: the "default" enterprise-Java choice.
  I ruled it out because an ORM's generated SQL is a black box relative
  to what I'm trying to keep identical. Entity mapping, lazy-loading,
  and auto-DDL all introduce *new* query patterns that didn't exist in
  the original — exactly the kind of behavior drift the migration was
  supposed to avoid. It would also make it harder to reason about
  the N+1-style query fan-out I'd already identified and load-tested
  in this app (`loadtest/BASELINE.md`) — an ORM can silently change the
  *shape* of that fan-out (extra lazy-load queries, different batching)
  in ways that are hard to diff against a Python baseline.
- **JdbcTemplate + raw SQL**: what I went with. Every DAO method is
  close to a literal transliteration of one Python function — same
  `SELECT`, same parameters, same row shape. When I needed to verify
  parity, I could diff the SQL string next to the Python one line by
  line instead of trusting an ORM to reproduce equivalent behavior.

The cost is boilerplate (a DAO method per query, manual `Map<String,Object>`
row handling) — but for a *migration*, predictability beats ergonomics.

## Decision 2: No Spring Security

Spring Security is the idiomatic way to do auth in Spring, but it comes
with its own opinions about CSRF, session fixation, and login flows that
don't map cleanly onto what Flask was actually doing (a plain
`flask.session` dict check, plus an HTTP Basic Auth fallback for the
API). Rather than fight Spring Security's defaults into submission, I
wrote a small `AuthUtil` that mirrors `api_utils.check_auth()` almost
statement-for-statement: check `HttpSession` first, fall back to
decoding an `Authorization: Basic` header, verify against the DB. Every
controller calls it explicitly, exactly like every Flask view called
`check_auth()` explicitly. This is less "framework-native," but it's a
direct, auditable port instead of a re-interpretation.

One deliberate exception I flagged rather than silently fixed: Flask's
`flask_wtf.CSRFProtect` was wired in but `WTF_CSRF_ENABLED = False`, so
CSRF was already inert in the app I inherited. Not using Spring Security
means there's no CSRF filter on the Java side either — so the *absence*
of CSRF protection is preserved, which is technically "no behavior
change," but I called it out explicitly rather than let it look like an
oversight.

## Decision 3: Postgres instead of keeping SQLite

This one was a scope decision from the user, not something I'd have
defaulted to for a pure "same behavior" migration — SQLite would have
been the zero-risk choice. Postgres meant:

- Converting `sql/schema.sql` from SQLite's `INTEGER PRIMARY KEY
  AUTOINCREMENT` to `SERIAL PRIMARY KEY`, and `DATETIME` to `TIMESTAMP`.
- Handling `last_insert_rowid()` → Postgres's `INSERT ... RETURNING id`,
  which is actually cleaner (one round trip instead of two).
- A cross-cutting risk I had to check explicitly: **existing password
  hashes**. The seed data has `sha512$<salt>$<hash>` strings computed by
  Python's `hashlib.sha512`. Swapping the DB engine doesn't touch stored
  data, but swapping the *language* verifying those hashes does. I
  confirmed `MessageDigest.getInstance("SHA-512")` over UTF-8 bytes of
  `salt+password` is bit-for-bit identical to `hashlib.sha512` — same
  algorithm, same input encoding, same hex output — so existing accounts
  kept working without a forced password reset. I verified this for
  real (not just "should be fine"): logged in as a seed user whose hash
  was computed months earlier by the Flask app, and it succeeded on the
  first try against the Java backend.

## Technical challenge 1: sequences after explicit-id seed inserts

The seed data inserts posts/comments/likes with explicit ids (`INSERT
INTO posts (postid, filename, owner) VALUES (1, ...)`), which is fine —
but Postgres's `SERIAL` sequence doesn't know that happened, so the
*next* auto-generated id would collide with an existing row (id 1 again
instead of continuing from 12). SQLite's `AUTOINCREMENT` handles this
differently and I initially ported the seed data without thinking about
it. Fix: three `SELECT setval('<table>_<col>_seq', (SELECT
MAX(<col>) FROM <table>))` calls at the end of `data.sql`, run once
after the explicit inserts, so the sequence "catches up" to the highest
id actually in the table.

## Technical challenge 2: Postgres identifier case-folding silently
renaming a column key

Early draft of `PostDao.findDetail()` used `SELECT p.filename AS
imageUrl` (camelCase, matching the Python dict key). Postgres folds
*unquoted* identifiers to lowercase, so the actual column name in the
result set became `imageurl`, not `imageUrl` — a silent rename that
would only surface as a `NullPointerException` (or worse, a silently
wrong value) wherever the Java code read `row.get("imageUrl")`. I caught
this before it caused a runtime bug by deciding early: SQL aliases in
this codebase are internal implementation details, not the final JSON
key (the controller builds the outward-facing JSON key separately,
e.g. `context.put("imgUrl", ...)`), so there's no reason to fight
Postgres's case folding at all — I just renamed the aliases to
`snake_case` (`post_filename`, `owner_img_filename`) and read them back
with the same casing. Cheaper than quoting every identifier, and removes
an entire class of "did Postgres just silently lowercase my column"
bugs.

## Technical challenge 3: timestamp format drift breaking the frontend's
relative-time display

SQLite stores `DATETIME` as plain text, so the Python code was passing
through a bare string like `"2021-05-06 19:52:44"` in JSON responses. A
Postgres `TIMESTAMP` column comes back through JDBC as a
`java.sql.Timestamp`, and `Timestamp.toString()` produces
`"2026-07-10 16:07:16.0"` — note the trailing `.0`. The frontend's
`dayjs.utc(timestamp).fromNow()` (in `post.jsx`) is lenient enough that
this might have silently parsed "close enough," but I didn't want to
ship a subtle format mismatch on faith. Fixed with an explicit
`DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")` applied to
`Timestamp.toLocalDateTime()` before it goes into the JSON response —
so the wire format is exactly what the Python backend used to send,
byte for byte.

## Technical challenge 4: the SPA catch-all could have silently broken
static asset serving

The original Flask catch-all (`spa_shell`) was safe because Werkzeug's
router always prefers a literal/specific route over a wildcard,
regardless of registration order. Spring MVC doesn't give you that
guarantee for free: annotated `@Controller` mappings
(`RequestMappingHandlerMapping`) are checked by the `DispatcherServlet`
*before* the resource-handler mapping that serves `/static/**`
(`SimpleUrlHandlerMapping`, registered at much lower priority). If I'd
registered the SPA fallback as a blanket `@GetMapping("/**")`, it would
have intercepted every request — including `/static/css/style.css` —
before Spring ever got a chance to serve the actual file, because
`DispatcherServlet` stops at the first `HandlerMapping` that matches,
it doesn't compare specificity *across* different `HandlerMapping`
implementations the way Werkzeug does within one router. I caught this
during planning (not by shipping and debugging a broken CSS load) by
scoping `SpaController`'s mapping to the known React Router prefixes
(`"/"`, `/accounts/**`, `/users/**`, `/posts/**`, `/explore/**`) instead
of a wildcard — a set I could enumerate exactly from `App.jsx`'s route
table, so there was no ambiguity about what should and shouldn't hit
the fallback.

## Technical challenge 5: a real bug the automated checks didn't catch,
that a browser check did

`pixframe/templates/index.html` had `{{ url_for('static',
filename='css/style.css') }}` — Jinja syntax that Flask's
`render_template()` used to resolve server-side. My `SpaController`
just reads the file and returns the bytes; there's no template engine
on the Java side. Every curl-based parity check I ran passed (200 OK,
right content-type on `/`), because curl doesn't care *what* the HTML
body says — it just checks the response landed. The bug only became
visible when I drove the app with a real headless browser: the page
hung on "Loading..." forever, because the browser was requesting
`GET /{{ url_for(...) }}` as a literal, malformed URL for the JS bundle
and never got React mounted. This is the reason I don't consider
curl/API-level testing sufficient proof of frontend-facing correctness
by itself — I ran a Playwright pass specifically because "the API
returns 200" and "the page actually renders for a user" are different
claims. Fixed by replacing the Jinja calls with the literal resolved
paths (`/static/css/style.css`, `/static/js/bundle.js`), since Spring
serves them at the same URLs Flask did.

## How I verified "no behavior change" instead of just asserting it

Four layers, cheapest-to-most-expensive, each catching a different
class of bug:

1. **Compile-time**: `mvn compile` — catches typos, wrong types, missing
   imports. Cheap, fast, catches nothing about actual behavior.
2. **Endpoint-level parity via curl**: a 20-step scripted sequence
   (login with a *pre-existing* hash, signup, profile fetch, post
   create/delete, like/unlike, comment create/delete, follow/unfollow,
   paginated feed, deep-link SPA shell, auth-gated `/uploads/`,
   unauthenticated 403s) — checks status codes and JSON shapes match
   what the Flask version produced for the same inputs.
3. **Visual/browser-level via Playwright**: caught the Jinja-placeholder
   bug that curl couldn't see, because curl never renders anything.
4. **Load-level**: re-ran the existing `loadtest/viral_post_test.py`
   (10k requests / 100 concurrency against a single post) against the
   new backend — not to chase a performance number, but to confirm the
   new stack holds up functionally (0 errors) under the same burst
   shape as the documented baseline, not just under a handful of
   one-off manual requests.

## What I'd flag as follow-up work, not silently dropped

- `X-DB-Query-Count` (the SQL-query-counting header added for
  `loadtest/BASELINE.md`) is SQLite/Flask-specific instrumentation
  (`sqlite3.Connection.set_trace_callback`) that has no direct Java
  equivalent wired up yet. The load test script still works for
  latency/RPS/CPU against the new backend; it just won't report a
  per-request query count until/unless someone adds a JDBC-level
  interceptor (e.g. a `DataSource` proxy) to replicate it.
- No automated test suite exists for the Java backend yet (`mvn test`
  currently has nothing to run) — the parity verification was manual/
  scripted, not committed as regression tests. If this were going into
  a real production pipeline rather than a portfolio migration, I'd
  turn the 20-step curl sequence into `@SpringBootTest` integration
  tests before calling it done, rather than relying on "I ran it once
  and it passed."
