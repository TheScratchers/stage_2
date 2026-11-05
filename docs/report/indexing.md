# 5.x Indexing Service

<!--
  Every figure of this file comes from the official run in benchmarks/results/20261105_* on the
  reference machine; each table names its source file.
-->

The Indexing Service turns the books of the datalake into the inverted index that Search reads. It
is a Java 17 Javalin application with Gson for JSON, `sqlite-jdbc` for the datamart and the
ActiveMQ client for the broker, packaged as an executable jar
(`indexing-service/target/indexing-service.jar`). It is configured with `INDEXING_PORT` (default
7002), `DATA_DIR`, `BROKER_URL` and `INDEXING_WORKERS` (consumer threads, default 2), all read
through `common.Config`.

It is the **only writer** of `DATA_DIR/datamarts/datamart.db`, which it creates on start-up. It reads
the datalake files and, read-only, the ingestion log `DATA_DIR/datalake/ingestion.db`, both owned by
Ingestion. It starts even if the broker is down and connects when it comes back.

## Internal structure

| Package | Class | Responsibility |
|---|---|---|
| `indexing` | `IndexingApp` | Wiring and start-up from the configuration; `/health`; closes the broker clients and the server on shutdown |
| `indexing` | `BookIndexer` | Indexes one book: parses the header, counts the terms of the body and stores both in one transaction |
| `indexing.metadata` | `HeaderParser`, `LanguageCodes`, `ParsedHeader` | Pure parsing of the Gutenberg header into title, author, ISO language code and year |
| `indexing.store` | `DatamartStore` | All SQL on `datamart.db`: schema, the per-book transaction, removal, counts and size |
| `indexing.source` | `IngestionLogReader`, `BookFiles` | Read-only lookup of available books and their file paths in `ingestion_log` |
| `indexing.service` | `IndexingService` | Logic of the administrative operations: update one book, rebuild, status |
| `indexing.api` | `IndexingController`, `ApiResponses` | HTTP layer: parameters, JSON in `snake_case` and the common error envelope |
| `indexing.events` | `BookDownloadedConsumer`, `BookIndexedPublisher`, event records | Consumption of `books.downloaded` and publication on `books.indexed` |

The layers depend only downwards: the controller knows nothing about SQL, and `DatamartStore`
knows nothing about HTTP or JMS. `BookIndexer` keeps no mutable state and `DatamartStore` holds no
connection (every call opens its own), so one instance of each is shared by all the threads of the
process.

## How a book is indexed

1. **Locating the files.** The paths come from the `BookDownloaded` event, or, for the HTTP
   endpoints, from the `ingestion_log` row of an `available` book. Indexing never builds datalake
   paths itself, so the datalake layout stays a private decision of Ingestion. A missing file is
   an error, never an empty book.
2. **Metadata.** `HeaderParser` reads the header:
   - Title and author are matched at the start of a line, case-insensitively, including the
     indented continuation lines that Gutenberg uses for long values.
   - The language is the first one listed on the `Language:` line, converted to an ISO 639-1 code
     (`English` → `en`), or `NULL` if it is missing or unknown.
   - The year is the first four-digit number of the `Release date:` line before any `[`, so the
     eBook number is never taken for a year. It is the year of the Gutenberg release, not of the
     original work.
3. **Terms.** The body is split by `common.TextTokenizer`, which Search also uses for queries: lower
   case with `Locale.ROOT`, then maximal runs of Unicode letters, with no stemming or stop words.
   Each term is counted, and its **frequency** in the book is stored because Search ranks by the
   sum of the frequencies of the query terms.
4. **One transaction per book.** `DatamartStore.indexBook` runs, in one SQLite transaction:

   ```sql
   DELETE FROM postings WHERE book_id = ?;                  -- re-indexing never duplicates
   INSERT INTO postings(term, book_id, frequency) VALUES ... -- one batch for all the terms
   INSERT OR REPLACE INTO books(...) VALUES (...);
   INSERT OR REPLACE INTO index_meta VALUES ('last_update', ?);
   COMMIT;
   ```

   Either all of the book is stored or nothing is. Indexing the same book twice leaves the same
   rows, which is what makes duplicate and redelivered events harmless.
5. **Publication.** After the commit, `BookIndexed` is published with the number of distinct terms.

## Storage: the datamart

