# Search Engine SOA - loads the fixed corpus into a running system (PowerShell / Windows).
#
# Usage, from the repository root, after .\scripts\start-all.ps1:
#   .\scripts\load-corpus.ps1                    # the 20 books of samples\books.txt
#   .\scripts\load-corpus.ps1 path\to\books.txt  # another list, one Gutenberg id per line
#
# Sends POST /ingest/{id} for every book (INGESTION_PORT, default 7001) and waits until the Indexing
# Service reports them indexed (INDEXING_PORT, default 7002). Books are downloaded from Project
# Gutenberg, or from GUTENBERG_BASE_URL if Ingestion was started with it.

param([string]$BooksFile = "samples\books.txt")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..")

$ingestion = "http://localhost:" + $(if ($env:INGESTION_PORT) { $env:INGESTION_PORT } else { 7001 })
$indexing = "http://localhost:" + $(if ($env:INDEXING_PORT) { $env:INDEXING_PORT } else { 7002 })
$ids = Get-Content $BooksFile | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith("#") }

Write-Host "Ingesting $($ids.Count) books through $ingestion"
$failed = 0
foreach ($id in $ids) {
    try {
        $result = Invoke-RestMethod -Method Post -Uri "$ingestion/ingest/$id" -TimeoutSec 120
        Write-Host ("  {0,-6} {1}" -f $id, $result.status)
    } catch {
        $failed++
        Write-Host ("  {0,-6} FAILED: {1}" -f $id, $_.ErrorDetails.Message)
    }
}

$expected = $ids.Count - $failed
Write-Host "Waiting until $expected books are indexed ($indexing/index/status)"
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Milliseconds 500
    try { $indexed = (Invoke-RestMethod -Uri "$indexing/index/status" -TimeoutSec 5).books_indexed } catch { $indexed = 0 }
    if ((Get-Date) -gt $deadline) { throw "Only $indexed of $expected books were indexed after 5 minutes" }
} while ($indexed -lt $expected)
$search = "http://localhost:" + $(if ($env:SEARCH_PORT) { $env:SEARCH_PORT } else { 7003 })
Write-Host "Done: $indexed books indexed, $failed failed. Try: Invoke-RestMethod '$search/search?q=monster'"
if ($failed -gt 0) { exit 1 }
