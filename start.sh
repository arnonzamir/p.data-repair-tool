#!/bin/bash
# Purchase Repair Tool - Start
# Downloads and runs everything with one command.
# Usage: curl -sH "Authorization: token $(gh auth token)" https://raw.githubusercontent.com/sunbit-dev/account-management-service/purchase-repair-sync/repair-tool/start.sh | bash

set -e

echo "========================================="
echo "  Purchase Repair Tool"
echo "========================================="
echo ""

# Check Docker
if ! docker info > /dev/null 2>&1; then
  echo "ERROR: Docker not running. Start Docker Desktop first."
  exit 1
fi

# Check Python
if ! python3 -c "pass" 2>/dev/null; then
  echo "ERROR: Python 3 not found."
  exit 1
fi

# Set up virtual environment for snowflake connector
VENV_DIR="$HOME/.sunbit/repair-venv"
if [ ! -d "$VENV_DIR" ]; then
  echo "Creating virtual environment (one-time)..."
  python3 -m venv "$VENV_DIR"
fi
source "$VENV_DIR/bin/activate"

# Install snowflake connector if needed
if ! python3 -c "import snowflake.connector" 2>/dev/null; then
  echo "Installing snowflake-connector-python (one-time)..."
  pip install --quiet snowflake-connector-python keyring
fi

# Get Snowflake user
SNOWFLAKE_USER="${SNOWFLAKE_USER:-}"
if [ -z "$SNOWFLAKE_USER" ]; then
  read -p "Snowflake username (your-name@sunbit.com): " SNOWFLAKE_USER
fi
if [ -z "$SNOWFLAKE_USER" ]; then
  echo "ERROR: Snowflake user is required."
  exit 1
fi

# Pull Docker image
echo ""
echo "Pulling latest image..."
docker pull sunbit/arnon-temp:purchase-repair-tool

# Stop any existing instance
docker stop repair-tool 2>/dev/null || true
docker rm repair-tool 2>/dev/null || true

# Kill any existing proxy
lsof -ti :8099 | xargs kill 2>/dev/null || true

# Download proxy script to a temp location
PROXY_SCRIPT="$HOME/.sunbit/snowflake-proxy.py"
mkdir -p "$HOME/.sunbit"
if command -v gh &> /dev/null; then
  curl -sH "Authorization: token $(gh auth token)" \
    "https://raw.githubusercontent.com/sunbit-dev/account-management-service/purchase-repair-sync/repair-tool/snowflake-proxy.py" \
    -o "$PROXY_SCRIPT"
elif [ -f "snowflake-proxy.py" ]; then
  cp snowflake-proxy.py "$PROXY_SCRIPT"
else
  echo "ERROR: Cannot download proxy script. Install gh CLI or run from the repo directory."
  exit 1
fi

# Start proxy in background
echo ""
echo "Starting Snowflake proxy (browser will open for Okta login)..."
python3 "$PROXY_SCRIPT" --user "$SNOWFLAKE_USER" --port 8099 &
PROXY_PID=$!

# Wait for proxy to be ready
echo "Waiting for Snowflake authentication..."
for i in $(seq 1 120); do
  if curl -s http://localhost:8099/health > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -s http://localhost:8099/health > /dev/null 2>&1; then
  echo "ERROR: Snowflake proxy did not start. Check browser for Okta login."
  exit 1
fi
echo "Snowflake: connected"

# Start Docker
echo ""
echo "Starting repair tool..."
mkdir -p "$HOME/.sunbit"
docker run -d --name repair-tool --restart unless-stopped \
  -p 8090:8090 \
  -e SNOWFLAKE_PROXY_URL=http://host.docker.internal:8099 \
  -e SYNC_ENABLED=true \
  -e SYNC_REPO_URL=git@github.com:sunbit-dev/account-management-service.git \
  -e SYNC_BRANCH=purchase-repair-sync \
  -e SYNC_FOLDER=repair-sync \
  -e SYNC_LOCAL_PATH=/data/sync \
  -v "$HOME/.ssh:/root/.ssh-host:ro" \
  -v "$HOME/.sunbit:/root/.sunbit" \
  --add-host=host.docker.internal:host-gateway \
  --add-host=sunbit-mysql:host-gateway \
  sunbit/arnon-temp:purchase-repair-tool > /dev/null

# Wait for app
for i in $(seq 1 30); do
  if curl -s http://localhost:8090/api/v1/rules > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

echo ""
echo "========================================="
echo "  Ready at http://localhost:8090"
echo "========================================="
echo ""
echo "  Snowflake proxy PID: $PROXY_PID"
echo ""
echo "  Stop:    docker stop repair-tool && kill $PROXY_PID"
echo "  Restart: docker start repair-tool"
echo "  Logs:    docker logs -f repair-tool"
echo ""
echo "  Note: keep this terminal open (Snowflake proxy runs here)."
echo "  If you close it, Snowflake queries will fail until you restart."
echo ""

# Keep proxy in foreground so the terminal stays open
wait $PROXY_PID
