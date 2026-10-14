# Stage 1: Java Performance Benchmark Report

**Module:** Datalake layouts, inverted-index structures (JSON monolithic, hierarchical JSON, SQLite) and SQLite metadata datamart
**Frameworks:** JMH 1.37 (index, JDK 24.0.1, GC profiler); wall-clock timing for datalake and metadata
**Author:** Amado Artiles Rodriguez (TheScratchers) - the full report with architecture and scope table is in `stage_1_java_report.pdf`

## 1. Methodology

All three benchmarks use the shared contract (`shared/CONTRACT.md`): the 20 real books of `shared/books.txt`, the contract tokenizer (lowercase, then maximal runs of `[A-Za-z]`), the 10 words of `shared/words.txt`, scales 100 / 1,000 / 10,000 (metadata also 100,000) and the replication rule: the synthetic book at 0-indexed position `p` uses real book `p mod 20`. Every benchmark stops with an error if any of the 20 books is missing in `data/datalake`.

**Inverted index (JMH):** one operation looks up the 10 words (per-word figure = batch time / 10); `build(map)` is timed from a pre-built in-memory map, with the storage cleaned before every iteration; the three structures are populated once at trial level before measuring queries. Average time (ms/op), 1 fork, 1 warm-up and 1 measurement iteration of 1 s. `gc.alloc.rate.norm` is bytes allocated per operation (allocation churn, not peak memory); it is analysed only for queries, because for builds the profiler window also covers the per-iteration setup.

**Datalake (`DatalakeBenchmark`):** for each layout (time-based, book-based, batch-based with 1,000 ids per batch) and scale: write N books, 100-200 random lookups (resolve path and read the body), storage overhead, incremental processing (detect which of the second half of the ids plus N/10 new ids are new, then write only those) and recovery (wipe, write the first half, resume skipping existing files, check that exactly N body files exist). A 200-book JVM warm-up pass is discarded; the hour directory of the time-based layout is fixed per run; synthetic data goes to the system temp directory (`-Dbench.dir` overrides it).

**Metadata (`MetadataBenchmark`):** upsert N rows with one connection per call (the production code path), 200 `findById` lookups, 30 repetitions of each of the two author queries (Charles Dickens = books 98, 46, 1400; Lewis Carroll = book 11) and the database size. Rows replicate the title/author/language of the 20 real headers by position. The benchmark aborts if the number of rows returned for an author differs from the expected one.

## 2. Results

### 2.1 Datalake layouts

| Layout | Books | Write (s) | Books/s | Lookup (ms) | Dirs | Max files/dir | Incr. detect (s) | Incr. write (s) | Resumed | Recovery (s) | Complete |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
| time_based | 100 | 0.54 | 184.7 | 2.18 | 2 | 100 | 0.0045 | 0.070 | 50 | 0.24 | yes |
| time_based | 1,000 | 5.42 | 184.3 | 2.63 | 2 | 1,000 | 0.0937 | 0.676 | 500 | 2.72 | yes |
| time_based | 10,000 | 51.91 | 192.7 | 2.84 | 2 | 10,000 | 0.5007 | 5.021 | 5,000 | 25.50 | yes |
| book_based | 100 | 0.50 | 201.9 | 2.04 | 100 | 1 | 0.0050 | 0.060 | 50 | 0.27 | yes |
| book_based | 1,000 | 5.28 | 189.2 | 2.01 | 1,000 | 1 | 0.0437 | 0.591 | 500 | 2.59 | yes |
| book_based | 10,000 | 49.47 | 202.1 | 2.45 | 10,000 | 1 | 0.4652 | 5.120 | 5,000 | 25.09 | yes |
| batch_based | 100 | 0.54 | 185.1 | 2.18 | 1 | 100 | 0.0047 | 0.062 | 50 | 0.25 | yes |
| batch_based | 1,000 | 5.04 | 198.4 | 2.23 | 1 | 1,000 | 0.0466 | 0.524 | 500 | 2.61 | yes |
| batch_based | 10,000 | 49.52 | 201.9 | 2.41 | 10 | 1,000 | 0.4361 | 5.101 | 5,000 | 25.54 | yes |

