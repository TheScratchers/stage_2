# 7.z Ingestion Throughput Benchmarks

<!--
  All figures in this section are drawn directly from the official benchmark execution recorded in
  benchmarks/results/20261105_ingestion-throughput/ on the team reference machine at commit 04132ab.
-->

The Ingestion Benchmark evaluates the throughput, latency distribution, scalability, and resource footprint of the Ingestion Service under concurrent load. It tests both fresh data ingestion (retrieval from a local Gutenberg stand-in, text demarcation, atomic datalake storage, SQLite ledger logging, and event dispatch) and idempotent re-ingestion (lookup in the ingestion log and re-publication of the event).

---

## 7.z.1 Method and Experimental Setup

All experiments were executed using the official benchmark harness (`IngestionBenchmark.java`) triggered via `scripts/run-ingestion-benchmark.ps1` on the reference machine.

**Reference Environment:**

| Component | Specification |
|---|---|
| CPU | 13th Gen Intel Core i7-13700KF (16 cores, 24 logical processors) |
| RAM | 32 GB (2 × 16 GB) Kingston FURY Beast DDR5 5200 MHz CL40 |
| Storage | 1 TB NVMe M.2 SSD (Kioxia Exceria G2, PCIe 3.0 x4) |
| Operating System | Windows 11 Pro 64-bit (build 10.0, amd64) |
| JVM | Eclipse Adoptium Temurin 21.0.12.1+1 LTS (64-Bit Server VM) |
| Baseline Commit | `04132ab` |
| Execution Date | 2026-11-05 (official benchmark milestone H6) |

**Controlled Experimental Conditions:**
- **Fixed Corpus:** Ingestion operates against the canonical 20-book corpus defined in `samples/books.txt`.
- **Local Gutenberg Mirror:** Books are served directly from `samples/books/` by a local HTTP mock server. This guarantees that external internet latency or Gutenberg rate-limiting never pollutes the measurements.
- **Closed-Loop Concurrency:** The benchmark generator maintains a fixed number of concurrent client threads (1, 2, and 4 clients). Each thread submits a synchronous `POST /ingest` request, waits for the HTTP response, and immediately requests the next book without think time.
- **Fresh vs. Idempotent Passes:**
  - **Fresh Mode:** Starts with a clean datalake directory and an empty `ingestion.db`. All 20 books undergo complete downloading, text splitting, atomic file writes, SQLite insertion, and JMS message dispatch.
  - **Idempotent Mode:** Immediately re-ingests the same 20 books into the now-populated system. It exercises the fast path: the ingestion log is checked, nothing is downloaded or written, and the `BookDownloaded` event is published again.
- **In-process deployment:** Unlike the integration benchmarks of section 7.x, this harness runs the Ingestion Service (`IngestionApp.start`), the client threads and an embedded, **non-persistent** ActiveMQ broker (`vm://localhost`) inside one JVM. It therefore measures the service's own work without the cost of persisting events to KahaDB; that cost is part of the coordination measurements of section 7.x.
- **Repetitions:** Each concurrency level is measured in 3 consecutive runs. There is no separate warm-up phase, so the first run includes JVM class loading and JIT compilation; the warm-up effect is visible in run 1 with one client and is excluded from the means of Table 7.z.2.

---

## 7.z.2 Benchmark Results

*Table 7.z.1. Ingestion throughput and request latencies for 1, 2, and 4 concurrent clients across 3 runs. Source: `benchmarks/results/20261105_ingestion-throughput/summary.csv`.*

