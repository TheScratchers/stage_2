# 6. Design decisions

<!--
  Figures come from benchmarks/results/20261105_* (reference machine, commit 04132ab), as presented
  in section 7; each one names the table it comes from.
-->

This section explains why the system is built the way sections 3 to 5 describe, which alternatives
were rejected, and what the measurements of section 7 say about each choice.

## 6.1 Coordination mechanism: choreography through a message broker

The services coordinate through two events on an embedded ActiveMQ broker. `BookDownloaded` goes on
a queue from Ingestion to Indexing, and `BookIndexed` goes on a topic from Indexing to every Search
replica. No service calls another over HTTP.

| Alternative | Why it was rejected |
|---|---|
| **HTTP orchestration** (a Control Module calls `POST /index/update` after each ingestion) | A coordinator has to track every book, retry failed calls and survive its own crashes. With events, that bookkeeping is done by the broker: a message stays until it is acknowledged |
| **Scheduled polling** (Indexing scans the datalake or the ingestion log for new books) | It adds a delay of up to one period to every book and keeps reading even when nothing changed. It also needs readiness markers so that a half-written book is never picked |
| **Shared memory** | It only works inside one host and couples the services' internal state, which would block the Stage 3 cluster |

The broker gives the guarantees the pipeline needs with little code of our own:
- **Persistent messages** survive a broker restart.
- **Acknowledgement after commit:** a book whose indexing fails or crashes is redelivered, up to
  5 times, and then parked in `ActiveMQ.DLQ` instead of being lost.
- **Competing consumers** on the queue spread the books over threads and replicas.
- **The topic** lets every Search replica hear about new books without knowing who indexed them.

Duplicates are allowed rather than prevented, because indexing a book replaces it (idempotency).
That is simpler and more robust than exactly-once delivery.

**Evidence.**
- Re-indexing a book through the broker costs a median of 3.8 ms more than doing it directly, about
  5 % of the work (Table 7.x.3). A new book is searchable about 64 ms after it is requested.
- The Indexing tests kill the process in the middle of a batch: no book is lost or half-indexed, and
  a restarted process completes the batch (section 5.x, Indexing).

**Cost.** The broker is one more process to run, and a single point of failure on one host. Section 8
explains how it is replicated on the cluster.

## 6.2 Single-machine deployment

Every process runs on one host, as Stage 2 requires: one broker, one Ingestion, one or more Indexing
and Search replicas, and the API checks and benchmarks. Each service is its own JVM started from its
own jar, with its own port and configuration. They share only the broker and the files under
`DATA_DIR`.

Separate processes, rather than one JVM with several modules, make the service boundaries real:
- a service can be stopped, restarted or replicated without touching the others;
- one service's crash does not take the others down;
- CPU and memory can be measured per service (Table 7.x.4).

Keeping everything on one host also keeps the measurements controlled. Every official figure comes
from the same machine, JDK and commit.

The price is that every process competes for the same 24 logical processors, memory and disk. Some
limits measured here are limits of the host and not of the design, such as the search ceiling of
section 7.x.

## 6.3 API design

- **One public API.** Only the Search Service is meant for the front-end. Its three operations are
  `GET` requests on resources a reader understands: a search, a book and the filter choices. They are
  idempotent and cacheable, and CORS lets a browser call them from another origin. Everything else is
  internal and kept out of the front-end contract (section 4).
- **One search endpoint with optional filters,** as the assignment asks, rather than one route per
  filter.
- **Search semantics.**
  - Words are combined with **AND**, because adding a word should narrow the results.
  - Results are ranked by the **sum of term frequencies**, which needs no global statistics and can
    be computed in one SQL query.
  - Ties are broken by `book_id`, so the order is deterministic and pages never overlap or skip a
    book.
- **Bounded pagination.** `offset` and `limit`, with `limit` at most 100, so no request can ask for
  an unbounded page. `total` is always reported, so a client knows how many pages there are.
- **Strict, explained validation.** Invalid input is rejected with `400` and a message that names
  the rule, rather than silently corrected. Empty filters are ignored, because forms send them.
- **Uniform errors.** All three services return the same envelope and the same small set of codes,
  so a client handles errors once.
- **Synchronous internal operations.** `POST /ingest`, `/index/update` and `/index/rebuild` return
  when their work is committed. Scripts and tests therefore know the result without polling a status
  endpoint, and repeating a call is always safe.
- **Contract first.** `docs/openapi.yaml` and `docs/contracts.md` were started before the
  endpoints. Every service then documented its routes in the same pull request that implemented
  them. A test fails the build if a checked behaviour is undocumented, or if a documented example
  differs from the real system (section 4.4).

## 6.4 Storage

| Data | Store | Writer | Readers |
|---|---|---|---|
| Book text | Datalake: header and body files under `DATA_DIR/datalake/` | Ingestion | Indexing |
| Which books are ready | `ingestion.db` (SQLite) | Ingestion | Indexing (read-only) |
| Inverted index and metadata | `datamart.db` (SQLite) | Indexing | Search (read-only) |
| Events in flight | KahaDB, the broker's store | Broker | — |

