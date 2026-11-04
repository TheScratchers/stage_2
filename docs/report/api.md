# 4. API design and contracts

The system exposes two kinds of interface. A **front-end API**, provided by the Search Service, is
the only one a user-facing application calls. **Internal interfaces** connect the services with each
other and with the operators who run them. Following the choreography design, services never call
each other over HTTP: Ingestion and Indexing coordinate through broker events, and the only internal
HTTP endpoints are administrative ones used by scripts, operators and benchmarks.

The complete, versioned specification is
[`docs/openapi.yaml`](https://github.com/TheScratchers/stage_2/blob/main/docs/openapi.yaml)
(OpenAPI 3.0, version 1.0.0). It contains every route, parameter, schema, status code and
example. The non-HTTP contracts (broker events, data ownership, SQLite schema and tokenizer) are in
[`docs/contracts.md`](https://github.com/TheScratchers/stage_2/blob/main/docs/contracts.md).

## 4.1 Front-end use cases and endpoints

A reader looking for books needs three operations, and the front-end API offers exactly these:

| Use case | Endpoint | Provider | Consumers |
|---|---|---|---|
| Search books by keywords, optionally filtered by author, language and year, page by page | `GET /search` | Search Service (port 7003) | Search front-end, API checks, search benchmarks |
| Show the details of a book from the results | `GET /books/{book_id}` | Search Service | Search front-end, API checks |
| Offer the available languages and years as filter choices | `GET /search/filters` | Search Service | Search front-end, API checks |

All three are read-only, idempotent and safe to retry. A front-end served from another origin can
call them directly: the Search Service enables CORS for any origin (no credentials), answers
preflight requests, and only exposes `GET` operations.

**`GET /search` semantics.** All parameters are query parameters.

| Parameter | Rule | Default |
|---|---|---|
| `q` | Required, at most 200 characters, at least one word after tokenization. Words are combined with **AND**; repeated words count once | — |
| `author` | Case-insensitive substring of the author (`twain` matches `Mark Twain`); books without an author never match | none |
| `language` | Two-letter ISO 639-1 code, case-insensitive, normalised to lower case | none |
| `year` | Four-digit Project Gutenberg release year (the e-book header has no original publication year) | none |
| `offset` | 0 to 999 999 999 | 0 |
| `limit` | 1 to 100 | 20 |

Filters combine with each other and with the query, and empty filter values are ignored. Results
are ordered by **score**, the sum of the frequencies of the query words in the book, from highest
to lowest; ties are broken by ascending `book_id`. The order is therefore deterministic and pages
never overlap. The response separates `total` (all matching books) from `results` (the requested
page) and echoes the normalised `filters`. A valid query with no matches returns `200` with an
empty list. An `offset` beyond `total` also returns an empty page and keeps `total`.

Example, recorded from the real system loaded with the fixed corpus:

```text
GET /search?q=darcy&language=EN&year=1998
200 {"query":"darcy","terms":["darcy"],"filters":{"language":"en","year":1998},"total":1,"offset":0,
     "limit":20,"results":[{"book_id":1342,"title":"Pride and Prejudice","author":"Jane Austen",
     "language":"en","year":1998,"score":432}]}
```

## 4.2 Internal and coordination interfaces

**Broker events (non-HTTP).** The main internal interactions are two events on the ActiveMQ broker.
Both are JSON with `snake_case` fields and a `version`, and both are sent as persistent messages.

| Destination | Type | Producer → consumers | Payload | Meaning |
|---|---|---|---|---|
| `books.downloaded` | queue | Ingestion → Indexing (competing consumers) | `event`, `version`, `book_id`, `header_path`, `body_path`, `ingested_at` | Both files of the book are complete in the datalake and recorded in the ingestion log |
| `books.indexed` | topic | Indexing → every Search replica | `event`, `version`, `book_id`, `terms`, `indexed_at` | The book has been committed to the datamart |

The rules that make this choreography reliable are part of the contract:
- **Acknowledgement after commit.** Indexing acknowledges a `BookDownloaded` message only after the
  SQLite transaction of the book has committed. It does so in the same JMS transaction that
  publishes `BookIndexed`. If anything fails, the message returns to the broker.
- **Retries and dead letters.** The broker redelivers a failed message up to 5 times and then moves
  it to `ActiveMQ.DLQ`.
- **Duplicates.** Redelivered or repeated events are harmless, because indexing a book replaces its
  previous version.
- **Ordering.** No order between different books is assumed.

**Administrative HTTP endpoints.** These are not meant for the front-end and do not enable CORS.

| Endpoint | Provider | Consumers | Completion | Idempotency and retries |
|---|---|---|---|---|
| `POST /ingest/{book_id}` | Ingestion (7001) | Corpus-loading scripts, operators | Synchronous: on `200` the files are in the datalake, the log says `available` and `BookDownloaded` has been published | Re-ingesting an available book downloads nothing and re-publishes the event (`already_available`); safe to retry |
| `GET /ingest/status/{book_id}`, `GET /ingest/list` | Ingestion | Scripts, operators | — | Read-only |
| `POST /index/update/{book_id}` | Indexing (7002) | Operators, recovery | Synchronous: on `200` the new version is committed and visible to Search | Replaces the book; safe to retry |
| `POST /index/rebuild` | Indexing | Operators, benchmarks | Synchronous; Search never sees an empty or partial index | Re-runnable; `409` if one is already running in that process |
| `GET /index/status` | Indexing | Scripts, benchmarks | — | Read-only |
| `GET /health` | Every service | Start-up scripts, tests, benchmarks, monitoring | Liveness of the process only | Read-only |

Callers of `POST /ingest` must expect a slow upstream. The Gutenberg download has a configurable
timeout (`GUTENBERG_TIMEOUT_SECONDS`, 30 s by default), so they should allow at least that long.
The failure responses tell the caller what to do:
- `404 NOT_FOUND`: the book does not exist on Gutenberg. Retrying returns the same error.
- `502 UPSTREAM_ERROR`: Gutenberg failed or timed out. Retrying later may succeed.
- `503 UNAVAILABLE`: the broker could not take the event. The book stays stored, and retrying the
  same request publishes the event again.

## 4.3 Common conventions

- **Format.** JSON in UTF-8, `snake_case` fields, `book_id` as a positive integer and timestamps
  in ISO-8601 UTC. Missing metadata is returned as an explicit `null`.
- **Errors.** Every error, in every service, uses one envelope:
  `{"error":{"code":"INVALID_PARAMETER","message":"limit must be between 1 and 100"}}`.
- **Status codes.**

  | Status | Code | Used for |
  |---|---|---|
  | 400 | `INVALID_PARAMETER` | Invalid input |
  | 404 | `NOT_FOUND` | Unknown book |
  | 409 | `CONFLICT` | Rebuild already running |
  | 502 | `UPSTREAM_ERROR` | Gutenberg failure |
  | 503 | `UNAVAILABLE` | Datamart not created yet, or broker down |
  | 500 | `INTERNAL_ERROR` | Unexpected errors; internal details are only logged |

- **Health.** Every process answers `GET /health` with its name and `UP`. Start-up scripts and
  benchmarks poll it before sending work.

## 4.4 Validation evidence

The contract is checked by code that runs on every build, not only by inspection. Everything is in
[`api-tests/`](https://github.com/TheScratchers/stage_2/tree/main/api-tests), with instructions and
expected results.

| Evidence | What it shows | How it runs |
|---|---|---|
| `cases/search.json`, 44 checks over a hand-made six-book datamart | Keyword search, AND of words, every filter alone and combined, empty results, pagination, every invalid parameter with its exact message, book details, filter values, `null` metadata, CORS | Every `mvn verify`, against an in-process Search Service |
| `cases/corpus-search.json`, 20 checks over the real pipeline | The exact `book_id`s and scores returned for each word of `samples/words.txt`, cross-checked against an independent SQL query, and the specification's examples | `java -jar api-tests/target/api-tests.jar pipeline` |
| `cases/ingestion.json` (10) and `cases/indexing.json` (9) | Internal operations and their completion and error behaviour: idempotent re-ingestion and update, unknown and invalid ids, a missing book on Gutenberg, a full rebuild | Same command |
| The pipeline itself | The broker contract end to end: books reach Indexing and Search only through `BookDownloaded` and `BookIndexed` | Same command |
| `OpenApiContractTest` | No checked route or status code is missing from `openapi.yaml`, every documented operation is exercised, and every `GET /search` example equals the real response | Every `mvn verify` |
| Broker and failure tests | Ingestion answers `503` and keeps the book when the broker is down; Indexing acknowledges only after commit, a killed Indexing process leaves no half-indexed book, and 1, 2 and 4 workers or 2 replicas build an identical index; Search keeps answering and stops caching while the broker is down | Every `mvn verify` (service test suites) |

The pipeline command starts the four services from their jars and loads the 20 corpus books. It ends
with `39/39 checks passed`. The books are served offline from `samples/books` by a local stand-in
for Project Gutenberg, so the expected results do not depend on the network. When the same checks
were run against the live site, every `book_id` and score still matched, but one title differed:
Gutenberg had edited book 84 after the corpus was captured in Stage 1. This is why every experiment
of this report uses the fixed corpus.
