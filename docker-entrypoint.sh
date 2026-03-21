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
  # Copy SSH keys from mounted host dir to writable location with correct permissions
  mkdir -p /root/.ssh
  if [ -d /root/.ssh-host ]; then
    # Copy all private key files (no .pub extension, not directories, not config/known_hosts)
    for f in /root/.ssh-host/*; do
      fname=$(basename "$f")
      if [ -f "$f" ] && [ "${fname##*.}" != "pub" ] && [ "$fname" != "config" ] && [ "$fname" != "known_hosts" ] && [ "$fname" != "authorized_keys" ]; then
        cp "$f" "/root/.ssh/$fname"
        chmod 600 "/root/.ssh/$fname"
      fi
    done
    # SSH config: list all copied keys explicitly
    {
      echo "Host github.com"
      echo "  StrictHostKeyChecking no"
      echo "  UserKnownHostsFile /dev/null"
      for kf in /root/.ssh/*; do
        kname=$(basename "$kf")
        if [ -f "$kf" ] && [ "$kname" != "config" ] && [ "$kname" != "known_hosts" ] && [ "$kname" != "known_hosts.old" ] && [ "$kname" != "authorized_keys" ]; then
          echo "  IdentityFile /root/.ssh/$kname"
        fi
      done
    } > /root/.ssh/config
    chmod 600 /root/.ssh/config
  fi

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
  elif ls /root/.ssh/ 2>/dev/null | grep -qv -e config -e known_hosts -e authorized_keys -e '\.pub$'; then
    KEY_COUNT=$(ls /root/.ssh/ 2>/dev/null | grep -cv -e config -e known_hosts -e authorized_keys -e '\.pub$' || echo 0)
    echo "Git auth:   $KEY_COUNT SSH key(s) found"
    if ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no -T git@github.com 2>&1 | grep -q "successfully authenticated"; then
      echo "GitHub SSH: OK"
    else
      echo "WARNING:    SSH keys found but GitHub auth failed. Keys may not be authorized."
      WARNINGS=$((WARNINGS + 1))
    fi
  else
    echo "WARNING:    No git credentials found."
    echo "            Option 1: Set GITHUB_TOKEN=ghp_... in .env (HTTPS)"
    echo "            Option 2: Ensure SSH keys exist in ~/.ssh/ on host"
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