| Clients | Run | Mode | Books | Errors | Duration (s) | Throughput (books/s) | Mean (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1 | fresh | 20 | 0 | 0.843 | 23.72 | 41.95 | 23.89 | 35.25 | 392.44 | 392.44 |
| 1 | 1 | idempotent | 20 | 0 | 0.194 | 102.89 | 9.66 | 8.18 | 10.07 | 32.95 | 32.95 |
| 1 | 2 | fresh | 20 | 0 | 0.454 | 44.03 | 22.66 | 20.35 | 37.06 | 39.09 | 39.09 |
| 1 | 2 | idempotent | 20 | 0 | 0.179 | 111.61 | 8.91 | 8.05 | 8.43 | 26.50 | 26.50 |
| 1 | 3 | fresh | 20 | 0 | 0.457 | 43.72 | 22.81 | 20.06 | 38.33 | 56.76 | 56.76 |
| 1 | 3 | idempotent | 20 | 0 | 0.165 | 121.55 | 8.18 | 7.05 | 8.50 | 25.41 | 25.41 |
| 2 | 1 | fresh | 20 | 0 | 0.267 | 74.93 | 25.50 | 24.38 | 37.93 | 39.12 | 39.12 |
| 2 | 1 | idempotent | 20 | 0 | 0.115 | 173.64 | 11.38 | 9.56 | 28.63 | 30.56 | 30.56 |
| 2 | 2 | fresh | 20 | 0 | 0.257 | 77.79 | 25.45 | 21.69 | 38.84 | 60.99 | 60.99 |
| 2 | 2 | idempotent | 20 | 0 | 0.120 | 166.21 | 11.87 | 9.80 | 30.01 | 32.30 | 32.30 |
| 2 | 3 | fresh | 20 | 0 | 0.286 | 69.95 | 27.84 | 25.26 | 40.01 | 44.67 | 44.67 |
| 2 | 3 | idempotent | 20 | 0 | 0.120 | 166.19 | 11.87 | 9.88 | 29.68 | 31.81 | 31.81 |
| 4 | 1 | fresh | 20 | 0 | 0.179 | 111.42 | 34.34 | 27.03 | 60.96 | 72.43 | 72.43 |
| 4 | 1 | idempotent | 20 | 0 | 0.115 | 173.22 | 22.09 | 14.71 | 37.49 | 39.90 | 39.90 |
| 4 | 2 | fresh | 20 | 0 | 0.176 | 113.88 | 33.75 | 28.33 | 61.34 | 63.47 | 63.47 |
| 4 | 2 | idempotent | 20 | 0 | 0.117 | 171.24 | 22.30 | 16.44 | 40.06 | 43.05 | 43.05 |
| 4 | 3 | fresh | 20 | 0 | 0.182 | 109.88 | 33.81 | 25.96 | 59.41 | 59.96 | 59.96 |
| 4 | 3 | idempotent | 20 | 0 | 0.115 | 174.37 | 22.05 | 14.38 | 40.08 | 42.16 | 42.16 |

*Table 7.z.2. Aggregated throughput and speed-up, computed from Table 7.z.1. Fresh mode: mean of runs 2 and 3 at every level, because run 1 includes the warm-up of the JVM. Idempotent mode: mean of the 3 runs, which run on a warm JVM. Speed-up is relative to 1 client.*

| Mode | 1 Client (books/s) | 2 Clients (books/s) | 4 Clients (books/s) | Speed-up 1 → 2 | Speed-up 1 → 4 |
|---|---:|---:|---:|---:|---:|
| **Fresh Ingestion** | 43.88 | 73.87 | 111.88 | **1.68×** | **2.55×** |
| **Idempotent Fast-Path** | 112.02 | 168.68 | 172.94 | **1.51×** | **1.54×** |

*Table 7.z.3. Resource use of the benchmark JVM (Ingestion Service, client threads and embedded broker together) over the whole run, sampled every 250 ms. CPU is `OperatingSystemMXBean.getProcessCpuLoad()`, a share of the whole machine (100 % = all 24 logical processors). Memory is the Java heap in use (`totalMemory - freeMemory`), not the resident memory of the process. Source: `benchmarks/results/20261105_ingestion-throughput/resources-summary.csv` (columns `mean_cpu_percent`, `max_cpu_percent`, `max_rss_mb`).*

| Process | Mean CPU (% of the machine) | Peak CPU (% of the machine) | Peak Java heap in use (MB) |
|---|---:|---:|---:|
| Benchmark JVM | 7.9 % | 28.2 % | 279.1 MB |

The resident memory of the deployed Ingestion process, measured from outside in the integration benchmarks, is about 293 MB (Table 7.x.4).

*Table 7.z.4. Storage footprint on disk generated by ingesting the 20-book corpus. Source: `benchmarks/results/20261105_ingestion-throughput/disk.csv`.*

| Storage Store | Path / Description | Size on Disk |
|---|---|---:|
| Datalake Text Artifacts | `DATA_DIR/datalake/yyyyMMdd/HH/*.txt` (40 files: 20 headers, 20 bodies) | 11,290,405 bytes (11.29 MB) |
| Ingestion SQLite Ledger | `DATA_DIR/datalake/ingestion.db` | 16,384 bytes (16.0 KB) |
| **Total Ingestion Storage** | — | **11.31 MB** |

---

## 7.z.3 Discussion of Findings

### 1. Fresh Ingestion Scales Concurrently
Fresh ingestion scales well with the number of clients: **1.68×** from 1 to 2 clients (43.9 → 73.9 books/s) and **2.55×** with 4 clients (111.9 books/s).
- **Why Ingestion scales where Indexing hits a ceiling:** In section 7.y, persisting the index stops improving after 2 threads, because every thread competes for the single write lock of `datamart.db`. Ingestion has no such shared hot spot: each book is fetched, split and written to its own files (`<book_id>.header.txt` and `<book_id>.body.txt`), so different books do not wait for each other.
- The only shared write is one `INSERT OR REPLACE` per book into `ingestion.db`, a single short statement serialized by SQLite. The near-linear scaling up to 4 clients indicates that it is not a bottleneck at this scale.

### 2. JIT Warm-Up and Latency Distribution
In Run 1 with 1 client, the maximum latency reached 392.4 ms due to one-time JVM classloading, Javalin/Jetty initialization, and initial JIT tier compilation. By Run 2, the pipeline is fully compiled:
- Median latency (`p50`) drops to **20.35 ms** for fresh ingestion.
- 95th percentile latency (`p95`) stays tightly bounded under **39 ms**.
- Even under 4 concurrent clients generating over 111 operations per second, median latency remains exceptionally low at **27.0 ms**.

### 3. Saturation of the Idempotent Fast-Path
When books are already available, the service skips fetching, splitting and writing: it looks the book up in `ingestion_log` and publishes the `BookDownloaded` event again.
- With 1 client, throughput rises to **112.0 books/s** (median latency about **7.8 ms**).
- With 2 clients, throughput reaches **168.7 books/s** (median latency about **9.7 ms**).
- With 4 clients, throughput stays at **172.9 books/s**, while the median latency grows to about **15 ms**: the extra clients mostly wait.
- **The ~173 books/s ceiling:** this benchmark does not record where the time of a fast-path request is spent, so the cause of the plateau cannot be identified from these data. Each request still makes an SQLite query and sends a JMS message, and the 4 client threads share one JVM with the service and the broker. Each run handles only 20 requests in about 0.12 s, so these figures are indicative rather than precise.

### 4. Zero Error Rate and Fault Resilience
Across all 18 benchmark executions (360 ingestion requests), **zero errors were recorded** (`errors = 0`), and every fresh run stored the complete corpus (11.29 MB in 40 files).

### 5. Resource Footprint
The whole benchmark JVM (service, clients and broker) used on average **7.9 %** of the machine, about 1.9 of its 24 logical processors, with a peak of **28.2 %** (about 6.8 processors). Its Java heap in use peaked at **279.1 MB**. The machine was therefore far from saturated: the limits observed above come from the work of each request, not from a lack of CPU.

### 6. Limits of the Evidence
- Each run ingests only the 20 corpus books and lasts between 0.12 and 0.84 s, so single runs are sensitive to scheduling noise; the three repetitions show this spread.
- The service, the clients and a non-persistent broker share one JVM, so the figures exclude the persistence of events in KahaDB and do not separate the CPU of the service from that of the clients.
- Books come from a local stand-in, so the real cost of downloading from Project Gutenberg over the Internet is not part of these figures.
