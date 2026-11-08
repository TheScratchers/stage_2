# Search Engine SOA - starts the whole system on this machine (PowerShell / Windows).
#
# Usage, from the repository root, after `mvn clean verify`:
#   .\scripts\start-all.ps1
#   $env:SEARCH_REPLICAS = 2; $env:INDEXING_REPLICAS = 2; .\scripts\start-all.ps1
#
# Settings (environment variables, all optional):
#   DATA_DIR           data directory of every service       (default .\data)
#   BROKER_PORT        port of the ActiveMQ broker           (default 61616)
#   INGESTION_PORT     port of Ingestion                     (default 7001)
#   INDEXING_PORT      port of the first Indexing replica    (default 7002; next ones +10: 7012, 7022, ...)
#   SEARCH_PORT        port of the first Search replica      (default 7003; next ones +10: 7013, 7023, ...)
#   INDEXING_REPLICAS  number of Indexing processes          (default 1)
#   SEARCH_REPLICAS    number of Search processes            (default 1)
#   INDEXING_WORKERS   consumer threads per Indexing process (default 2)
#   JAVA_OPTS          JVM options of every service          (default -Xmx1g)
#
# Each process writes its output to DATA_DIR\logs\<name>.log and its PID to DATA_DIR\run\<name>.pid.
# Stop everything with .\scripts\stop-all.ps1.

$ErrorActionPreference = "Stop"
Set-Location (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..")

function Setting($name, $default) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $default } else { return $value }
}

$DataDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath((Setting "DATA_DIR" "data"))
$BrokerPort = [int](Setting "BROKER_PORT" 61616)
$IngestionPort = [int](Setting "INGESTION_PORT" 7001)
$IndexingPort = [int](Setting "INDEXING_PORT" 7002)
$SearchPort = [int](Setting "SEARCH_PORT" 7003)
$IndexingReplicas = [int](Setting "INDEXING_REPLICAS" 1)
$SearchReplicas = [int](Setting "SEARCH_REPLICAS" 1)
$Workers = [int](Setting "INDEXING_WORKERS" 2)
$JavaOpts = (Setting "JAVA_OPTS" "-Xmx1g") -split '\s+' | Where-Object { $_ }
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }

$LogDir = Join-Path $DataDir "logs"
$RunDir = Join-Path $DataDir "run"
New-Item -ItemType Directory -Force -Path $LogDir, $RunDir | Out-Null
if (Get-ChildItem -Path $RunDir -Filter *.pid -ErrorAction SilentlyContinue) {
    throw "Services seem to be running already ($RunDir has PID files). Run .\scripts\stop-all.ps1 first."
}
foreach ($module in "broker", "ingestion-service", "indexing-service", "search-service") {
    if (-not (Test-Path "$module\target\$module.jar")) { throw "Missing $module\target\$module.jar: run mvn clean verify first." }
}

# Starts one service with its own environment variables. Start-Process passes the environment of
# this session, so the variables are set just for the launch and then restored.
function Start-EngineProcess($name, $module, $vars) {
    $vars = @{ DATA_DIR = $DataDir; BROKER_URL = "tcp://localhost:$BrokerPort" } + $vars
    $saved = @{}
    foreach ($key in $vars.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key)
        [Environment]::SetEnvironmentVariable($key, [string]$vars[$key])
    }
    try {
        $log = Join-Path $LogDir "$name.log"
        $arguments = @($JavaOpts) + @("-jar", "$module\target\$module.jar")
        $process = Start-Process -FilePath $Java -ArgumentList $arguments -NoNewWindow -PassThru `
            -RedirectStandardOutput $log -RedirectStandardError (Join-Path $LogDir "$name.err.log")
    } finally {
        foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key]) }
    }
    Set-Content -Path (Join-Path $RunDir "$name.pid") -Value $process.Id -Encoding ascii
    Write-Host ("  started {0,-12} pid {1}" -f $name, $process.Id)
}

function Wait-Until($what, $condition) {
    $deadline = (Get-Date).AddSeconds(60)
    while (-not (& $condition)) {
        if ((Get-Date) -gt $deadline) { throw "Timed out waiting for $what; see $LogDir" }
        Start-Sleep -Milliseconds 300
    }
}

function Test-Port($port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect("localhost", $port); return $true } catch { return $false } finally { $client.Close() }
}

function Test-Health($url) {
    try { return (Invoke-RestMethod -Uri "$url/health" -TimeoutSec 2).status -eq "UP" } catch { return $false }
}

Write-Host "Starting the search engine (DATA_DIR = $DataDir)"
Start-EngineProcess "broker" "broker" @{}
Wait-Until "the broker on port $BrokerPort" { Test-Port $BrokerPort }

$urls = @()
for ($i = 0; $i -lt $IndexingReplicas; $i++) {
    $port = $IndexingPort + 10 * $i
    Start-EngineProcess ("indexing-" + ($i + 1)) "indexing-service" @{ INDEXING_PORT = $port; INDEXING_WORKERS = $Workers }
    $urls += "http://localhost:$port"
}
for ($i = 0; $i -lt $SearchReplicas; $i++) {
    $port = $SearchPort + 10 * $i
    Start-EngineProcess ("search-" + ($i + 1)) "search-service" @{ SEARCH_PORT = $port }
    $urls += "http://localhost:$port"
}
Start-EngineProcess "ingestion" "ingestion-service" @{ INGESTION_PORT = $IngestionPort }
$urls += "http://localhost:$IngestionPort"

foreach ($url in $urls) { Wait-Until "$url/health" { Test-Health $url } }
Write-Host "All services are up:"
foreach ($url in $urls) { Write-Host "  $url" }
Write-Host "Load the corpus with .\scripts\load-corpus.ps1; stop everything with .\scripts\stop-all.ps1"
