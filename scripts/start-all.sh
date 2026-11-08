#!/usr/bin/env bash
# Search Engine SOA - starts the whole system on this machine (bash / Linux / macOS).
#
# Usage, from the repository root, after `mvn clean verify`:
#   ./scripts/start-all.sh
#   SEARCH_REPLICAS=2 INDEXING_REPLICAS=2 ./scripts/start-all.sh
#
# Settings (environment variables, all optional):
#   DATA_DIR           data directory of every service       (default ./data)
#   BROKER_PORT        port of the ActiveMQ broker           (default 61616)
#   INGESTION_PORT     port of Ingestion                     (default 7001)
#   INDEXING_PORT      port of the first Indexing replica    (default 7002; next ones +10: 7012, 7022, ...)
#   SEARCH_PORT        port of the first Search replica      (default 7003; next ones +10: 7013, 7023, ...)
#   INDEXING_REPLICAS  number of Indexing processes          (default 1)
#   SEARCH_REPLICAS    number of Search processes            (default 1)
#   INDEXING_WORKERS   consumer threads per Indexing process (default 2)
#   JAVA_OPTS          JVM options of every service          (default -Xmx1g)
#
# Each process writes its output to DATA_DIR/logs/<name>.log and its PID to DATA_DIR/run/<name>.pid.
# Stop everything with ./scripts/stop-all.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

DATA_DIR="$(mkdir -p "${DATA_DIR:-data}" && cd "${DATA_DIR:-data}" && pwd)"
BROKER_PORT="${BROKER_PORT:-61616}"
INGESTION_PORT="${INGESTION_PORT:-7001}"
INDEXING_PORT="${INDEXING_PORT:-7002}"
SEARCH_PORT="${SEARCH_PORT:-7003}"
INDEXING_REPLICAS="${INDEXING_REPLICAS:-1}"
SEARCH_REPLICAS="${SEARCH_REPLICAS:-1}"
INDEXING_WORKERS="${INDEXING_WORKERS:-2}"
JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
LOG_DIR="$DATA_DIR/logs"
RUN_DIR="$DATA_DIR/run"
mkdir -p "$LOG_DIR" "$RUN_DIR"

if ls "$RUN_DIR"/*.pid >/dev/null 2>&1; then
    echo "Services seem to be running already ($RUN_DIR has PID files). Run ./scripts/stop-all.sh first." >&2
    exit 1
fi
for module in broker ingestion-service indexing-service search-service; do
    [ -f "$module/target/$module.jar" ] || { echo "Missing $module/target/$module.jar: run mvn clean verify first." >&2; exit 1; }
done

# start_process NAME MODULE [VAR=value ...]
start_process() {
    local name="$1" module="$2"
    shift 2
    # shellcheck disable=SC2086  # JAVA_OPTS is a list of options
    env DATA_DIR="$DATA_DIR" BROKER_URL="tcp://localhost:$BROKER_PORT" "$@" \
        nohup "$JAVA" $JAVA_OPTS -jar "$module/target/$module.jar" > "$LOG_DIR/$name.log" 2>&1 &
    echo $! > "$RUN_DIR/$name.pid"
    printf '  started %-12s pid %s\n' "$name" "$!"
}

# wait_until DESCRIPTION COMMAND...
wait_until() {
    local what="$1"
    shift
    for _ in $(seq 1 200); do
        if "$@" >/dev/null 2>&1; then return 0; fi
        sleep 0.3
    done
    echo "Timed out waiting for $what; see $LOG_DIR" >&2
    exit 1
}

port_open() { (exec 3<>"/dev/tcp/localhost/$1") 2>/dev/null; }
healthy() { curl -fsS "$1/health" | grep -q '"UP"'; }

echo "Starting the search engine (DATA_DIR = $DATA_DIR)"
start_process broker broker
wait_until "the broker on port $BROKER_PORT" port_open "$BROKER_PORT"

urls=()
for ((i = 0; i < INDEXING_REPLICAS; i++)); do
    port=$((INDEXING_PORT + 10 * i))
    start_process "indexing-$((i + 1))" indexing-service INDEXING_PORT="$port" INDEXING_WORKERS="$INDEXING_WORKERS"
    urls+=("http://localhost:$port")
done
for ((i = 0; i < SEARCH_REPLICAS; i++)); do
    port=$((SEARCH_PORT + 10 * i))
    start_process "search-$((i + 1))" search-service SEARCH_PORT="$port"
    urls+=("http://localhost:$port")
done
start_process ingestion ingestion-service INGESTION_PORT="$INGESTION_PORT"
urls+=("http://localhost:$INGESTION_PORT")

for url in "${urls[@]}"; do wait_until "$url/health" healthy "$url"; done
echo "All services are up:"
printf '  %s\n' "${urls[@]}"
echo "Load the corpus with ./scripts/load-corpus.sh; stop everything with ./scripts/stop-all.sh"
