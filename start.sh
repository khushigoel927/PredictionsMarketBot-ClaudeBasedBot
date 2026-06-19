#!/usr/bin/env bash
#
# Convenience launcher: load Kalshi API credentials from kalshi-env.sh (if present)
# and start both the production (:8080) and demo (:8081) servers.
#
#   ./start.sh            # run both environments with credentials
#
# Credentials live in kalshi-env.sh (gitignored). If that file is missing the
# servers still start, just without auth (balance/live-prices fall back).

set -euo pipefail
cd "$(dirname "$0")"

if [ -f kalshi-env.sh ]; then
  echo "==> Loading credentials from kalshi-env.sh"
  # shellcheck disable=SC1091
  source kalshi-env.sh
else
  echo "==> kalshi-env.sh not found — starting without API credentials"
fi

exec ./run-both.sh
