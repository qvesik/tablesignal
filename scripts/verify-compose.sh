#!/bin/sh
# Isolated, destructive acceptance check for synthetic data only.
set -eu
cd "$(dirname "$0")/.."
command -v docker >/dev/null 2>&1 || { echo "Docker is required for container acceptance."; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "Python 3 is required for HTTP acceptance checks."; exit 1; }
docker compose version >/dev/null
export COMPOSE_PROJECT_NAME="${TABLESIGNAL_CHECK_PROJECT:-tablesignal-acceptance}"
export TABLESIGNAL_HTTP_PORT="${TABLESIGNAL_CHECK_HTTP_PORT:-18080}"
export TABLESIGNAL_DB_PORT="${TABLESIGNAL_CHECK_DB_PORT:-15439}"
export TABLESIGNAL_BASE_URL="http://127.0.0.1:${TABLESIGNAL_HTTP_PORT}"
if [ "${TABLESIGNAL_CHECK_KEEP_RUNNING:-0}" != 1 ]; then
  trap 'docker compose down' EXIT INT TERM
fi
echo "Starting isolated Compose acceptance project: $COMPOSE_PROJECT_NAME"
echo "This replaces synthetic data only in that project's database. Volumes are preserved."
docker compose up -d --build
python3 scripts/verify_compose_http.py
