#!/bin/sh
# Launch the local application and persistent PostgreSQL database.
set -eu
cd "$(dirname "$0")"
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker with Compose v2 is required for this launcher."
  echo "See README.md for an IDE/local-PostgreSQL alternative."
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required."
  exit 1
fi
echo "TableSignal will be available at http://127.0.0.1:8080 after startup."
echo "Press Ctrl+C to stop. The database volume is preserved."
exec docker compose up --build
