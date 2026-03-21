#!/bin/bash
# Entry point for the Purchase Repair Tool.
# First run: walks through setup. Subsequent runs: starts directly.

cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "First run detected. Starting setup..."
  echo ""
  exec ./setup.sh
fi

# .env exists, just start
source .env
echo "Starting Purchase Repair Tool for $SNOWFLAKE_USER..."
docker compose up
