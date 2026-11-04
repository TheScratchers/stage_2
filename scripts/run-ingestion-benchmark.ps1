# Search Engine SOA - Ingestion Service Benchmark Runner (PowerShell / Windows)
# Usage:
#   .\scripts\run-ingestion-benchmark.ps1           # Official run: 1, 2, 4 clients, 3 repeats
#   .\scripts\run-ingestion-benchmark.ps1 --quick   # Fast verification run (1 repeat)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location (Join-Path $ScriptDir "..")

Write-Host "Compiling benchmark test harness..."
mvn -pl ingestion-service test-compile -q

Write-Host "Starting Ingestion Benchmark..."
mvn -pl ingestion-service exec:java `
  -Dexec.mainClass=com.thescratchers.searchengine.ingestion.benchmark.IngestionBenchmark `
  -Dexec.classpathScope=test `
  -Dexec.args="$args"