**Rule: one writer per file.** Every file has exactly one service that writes it. This removes any
need to coordinate writers across services and makes ownership explicit in the contract.

**The datalake as files.** Books are written once and read whole, so plain files are the simplest
store. Each file is written to a temporary name and then moved atomically, so a reader never sees a
half-written book. The datalake is also the source of truth: the index can always be rebuilt from
it with `POST /index/rebuild`.

**SQLite for the datamart.** SQLite was the Stage 1 store, and it gives what the index needs without
running a database server:
- **Transactions.** Each book is indexed in one transaction, so it is visible either whole or not
  at all, and re-indexing it never duplicates rows.
- **Fast lookups.** The `(term, book_id)` primary key answers searches.
- **Concurrent reads.** In WAL mode, Search reads while Indexing writes, each reader on a consistent
  snapshot.

The settings are `synchronous=NORMAL`, which survives a process crash and is faster than `FULL`, and
`busy_timeout=5000`, so a writer waits for the lock instead of failing (section 5.x, Indexing).
Section 7 shows the result: the index takes 64 % of the size of its text (Table 7.x.5), and a search
below saturation takes about 1–2.5 ms at the median (Table 7.x.1).

**Its cost: one writer at a time.** SQLite allows one write transaction per database. This is the
limit that caps indexing at about 18.5 books per second, whatever the number of threads or replicas
(Table 7.x.2). A server database or a partitioned index would remove this limit. On one host the
simplicity was worth more than the extra throughput; on the cluster the index will have to be
partitioned (section 8).

## 6.5 Concurrency model, replicas, threads and ports

**Threads.**
- **Search.** Javalin's Jetty thread pool serves requests concurrently. Each request opens its own
  read-only SQLite connection and runs in one read transaction.
- **Indexing.** `INDEXING_WORKERS` consumer threads (2 by default) compete for the queue. Each has its
  own JMS session and its own SQLite connections. The default is 2 because the second thread gives
  most of the measured gain and a third gives almost none (Table 7.x.2).
- **Ingestion.** Requests are served by Jetty threads.

**Replicas and ports.**

| Process | Port | Replicable | How work is shared |
|---|---|---|---|
| Ingestion | 7001 | Not tested | One process; ingestion is bound by Project Gutenberg |
| Indexing | 7002, then 7012, 7022, ... | Yes | Competing consumers on one queue, writing one datamart |
| Search | 7003, then 7013, 7023, ... | Yes | Stateless apart from a cache; clients spread requests over replicas |
| Broker | 61616 | No (one per host) | — |

Ports follow one pattern so that scripts can start replicas without a registry, and every port can
be changed by configuration.

**Protection of shared state.** There is almost no shared mutable state, and what exists is guarded
by the component that already owns it rather than by locks of our own:

| Shared resource | Protection |
|---|---|
| A book in the queue | The broker delivers each message to one consumer (prefetch 1) and keeps it until acknowledged |
| `datamart.db` | SQLite serialises writers with its file lock; readers use WAL snapshots |
| Datalake files | One writer (Ingestion); atomic moves; consumers only read books announced as available |
| JDBC connections and JMS sessions | Never shared: one per request or per thread |
| Search filter cache | Synchronised, with a generation counter so that an outdated read never overwrites a newer invalidation |
| "One rebuild at a time" | An atomic flag per Indexing process; a concurrent rebuild in another replica is harmless because each book is still one transaction |

The Indexing tests show that the result does not depend on the concurrency. 1, 2 and 4 threads, or
2 replicas, build the same index row by row, even with every book delivered twice (section 5.x).

## 6.6 Where replication and threading help, and where they cause contention

| Work | Parallelism helps? | Evidence |
|---|---|---|
| Analysing books (reading, parsing, tokenizing) | **Yes**, almost linearly | JMH: 3.54× with 4 threads (section 7.y) |
| Ingesting books | **Yes**: each book writes its own files | From about 44 books/s with 1 client (runs 2–3; the first run includes the JVM warm-up) to about 112 with 4 clients (`20261105_ingestion-throughput/summary.csv`) |
| Writing the index | **No**: one writer per database | JMH: persisting stops improving after 2 threads (section 7.y); pipeline: 12.3 → 18.0 → 18.5 books/s with 1, 2 and 4 threads and 18.4 with 2 replicas (Table 7.x.2) |
| Answering searches | **Up to the host's limit** | Peak about 5,500 requests/s at 16 clients; a second replica on the same host does not raise it (Table 7.x.1) |

- **Where it helps.** Extra threads pay off where work is independent: analysing different books,
  ingesting different books, or serving different searches while CPU is free.
- **Where it causes contention.** It stops paying off where everything meets one resource. For
  Indexing that resource is the SQLite write lock: extra threads only wait for it (about 140 % CPU
  with 4 threads against 134 % with 2). For Search on one host it is the machine itself, shared
  with the load generator.
- **What this means for the configuration.**
  - Indexing runs **2 workers** by default: the best measured value per thread.
  - On one host, a second Search replica is worth running for **availability** (one can be
    restarted while the other answers) rather than for speed.
  - Ingestion's clients can safely send several books at once.

  On the cluster, threads remain a per-machine setting, and replicas on separate machines become the
  way to scale.
