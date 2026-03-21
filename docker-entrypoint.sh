#!/bin/bash
set -e

echo "========================================="
echo "  Purchase Repair Tool - Container Start"
echo "========================================="

WARNINGS=0

# -----------------------------------------------
# 1. Snowflake user (mandatory)
# -----------------------------------------------
if [ -z "$SNOWFLAKE_USER" ]; then
  echo ""
  echo "ERROR: SNOWFLAKE_USER is not set."
  echo "Run with: docker compose up (after creating .env)"
  echo "Or:       docker run -e SNOWFLAKE_USER=you@sunbit.com ..."
  exit 1
fi
echo "Snowflake user: $SNOWFLAKE_USER"

# -----------------------------------------------
# 2. Git auth for sync
# -----------------------------------------------
if [ "$SYNC_ENABLED" = "true" ]; then
  if [ -n "$GITHUB_TOKEN" ]; then
    # HTTPS mode: configure git credential helper with token
    echo "Git auth:   HTTPS token"
    git config --global credential.helper store
    echo "https://x-access-token:${GITHUB_TOKEN}@github.com" > /root/.git-credentials
    # Rewrite SSH URL to HTTPS if needed
    if echo "$SYNC_REPO_URL" | grep -q "^git@github.com:"; then
      HTTPS_URL=$(echo "$SYNC_REPO_URL" | sed 's|git@github.com:|https://github.com/|' | sed 's|\.git$||').git
      export SYNC_REPO_URL="$HTTPS_URL"
      echo "            Rewrote repo URL to: $SYNC_REPO_URL"
    fi
  elif [ -n "$SSH_AUTH_SOCK" ] && [ -e "$SSH_AUTH_SOCK" ]; then
    echo "Git auth:   SSH agent forwarded"
    if ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no -T git@github.com 2>&1 | grep -q "successfully authenticated"; then
      echo "GitHub SSH: OK"
    else
      echo "WARNING:    SSH agent forwarded but GitHub auth failed."
      WARNINGS=$((WARNINGS + 1))
    fi
  else
    echo "WARNING:    No git credentials. Set GITHUB_TOKEN for HTTPS or ensure SSH agent is running."
    echo "            HTTPS: add GITHUB_TOKEN=ghp_... to .env"
    echo "            SSH:   make sure ssh-agent is running with your key loaded (ssh-add -l)"
    WARNINGS=$((WARNINGS + 1))
  fi
else
  echo "Git sync:   disabled"
fi

# -----------------------------------------------
# 3. Check purchase-service connectivity
# -----------------------------------------------
if [ -n "$ADMIN_API_LOCAL_URL" ]; then
  if curl -s --connect-timeout 3 "$ADMIN_API_LOCAL_URL" > /dev/null 2>&1; then
    echo "Purchase service (local): reachable"
  else
    echo "WARNING:    Local purchase-service not reachable at $ADMIN_API_LOCAL_URL"
    echo "            Manipulators targeting LOCAL will fail."
    WARNINGS=$((WARNINGS + 1))
  fi
fi

# -----------------------------------------------
# 4. Check MySQL
# -----------------------------------------------
if nc -z -w3 sunbit-mysql 30306 2>/dev/null || nc -z -w3 host.docker.internal 30306 2>/dev/null; then
  echo "MySQL:      reachable"
else
  echo "WARNING:    MySQL not reachable. Replication to local DB will not work."
  WARNINGS=$((WARNINGS + 1))
fi

# -----------------------------------------------
# 5. Check data directory and seed if needed
# -----------------------------------------------
mkdir -p /root/.sunbit
if [ -f /root/.sunbit/purchase-repair-cache.db ]; then
  CACHE_SIZE=$(du -h /root/.sunbit/purchase-repair-cache.db | cut -f1)
  echo "Cache DB:   $CACHE_SIZE (existing data found)"
elif [ -f /app/seed-data.db ]; then
  cp /app/seed-data.db /root/.sunbit/purchase-repair-cache.db
  CACHE_SIZE=$(du -h /root/.sunbit/purchase-repair-cache.db | cut -f1)
  echo "Cache DB:   $CACHE_SIZE (seeded from built-in data)"
else
  echo "Cache DB:   empty (will be created on first use)"
fi

# -----------------------------------------------
# Summary
# -----------------------------------------------
echo ""
if [ $WARNINGS -gt 0 ]; then
  echo "$WARNINGS warning(s). The tool will start but some features may not work."
fi
# Configure git identity for sync commits
git config --global user.email "$SNOWFLAKE_USER"
git config --global user.name "${SNOWFLAKE_USER%%@*}"

echo "Starting on port ${SERVER_PORT:-8090}..."
echo "========================================="
echo ""

exec java $JAVA_OPTS -jar app.jar
