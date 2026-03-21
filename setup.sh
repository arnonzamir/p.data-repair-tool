#!/bin/bash
set -e

echo "========================================="
echo "  Purchase Repair Tool - Setup"
echo "========================================="
echo ""

# -----------------------------------------------
# 1. Snowflake user (mandatory)
# -----------------------------------------------
if [ -f .env ]; then
  source .env 2>/dev/null
fi

if [ -z "$SNOWFLAKE_USER" ]; then
  read -p "Snowflake username (your-name@sunbit.com): " SNOWFLAKE_USER
  if [ -z "$SNOWFLAKE_USER" ]; then
    echo "ERROR: Snowflake user is required."
    exit 1
  fi
fi
echo "Snowflake user: $SNOWFLAKE_USER"
echo ""

# -----------------------------------------------
# 2. Git auth for sync
# -----------------------------------------------
echo "Checking GitHub access for team sync..."
GITHUB_TOKEN="${GITHUB_TOKEN:-}"

if ssh -T git@github.com 2>&1 | grep -q "successfully authenticated"; then
  echo "  GitHub SSH: OK"
elif [ -n "$GITHUB_TOKEN" ]; then
  echo "  GitHub token: found in environment"
else
  echo ""
  echo "  Git sync requires GitHub access. Choose one:"
  echo "    1) SSH key (already configured on this machine)"
  echo "    2) GitHub personal access token"
  echo "    3) Skip sync (work offline, no team collaboration)"
  echo ""
  read -p "  Choice [1/2/3]: " GIT_CHOICE
  case "$GIT_CHOICE" in
    1)
      echo "  SSH key not working. Make sure your key is added to GitHub."
      echo "  Test with: ssh -T git@github.com"
      read -p "  Press ENTER to continue anyway (sync may fail)..." _
      ;;
    2)
      echo "  Create a token at: https://github.com/settings/tokens"
      echo "  Required scope: repo (full control of private repositories)"
      read -p "  Paste your GitHub token: " GITHUB_TOKEN
      if [ -z "$GITHUB_TOKEN" ]; then
        echo "  No token provided. Sync will be disabled."
        SYNC_ENABLED=false
      fi
      ;;
    3)
      SYNC_ENABLED=false
      echo "  Sync disabled. You can enable it later by setting GITHUB_TOKEN in .env"
      ;;
    *)
      echo "  Invalid choice. Skipping sync."
      SYNC_ENABLED=false
      ;;
  esac
fi
echo ""

# -----------------------------------------------
# 3. Check local purchase-service
# -----------------------------------------------
echo "Checking local purchase-service (localhost:8080)..."
if curl -s --connect-timeout 3 http://localhost:8080/actuator/health > /dev/null 2>&1; then
  echo "  Purchase service: OK"
elif curl -s --connect-timeout 3 http://localhost:8080 > /dev/null 2>&1; then
  echo "  Purchase service: reachable (health endpoint not available)"
else
  echo "  WARNING: Local purchase-service not reachable at localhost:8080."
  echo "  Manipulators targeting LOCAL will fail. You can still use STAGING/PROD targets."
  read -p "  Press ENTER to continue anyway..." _
fi
echo ""

# -----------------------------------------------
# 4. Check MySQL
# -----------------------------------------------
echo "Checking MySQL (sunbit-mysql:30306)..."
if nc -z -w3 sunbit-mysql 30306 2>/dev/null; then
  echo "  MySQL: OK"
elif nc -z -w3 localhost 30306 2>/dev/null; then
  echo "  MySQL: reachable at localhost:30306"
else
  echo "  WARNING: MySQL not reachable at sunbit-mysql:30306."
  echo "  Purchase replication to local DB will not work."
  read -p "  Press ENTER to continue anyway..." _
fi
echo ""

# -----------------------------------------------
# 5. Check Docker
# -----------------------------------------------
echo "Checking Docker..."
if ! command -v docker &> /dev/null; then
  echo "  ERROR: Docker not found. Install Docker Desktop first."
  exit 1
fi
if ! docker info > /dev/null 2>&1; then
  echo "  ERROR: Docker daemon not running. Start Docker Desktop first."
  exit 1
fi
echo "  Docker: OK"
echo ""

# -----------------------------------------------
# 6. Write .env
# -----------------------------------------------
cat > .env << ENVEOF
SNOWFLAKE_USER=$SNOWFLAKE_USER
SNOWFLAKE_ACCOUNT=${SNOWFLAKE_ACCOUNT:-WXA20498.us-west-2.privatelink}
SNOWFLAKE_WAREHOUSE=${SNOWFLAKE_WAREHOUSE:-DATA_ENG_WH}
SNOWFLAKE_ROLE=${SNOWFLAKE_ROLE:-DBT_DEV_ROLE}
SNOWFLAKE_AUTHENTICATOR=${SNOWFLAKE_AUTHENTICATOR:-externalbrowser}
SYNC_ENABLED=${SYNC_ENABLED:-true}
GITHUB_TOKEN=${GITHUB_TOKEN:-}
ENVEOF
echo "Wrote .env"
echo ""

# -----------------------------------------------
# 7. Build and run
# -----------------------------------------------
echo "Building Docker image (this takes a few minutes on first run)..."
docker compose build

echo ""
echo "========================================="
echo "  Starting Purchase Repair Tool"
echo "========================================="
echo ""
echo "  URL:       http://localhost:8090"
echo "  Snowflake: $SNOWFLAKE_USER (SSO browser login on first query)"
echo "  Sync:      git@github.com:sunbit-dev/account-management-service.git"
echo "  Data:      Persisted in Docker volume 'repair-cache'"
echo ""
echo "  Stop with: docker compose down"
echo "  Logs with: docker compose logs -f"
echo ""

docker compose up