`datamart.db` holds three tables, with the schema of the team contract: `books` (metadata and
`indexed_at`), `postings` (`term`, `book_id`, `frequency`, primary key `(term, book_id)`) and
`index_meta` (`last_update`). The primary key serves the lookups of Search by term, and the extra
index `idx_postings_book` serves the per-book `DELETE` of re-indexing.

**SQLite settings (correction 7).** Every connection uses `journal_mode=WAL`,
`synchronous=NORMAL` and `busy_timeout=5000`, with the default page cache.
- **WAL** lets Search read while Indexing writes. Each reader keeps a snapshot, so it sees a book
  either before or after its commit, never half-indexed.
- **`synchronous=NORMAL`** does not sync the WAL on every commit. A committed book survives a
  crash of the process, but the last commits can be lost if the operating system crashes or the
  power fails. The corresponding messages may already be acknowledged by then, so they would not be
  redelivered. `POST /index/rebuild` restores the index from the ingestion log in that case. We
  accept this trade-off because the index can always be rebuilt from the datalake, which is the
  source of truth.
- **One writer at a time.** SQLite serialises the writers of a database: a writer waits up to
  `busy_timeout` for the lock. Worker threads and replicas therefore index in parallel but commit
  one after another. Section 7.y measures what this costs.

*Table 5.x.1. Size of the index for the 20-book corpus (1 MB = 10^6 bytes). Source:
`benchmarks/results/20261105_indexing-jmh/footprint.csv` (1 thread).*

| Measure | Value |
|---|---:|
| Corpus (header and body files) | 11.29 MB |
| `books` rows | 20 |
| `postings` rows | 139,869 |
| `datamart.db` after a WAL checkpoint (tables and indexes) | 7.19 MB |

The whole index takes 64 % of the size of the text it indexes.

## Coordination: consuming `BookDownloaded`

`BookDownloadedConsumer` starts `INDEXING_WORKERS` threads that compete for the `books.downloaded`
queue. Each thread has its own **transacted** JMS session, because a JMS session must not be shared
between threads. A message is handled in this order:

1. Parse and validate the event.
2. Index the book and commit it in SQLite.
3. Send `BookIndexed` on the `books.indexed` topic, inside the JMS transaction.
4. Commit the JMS session. This acknowledges the message and releases the event in one step.

If any step fails, the session is rolled back: nothing is published and the message returns to the
broker. The broker redelivers it up to 5 times (6 deliveries in total) and then moves it to
`ActiveMQ.DLQ`. Client-side redelivery is disabled, so that only the broker counts attempts.

**What a crash leaves behind.** A crash can happen at any point of that sequence:
- **Before the SQLite commit:** nothing of the book is stored, and the message is redelivered.
- **Between the SQLite commit and the JMS commit:** the book is stored, but `BookIndexed` has not
  been released and the message is not acknowledged. The message is redelivered, the book is
  re-indexed to the same rows, and the event is then published.
- **After the JMS commit:** the work is complete.

So every acknowledged message has a committed book, and `BookIndexed` is published at least once,
always after the commit.

**Other choices.**
- **Prefetch 1.** A worker never holds messages it is not processing, so an idle worker or another
  replica can always take the next book. With the default prefetch, one busy consumer could hoard a
  batch while the others wait.
- **`failover:` transport.** The consumer connects in the background and reconnects after a broker
  restart, so the service never waits for the broker at start-up.
- **Invalid messages.** Unreadable JSON or an event without a valid `book_id` or paths fails like
  any other error and ends in the dead-letter queue, without stopping the worker.

**Events after the HTTP endpoints.** Books indexed by `POST /index/update` or `POST /index/rebuild`
also publish `BookIndexed`, through `BookIndexedPublisher`, so that Search refreshes its filter cache
in both cases. That publication runs inside an HTTP request after the commit, so it must neither
fail nor block the request. It uses a direct connection with a 3 s timeout instead of `failover:`. If
the broker is down, it logs a warning and tries again on the next publication. Search loses only
the freshness of its cache until the next event (see the Search Service section).

## Administrative operations

The contract of the endpoints is in section 4.2; this is how they behave:
- **`POST /index/update/{book_id}`** looks the book up in the ingestion log and indexes it as above.
  It answers `404` if Ingestion has not made the book available.
- **`POST /index/rebuild`** re-indexes every available book, one transaction per book, and then
  removes the indexed books that are no longer available. The final state is the same as indexing
  from scratch. Because the old version of each book stays until its new version commits, Search
  never sees an empty or partial index during a rebuild. A book that fails is reported in
  `failed_books` and keeps its previous version. A second rebuild in the same process answers `409`.
  Two replicas could rebuild at the same time, which wastes work but is harmless, because each book
  is still written in one transaction.
