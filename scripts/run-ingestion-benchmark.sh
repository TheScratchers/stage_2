#!/usr/bin/env bash
set -euo pipefail

# Search Engine SOA - Ingestion Service Benchmark Runner
# Usage:
#   ./scripts/run-ingestion-benchmark.sh           # Official run: 1, 2, 4 clients, 3 repeats
#   ./scripts/run-ingestion-benchmark.sh --quick   # Fast verification run (1 repeat)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "Compiling benchmark test harness..."
mvn -pl ingestion-service test-compile -q

echo "Starting Ingestion Benchmark..."
mvn -pl ingestion-service exec:java \
  -Dexec.mainClass=com.thescratchers.searchengine.ingestion.benchmark.IngestionBenchmark \
  -Dexec.classpathScope=test \
  -Dexec.args="$*"
