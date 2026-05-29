#!/usr/bin/env bash
#
# Kill any running PredictionsMarket server and start a fresh one.
#
# Usage:
#   ./run.sh              # web UI on http://localhost:8080
#   ./run.sh console      # one-shot console report instead of the web UI
#   PORT=9000 ./run.sh    # use a different port for the web UI
#
# Kalshi WebSocket credentials (optional) are inherited from the environment:
#   KALSHI_API_KEY_ID, KALSHI_PRIVATE_KEY_PATH
# Without them the app falls back to 2s REST polling.

set -euo pipefail

# Always operate from the project directory (where this script lives).
cd "$(dirname "$0")"

PORT="${PORT:-8080}"

echo "==> Stopping anything on port ${PORT} ..."
PIDS="$(lsof -ti "tcp:${PORT}" -sTCP:LISTEN 2>/dev/null || true)"
if [ -n "${PIDS}" ]; then
  echo "    killing PID(s): ${PIDS}"
  # shellcheck disable=SC2086
  kill -9 ${PIDS} 2>/dev/null || true
fi

# Also clear any stray app processes that may not be holding the port yet.
pkill -9 -f "com.kg.predictions.Main" 2>/dev/null || true
pkill -9 -f "ExecJavaMojo" 2>/dev/null || true

# Wait for the port to actually free up.
for _ in $(seq 1 10); do
  lsof -ti "tcp:${PORT}" -sTCP:LISTEN >/dev/null 2>&1 || break
  sleep 0.5
done

echo "==> Building ..."
mvn -q compile

echo "==> Starting (port ${PORT}) ..."
if [ "$#" -gt 0 ]; then
  # Pass through args (e.g. "console") to the program.
  exec mvn -q exec:java -Dexec.mainClass=com.kg.predictions.Main -Dexec.args="$*"
else
  exec mvn -q exec:java -Dexec.mainClass=com.kg.predictions.Main
fi