- **`GET /index/status`** reports the number of books, the time of the last committed change and
  the size of `datamart.db` with its WAL.

## Concurrency: worker threads and replicas

There are two ways to index in parallel, and both compete for the same queue:
- **Threads.** `INDEXING_WORKERS` consumer threads per process. Each has its own JMS session and
  opens its own SQLite connections; JDBC connections are never shared between threads.
- **Replicas.** Several Indexing processes on different ports (7002, 7012, ...) over the same
  `DATA_DIR`. ActiveMQ delivers each message to only one consumer, whatever process it belongs to.

The only state shared between threads is immutable (the configuration and the stateless
`BookIndexer` and `DatamartStore`), so there is nothing to lock in Java. The coordination happens in
two places: the broker, which hands each book to one worker, and SQLite, which serialises the
commits. Reading, parsing and tokenising run fully in parallel; committing does not. No order
between books is assumed, so the books may be committed in any order.

## Failure and concurrency evidence

Automated tests check the guarantees above over the whole corpus (`IndexingRecoveryTest`). Every test
sends the 20 books **twice**, so duplicates are always part of the workload, and compares the
resulting datamart row by row with a reference index built sequentially. The comparison covers
every `books` row except `indexed_at`, and every `postings` row.

| Scenario | Check |
|---|---|
| A real `IndexingApp` process is killed abruptly (no shutdown hook) as soon as it has committed its first book | Messages were still pending; the datamart contains only complete books, with no half-indexed book and no orphan postings |
| A new process is started on the same queue and datamart | It completes the batch: the index equals the reference, nothing is duplicated and the dead-letter queue is empty |
| 1, 2 and 4 worker threads in one process | The index equals the reference in every case |
| Two `IndexingApp` processes with 2 workers each, sharing one `datamart.db` | The index equals the reference, and both processes indexed books |
| A book whose files are missing (`BookDownloadedConsumerTest`) | It is delivered 6 times, ends in `ActiveMQ.DLQ` and nothing is indexed or published |

The tests use a real ActiveMQ broker with the same redelivery policy as the project's broker. The
killed and the replicated services are the real `IndexingApp`, each in its own JVM. Throughput with
threads and replicas on the running system is measured in section 7.x.

## Tests

The service has 62 tests:

| Test class | Tests | What it covers |
|---|---|---|
| `HeaderParserTest` | 9 | Titles and authors on several lines, ISO language codes, release year, missing fields |
| `LanguageCodesTest` | 3 | Language names to ISO 639-1 codes |
| `DatamartStoreTest` | 13 | Schema in a missing directory, WAL, idempotent re-indexing, a failed transaction leaves no trace or keeps the previous version, removal, counts and size |
| `BookIndexerTest` | 4 | Indexing sample books from their files, frequencies, missing files |
| `IngestionLogReaderTest` | 4 | Available books only, missing ingestion log |
| `IndexingServiceTest` | 8 | Update, rebuild (removals, idempotency, books with missing files, a second rebuild while one runs), status, and events only for committed books |
| `IndexingControllerTest` | 6 | Every endpoint over HTTP, including `400` for an invalid id and `404` for a book that is not available |
| `BookDownloadedConsumerTest` | 8 | Real broker: indexing and publication, duplicates, dead-letter queue, invalid messages, 4 workers over the corpus, start without a broker |
| `BookIndexedPublisherTest` | 2 | Publication, and a broker that is down |
| `IndexingRecoveryTest` | 5 | The failure and concurrency scenarios of the previous section |

On top of these, the API checks of section 4.4 exercise the running service.

# 7.y Microbenchmarks of the Indexing components (JMH)

The JMH microbenchmarks measure the indexing code on its own, with no broker, HTTP or other
process, so that the cost of each step can be told apart from the cost of coordination measured in
section 7.x.

## Method

**What is measured.** Following correction 4 of Stage 1, construction, persistence and incremental
update are measured separately, and the complete rebuild on its own:

