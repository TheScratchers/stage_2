# 7.x Integration benchmarks

<!--
  Every figure of this section must come from the official run in benchmarks/results/ on the
  reference machine. Cells marked FILL are completed from the file named in the caption.
-->

The integration benchmarks measure the whole system as it is deployed on the Stage 2 host: four
kinds of process (broker, Ingestion, Indexing and Search), each in its own JVM, coordinating through
ActiveMQ and sharing the SQLite files on one disk. They complement the JMH microbenchmarks of
section 7.y, which measure single components without coordination.

## Method

All the experiments are run by one command,
`java -jar benchmarks/integration/target/integration-bench.jar`, on the reference machine. Its
procedure is in `benchmarks/README.md`.

**Host.**

| Item | Value |
|---|---|
| CPU | FILL from `host.txt` (model, logical processors) |
| RAM | FILL from `host.txt` |
| Disk | FILL (type) |
| OS | FILL from `host.txt` |
| JDK | Temurin 21.0.12 |
| Commit | FILL from `command.txt` |

The same host, JDK and commit are used for every table of this section and for the JMH results.

**Controlled conditions.**
- **Fresh system per run.** Every run starts with a new `DATA_DIR` and starts the services from
  their jars, each with `-Xmx1g`.
- **Fixed corpus.** It loads the 20 books of `samples/books.txt` through `POST /ingest`. The books
  are served from `samples/books` by a local stand-in for Project Gutenberg, so network latency is
  never part of a measurement and the corpus cannot change between runs.
- **Same settings as production.** SQLite runs with `journal_mode=WAL` and `synchronous=NORMAL`.
  The broker persists messages in KahaDB.
- **Load generator on the same host.** It competes for the same CPU, which is part of the
  single-host deployment being measured.

**Factors.** Following section 6.2 of the assignment, one factor changes at a time, keeping the
corpus, the workload and the host fixed:

| Factor | Values | Meaning |
|---|---|---|
| Client concurrency | 1, 2, 4, 8, 16, 32, 64 | Concurrent search clients, each with one connection and no think time (closed loop) |
| Search replicas | 1, 2 | Search processes over the same datamart; clients spread requests round-robin |
| Indexing worker threads | 1, 2, 4 | `INDEXING_WORKERS`: consumer threads of one Indexing process |
| Indexing replicas | 1, 2 (with 2 threads each) | Indexing processes competing for the same queue |

Client concurrency is reported separately from internal threads and replicas.

**What is recorded.**
- **Search latency.** Per concurrency level: a 10 s warm-up that is not recorded, then 30 s of
  measurement. Every request is stored with its latency and the number of matches it returned
  (`raw.csv`). The percentiles use the nearest-rank method over successful requests.
- **Indexing throughput.** 200 `BookDownloaded` events (10 rounds of the corpus) are sent straight
  to the queue and timed until the 200 `BookIndexed` events have arrived. Each configuration is run
  3 times.
- **Coordination cost.** On an empty system, each book is ingested alone and timed until its
  `POST /ingest` response, its `BookIndexed` event and its visibility in `GET /books/{id}`. Then
  the same re-indexing is timed through the broker and as a direct `POST /index/update`, with no
  broker in the path.
- **Resources.** Throughout, the CPU % (100 % = one core) and resident memory of every process are
  sampled every second, and the disk used by each store is recorded.

**Correctness of every configuration.** Throughput only counts if the results stay correct:
- **Search.** Every request of the same word must return the same number of matches, whatever the
  concurrency or the replica (the `total` column of `raw.csv`). The exact `book_id`s are checked by
  the API checks of section 4.4.
- **Indexing.** After each throughput run, the index must still hold exactly 20 books and `darcy`
  must match exactly one book (`index_ok`), so re-indexing never duplicates anything. The Indexing
  test suite also checks, row by row, that 1, 2 and 4 threads and 2 replicas build the same index.

## Results

### Search latency and throughput

*Table 7.x.1. `GET /search` with 1 and 2 Search replicas. Sources:
`benchmarks/results/<day>_search-latency-1-replica/summary.csv` and `..._search-latency-2-replicas/summary.csv`.*

