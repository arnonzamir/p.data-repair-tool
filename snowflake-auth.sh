#!/bin/bash
# One-time Snowflake SSO authentication helper.
# Run this ON YOUR HOST (not in Docker) to cache a Snowflake token.
# The Docker container reads the cached token automatically.
#
# Usage: bash snowflake-auth.sh [your-name@sunbit.com]
# Tokens last ~4 hours. Re-run when they expire.

set -e

USER="${1:-${SNOWFLAKE_USER}}"
if [ -z "$USER" ]; then
  read -p "Snowflake username (your-name@sunbit.com): " USER
fi

if [ -z "$USER" ]; then
  echo "ERROR: Snowflake user required."
  exit 1
fi

# Check for Python snowflake connector
if ! python3 -c "import snowflake.connector" 2>/dev/null; then
  echo "Installing snowflake-connector-python (one-time)..."
  pip3 install --quiet snowflake-connector-python
fi

echo "Authenticating $USER to Snowflake..."
echo "A browser window will open for Okta login."
echo ""

python3 << PYEOF
import snowflake.connector
import json, os

conn = snowflake.connector.connect(
    account='WXA20498.us-west-2.privatelink',
    user='$USER',
    authenticator='externalbrowser',
    warehouse='DATA_ENG_WH',
    role='DBT_DEV_ROLE',
    database='BRONZE',
    client_store_temporary_credential=True,
)

# Get the ID token (what JDBC CLIENT_STORE_TEMPORARY_CREDENTIAL reads)
id_token = getattr(conn.rest, '_id_token', None) or conn.rest._token

# Write in the format JDBC expects: ~/.cache/snowflake/temporary_credential.json
# JDBC looks for: {"ACCOUNT:USER:AUTHENTICATOR": "id_token"}
# The account format varies, try the most common patterns
account_upper = 'WXA20498.US-WEST-2.PRIVATELINK'
user_upper = '$USER'.upper()

cred = {}
# JDBC may look up by different key formats
for acc in [account_upper, 'WXA20498']:
    for auth in ['EXTERNALBROWSER']:
        key = f"{acc}:{user_upper}:{auth}"
        cred[key] = id_token

# Write to ~/.cache/snowflake/ (JDBC default on Linux)
cache_dir = os.path.expanduser('~/.cache/snowflake')
os.makedirs(cache_dir, exist_ok=True)
cache_file = os.path.join(cache_dir, 'temporary_credential.json')
with open(cache_file, 'w') as f:
    json.dump(cred, f)
os.chmod(cache_file, 0o600)

# Also write to ~/.sunbit/ for the Docker mount
sunbit_dir = os.path.expanduser('~/.sunbit/snowflake_cache')
os.makedirs(sunbit_dir, exist_ok=True)
sunbit_file = os.path.join(sunbit_dir, 'temporary_credential.json')
with open(sunbit_file, 'w') as f:
    json.dump(cred, f)
os.chmod(sunbit_file, 0o600)

conn.close()
print("")
print("Authentication successful. Token cached.")
print(f"  Host cache:   {cache_file}")
print(f"  Docker cache: {sunbit_file}")
print("")
print("Tokens expire after ~4 hours. Re-run this script when needed.")
PYEOF
