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
tokenizer, corpus) are in [`docs/contracts.md`](docs/contracts.md).

> **Status:** base setup. Every service builds, starts and answers `GET /health`; the business
> endpoints, the broker and the API specification are being added on top of this base.

## Repository layout

```text
stage_2/
├── pom.xml               # parent POM: modules, dependency versions, plugins, "jmh" profile
├── common/               # Config, TextTokenizer, Sqlite, BookRecord (shared by all services)
├── ingestion-service/    # com.thescratchers.searchengine.ingestion
├── indexing-service/     # com.thescratchers.searchengine.indexing
├── search-service/       # com.thescratchers.searchengine.search
├── samples/              # fixed corpus: books.txt, words.txt and the 20 books split in books/
└── docs/
    └── contracts.md      # shared contracts between services
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

Each service is an independent process:

```bash
java -jar ingestion-service/target/ingestion-service.jar
java -jar indexing-service/target/indexing-service.jar
java -jar search-service/target/search-service.jar

curl http://localhost:7003/health     # {"service":"search-service","status":"UP"}
```

## Configuration

Every value is read from an environment variable, then from a JVM system property with the same
name (`-DKEY=value`), then from the default. Nothing is hard-coded in the services.

| Variable | Default | Meaning |
|---|---|---|
| `DATA_DIR` | `./data` | Root of `datalake/` and `datamarts/` (created on startup if missing) |
| `INGESTION_PORT` | `7001` | HTTP port of the Ingestion Service |
| `INDEXING_PORT` | `7002` | HTTP port of the Indexing Service |
| `SEARCH_PORT` | `7003` | HTTP port of the Search Service |

Example (PowerShell): `$env:SEARCH_PORT=8003; java -jar search-service/target/search-service.jar`

## Fixed corpus

All tests, API checks and benchmarks use the same 20 Gutenberg books (`samples/books.txt`) and the
same 10 query words (`samples/words.txt`), inherited from Stage 1. The books are bundled in
`samples/books/` already split into `<id>.header.txt` and `<id>.body.txt`, so tests run offline.

## Microbenchmarks (JMH)

JMH benchmarks live in `src/test/java/.../benchmark/` of each service. To run those of one module:

```bash
mvn -q install -DskipTests
mvn -pl indexing-service -Pjmh test-compile exec:exec -Djmh.args="-rf csv -rff jmh-results.csv"
```

## Technology versions

Java 17 · Javalin 6.1.3 · Gson 2.11.0 · SLF4J 2.0.9 · sqlite-jdbc 3.47.1.0 · ActiveMQ 6.3.2 ·
JMH 1.37 · JUnit 5.11.4 (all pinned in the parent `pom.xml`).
