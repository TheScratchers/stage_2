# 7.x Integration benchmarks

<!--
  Every figure of this section comes from the official run in benchmarks/results/20261105_* on the
  reference machine; each table names its source file. Means, speed-ups and sums over processes
  were computed from those files.
-->

<!-- TO CONFIRM: the disk type of the reference machine is not recorded in host.txt (ask Pablo). -->

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
| CPU | 13th Gen Intel Core i7-13700KF, 24 logical processors |
| RAM | 31.9 GB |
| Disk | TO CONFIRM |
| OS | Windows 11 (amd64) |
| JDK | Eclipse Temurin 21.0.12 (OpenJDK 64-Bit Server VM) |
| Commit | `04132ab` |
| Date | 2026-11-05, started 17:54 UTC, after the ingestion and JMH runs had finished (17:53 UTC) |

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
  sampled every second (every half second in the shorter indexing and coordination runs), and the
  disk used by each store is recorded.

**Correctness of every configuration.** Throughput only counts if the results stay correct:
- **Search.** Every request of the same word must return the same number of matches, whatever the
  concurrency or the replica (the `total` column of `raw.csv`). The exact `book_id`s are checked by
  the API checks of section 4.4.
- **Indexing.** After each throughput run, the index must still hold exactly 20 books and `darcy`
  must match exactly one book (`index_ok`), so re-indexing never duplicates anything. The Indexing
  test suite also checks, row by row, that 1, 2 and 4 threads and 2 replicas build the same index.

## Results

No request failed in any experiment, and every configuration kept correct results:
- **Search.** Over the 1.47 million measured requests, each word always returned the same number of
  matches, with 1 and with 2 replicas (`darcy` 1, `wonderland` 1, `monster` 14, `fortune` 18,
  `heart` 19, `truth` 19, `death` 20, `love` 20, `nothing` 20, `time` 20).
- **Indexing.** All 12 throughput runs ended with `index_ok = true`.

### Search latency and throughput

*Table 7.x.1. `GET /search` with 1 and 2 Search replicas; 30 s of measurement per level, after
10 s of warm-up. Sources: `benchmarks/results/20261105_search-latency-1-replica/summary.csv` and
`benchmarks/results/20261105_search-latency-2-replicas/summary.csv`.*

| Clients | 1 replica: req/s | p50 (ms) | p95 (ms) | p99 (ms) | 2 replicas: req/s | p50 (ms) | p95 (ms) | p99 (ms) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 631 | 1.23 | 2.80 | 4.91 | 842 | 1.15 | 1.45 | 1.77 |
| 2 | 1,054 | 1.16 | 4.70 | 15.54 | 1,297 | 1.08 | 1.78 | 15.29 |
| 4 | 1,615 | 1.65 | 13.04 | 16.07 | 2,582 | 1.01 | 1.57 | 15.81 |
| 8 | 4,645 | 1.08 | 2.13 | 16.00 | 4,646 | 1.27 | 2.06 | 16.01 |
| 16 | **5,523** | 2.49 | 4.13 | 16.48 | **5,376** | 2.47 | 4.38 | 16.45 |
| 32 | 5,315 | 5.86 | 6.95 | 13.70 | 5,215 | 5.85 | 7.52 | 18.79 |
| 64 | 5,242 | 11.93 | 13.44 | 22.09 | 5,099 | 11.61 | 15.56 | 41.25 |

*Figure 7.x.1 (to draw from Table 7.x.1). Throughput and p50 latency against the number of
clients, one line per replica count.*

