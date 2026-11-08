#!/usr/bin/env bash
# Search Engine SOA - loads the fixed corpus into a running system (bash / Linux / macOS).
#
# Usage, from the repository root, after ./scripts/start-all.sh:
#   ./scripts/load-corpus.sh                     # the 20 books of samples/books.txt
#   ./scripts/load-corpus.sh path/to/books.txt   # another list, one Gutenberg id per line
#
# Sends POST /ingest/{id} for every book (INGESTION_PORT, default 7001) and waits until the Indexing
# Service reports them indexed (INDEXING_PORT, default 7002). Books are downloaded from Project
# Gutenberg, or from GUTENBERG_BASE_URL if Ingestion was started with it.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

BOOKS_FILE="${1:-samples/books.txt}"
INGESTION="http://localhost:${INGESTION_PORT:-7001}"
INDEXING="http://localhost:${INDEXING_PORT:-7002}"
ids=()
while IFS= read -r line || [ -n "$line" ]; do
    ids+=("$line")
done < <(sed -e 's/\r$//' -e 's/^[[:space:]]*//;s/[[:space:]]*$//' "$BOOKS_FILE" | grep -v -e '^#' -e '^$')

echo "Ingesting ${#ids[@]} books through $INGESTION"
failed=0
for id in "${ids[@]}"; do
    if body="$(curl -fsS -X POST --max-time 120 "$INGESTION/ingest/$id" 2>&1)"; then
        printf '  %-6s %s\n' "$id" "$(echo "$body" | grep -o '"status":"[a-z_]*"' | cut -d'"' -f4)"
    else
        failed=$((failed + 1))
        printf '  %-6s FAILED: %s\n' "$id" "$body"
    fi
done

expected=$((${#ids[@]} - failed))
echo "Waiting until $expected books are indexed ($INDEXING/index/status)"
indexed=0
for _ in $(seq 1 600); do
    indexed="$(curl -fsS "$INDEXING/index/status" 2>/dev/null | grep -o '"books_indexed":[0-9]*' | cut -d: -f2 || true)"
    [ "${indexed:-0}" -ge "$expected" ] && break
    sleep 0.5
done
if [ "${indexed:-0}" -lt "$expected" ]; then
    echo "Only ${indexed:-0} of $expected books were indexed after 5 minutes" >&2
    exit 1
fi
echo "Done: $indexed books indexed, $failed failed. Try: curl 'http://localhost:${SEARCH_PORT:-7003}/search?q=monster'"
[ "$failed" -eq 0 ]
