# Stage 1: Build frontend
FROM node:20-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: Build backend
FROM gradle:8.5-jdk21 AS backend-build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
COPY src/ src/
# Copy frontend build into Spring Boot static resources
COPY --from=frontend-build /app/frontend/build/ src/main/resources/static/
RUN gradle bootJar --no-daemon -x test

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y git openssh-client socat && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=backend-build /app/build/libs/*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
COPY docker-auth-proxy.sh /app/docker-auth-proxy.sh
RUN chmod +x /app/docker-entrypoint.sh /app/docker-auth-proxy.sh

# Fake browser opener for Snowflake SSO: prints URL to docker logs + starts port proxy
RUN printf '#!/bin/sh\necho "$1" > /tmp/snowflake-auth-url\n/app/docker-auth-proxy.sh "$1" >> /proc/1/fd/1 2>&1 &\n' > /usr/local/bin/xdg-open && chmod +x /usr/local/bin/xdg-open
RUN ln -sf /usr/local/bin/xdg-open /usr/local/bin/sensible-browser
RUN ln -sf /usr/local/bin/xdg-open /usr/local/bin/x-www-browser

# Seed data: pre-loaded cache with lists, snapshots, notes, audit
COPY seed-data.db /app/seed-data.db

# Create directories for persistent data
RUN mkdir -p /data/cache /data/sync /root/.ssh

# Default SSH config for git (accept all host keys for github)
RUN echo "Host github.com\n  StrictHostKeyChecking no\n  UserKnownHostsFile /dev/null" > /root/.ssh/config

EXPOSE 8090 8099

# JVM flags for Snowflake Arrow compatibility
ENV JAVA_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED"

# Application defaults (override via env vars or docker-compose)
ENV SERVER_PORT=8090
ENV SNOWFLAKE_ACCOUNT=WXA20498.us-west-2.privatelink
ENV SNOWFLAKE_WAREHOUSE=DATA_ENG_WH
ENV SNOWFLAKE_ROLE=DBT_DEV_ROLE
ENV SYNC_ENABLED=true
ENV SYNC_REPO_URL=git@github.com:sunbit-dev/account-management-service.git
ENV SYNC_LOCAL_PATH=/data/sync
ENV SYNC_BRANCH=purchase-repair-sync
ENV SYNC_FOLDER=repair-sync
ENV SYNC_POLL_INTERVAL=30

ENTRYPOINT ["/app/docker-entrypoint.sh"]
