#!/usr/bin/env bash
# Search Engine SOA - stops every process started by start-all.sh (bash / Linux / macOS).
#
# Usage, from the repository root:  ./scripts/stop-all.sh
# Uses the PID files in DATA_DIR/run (default ./data/run). Services are stopped in reverse order of
# start (Ingestion and Search first, then Indexing, the broker last).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

RUN_DIR="${DATA_DIR:-data}/run"
if ! ls "$RUN_DIR"/*.pid >/dev/null 2>&1; then
    echo "Nothing to stop: no PID files in $RUN_DIR"
    exit 0
fi

stop() {
    local file="$1" name pid
    name="$(basename "$file" .pid)"
    pid="$(head -n 1 "$file")"
    if kill "$pid" 2>/dev/null; then
        for _ in $(seq 1 50); do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
        kill -9 "$pid" 2>/dev/null || true
        printf '  stopped %-12s pid %s\n' "$name" "$pid"
    else
        printf '  %-12s pid %s was not running\n' "$name" "$pid"
    fi
    rm -f "$file"
}

for file in "$RUN_DIR"/ingestion.pid "$RUN_DIR"/search-*.pid "$RUN_DIR"/indexing-*.pid "$RUN_DIR"/broker.pid; do
    [ -f "$file" ] && stop "$file"
done
echo "All services stopped. The data in ${DATA_DIR:-data} is kept."