### 2.2 Inverted index: build (bulk)

| Structure | Books | Build time (s) | vs. previous scale |
| :--- | ---: | ---: | ---: |
| JSON monolithic | 100 | 0.022 | - |
| JSON monolithic | 1,000 | 0.181 | x8.0 |
| JSON monolithic | 10,000 | 2.50 | x13.8 |
| Hierarchical JSON | 100 | 0.067 | - |
| Hierarchical JSON | 1,000 | 0.316 | x4.7 |
| Hierarchical JSON | 10,000 | 2.04 | x6.4 |
| SQLite | 100 | 0.914 | - |
| SQLite | 1,000 | 7.90 | x8.6 |
| SQLite | 10,000 | 111.72 | x14.1 |

### 2.3 Inverted index: query (batch of the 10 contract words)

| Structure | Books | Batch of 10 words (ms) | Per word (ms) | vs. previous scale | Allocated (MB/op) |
| :--- | ---: | ---: | ---: | ---: | ---: |
| JSON monolithic | 100 | 598.1 | 59.81 | - | 227.3 |
| JSON monolithic | 1,000 | 2,879.0 | 287.9 | x4.8 | 2,194.3 |
| JSON monolithic | 10,000 | 33,092.2 | 3,309.2 | x11.5 | 21,606.6 |
| Hierarchical JSON | 100 | 27.33 | 2.73 | - | 12.1 |
| Hierarchical JSON | 1,000 | 107.7 | 10.77 | x3.9 | 102.1 |
| Hierarchical JSON | 10,000 | 1,064.6 | 106.5 | x9.9 | 988.5 |
| SQLite | 100 | 1.02 | 0.10 | - | 0.02 |
| SQLite | 1,000 | 1.93 | 0.19 | x1.9 | 0.23 |
| SQLite | 10,000 | 10.80 | 1.08 | x5.6 | 2.46 |

### 2.4 Metadata datamart (SQLite)

| Books | Insert (s) | Books/s | find_by_id (ms) | Dickens (ms) | Carroll (ms) | DB size (KB) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 0.79 | 127.3 | 1.80 | 2.14 | 1.75 | 16 |
| 1,000 | 7.09 | 141.1 | 1.57 | 2.54 | 2.35 | 72 |
| 10,000 | 69.58 | 143.7 | 1.54 | 8.38 | 6.55 | 620 |
| 100,000 | 675.10 | 148.1 | 1.65 | 65.12 | 43.84 | 6,112 |

## 3. Analysis

**Datalake**

- Writing is I/O-bound and layout-neutral: 184-202 books/s at every layout and scale, so total time is linear in the number of books. At 10,000 books time-based is the slowest (192.7 against 202.1 and 201.9 books/s), a difference of about 5% from a single run.
- The layouts differ in structure: 2 / 10,000 / 10 directories at 10,000 books, with up to 10,000 / 1 / 1,000 body files per directory. Lookups cost 2.0-2.8 ms in all cases.
- Incremental ingestion is cheap compared to rebuilding: at 10,000 books, detecting which of 6,000 candidates are new takes about 0.50 s, and writing the 1,000 new books about 5 s, against 49-52 s for the full 10,000.
- Recovery is correct and idempotent in all 9 combinations: after the simulated crash, resuming wrote exactly the missing half and left exactly N body files.
- Cross-check: the structural results (directories, depth, files per directory, candidates, new books found, resumed books) are identical to the committed Python results in all 9 combinations; throughput is of the same order (Python 201-234 books/s at 10,000), on different machines.

**Inverted index**

