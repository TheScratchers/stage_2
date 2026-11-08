# Search Engine – Stage 2: Service-Oriented Architecture

Big Data · BSc in Data Science and Engineering · Universidad de Las Palmas de Gran Canaria (ULPGC) — team **TheScratchers**

Stage 2 turns the Stage 1 data layer (datalake + datamarts) into cooperating Java 17 microservices
built with Javalin and Gson, all running on a single machine:

| Service | Responsibility | Default port |
|---|---|---|
| `ingestion-service` | Downloads Project Gutenberg books, splits header/body and stores them in the datalake | 7001 |
| `indexing-service` | Extracts metadata (title, author, language, year) and builds the inverted index in the datamart | 7002 |
| `search-service` | Front-end API: keyword search with author, language and year filters and pagination | 7003 |

Coordination is choreography through an **ActiveMQ** broker (`BookDownloaded` / `BookIndexed` events),
complemented by internal HTTP endpoints. The shared rules (data ownership, SQLite schema, events,
tokenizer, corpus) are in [`docs/contracts.md`](docs/contracts.md), and every HTTP endpoint is
specified in [`docs/openapi.yaml`](docs/openapi.yaml) (OpenAPI 3.0).

> **Status:** complete. All three services (`ingestion-service`, `indexing-service`, `search-service`),
> the ActiveMQ broker, automated end-to-end integration tests, and official benchmarks on the reference
> machine are implemented, verified (`mvn clean verify` passes cleanly across all 9 modules), and
> thoroughly documented in [`docs/report/`](docs/report/).

## Repository layout

```text
stage_2/
├── pom.xml               # parent POM: modules, dependency versions, plugins, "jmh" profile
├── common/               # Config, TextTokenizer, Sqlite, BookRecord (shared by all services)
├── broker/               # com.thescratchers.searchengine.broker (ActiveMQ broker process)
├── ingestion-service/    # com.thescratchers.searchengine.ingestion
├── indexing-service/     # com.thescratchers.searchengine.indexing
├── search-service/       # com.thescratchers.searchengine.search
├── api-tests/            # data-driven HTTP checks of the APIs and their expected results
├── benchmarks/           # load-client/ (HTTP load generator) and results/ of the integration benchmarks
├── samples/              # fixed corpus: books.txt, words.txt and the 20 books split in books/
├── scripts/              # POSIX and PowerShell runner scripts (benchmarks, automation)
└── docs/
    ├── contracts.md      # shared contracts between services
    └── openapi.yaml      # versioned HTTP API specification (OpenAPI 3.0)
```

## Requirements

- JDK 17 or newer (the code targets Java 17)
- Maven 3.9 or newer

## Build and test

```bash
mvn clean verify
```

This compiles every module, runs all the tests and produces one executable jar per service:
`<service>/target/<service>.jar`. Benchmark classes live under `src/test/java` and are never
included in those jars.

## Run the services

Every service is an independent process on this machine. After `mvn clean verify`, the scripts in
`scripts/` start the whole system, load the fixed corpus and stop it again (PowerShell on Windows,
bash on Linux and macOS):

```bash
./scripts/start-all.sh        # .\scripts\start-all.ps1   broker, Indexing, Search, Ingestion; waits for /health
./scripts/load-corpus.sh      # .\scripts\load-corpus.ps1 POST /ingest/{id} for samples/books.txt, waits until indexed
curl "http://localhost:7003/search?q=monster&limit=3"
./scripts/stop-all.sh         # .\scripts\stop-all.ps1    stops every process; the data is kept
```

`start-all` starts the broker first, then the Indexing and Search replicas and finally Ingestion,
each with `JAVA_OPTS` (default `-Xmx1g`). Each process writes its log to `DATA_DIR/logs/<name>.log`
and its PID to `DATA_DIR/run/<name>.pid`. Replicas and threads are set with environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `INDEXING_REPLICAS` | `1` | Indexing processes, on ports `INDEXING_PORT`, `+10`, `+20`, ... (7002, 7012, ...) |
| `SEARCH_REPLICAS` | `1` | Search processes, on ports `SEARCH_PORT`, `+10`, `+20`, ... (7003, 7013, ...) |
| `INDEXING_WORKERS` | `2` | Consumer threads of each Indexing process |
| `BROKER_PORT` | `61616` | Port of the broker; every service gets `BROKER_URL=tcp://localhost:<port>` |
| `JAVA_OPTS` | `-Xmx1g` | JVM options of every service |

For example, `SEARCH_REPLICAS=2 INDEXING_REPLICAS=2 ./scripts/start-all.sh`
(PowerShell: `$env:SEARCH_REPLICAS=2; $env:INDEXING_REPLICAS=2; .\scripts\start-all.ps1`).

A single service can also be started by hand, for example
`java -jar search-service/target/search-service.jar`, with the settings of the next section.

Search API examples (full contract in [`docs/openapi.yaml`](docs/openapi.yaml)):

