#!/bin/bash
# Purchase Repair Tool - One-line installer
# Usage: curl -sL <url> | bash
# Or:    bash install.sh

set -e

echo "========================================="
echo "  Purchase Repair Tool - Install"
echo "========================================="
echo ""

# Check Docker
if ! command -v docker &> /dev/null; then
  echo "ERROR: Docker not found. Install Docker Desktop first."
  exit 1
fi
if ! docker info > /dev/null 2>&1; then
  echo "ERROR: Docker daemon not running. Start Docker Desktop."
  exit 1
fi
echo "Docker: OK"

# Get Snowflake user
if [ -z "$SNOWFLAKE_USER" ]; then
  read -p "Snowflake username (your-name@sunbit.com): " SNOWFLAKE_USER
  if [ -z "$SNOWFLAKE_USER" ]; then
    echo "ERROR: Snowflake user is required."
    exit 1
  fi
fi
echo "User: $SNOWFLAKE_USER"
echo ""

# -----------------------------------------------
# 1b. Snowflake token (authenticate on host for Docker)
# -----------------------------------------------
if [ ! -f "$HOME/.sunbit/snowflake_token" ]; then
  echo "Snowflake SSO requires a browser. Authenticating on your machine first..."
  echo "(This is a one-time step. Tokens last ~4 hours.)"
  echo ""

  VENV_DIR="$HOME/.sunbit/repair-venv"
  if [ ! -d "$VENV_DIR" ]; then
    python3 -m venv "$VENV_DIR"
  fi
  source "$VENV_DIR/bin/activate"

  if ! python3 -c "import snowflake.connector" 2>/dev/null; then
    echo "Installing snowflake-connector-python..."
    pip install --quiet snowflake-connector-python keyring 2>/dev/null
  fi

  if python3 -c "import snowflake.connector" 2>/dev/null; then
    python3 << PYEOF
import snowflake.connector, os
conn = snowflake.connector.connect(
    account='WXA20498.us-west-2.privatelink',
    user='$SNOWFLAKE_USER',
    authenticator='externalbrowser',
    warehouse='DATA_ENG_WH',
    role='DBT_DEV_ROLE',
    database='BRONZE',
)
token = conn.rest._master_token
cache_dir = os.path.expanduser('~/.sunbit')
os.makedirs(cache_dir, exist_ok=True)
with open(os.path.join(cache_dir, 'snowflake_token'), 'w') as f:
    f.write(token)
os.chmod(os.path.join(cache_dir, 'snowflake_token'), 0o600)
conn.close()
print("Snowflake: authenticated, token cached")
PYEOF
  else
    echo "WARNING: Could not install snowflake-connector-python."
    echo "         Snowflake queries will use SSO from Docker (may not work)."
    echo "         Run 'bash snowflake-auth.sh' later to fix."
    read -p "Press ENTER to continue..." _
  fi
else
  echo "Snowflake: token cached (use 'bash snowflake-auth.sh' to refresh)"
fi
echo ""

# Check SSH keys
if [ -d "$HOME/.ssh" ] && ls "$HOME/.ssh"/*.pub > /dev/null 2>&1; then
  echo "SSH keys: found"
else
  echo "WARNING: No SSH keys in ~/.ssh/. Git sync will not work."
  echo "You can still use the tool without sync."
  read -p "Press ENTER to continue..." _
fi

# Pull image
echo ""
echo "Pulling image (this may take a minute on first run)..."
docker pull sunbit/arnon-temp:purchase-repair-tool

# Stop any existing container
docker stop repair-tool 2>/dev/null || true
docker rm repair-tool 2>/dev/null || true

# Create persistent volume directory
mkdir -p "$HOME/.sunbit"

# Run
echo ""
echo "Starting..."
docker run -d \
  --name repair-tool \
  --restart unless-stopped \
  -p 8090:8090 \
  -p 8099:8099 \
  -e SNOWFLAKE_USER="$SNOWFLAKE_USER" \
  -e SYNC_ENABLED=true \
  -e SYNC_REPO_URL=git@github.com:sunbit-dev/account-management-service.git \
  -e SYNC_BRANCH=purchase-repair-sync \
  -e SYNC_FOLDER=repair-sync \
  -e SYNC_LOCAL_PATH=/data/sync \
  -v "$HOME/.ssh:/root/.ssh-host:ro" \
  -v "$HOME/.sunbit:/root/.sunbit" \
  -v "$HOME/.sunbit/snowflake_cache:/root/.cache/snowflake" \
  --add-host=host.docker.internal:host-gateway \
  --add-host=sunbit-mysql:host-gateway \
  sunbit/arnon-temp:purchase-repair-tool > /dev/null

# Wait for startup
echo "Waiting for startup..."
for i in $(seq 1 30); do
  if curl -s http://localhost:8090/api/v1/rules > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

if curl -s http://localhost:8090/api/v1/rules > /dev/null 2>&1; then
  RULES=$(curl -s http://localhost:8090/api/v1/rules | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "?")
  LISTS=$(curl -s http://localhost:8090/api/v1/lists | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "?")
  echo ""
  echo "========================================="
  echo "  Purchase Repair Tool is running"
  echo "========================================="
  echo ""
  echo "  URL:    http://localhost:8090"
  echo "  Rules:  $RULES"
  echo "  Lists:  $LISTS"
  echo ""
  echo "  Stop:   docker stop repair-tool"
  echo "  Start:  docker start repair-tool"
  echo "  Logs:   docker logs -f repair-tool"
  echo "  Update: docker pull sunbit/arnon-temp:purchase-repair-tool && docker stop repair-tool && docker rm repair-tool && bash install.sh"
  echo ""
else
  echo ""
  echo "Container started but not responding yet."
  echo "Check logs with: docker logs -f repair-tool"
  echo "URL will be: http://localhost:8090"
fi
