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

exec java -jar pixframe-backend.jar