```bash
curl "http://localhost:7003/search?q=monster&limit=3"
curl "http://localhost:7003/search?q=adventure&author=twain&language=en&year=2004"
curl "http://localhost:7003/books/1342"
curl "http://localhost:7003/search/filters"
```

The Search Service starts even if the datamart or the broker are not available yet: searches
answer `503 UNAVAILABLE` until Indexing has created the datamart, and the subscription to
`BookIndexed` events connects (and reconnects) in the background. CORS is enabled for any origin,
so a browser front-end on another origin can call it directly.

## Configuration

Every value is read from an environment variable, then from a JVM system property with the same
name (`-DKEY=value`), then from the default. Nothing is hard-coded in the services.

| Variable | Default | Meaning |
|---|---|---|
| `DATA_DIR` | `./data` | Root of `datalake/` and `datamarts/` (created on startup if missing) |
| `INGESTION_PORT` | `7001` | HTTP port of the Ingestion Service |
| `INDEXING_PORT` | `7002` | HTTP port of the Indexing Service |
| `SEARCH_PORT` | `7003` | HTTP port of the Search Service |
| `BROKER_URL` | `tcp://localhost:61616` | ActiveMQ broker connector URL |
| `GUTENBERG_BASE_URL` | `https://www.gutenberg.org` | Source of the downloads (`/cache/epub/{id}/pg{id}.txt`) |
| `GUTENBERG_TIMEOUT_SECONDS` | `30` | Connection and request timeout for each download |
| `DATALAKE_LAYOUT` | `time_based` | Datalake structure: `time_based` (`yyyyMMdd/HH`, UTC), `book_based` or `batch_based` |

Example (PowerShell): `$env:SEARCH_PORT=8003; java -jar search-service/target/search-service.jar`

## API checks

[`api-tests/`](api-tests/README.md) holds automated HTTP checks of the APIs with their expected
results. The Search checks over a fixture run on every `mvn clean verify`. The end-to-end checks
start the whole system from its jars, load the fixed corpus and check the front-end and internal
APIs, including the exact `book_id`s returned for every query word:

```bash
java -jar api-tests/target/api-tests.jar pipeline          # 39/39 checks passed
java -jar api-tests/target/api-tests.jar check http://localhost:7003 api-tests/cases/search.json
```

See [`api-tests/README.md`](api-tests/README.md) for the procedures, the data and what each case
file checks.

## Fixed corpus

All tests, API checks and benchmarks use the same 20 Gutenberg books (`samples/books.txt`) and the
same 10 query words (`samples/words.txt`), inherited from Stage 1. The books are bundled in
`samples/books/` already split into `<id>.header.txt` and `<id>.body.txt`, so tests run offline.

## Ingestion benchmarks

The Ingestion Service throughput and latency benchmark (`IngestionBenchmark.java`) evaluates fresh
and idempotent ingestion across 1, 2, and 4 concurrent clients:

```bash
# POSIX (Linux / macOS)
./scripts/run-ingestion-benchmark.sh

# Windows (PowerShell)
.\scripts\run-ingestion-benchmark.ps1
```

Results are stored in `benchmarks/results/<date>_ingestion-throughput/`.

## Microbenchmarks (JMH)

JMH benchmarks live in `src/test/java/.../benchmark/` of each service. To run those of one module:

```bash
mvn -q install -DskipTests
mvn -pl indexing-service -Pjmh test-compile exec:exec -Djmh.args="-rf csv -rff jmh-results.csv"
```

## Integration benchmarks

`benchmarks/load-client` sends concurrent searches to one or more Search replicas and records
every request, the p50/p95/p99 latencies per concurrency level, the exact command and the host
description. `benchmarks/integration` runs every integration experiment of the report on the real
system with one command (search latency with 1 and 2 replicas, indexing throughput with 1 to 4
workers and 2 replicas, coordination cost, CPU, memory and disk per process):

```bash
java -jar benchmarks/integration/target/integration-bench.jar
```

See [`benchmarks/README.md`](benchmarks/README.md) for the experiments, the result files and the
procedure for the official run.

## Report

The report is [`docs/report.pdf`](docs/report.pdf). Its source is the set of Markdown sections in
[`docs/report/`](docs/report/), one per section of the assignment, with figures in
`docs/report/figures/`. To rebuild the PDF after editing a section (needs pandoc 3.1+ and Typst
0.13+ on the PATH):

```bash
python scripts/build_report.py
```

The script assembles the sections in order, numbers the cross-references, inserts the rendered
architecture diagrams and compiles `docs/report.typ` into `docs/report.pdf`, with a cover page, a
table of contents and page numbers. If a Mermaid diagram of `docs/report/arquitectura.md` changes,
render it again into `docs/report/figures/architecture-<n>.png` with the Mermaid CLI (`mmdc`).

## Technology versions

Java 17 · Javalin 6.1.3 · Gson 2.11.0 · SLF4J 2.0.9 · sqlite-jdbc 3.47.1.0 · ActiveMQ 6.3.2 ·
JMH 1.37 · JUnit 5.11.4 (all pinned in the parent `pom.xml`).