- **Saturation at 16 clients.** Throughput grows almost linearly up to 8 clients and peaks at
  16 clients, with about 5,500 requests per second. Beyond that point it stays flat or drops
  slightly, and the latency grows in proportion to the number of clients. With 64 clients the mean
  latency is 12.2 ms, which is 64 clients divided by 5,242 requests per second (Little's law). The
  extra clients only wait in a queue. Below saturation a search takes about 1–2.5 ms at the median.
- **A second replica does not raise the ceiling.** With 2 replicas the peak is the same within a
  few percent (5,376 against 5,523 requests/s), and so is the latency at 16 clients or more.
- **The limit is not inside the Search process.** At peak load the Search processes together used
  at most 876 % CPU with one replica and 887 % with two, that is, about 9 of the 24 logical
  processors (Table 7.x.4). The two replicas split the same total work (485 % and 457 % at most)
  instead of adding capacity. If one Search process were the bottleneck, a second one would have
  raised the ceiling. The constraint is therefore shared by the whole host. The most likely cause is
  the load generator, which runs on the same machine with up to 64 client threads; its CPU was not
  sampled, so this cannot be confirmed from the data. The Stage 2 deployment cannot separate the
  two; on the cluster, clients and servers will run on different machines.
- **Low concurrency.** With 1 to 4 clients, 2 replicas gave higher throughput and much lower p95:
  4 clients reached 2,582 requests/s against 1,615, with a p95 of 1.57 ms against 13.04 ms. With one
  client the requests are sequential, so the gain cannot come from parallelism. These runs do not
  show the cause, so we do not attribute this difference to replication.
- **A latency floor of about 16 ms in the tail.** From 2 to 16 clients, p99 stays between 15.3 and
  16.5 ms in both configurations, although p50 is about 1 ms. This value matches the default
  15.6 ms timer interval of Windows, which suggests that the tail is set by the operating system
  scheduler rather than by the search itself. We did not verify this with another timer setting.

### Indexing throughput, threads and replicas

*Table 7.x.2. Books indexed per second from the queue: 200 `BookDownloaded` events per run, mean of
3 runs with min–max, and the CPU used by the Indexing processes (mean of the per-run averages).
Source: `benchmarks/results/20261105_indexing-throughput/raw.csv` and `resources-*.csv` of the same
directory.*

| Indexing processes × threads | Books/s (min–max) | Speed-up vs 1 × 1 | Indexing CPU % | `index_ok` |
|---|---:|---:|---:|---|
| 1 × 1 | 12.3 (12.1–12.5) | 1.00 | 92 | 3/3 |
| 1 × 2 | 18.0 (17.8–18.1) | 1.46 | 134 | 3/3 |
| 1 × 4 | 18.5 (18.1–18.7) | 1.50 | 142 | 3/3 |
| 2 × 2 | 18.4 (18.3–18.5) | 1.49 | 140 | 3/3 |

- **A ceiling of about 18.5 books per second.** A second thread improves throughput by 46 %, but 4
  threads or 2 processes add almost nothing more. The three repetitions of each configuration are
  within ±2 %, so the plateau is not noise.
- **The extra threads wait.** With 1 thread the process uses about one core (92 %). With 2 threads
  it uses 134 %, and with 4 threads, or with 2 processes, it still uses only about 140 %. Doubling
  the threads did not double the CPU used: the additional threads spend their time waiting, not
  working.
- **They wait for the SQLite writer.** SQLite lets one transaction write a database at a time, and
  every book is one transaction (section 5.x, Indexing). Reading and tokenizing a book run in
  parallel, which explains the gain of the second thread. Committing is serialised, so beyond two
  threads the workers queue on the write lock. The JMH results of section 7.y show the same split
  without the broker: analysing the corpus scales 3.5× with 4 threads, whereas persisting it stops
  improving after 2.
- **Replicas do not help either.** A second process writes the same `datamart.db`, so it competes
  for the same lock. 2 × 2 performs like 1 × 4.

### Coordination overhead

*Table 7.x.3. Per-book times in milliseconds over 3 runs × 20 books, each book processed alone.
Source: `benchmarks/results/20261105_coordination/summary.csv`, `chain.csv` and
`direct-vs-event.csv`.*

| Measure | Median | p95 | Max |
|---|---:|---:|---:|
| `POST /ingest` response (local stand-in download, split, atomic write, log, publish) | 19.4 | 38.6 | 304.3 |
| Until its `BookIndexed` arrives | 59.7 | 104.8 | 356.7 |
| Until it is searchable (`GET /books/{id}` = 200) | 64.3 | 109.2 | 429.4 |
| Re-index with direct `POST /index/update` (no broker) | 72.9 | 117.7 | 160.2 |
| Re-index through the broker (`BookDownloaded` → `BookIndexed`) | 73.9 | 119.6 | 172.7 |

- **The whole pipeline takes about 64 ms per book.** A new book becomes searchable at the median
  64 ms after it is requested: 19 ms in Ingestion, about 40 ms more until Indexing has committed it
  and announced it, and about 5 ms more until the client sees it in Search.
- **The maximum is the first book of each run.** It is the first request served by freshly
  started JVMs: book 11, searchable after 429, 331 and 329 ms in the three runs. The p95 values
  describe the warm system.
- **The broker costs about 4 ms per book.** Re-indexing the same book took a median 72.9 ms
  directly and 73.9 ms through the broker. Compared book by book, the broker path was slower by a
  median of 3.8 ms (mean 3.3 ms over the 60 pairs). That is about 5 % of the cost of indexing a
  book, and it pays for the persistent send, the delivery to a consumer, the JMS transaction and the
  `BookIndexed` event. In return the services are decoupled and a crash loses no book.
- **Re-indexing costs more than indexing.** Indexing a new book (in the chain) is faster than
  re-indexing it (in the last two rows), because re-indexing must also delete the previous postings
  of the book. Section 7.y measures the same effect.

### Resource usage

*Table 7.x.4. CPU and resident memory per process during the search load (all levels from 1 to 64
clients), with every service started with `-Xmx1g`. 100 % CPU is one busy logical processor out of
24. Sources: `resources-summary.csv` and `resources.csv` of
`benchmarks/results/20261105_search-latency-1-replica/` and `..._search-latency-2-replicas/`.*

| Process | 1 replica: mean CPU % | Max CPU % | Max RSS (MB) | 2 replicas: mean CPU % | Max CPU % | Max RSS (MB) |
|---|---:|---:|---:|---:|---:|---:|
| search-1 | 424 | 876 | 447 | 220 | 485 | 446 |
| search-2 | — | — | — | 218 | 457 | 443 |
| indexing-1 | 0.1 | 2.8 | 328 | 0.1 | 2.8 | 332 |
| ingestion | 0.0 | 1.4 | 294 | 0.1 | 2.8 | 293 |
| broker | 0.2 | 5.7 | 103 | 0.1 | 4.3 | 105 |
| **All services** (sum per sample) | 425 | 876 | — | 439 | 887 | — |

While books are being ingested and indexed (coordination experiment, last run), Indexing averaged
76 % CPU with a peak of 609 MB of resident memory, and Ingestion averaged 17 % with a peak of 293 MB
(`benchmarks/results/20261105_coordination/resources-summary.csv`).

- **Memory is mostly the JVM.** During the search load each process uses 100–450 MB, well below its 1 GB heap limit. The
  index lives on disk and is read through SQLite and the operating system cache, so Search memory
  does not grow with the load.
- **Under search load only Search works.** The other services stay idle, and the busiest moments
  of the whole system use about 9 of the 24 logical processors.

*Table 7.x.5. Disk used by the 20-book corpus (1 MB = 10^6 bytes). Source:
`benchmarks/results/20261105_search-latency-1-replica/disk.csv`.*

| Store | Size (MB) |
|---|---:|
| Datalake (headers and bodies) | 11.29 |
| Ingestion log (`ingestion.db`) | 0.02 |
| Datamart (`datamart.db` with its WAL) | 7.23 |
| Broker store (KahaDB) | 67.31 |

The datamart takes 64 % of the size of the text it indexes. The broker store is the largest, but
only because KahaDB preallocates its journal files: the messages themselves are a few hundred bytes
each.

## Limits of the evidence

- **One host.** Every number is measured on a single host, where the services, the broker and the
  load generator share the CPU, the memory and one disk. A speed-up measured here shows how the
  design uses one machine, not how it will scale on the Stage 3 cluster. In particular, the search
  ceiling could not be attributed to a single component, because the load generator could not run
  on another machine.
- **Host CPU.** The CPU of the host is reported as the sum of the service processes. The load
  generator and the rest of the operating system were not sampled.
- **Small corpus.** The 20-book corpus keeps the experiments reproducible but small. The datamart
  fits in the operating system's file cache, so the search latencies reflect CPU and SQLite work,
  not disk reads.
- **Untested configurations.**
  - Search replicas beyond 2 were not tested; on one host they can only share the same cores.
  - Ingestion replicas were not tested: ingestion is bound by Project Gutenberg, which is replaced
    here by a local stand-in.
  - Concurrent ingestion of many books was not measured end to end; the ingestion benchmark of
    section 7.z covers it.
