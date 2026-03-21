#!/bin/bash
# Snowflake SSO auth proxy for Docker.
# Called by the fake xdg-open when Snowflake tries to open a browser.
#
# 1. Extracts the random callback port from the auth URL
# 2. Rewrites the URL to use port 8099 (published to host)
# 3. Starts a TCP proxy from 8099 to the random port
# 4. Prints the rewritten URL so the user can open it on the host

AUTH_URL="$1"

# Extract the random port from the redirect URL parameter
# The URL looks like: https://...snowflake.../console/login?...&browser_mode_redirect_port=NNNNN...
RANDOM_PORT=$(echo "$AUTH_URL" | grep -oP 'browser_mode_redirect_port=\K[0-9]+' || echo "")

if [ -z "$RANDOM_PORT" ]; then
  # Try alternative format: redirectPort=NNNNN
  RANDOM_PORT=$(echo "$AUTH_URL" | grep -oP 'redirectPort=\K[0-9]+' || echo "")
fi

if [ -z "$RANDOM_PORT" ]; then
  # Try extracting from redirect_uri=http://localhost:PORT/
  RANDOM_PORT=$(echo "$AUTH_URL" | grep -oP 'redirect_uri=http%3A%2F%2Flocalhost%3A\K[0-9]+' || echo "")
fi

if [ -z "$RANDOM_PORT" ]; then
  # Can't find port, just print the URL as-is
  echo ""
  echo "============================================="
  echo "  SNOWFLAKE SSO LOGIN"
  echo "============================================="
  echo ""
  echo "  Open this URL in your browser:"
  echo ""
  echo "  $AUTH_URL"
  echo ""
  echo "  (Note: callback may not work from Docker)"
  echo "============================================="
  exit 0
fi

# Rewrite URL: replace random port with 8099
REWRITTEN_URL=$(echo "$AUTH_URL" | sed "s/$RANDOM_PORT/8099/g")

# Start TCP proxy: forward port 8099 to the random port
# Use socat if available, otherwise netcat
if command -v socat &> /dev/null; then
  socat TCP-LISTEN:8099,fork TCP:localhost:$RANDOM_PORT &
elif command -v ncat &> /dev/null; then
  ncat -l -p 8099 -c "ncat localhost $RANDOM_PORT" &
else
  # Fallback: simple shell proxy
  while true; do
    nc -l -p 8099 -c "nc localhost $RANDOM_PORT" 2>/dev/null || break
  done &
fi

PROXY_PID=$!

echo ""
echo "============================================="
echo "  SNOWFLAKE SSO LOGIN"
echo "============================================="
echo ""
echo "  Open this URL in your browser:"
echo ""
echo "  $REWRITTEN_URL"
echo ""
echo "  After login, Snowflake will connect automatically."
echo "============================================="
echo ""

# Keep proxy running for 2 minutes then clean up
(sleep 120 && kill $PROXY_PID 2>/dev/null) &
