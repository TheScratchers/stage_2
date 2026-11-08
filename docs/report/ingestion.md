# 5.x Ingestion Service

The Ingestion Service is the entry point for external data into the search engine. It acquires literary works from Project Gutenberg, parses and separates raw texts into structured headers and bodies, stores artifacts atomically in the datalake, records status in a local SQLite ledger, and emits choreography events to notify downstream services.

It is implemented as a lightweight Java 17 service utilizing Javalin 6.1.3 for its HTTP server, Google Gson 2.11.0 for JSON serialization, `sqlite-jdbc` 3.47.1.0 for local state management, and Apache ActiveMQ 6.3.2 client libraries for event publishing. The service is packaged as an autonomous executable jar (`ingestion-service/target/ingestion-service.jar`).

It is configured through environment variables or system properties via `common.Config`:

| Configuration Variable | Default Value | Description |
|---|---|---|
| `INGESTION_PORT` | `7001` | HTTP listening port |
| `DATA_DIR` | `./data` | Root path for datalake directories and SQLite databases |
| `BROKER_URL` | `tcp://localhost:61616` | ActiveMQ broker connection URL |
| `GUTENBERG_BASE_URL` | `https://www.gutenberg.org` | Base URL for fetching Gutenberg texts (can be pointed to a local mirror) |
| `GUTENBERG_TIMEOUT_SECONDS` | `30` | HTTP client read and connect timeout |
| `DATALAKE_LAYOUT` | `time_based` | Datalake folder organization strategy (`time_based`: `yyyyMMdd/HH/`) |

---


## 5.x.1 Internal Structure and Package Organization

The codebase is organized into decoupled packages with single responsibilities and downward-only dependencies:

| Package | Class / Component | Responsibility |
|---|---|---|
| `ingestion` | `IngestionApp` | Service entry point; wires the dependencies from the configuration and maps the Javalin routes `/health`, `POST /ingest/{book_id}`, `GET /ingest/status/{book_id}` and `GET /ingest/list`, translating failures into the common error envelope. |
| `ingestion` | `IngestionService` | Core business logic: checks the ingestion log, delegates the download, records the result and publishes the `BookDownloaded` event. |
| `ingestion` | `BookDownloader` | Downloads one book, splits it and stores both files in the datalake (download → split → store). It keeps no state, so several threads can download different books at the same time. |
| `ingestion` | `IngestResult` | Immutable record returned by `POST /ingest/{book_id}` (`bookId`, `status`, `path`). |
| `ingestion.gutenberg` | `GutenbergClient` | HTTP client of Project Gutenberg (`/cache/epub/{id}/pg{id}.txt`). One shared `HttpClient`, with a connection and request timeout (`GUTENBERG_TIMEOUT_SECONDS`). It maps HTTP 404 to `NOT_FOUND` and any other status, timeout or network failure to `UPSTREAM_ERROR`. |
| `ingestion.gutenberg` | `GutenbergText` | Pure text processor: finds the START and END markers (case-insensitive, `THE` or `THIS`) and splits the text into header and body. |
| `ingestion.gutenberg` | `SplitBook`, `DownloadException` | Header and body of a book; download failure with its reason (`NOT_FOUND`, `UPSTREAM_ERROR`, `INVALID_FORMAT`). |
| `ingestion.datalake` | `DatalakeLayout` | Computes the directory of a book from the ingestion time (UTC) and the configured layout (`yyyyMMdd/HH/` by default). |
| `ingestion.datalake` | `DatalakeWriter` | Writes each file atomically: temporary file, then atomic move to its final name. |
| `ingestion.datalake` | `IngestionLogStore`, `IngestionRecord` | SQL access to `ingestion_log` in `DATA_DIR/datalake/ingestion.db` (`book_id`, `status`, `header_path`, `body_path`, `ingested_at`). |
| `ingestion.broker` | `BookDownloadedPublisher`, `BookDownloadedEvent` | Publishes the persistent `BookDownloaded` JSON event on the `books.downloaded` queue, as specified in `docs/contracts.md`. |

---

## 5.x.2 The Ingestion Pipeline: Step-by-Step Execution

A client or script ingests a book with `POST /ingest/{book_id}` (for example `POST /ingest/1342`; there is no request body). The service executes the following sequence:

