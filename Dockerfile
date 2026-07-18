# syntax=docker/dockerfile:1

# ---- Stage 1: build the frontend (webpack -> pixframe/static/js/bundle.js) ----
FROM node:24-slim AS frontend-build
WORKDIR /app
COPY package.json package-lock.json webpack.config.js tsconfig.json ./
# --ignore-scripts: skips vnu-jar's postinstall (downloads a Java runtime
# for Cypress-based HTML validation, unrelated to and unneeded for the
# webpack build below).
RUN npm ci --ignore-scripts
COPY pixframe/js ./pixframe/js
RUN npx webpack

# ---- Stage 2: build the backend (mvn package -> pixframe-backend.jar) ----
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app/backend
COPY backend/pom.xml .
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

# ---- Stage 3: runtime ----
# Reconstructs the same relative directory layout local dev relies on
# (backend/ as the jar's working directory, with pixframe/ and var/ as
# siblings), so app.static-dir=../pixframe/static, app.templates-dir=
# ../pixframe/templates, and app.upload-dir=../var/uploads all resolve
# exactly as they do locally -- no Java code changes needed.
FROM eclipse-temurin:21-jre
WORKDIR /app

# psql client -- needed by the preDeployCommand in render.yaml, which
# seeds the database via the checked-in sql/schema.sql + sql/data.sql.
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client \
    && rm -rf /var/lib/apt/lists/*

COPY --from=backend-build /app/backend/target/pixframe-backend.jar /app/backend/pixframe-backend.jar

# Schema/seed SQL, referenced by absolute path from render.yaml's
# preDeployCommand (see /app/sql/*.sql below).
COPY sql/schema.sql sql/data.sql /app/sql/

# Static assets/templates from the repo (source-controlled CSS/images/HTML),
# then overlay the webpack build output on top -- bundle.js/.map are
# gitignored, so they only exist after the frontend-build stage above.
COPY pixframe/static /app/pixframe/static
COPY pixframe/templates /app/pixframe/templates
COPY --from=frontend-build /app/pixframe/static/js/bundle.js /app/pixframe/static/js/bundle.js
COPY --from=frontend-build /app/pixframe/static/js/bundle.js.map /app/pixframe/static/js/bundle.js.map

# Seed images baked into the image at build time -- Render's web service
# disk is ephemeral and not shared with any one-off job, so this is the
# only reliable way to have sample post/avatar images present on boot.
COPY sql/uploads /app/var/uploads

COPY docker-entrypoint.sh /app/backend/docker-entrypoint.sh
RUN chmod +x /app/backend/docker-entrypoint.sh

WORKDIR /app/backend
EXPOSE 8000
ENTRYPOINT ["./docker-entrypoint.sh"]