| Clients | 1 replica: req/s | p50 (ms) | p95 (ms) | p99 (ms) | 2 replicas: req/s | p50 (ms) | p95 (ms) | p99 (ms) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | FILL | | | | FILL | | | |
| 2 | FILL | | | | FILL | | | |
| 4 | FILL | | | | FILL | | | |
| 8 | FILL | | | | FILL | | | |
| 16 | FILL | | | | FILL | | | |
| 32 | FILL | | | | FILL | | | |
| 64 | FILL | | | | FILL | | | |

*Figure 7.x.1. Throughput (requests/s) and p95 latency against the number of clients, one line per
replica count.*

Points to discuss, using the figures:
- **Throughput and saturation.** Where throughput stops growing with more clients, and how latency
  grows beyond that point (queueing in a closed loop).
- **Replicas.** Whether a second replica helps. On one host both replicas share the same cores, so
  the gain is bounded by the CPU left over. Compare with the CPU columns of
  `resources-summary.csv`.
- **Tail latency.** The tail (p99) compared with the median.

### Indexing throughput, threads and replicas

*Table 7.x.2. Books indexed per second from the queue (mean of 3 runs, with min–max). Source:
`benchmarks/results/<day>_indexing-throughput/raw.csv`.*

| Indexing processes × threads | Books/s | Speed-up vs 1 × 1 | `index_ok` |
|---|---:|---:|---|
| 1 × 1 | FILL | 1.0 | FILL |
| 1 × 2 | FILL | FILL | FILL |
| 1 × 4 | FILL | FILL | FILL |
| 2 × 2 | FILL | FILL | FILL |

Points to discuss: SQLite has a single writer per database. Every book is committed in one
transaction while all other writers wait (`busy_timeout`). Tokenising and parsing a book can run in
parallel, but the commit cannot. Expect throughput to stop growing once the commits saturate the
writer. A second process does not add writer capacity either, because both processes write the
same file. The resource files show whether the cores were busy or waiting.

### Coordination overhead

*Table 7.x.3. Per-book times in milliseconds (median, p95, max over 3 runs × 20 books). Source:
`benchmarks/results/<day>_coordination/summary.csv`.*

| Measure | Median | p95 | Max |
|---|---:|---:|---:|
| `POST /ingest` response (download from the local stand-in, split, atomic write, log, publish) | FILL | | |
| Until `BookIndexed` arrives | FILL | | |
| Until searchable (`GET /books/{id}` = 200) | FILL | | |
| Re-index with direct `POST /index/update` (no broker) | FILL | | |
| Re-index through the broker (`BookDownloaded` → `BookIndexed`) | FILL | | |

The difference between the last two rows is the cost of coordinating through the broker: a
persistent send, delivery to a consumer and the publication of the event. It is to be compared
with the time spent indexing the book itself.

### Resource usage

*Table 7.x.4. CPU and memory per process during the search load with 2 replicas. Source:
`..._search-latency-2-replicas/resources-summary.csv`. Host totals: sum of the processes plus
the load generator.*

| Process | Mean CPU % | Max CPU % | Max resident memory (MB) |
|---|---:|---:|---:|
| search-1 | FILL | | |
| search-2 | FILL | | |
| indexing-1 | FILL | | |
| ingestion | FILL | | |
| broker | FILL | | |

*Table 7.x.5. Disk used by the 20-book corpus. Source: `..._search-latency-1-replica/disk.csv`.*

| Store | Size (MB) |
|---|---:|
| Datalake (headers and bodies) | FILL |
| Ingestion log (`ingestion.db`) | FILL |
| Datamart (`datamart.db`, WAL included) | FILL |
| Broker store (KahaDB) | FILL |

KahaDB preallocates its journal files, so the broker store is larger than the messages it holds.

## Limits of the evidence

- **One host.** Every number is measured on a single host, where the services, the broker and the
  load generator share the CPU, the memory and one disk. A speed-up measured here shows how the
  design uses one machine, not how it will scale on the Stage 3 cluster.
- **Small corpus.** The 20-book corpus keeps the experiments reproducible but small. The datamart
  fits in the operating system's file cache, so the search latencies reflect CPU and SQLite work,
  not disk reads.
- **Untested configurations.**
  - Search replicas beyond 2 were not tested; on one host they can only share the same cores.
  - Ingestion replicas were not tested: ingestion is bound by Project Gutenberg, which is replaced
    here by a local stand-in.
  - Concurrent ingestion of many books was not measured end to end; the ingestion benchmark of
    section 7.z covers it.
