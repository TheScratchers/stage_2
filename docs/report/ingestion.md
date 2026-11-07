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

The codebase is organized into cleanly decoupled packages adhering strictly to single-responsibility principles and downward-only dependencies:

| Package | Class / Component | Responsibility |
|---|---|---|
| `ingestion` | `IngestionApp` | Service entry point; parses environment configuration, initialises dependencies, maps Javalin HTTP routes (`/health`, `/ingest`), and registers JVM shutdown hooks. |
| `ingestion` | `IngestionService` | Core business orchestrator; checks idempotency in SQLite, delegates downloading, invokes text splitting, coordinates atomic datalake storage, updates the ledger, and triggers event publishing. |
| `ingestion` | `BookDownloader` | High-level downloader handling HTTP retries, exponential backoff, and error translation into domain exceptions. |
| `ingestion` | `IngestResult` | Immutable record representing the operation outcome (`bookId`, `status`, `message`, `headerPath`, `bodyPath`). |
| `ingestion.gutenberg` | `GutenbergClient` | Low-level HTTP client executing HTTP GET requests against the configured Gutenberg base URL. |
| `ingestion.gutenberg` | `GutenbergText` | Pure algorithmic text processor; identifies Gutenberg boundary markers, normalizes line endings (`\r\n` → `\n`), and splits text into header and body segments. |
| `ingestion.gutenberg` | `SplitBook` | Immutable data carrier containing separated `headerText` and `bodyText`. |
| `ingestion.datalake` | `DatalakeLayout` | Computes deterministic, normalized filesystem paths based on ingestion timestamp and book identifier (`yyyyMMdd/HH/<book_id>.*.txt`). |
| `ingestion.datalake` | `DatalakeWriter` | Implements atomic file writing using temporary files and filesystem-level atomic rename operations. |
| `ingestion.datalake` | `IngestionLogStore` | Manages local SQLite connection and CRUD queries for `DATA_DIR/datalake/ingestion.db`. |
| `ingestion.datalake` | `IngestionRecord` | Model representing a row in `ingestion_log` (`book_id`, `status`, `header_path`, `body_path`, `ingested_at`). |
| `ingestion.broker` | `BookDownloadedPublisher` | JMS message producer; constructs JSON payloads and dispatches `BookDownloaded` events onto the `books.downloaded` queue with `DeliveryMode.PERSISTENT`. |
| `ingestion.broker` | `BookDownloadedEvent` | Strongly-typed record serialized to JSON matching the contract schema in `docs/contracts.md`. |

---

## 5.x.2 The Ingestion Pipeline: Step-by-Step Execution

When a client or automated script initiates an ingestion request via `POST /ingest` with `{"book_id": 1342}`, the service executes the following sequence:

```
  POST /ingest
       |
       v
+--------------+   Exists in SQLite &
| Idempotency  |---------------------> Return 200 OK (already_available)
| Check        |   Files Present?      (Fast-path: no network or disk write)
+-------+------+
        | Not present / failed
        v
+--------------+
| HTTP Fetch   |-----> Download raw text from Gutenberg / local mirror
+-------+------+       (3 retries with exponential backoff: 500ms, 1000ms, 2000ms)
        |
        v
+--------------+
| Split Text   |-----> Scan for canonical START/END Gutenberg markers
+-------+------+       Separate into Header and Body strings
        |
        v
+--------------+
| Atomic Write |-----> Write to <id>.header.txt.tmp & <id>.body.txt.tmp
+-------+------+       Files.move(..., ATOMIC_MOVE) -> guarantees no partial files
        |
        v
+--------------+
| SQLite Log   |-----> INSERT OR REPLACE INTO ingestion_log
+-------+------+       Set status = 'available', record paths & UTC timestamp
        |
        v
+--------------+
| Broker Event |-----> Publish BookDownloaded to queue books.downloaded
+-------+------+       (Persistent JMS Message, deliveryMode = PERSISTENT)
        |
        v
   Return 201 Created ({"status": "downloaded", "book_id": 1342, ...})
```