| Benchmark | One operation | Threads |
|---|---|---|
| `tokenize`, `parseHeader`, `indexBook` | Tokenizing the body, parsing the header, and storing book 1342 (*Pride and Prejudice*) | 1 |
| `analyseCorpus` | **Construction**: reading, parsing and tokenizing the 20 books and counting their terms, in memory | 1, 2, 4 |
| `persistCorpus` | **Persistence**: storing the 20 precomputed books in an empty datamart, one transaction per book | 1, 2, 4 |
| `buildCorpus` | Both, as the service does it, from the files into an empty datamart | 1, 2, 4 |
| `updateBatch` | **Incremental update**: re-indexing an identical batch (the corpus) into a datamart that already holds it | 1, 2, 4 |
| `rebuild` | The complete rebuild of `POST /index/rebuild` over a full datamart (sequential, as the service runs it) | 1 |

The threads play the role of the `INDEXING_WORKERS` consumers: each one takes the next book from a
shared queue, and the operation ends when the 20 books are done.

**Settings (corrections 7 and 8).**
- JMH 1.37, one fork per benchmark, with `-Xms2G -Xmx2G`.
- Book 1342: average time, 5 warm-up and 10 measured iterations of 10 s.
- Corpus: single-shot time of one pass, 5 warm-up and 10 measured passes, with an explicit limit
  of 2 minutes per pass. Logging is reduced to warnings, so that writing a line per book is not
  measured.
- The GC profiler adds the bytes allocated per operation (`gc.alloc.rate.norm`).
- The datamarts are created in temporary directories on the system disk, with the production
  SQLite settings (WAL, `synchronous=NORMAL`, `busy_timeout=5000`).
- Every run uses the fixed corpus of `samples/books.txt`.
- All the official figures come from the team's reference machine, with Temurin 21.0.12 and the same
  commit as the integration benchmarks. The exact command, commit and settings are in `command.txt`,
  and the machine is described in `host.txt`.

The whole run is one command, described in `indexing-service/README.md`. It writes
`benchmarks/results/<day>_indexing-jmh/`: `jmh.csv` (raw JMH results), `jmh.txt` (JMH log),
`footprint.csv`, `command.txt` and `host.txt`.

## Results

All the figures come from `benchmarks/results/20261105_indexing-jmh/`, measured on the reference
machine (13th Gen Intel Core i7-13700KF, 24 logical processors, 31.9 GB of memory, Windows 11,
Temurin 21.0.12) at commit `04132ab`, the same commit and machine as section 7.x. 1 MB = 10^6 bytes.

*Table 7.y.1. Steps of indexing one book (book 1342), mean ± 99.9 % error over 10 iterations.
Source: `.../jmh.csv`.*

| Step | Time (ms/op) | Allocated (MB/op) |
|---|---:|---:|
| `parseHeader` | 0.0063 ± 0.0000 | 0.004 |
| `tokenize` | 8.09 ± 0.06 | 9.66 |
| `indexBook` (one SQLite transaction) | 27.35 ± 1.04 | 0.69 |

*Table 7.y.2. Time to process the 20-book corpus with 1, 2 and 4 threads, mean ± 99.9 % error
over 10 passes. Speed-up is relative to 1 thread. Source: `.../jmh.csv`.*

| Benchmark | 1 thread (ms) | 2 threads (ms) | 4 threads (ms) | Speed-up 2 / 4 |
|---|---:|---:|---:|---:|
| `analyseCorpus` (construction) | 224 ± 16 | 115 ± 18 | 63 ± 10 | 1.94 / 3.54 |
| `persistCorpus` (persistence) | 1303 ± 405 | 591 ± 83 | 630 ± 138 | 2.21 / 2.07 |
| `buildCorpus` (both) | 3020 ± 988 | 1127 ± 348 | 788 ± 445 | 2.68 / 3.83 |
| `updateBatch` (incremental update) | 1654 ± 149 | 1333 ± 154 | 1199 ± 92 | 1.24 / 1.38 |

*Table 7.y.3. Complete rebuild against building from scratch and updating the same batch
(1 thread). Source: `.../jmh.csv`.*

| Operation | Time (ms) | Books/s |
|---|---:|---:|
| `buildCorpus`, empty datamart | 3020 ± 988 | 6.6 |
| `updateBatch`, full datamart | 1654 ± 149 | 12.1 |
| `rebuild` (`POST /index/rebuild`) | 1679 ± 106 | 11.9 |

*Table 7.y.4. Memory of indexing the corpus. Source: `.../footprint.csv` and `.../jmh.csv`.*

