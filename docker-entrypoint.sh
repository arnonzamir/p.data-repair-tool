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
# 2. SSH key for git sync
# -----------------------------------------------
if [ "$SYNC_ENABLED" = "true" ]; then
  if [ -f /root/.ssh/id_rsa ] || [ -f /root/.ssh/id_ed25519 ]; then
    echo "SSH key:    found"
  else
    echo "WARNING:    No SSH key mounted. Git sync will fail on push."
    echo "            Mount via: -v \$HOME/.ssh/id_ed25519:/root/.ssh/id_ed25519:ro"
    WARNINGS=$((WARNINGS + 1))
  fi

  # Test git access
  if ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no -T git@github.com 2>&1 | grep -q "successfully authenticated"; then
    echo "GitHub SSH: OK"
  else
    echo "WARNING:    GitHub SSH auth failed. Sync will work read-only (pull) but not push."
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
# 5. Check data directory
# -----------------------------------------------
mkdir -p /root/.sunbit
if [ -f /root/.sunbit/purchase-repair-cache.db ]; then
  CACHE_SIZE=$(du -h /root/.sunbit/purchase-repair-cache.db | cut -f1)
  echo "Cache DB:   $CACHE_SIZE (existing data found)"
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
echo "Starting on port ${SERVER_PORT:-8090}..."
echo "========================================="
echo ""

exec java $JAVA_OPTS -jar app.jar
