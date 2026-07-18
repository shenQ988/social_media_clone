#!/bin/sh
# Render's Blueprint spec can wire discrete Postgres fields (host/port/
# database/user/password) into env vars, but can't concatenate them into
# one value -- and Spring needs a jdbc:postgresql://host:port/db URL, not
# Render's own postgres://user:pass@host:port/db connection string. This
# builds SPRING_DATASOURCE_URL from the discrete PG* vars if present,
# otherwise falls through to whatever's already in the environment (or
# application.properties' localhost default, for local `docker run`).
set -e

if [ -n "$PGHOST" ] && [ -n "$PGPORT" ] && [ -n "$PGDATABASE" ]; then
    export SPRING_DATASOURCE_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}"
fi

# Render's free tier doesn't support the dashboard's Pre-Deploy Command
# feature, so the same idempotent seed step (mirrors bin/pixframedb
# create's own guard) runs here instead, on every container start.
# Safe to repeat: it's a no-op once the `users` table exists.
if [ -n "$DATABASE_URL" ]; then
    if ! psql "$DATABASE_URL" -tAc \
        "SELECT 1 FROM information_schema.tables WHERE table_name='users'" \
        | grep -q 1; then
        echo "Seeding database (first boot)..."
        psql "$DATABASE_URL" -f /app/sql/schema.sql
        psql "$DATABASE_URL" -f /app/sql/data.sql
    fi
fi

exec java -jar pixframe-backend.jar