| Measure | 1 thread | 2 threads | 4 threads |
|---|---:|---:|---:|
| Java heap before indexing, after a GC (MB) | 3.82 | 3.92 | 3.93 |
| Peak Java heap while indexing (MB) | 208.0 | 210.5 | 210.5 |
| Java heap after indexing, after a GC (MB) | 3.91 | 3.93 | 3.93 |
| Allocated per corpus pass, `buildCorpus` (MB) | 254.1 | 254.1 | 254.1 |

The peak heap is the sum of the peaks of the heap pools, so it is an upper bound that depends on
the garbage collector. The SQLite page cache is native memory and is not included. The resident
memory of the whole Indexing process is in Table 7.x.4.

## Discussion

- **Construction scales, persistence does not.** Analysing the corpus in memory is almost
  perfectly parallel: 1.94× with 2 threads and 3.54× with 4 (Table 7.y.2). Persisting it stops
  improving after 2 threads (591 ms with 2 threads, 630 ms with 4, within the error). This is the
  single SQLite writer: extra threads only queue on the write lock. The incremental update, where
  storing dominates, gains only 1.24× and 1.38×. The integration benchmarks see the same ceiling
  through the broker: 12.3 books/s with 1 worker, 18.0 with 2, 18.5 with 4 and 18.4 with two
  replicas (mean of 3 runs, `20261105_indexing-throughput/raw.csv`). A second replica adds no
  writer capacity, because both processes write the same file.
- **The cost of a book is the transaction, not the text.** For book 1342, `indexBook` takes
  27.3 ms against 8.1 ms for tokenizing and 0.006 ms for the header (Table 7.y.1). Tokenizing
  allocates 14 times more memory than storing, but it runs in parallel; the transaction does not.
- **Rebuild and update cost the same.** The complete rebuild takes 1679 ms against 1654 ms for
  re-indexing the same batch with 1 thread (Table 7.y.3): reading the ingestion log and checking
  for removed books is negligible next to the re-indexing itself. With 1 thread both handle about
  12 books per second, as fast as the whole pipeline with 1 worker (12.3 books/s), which mostly
  re-indexes books as well. Coordinating through the broker therefore adds little to the cost of
  indexing a book.
- **Why one thread is penalised.** With 1 thread, `persistCorpus` is more than twice as slow as
  with 2 (2.21×), which a single writer cannot explain. Each `DatamartStore` call opens and closes
  its own connection, and when the last connection to a WAL database closes, SQLite checkpoints the
  whole WAL into the database file and deletes it. With one thread and no other open connection,
  that happens after every book. With two or more threads the connections overlap, and the costly
  checkpoint is mostly avoided. A diagnostic run outside the official measurements, holding one
  extra connection open during the pass, removed most of the penalty, which supports this
  explanation. With the default of 2 workers the connections of the service usually overlap, so
  the figures with 2 or more threads are the representative ones; a single worker would pay this
  checkpoint after every book.
- **An unexplained outlier.** `buildCorpus` with 1 thread (3020 ± 988 ms, individual passes from
  2.1 to 3.8 s) is slower than its two parts measured separately (224 ms + 1303 ms), and slower than
  updating a full datamart. It is the only configuration with that behaviour; with 2 and 4 threads,
  building is faster than updating (1127 against 1333 ms, and 788 against 1199 ms), as expected
  because an update must also delete the previous postings. It only affects passes that create a
  new database file in the system's temporary directory, so we suspect the file system or the
  antivirus scanning the new files of that machine, but we could not reproduce it elsewhere. We
  therefore do not draw conclusions from that single figure.
- **Memory.** The index lives on disk. The heap returns to under 4 MB after indexing, whatever the
  number of threads (Table 7.y.4). While indexing, the peak stays near 210 MB with 1, 2 or 4
  threads, and a pass allocates 254 MB, 94 % of it in the analysis (tokenizing and counting). These
  are short-lived objects, so the garbage collector reclaims them cheaply.
- **Possible improvement.** Keeping one connection per worker open for its whole life, instead of
  one per book, would avoid the per-book checkpoint with 1 worker and the cost of opening
  connections. We did not change it after the functional freeze, because that would invalidate
  the measurements; it is a candidate for Stage 3.

## Limits of the evidence

- **One machine, one disk.** The benchmarks measure the code on the reference machine. The cost of
  the commits depends on its disk and file cache.
- **Small corpus.** With 20 books, the datamart fits in the file cache, so the times reflect CPU and
  SQLite work rather than disk reads. A larger corpus would make the single writer and the size of
  the `postings` index weigh more.
- **No coordination.** These figures exclude the broker, HTTP and event publication; section 7.x
  measures the whole chain.