- **Build:** SQLite is the slowest to build (111.7 s at 10,000 books, about 45x the monolithic JSON and 55x the hierarchical JSON): every (term, book) pair becomes a row in a primary-key B-tree, whereas JSON is serialized as a stream. Monolithic JSON and SQLite grow slightly more than linearly from 1,000 to 10,000 books (x13.8 and x14.1 for 10x more data); hierarchical JSON grows only x6.4, which suggests fixed folder/file-creation overhead dominates at small scales.
- **Query:** the ranking reverses. At 10,000 books the 10 words take 10.8 ms in SQLite, 1,065 ms in hierarchical JSON and 33.1 s in monolithic JSON (SQLite about 99x faster than hierarchical and about 3,063x faster than monolithic). SQLite's time still grows with the scale because each term appears in a fixed fraction of the books, so a lookup returns more rows; this is result-set cost, not B-tree depth.
- **Why JSON loses on reads:** each Java JSON lookup re-reads and parses its file. The monolithic structure allocates about 21.6 GB for one 10-word batch at 10,000 books, hierarchical about 989 MB and SQLite about 2.5 MB.
- **Reading:** JSON is the cheapest structure to write and the most expensive to read; SQLite is the opposite. For an index that is built once and queried many times, the query side dominates, which favours SQLite.

**Metadata**

- Insertion is flat per row (127-148 books/s), so total time is linear: 675 s (11.3 minutes) for 100,000 rows. Each upsert opens a connection, commits one transaction and closes it, so a fixed per-call cost dominates.
- `findById` costs 1.54-1.80 ms from 100 to 100,000 rows (primary-key B-tree); the floor is probably the cost of opening a JDBC connection per call (not measured separately).
- Author search is a full table scan (no index; `LIKE '%...%'`): Charles Dickens 8.4 -> 65.1 ms and Lewis Carroll 6.5 -> 43.8 ms from 10,000 to 100,000 rows. The repeated author is only 1.49x slower although it returns 3x the rows, because the scan is paid in both cases.
- Cross-check with Python (same workload, different machine): the curves have the same shape; Java insertion takes 1.13-1.43x Python's time, the author queries at 100,000 rows 1.14x (Dickens) and 1.27x (Carroll), and `find_by_id` 2.4-3.9x.

## 4. Limitations (for the cross-language comparison)

- Hierarchical JSON in Java is one file per first letter of the term; Python and C++ use one file per term (about 35,000 files), so hierarchical index figures are not directly comparable across languages.
- Java JSON lookups are cold reads (read and parse on every lookup); Python and C++ load once and measure in-memory lookups.
- Java reports allocation per operation, Python peak traced allocations and C++ process resident memory: not the same metric.
- Index figures are one JMH measurement iteration (no confidence interval). At 100 books the SQLite build lasts under one second, so JMH repeats it inside the iteration and the repetition probably inserts into rows that already exist; that figure is likely slightly optimistic.
- Datalake and metadata figures are single wall-clock runs (not JMH), with a JVM warm-up pass but no repetitions.
- The three languages were run on different machines and operating systems; the result files do not record CPU, RAM, disk or JVM version.
- The Java metadata table has no `body_path`/`header_path` columns, so its database size is not comparable with Python's.
- Not recorded for the index: update timing, disk size, file counts and vocabulary size.

## 5. How to reproduce

From the `java/` directory, with the 20 contract books under `java/data/datalake` (`DownloadContractBooks` downloads the missing ones):

```
mvn clean package
java -cp target/benchmarks.jar com.thescratchers.searchengine.benchmark.DownloadContractBooks
java -cp target/benchmarks.jar com.thescratchers.searchengine.benchmark.BenchmarkRunner      # -> results.json
java -cp target/benchmarks.jar com.thescratchers.searchengine.benchmark.DatalakeBenchmark    # -> datamarts/benchmark_datalake_results.json
java -cp target/benchmarks.jar com.thescratchers.searchengine.benchmark.MetadataBenchmark    # -> datamarts/benchmark_metadata_results.json
```

The index tables come from `java/results.json` (commit `f57697a`); the datalake and metadata tables from `java/datamarts/` (commit `4beceb0`).