```
  POST /ingest/{book_id}
       |
       v
+--------------+   Status 'available'
| Ingestion    |----------------------> Re-publish BookDownloaded
| log check    |   in ingestion_log      Return 200 OK (status: already_available)
+-------+------+
        | Not ingested yet, or 'failed'
        v
+--------------+
| HTTP fetch   |-----> GET /cache/epub/{id}/pg{id}.txt (timeout GUTENBERG_TIMEOUT_SECONDS)
+-------+------+       404 -> log 'failed', answer 404 NOT_FOUND
        |              other error / timeout -> log 'failed', answer 502 UPSTREAM_ERROR
        v
+--------------+
| Split text   |-----> START/END Gutenberg markers -> header and body
+-------+------+       no markers -> INVALID_FORMAT, nothing is written
        |
        v
+--------------+
| Atomic write |-----> <id>.body.txt and <id>.header.txt written to temporary files
+-------+------+       Files.move(..., ATOMIC_MOVE) -> no partial file is ever visible
        |
        v
+--------------+
| Ingestion log|-----> INSERT OR REPLACE INTO ingestion_log
+-------+------+       status = 'available', absolute paths, UTC timestamp
        |
        v
+--------------+
| Broker event |-----> Publish BookDownloaded to the books.downloaded queue (persistent)
+-------+------+       broker unavailable -> answer 503 UNAVAILABLE (the book stays stored)
        |
        v
   Return 200 OK ({"book_id": 1342, "status": "downloaded", "path": "..."})
```

### 1. Idempotency Fast-Path
Before any download, `IngestionService` looks the book up in `ingestion.db`. If it is already recorded as `available`, nothing is downloaded or written again: the service **re-publishes** the `BookDownloaded` event with the recorded paths and returns `200 OK` with `status: already_available`. Re-publishing makes a repeated request the recovery procedure for an event that was lost; it is harmless because indexing a book is idempotent. As the official benchmarks show (section 7.z), this path answers in about 7–16 ms at the median, depending on the number of concurrent clients.

### 2. Network Acquisition
If the book is not available yet, `BookDownloader` fetches the raw text through `GutenbergClient`:
- The connection and the request have a timeout (`GUTENBERG_TIMEOUT_SECONDS`, 30 s by default), so a slow or silent server cannot block a request forever.
- There are no automatic retries inside the service. If Gutenberg answers with an error other than 404, times out or cannot be reached, the attempt is recorded as `failed` in `ingestion_log` and the client receives `502 UPSTREAM_ERROR`. It can simply repeat the request later.
- If the book does not exist (HTTP 404), the attempt is recorded as `failed` and the client receives `404 NOT_FOUND`; repeating the request gives the same answer.

### 3. Text Demarcation
Project Gutenberg texts surround the literary work with a header and a license. `GutenbergText` isolates it with the standard markers, matched case-insensitively and accepting both `THE` and `THIS`:
- **Header:** everything before the line `*** START OF THE PROJECT GUTENBERG EBOOK ... ***`.
- **Body:** everything between that line and `*** END OF THE PROJECT GUTENBERG EBOOK ... ***`.
- If a marker is missing, the text is rejected as `INVALID_FORMAT` and nothing is written to the datalake, so an incomplete or unexpected file never reaches Indexing.

### 4. Atomic Datalake Persistence
Writing a file directly to its final path could expose a truncated file if the process crashed mid-write. `DatalakeWriter` prevents it:
1. The book's directory (`DATA_DIR/datalake/yyyyMMdd/HH/` by default) is created if needed.
2. Each file is written to a temporary file in the same directory (`<id>.body.txt.<random>.tmp`, then the header).
3. `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` gives the temporary file its final name in one step. Readers see either the previous file or the complete new one, never a partial write. Storing a book again replaces its files.

### 5. Ingestion Log
The absolute paths of both files and the ingestion time are recorded in `DATA_DIR/datalake/ingestion.db` with one statement:
```sql
INSERT OR REPLACE INTO ingestion_log(book_id, status, header_path, body_path, ingested_at)
VALUES (?, 'available', ?, ?, ?);
```
The paths are absolute because other processes (Indexing) use them exactly as published, whatever their working directory.

### 6. Event Dispatch
After the log is updated, `BookDownloadedPublisher` serializes the `BookDownloaded` event and sends it to the ActiveMQ queue `books.downloaded` as a persistent message. The connection to the broker is opened lazily and re-created after a failure. If the broker cannot take the event, the client receives `503 UNAVAILABLE`. The book stays stored and recorded as `available`, so repeating the request publishes the event again through the fast-path above, and no book is lost.

---

## 5.x.3 Concurrency and Error Semantics

- **Thread Safety:** Javalin's Jetty threads serve the requests concurrently. `IngestionService` and `BookDownloader` hold no mutable state, every call opens its own SQLite connection, and the publisher synchronizes access to its shared JMS connection, so several books can be ingested at the same time. Section 7.z measures the effect of 1, 2 and 4 concurrent clients.
- **SQLite Concurrency:** `ingestion.db` uses the project's settings (`journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=5000`). Status queries do not block, and the short write statements are serialized by SQLite, waiting up to 5 s for the lock.
- **Error Envelope:** Every HTTP error uses the project's common envelope, for example:
  ```json
  {"error": {"code": "NOT_FOUND", "message": "Book 99999999 does not exist on Project Gutenberg"}}
  ```
  The codes used by the Ingestion Service are `400 INVALID_PARAMETER` (invalid id), `404 NOT_FOUND` (book missing on Gutenberg, or never ingested for the status endpoint), `502 UPSTREAM_ERROR`, `503 UNAVAILABLE` (broker down) and `500 INTERNAL_ERROR`, whose details are only logged.
