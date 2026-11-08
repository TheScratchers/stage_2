# Search Engine SOA - stops every process started by start-all.ps1 (PowerShell / Windows).
#
# Usage, from the repository root:  .\scripts\stop-all.ps1
# Uses the PID files in DATA_DIR\run (default .\data\run). Services are stopped in reverse order of
# start (Ingestion and Search first, the broker last).

$ErrorActionPreference = "Stop"
Set-Location (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..")

$dataDir = if ([string]::IsNullOrWhiteSpace($env:DATA_DIR)) { "data" } else { $env:DATA_DIR }
$runDir = Join-Path ($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($dataDir)) "run"
$pidFiles = @(Get-ChildItem -Path $runDir -Filter *.pid -ErrorAction SilentlyContinue)
if ($pidFiles.Count -eq 0) {
    Write-Host "Nothing to stop: no PID files in $runDir"
    exit 0
}

$order = { if ($_.BaseName -eq "broker") { 2 } elseif ($_.BaseName -like "indexing-*") { 1 } else { 0 } }
foreach ($file in ($pidFiles | Sort-Object -Property $order)) {
    $processId = [int](Get-Content $file.FullName -TotalCount 1)
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $processId -Force
        Write-Host ("  stopped {0,-12} pid {1}" -f $file.BaseName, $processId)
    } else {
        Write-Host ("  {0,-12} pid {1} was not running" -f $file.BaseName, $processId)
    }
    Remove-Item $file.FullName
}
Write-Host "All services stopped. The data in $dataDir is kept."