### 1. Idempotency Fast-Path
Before opening any network connections, `IngestionService` queries `ingestion.db`. If book 1342 is already recorded with status `available` and both files exist on disk, the call immediately short-circuits and returns HTTP `200 OK` (`already_available`). As demonstrated in the official benchmarks (Section 7), this fast-path answers in under 8 ms without triggering redundant network downloads, disk writes, or broker events.

### 2. Network Acquisition and Resilience
If the book is absent, `BookDownloader` requests the raw text via `GutenbergClient`. Network flakiness is handled gracefully:
- Connections use a strict 30-second timeout.
- Failed attempts (e.g., HTTP 5xx or `SocketTimeoutException`) trigger up to 3 automatic retries with exponential backoff (500 ms, 1000 ms, 2000 ms).
- If the book does not exist on the remote server (HTTP 404), the service immediately records a `failed` entry in `ingestion_log` and returns `404 NOT_FOUND` without retrying.

### 3. Text Demarcation and Normalization
Project Gutenberg texts prepend licensing and bibliographic headers and append legal disclaimers. `GutenbergText` isolates the substantive literary work using standard demarcation regex patterns:
- **Header:** Everything prior to `*** START OF THE PROJECT GUTENBERG EBOOK <TITLE> ***`.
- **Body:** The content following the start delimiter up to `*** END OF THE PROJECT GUTENBERG EBOOK <TITLE> ***`.
- If markers are absent (common in older Gutenberg transcriptions), the splitter logs a warning and stores the entire text as the body with a minimal synthetic header, preventing download failure.

### 4. Atomic Datalake Persistence
Writing files directly to their target paths risks exposing partially-written, truncated files if the JVM or host crashes mid-write. `DatalakeWriter` enforces strict filesystem atomicity:
1. Destination directories (`DATA_DIR/datalake/yyyyMMdd/HH/`) are created recursively.
2. Content is written to temporary siblings: `<book_id>.header.txt.<uuid>.tmp` and `<book_id>.body.txt.<uuid>.tmp`.
3. File streams are flushed to disk.
4. `Files.move(..., StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)` commits the temporary file to its final destination in a single atomic filesystem operation. Downstream readers will either observe the prior file state or the complete new file, never a partial write.

### 5. Persistent Ledger Commit
The newly stored paths and timestamp are committed to `DATA_DIR/datalake/ingestion.db` within a single SQLite transaction:
```sql
INSERT INTO ingestion_log (book_id, status, header_path, body_path, ingested_at)
VALUES (?, 'available', ?, ?, ?)
ON CONFLICT(book_id) DO UPDATE SET
  status = excluded.status,
  header_path = excluded.header_path,
  body_path = excluded.body_path,
  ingested_at = excluded.ingested_at;
```

### 6. Asynchronous Event Dispatch
Once the database transaction is safely committed, `BookDownloadedPublisher` serializes a `BookDownloaded` JSON event and publishes it to the ActiveMQ queue `books.downloaded`. The JMS message is marked `DeliveryMode.PERSISTENT`, guaranteeing that ActiveMQ writes it to KahaDB disk storage before returning control.

---

## 5.x.3 Concurrency and Error Semantics

- **Thread Safety:** All components in `ingestion-service` are strictly thread-safe. `IngestionService` maintains no mutable state; each HTTP worker thread handles its own request, executes text operations independently, and obtains its own SQLite connection from the connection provider.
- **SQLite Concurrency:** Because `ingestion.db` operates in WAL mode (`journal_mode=WAL`), concurrent client threads do not block each other while preparing requests or reading book status. Brief database write transactions are coordinated via SQLite's internal locking mechanism with a 5-second `busy_timeout`.
- **Error Envelope:** All HTTP errors adhere to the project's universal JSON error envelope:
  ```json
  {
    "error": "DOWNLOAD_FAILED",
    "message": "Gutenberg book 999999 was not found on remote server",
    "status": 404,
    "timestamp": "2026-11-05T17:52:10.123Z"
  }
  ```
