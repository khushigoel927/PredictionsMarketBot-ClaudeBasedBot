#!/usr/bin/env bash
#
# Run BOTH Kalshi environments at once, each in its own server:
#   - production on http://localhost:8080
#   - demo       on http://localhost:8081
# Open both in separate browser tabs. Ctrl+C stops both.
#
# Credentials are optional (each instance falls back to 2s REST polling without
# them). Provide a key per environment via these env vars:
#   Production: KALSHI_PROD_API_KEY_ID, KALSHI_PROD_PRIVATE_KEY_PATH
#               (falls back to KALSHI_API_KEY_ID / KALSHI_PRIVATE_KEY_PATH)
#   Demo:       KALSHI_DEMO_API_KEY_ID, KALSHI_DEMO_PRIVATE_KEY_PATH
#
# Ports can be overridden with PROD_PORT / DEMO_PORT.

set -euo pipefail
cd "$(dirname "$0")"

PROD_PORT="${PROD_PORT:-8080}"
DEMO_PORT="${DEMO_PORT:-8081}"

# Per-environment credentials (each may be empty -> that instance uses polling).
PROD_KEY_ID="${KALSHI_PROD_API_KEY_ID:-${KALSHI_API_KEY_ID:-}}"
PROD_KEY_PATH="${KALSHI_PROD_PRIVATE_KEY_PATH:-${KALSHI_PRIVATE_KEY_PATH:-}}"
DEMO_KEY_ID="${KALSHI_DEMO_API_KEY_ID:-}"
DEMO_KEY_PATH="${KALSHI_DEMO_PRIVATE_KEY_PATH:-}"

free_port() {
  local pids
  pids="$(lsof -ti "tcp:$1" -sTCP:LISTEN 2>/dev/null || true)"
  if [ -n "${pids}" ]; then
    # shellcheck disable=SC2086
    kill -9 ${pids} 2>/dev/null || true
  fi
}

echo "==> Stopping anything on ports ${PROD_PORT} and ${DEMO_PORT} ..."
free_port "${PROD_PORT}"
free_port "${DEMO_PORT}"
pkill -9 -f "ExecJavaMojo" 2>/dev/null || true
sleep 1

echo "==> Building ..."
mvn -q compile

PIDS=()
cleanup() {
  echo
  echo "==> Stopping both servers ..."
  for pid in "${PIDS[@]:-}"; do
    [ -n "${pid}" ] && kill "${pid}" 2>/dev/null || true
  done
  free_port "${PROD_PORT}"
  free_port "${DEMO_PORT}"
}
trap cleanup INT TERM EXIT

echo "==> Starting PRODUCTION on http://localhost:${PROD_PORT} ..."
env KALSHI_ENV=prod PORT="${PROD_PORT}" \
    KALSHI_API_KEY_ID="${PROD_KEY_ID}" KALSHI_PRIVATE_KEY_PATH="${PROD_KEY_PATH}" \
    mvn -q exec:java -Dexec.mainClass=com.kg.predictions.Main > /tmp/kalshi-prod.log 2>&1 &
PIDS+=("$!")

echo "==> Starting DEMO on http://localhost:${DEMO_PORT} ..."
env KALSHI_ENV=demo PORT="${DEMO_PORT}" \
    KALSHI_API_KEY_ID="${DEMO_KEY_ID}" KALSHI_PRIVATE_KEY_PATH="${DEMO_KEY_PATH}" \
    mvn -q exec:java -Dexec.mainClass=com.kg.predictions.Main > /tmp/kalshi-demo.log 2>&1 &
PIDS+=("$!")

echo
echo "Production : http://localhost:${PROD_PORT}   (log: /tmp/kalshi-prod.log)"
echo "Demo       : http://localhost:${DEMO_PORT}   (log: /tmp/kalshi-demo.log)"
echo "Press Ctrl+C to stop both."
wait
