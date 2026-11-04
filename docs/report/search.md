# 5.x Search Service

The Search Service implements the front-end API of section 4.1. It is a Java 17 Javalin application
with Gson for JSON and `sqlite-jdbc` to read the datamart, packaged as an executable jar
(`search-service/target/search-service.jar`). It is configured with `SEARCH_PORT` (default 7003),
`DATA_DIR` and `BROKER_URL`. It never writes any data: it only reads the datamart that the Indexing
Service owns, and it listens to `BookIndexed` events.

## Internal structure

| Package | Class | Responsibility |
|---|---|---|
| `search` | `SearchApp` | Wiring and start-up from the configuration; CORS; `/health`; closes the subscription on shutdown |
| `search.api` | `SearchController`, `ApiResponses` | HTTP layer: reads parameters, calls the core, writes JSON in `snake_case` with explicit `null`s, and maps each exception to the common error envelope and its status code |
| `search.query` | `SearchQuery`, `InvalidParameterException` | Validation and normalisation of the search parameters; the only place where the rules of section 4.1 are enforced |
| `search.store` | `DatamartReader`, `IndexUnavailableException` | Read-only SQL over `datamart.db`: search, book details and filter values |
| `search.cache` | `FilterValuesCache` | In-memory filter values, trusted only while the event subscription is up |
| `search.events` | `BookIndexedSubscriber`, `BookIndexedEvent` | Subscription to the `books.indexed` topic |
| `search.model` | `SearchHit`, `SearchPage`, `BookDetails`, `FilterValues` | Immutable results passed between the layers |

The layers depend only downwards. The controller knows nothing about SQL, and the reader knows
nothing about HTTP, so each one is tested on its own: validation, SQL against a hand-built datamart,
HTTP against a running server, and the subscription against a real broker.

## How a search is answered

1. **Validation.** `SearchQuery.parse` checks every parameter and normalises it: it trims `q` and
   the filters, lower-cases the language and ignores blank filters. A broken rule raises
   `InvalidParameterException`, whose message is returned with `400 INVALID_PARAMETER`.
2. **Tokenization.** `q` is split by `common.TextTokenizer`, the class the Indexing Service also
   uses to build the index. The query words are therefore produced by exactly the same rule as the
   indexed terms: lower case with `Locale.ROOT`, then runs of Unicode letters. The terms are
   de-duplicated, keeping the order of appearance.
3. **One SQL query for the matches.** A subquery keeps the books whose postings contain all the
   terms and sums their frequencies:

   ```sql
   SELECT book_id, SUM(frequency) AS score FROM postings
   WHERE term IN (?, ?, ...) GROUP BY book_id HAVING COUNT(*) = <number of terms>
   ```

   The primary key `(term, book_id)` of `postings` serves the `IN` lookup. The filters are applied
   on `books`, the result is counted for `total` and the page is read with
   `ORDER BY score DESC, book_id ASC LIMIT ? OFFSET ?`.
4. **Unicode-aware author filter.** SQLite's `lower()` only folds ASCII letters, so `BRONTË` would
   not match `Brontë`. `DatamartReader` therefore registers a small SQL function, `casefold`, that
   calls Java's `toLowerCase(Locale.ROOT)`, and filters with `instr(casefold(author), ?) > 0`.
5. **Consistent reads.** Every request opens its own read-only connection and runs the count and
   the page in **one read transaction**. In WAL mode a reader keeps a snapshot, so `total` and
   `results` always describe the same version of the index, even if Indexing commits a book in
   between. Search sees each book either before or after its commit, never half-indexed.
   Connections are never shared between Jetty threads.

## Readiness and failure behaviour

- **Index not created yet.** Search starts even if the datamart does not exist yet, for example
  before Indexing has run. While the file or its tables are missing, every data endpoint answers
  `503 UNAVAILABLE`, and `/health` still answers `200`. Search never creates the datamart, so it
  cannot interfere with its owner.
- **Broker down.** Search also starts without a broker. The subscription uses ActiveMQ's
  `failover:` transport, connects in a background thread and reconnects with a growing delay after
  a broker restart, so searches never wait for the broker.
- **Unexpected errors.** They are logged with their stack trace and returned as `500 INTERNAL_ERROR`
  with a fixed message, so internal details never reach the client.

## Publication of completed index updates

Searches read the datamart directly, so a book is searchable as soon as Indexing commits it. The
`BookIndexed` events only matter for the one cached value, the filter choices of
`GET /search/filters`, which would otherwise scan the `books` table on every call.

- **While subscribed.** `FilterValuesCache` keeps the values in memory, and every `BookIndexed`
  event drops them.
- **While the connection is interrupted.** Events could be missed, so the cache is disabled and
  every call reads the datamart. When the connection is restored, the cache is emptied and enabled
  again.
- **Races.** A generation counter prevents a slow read that started before an event from storing
  outdated values after it.

The topic subscription is not durable, so every replica receives every event while it is
connected. Events missed while it is disconnected do not matter, because of the rule above.

## Replicas and threads

The service is stateless apart from that cache, so any number of replicas can run on different
ports over the same datamart. Each replica subscribes to the topic and keeps its own cache. Within a
process, Javalin's Jetty thread pool serves requests concurrently. There is no shared mutable state
apart from the cache, which is synchronised, and each request has its own SQLite connection. Read
transactions in WAL mode do not block each other or the Indexing writer, so concurrency is limited
by CPU rather than by locks. The integration benchmarks (section 7) measure this with 1 to 64
clients and with 1 and 2 replicas.

## Tests

The service has 78 tests:

| Test class | Tests | What it covers |
|---|---|---|
| `SearchAppHealthTest` | 1 | `/health` |
| `SearchQueryTest` | 39 | Every rule and message |
| `DatamartReaderTest` | 18 | AND, ordering with ties, each filter, pagination, `null` metadata, empty and missing index |
| `SearchControllerTest` | 10 | Exact documented bodies, every status code, CORS |
| `FilterValuesCacheTest` | 5 | The cache |
| `BookIndexedSubscriberTest` | 5 | Real ActiveMQ broker, including a broker restart |

On top of these, the API checks of section 4.4 exercise the running service.
