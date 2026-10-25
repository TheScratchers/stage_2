# Shared contracts – Stage 2

This document is the boundary between the three services. Any change to it must be agreed by the
whole team in a dedicated pull request, because it affects every service.

## 1. Configuration

Read through `common.Config` (environment variable → JVM system property → default).

| Variable | Default | Used by |
|---|---|---|
| `DATA_DIR` | `./data` | all services |
| `INGESTION_PORT` | `7001` | ingestion-service |
| `INDEXING_PORT` | `7002` | indexing-service (replicas: 7012, 7022, …) |
| `SEARCH_PORT` | `7003` | search-service (replicas: 7013, 7023, …) |
| `BROKER_URL` | `tcp://localhost:61616` | all services |
| `INDEXING_WORKERS` | `2` | indexing-service (consumer threads per process) |
| `GUTENBERG_BASE_URL` | `https://www.gutenberg.org` | ingestion-service |
| `GUTENBERG_TIMEOUT_SECONDS` | `30` | ingestion-service |
| `DATALAKE_LAYOUT` | `time_based` | ingestion-service |

## 2. Data on disk and single-writer ownership

Every file or database has exactly **one** service that writes to it; the others only read.
Each service creates its directories at startup, before opening SQLite.

| Path (under `DATA_DIR`) | Writer | Readers | Content |
|---|---|---|---|
| `datalake/yyyyMMdd/HH/<id>.header.txt`, `<id>.body.txt` | Ingestion | Indexing | Book split into header and body (Stage 1 time-based layout, UTC ingestion hour). Consumers must use the paths published in events / `ingestion_log`, never rebuild them. |
| `datalake/ingestion.db` | Ingestion | Ingestion | `ingestion_log`: persistent record of books ready for indexing |
| `datamarts/datamart.db` | Indexing | Search (read-only) | `books`, `postings`, `index_meta` |

All SQLite databases are opened through `common.Sqlite` with `journal_mode=WAL`,
`synchronous=NORMAL` and `busy_timeout=5000`. Search opens the datamart read-only.
Connections are never shared between threads.

```sql
-- datalake/ingestion.db (owner: Ingestion)
CREATE TABLE IF NOT EXISTS ingestion_log (
  book_id     INTEGER PRIMARY KEY,
  status      TEXT NOT NULL,          -- 'available' | 'failed'
  header_path TEXT,
  body_path   TEXT,
  ingested_at TEXT NOT NULL           -- ISO-8601 UTC
);

-- datamarts/datamart.db (owner: Indexing)
CREATE TABLE IF NOT EXISTS books (
  book_id    INTEGER PRIMARY KEY,
  title      TEXT NOT NULL,           -- "Untitled" when the header has no title
  author     TEXT,                    -- NULL when missing
  language   TEXT,                    -- ISO 639-1 lowercase ('en', 'es', ...); NULL when unknown
  year       INTEGER,                 -- year of the Gutenberg "Release date"; NULL when missing
  indexed_at TEXT NOT NULL            -- ISO-8601 UTC
);
CREATE TABLE IF NOT EXISTS postings (
  term      TEXT    NOT NULL,
  book_id   INTEGER NOT NULL,
  frequency INTEGER NOT NULL,         -- occurrences of the term in the book
  PRIMARY KEY (term, book_id)
);
CREATE INDEX IF NOT EXISTS idx_postings_book ON postings(book_id);
CREATE TABLE IF NOT EXISTS index_meta (key TEXT PRIMARY KEY, value TEXT);
```

## 3. Book lifecycle

1. **Ingestion** downloads `/cache/epub/{id}/pg{id}.txt`, writes header and body to temporary files
   and moves them atomically to their final names, records `status='available'` in `ingestion_log`
   and only then publishes `BookDownloaded`. A book is eligible for indexing only from that point.
2. **Indexing** indexes each book in **one SQLite transaction** (delete the book's postings, insert
   the new ones, upsert `books`, update `index_meta.last_update`, commit), acknowledges the message
   only after the commit and then publishes `BookIndexed`. Re-indexing a book never duplicates rows.
3. **Search** reads the datamart read-only; thanks to WAL it sees either the previous or the new
   state of a book, never a partial one.

## 4. Broker events (ActiveMQ)

JSON serialised with Gson, `snake_case` fields, persistent delivery.

| Destination | Type | Producer | Consumer | Payload |
|---|---|---|---|---|
| `books.downloaded` | queue | Ingestion | Indexing (competing consumers) | `{"event":"BookDownloaded","version":1,"book_id":1342,"header_path":"...","body_path":"...","ingested_at":"2026-10-21T14:05:00Z"}` |
| `books.indexed` | topic | Indexing | Search (refreshes caches and filter values) | `{"event":"BookIndexed","version":1,"book_id":1342,"terms":5321,"indexed_at":"2026-10-21T14:05:03Z"}` |

- The consumer acknowledges a message only after its work has been committed; on failure there is
  no acknowledgement and the broker redelivers it. After 5 redeliveries it goes to `ActiveMQ.DLQ`.
- Duplicates are harmless because indexing is idempotent. No ordering between different books is
  assumed.

## 5. Tokenization

A single `common.TextTokenizer` is used both for indexing and for queries: lowercase with
`Locale.ROOT`, then maximal runs of Unicode letters (`\p{L}+`); no stemming, no stopwords.

## 6. HTTP conventions

- JSON in UTF-8, `snake_case` fields, `book_id` as integer, timestamps in ISO-8601 UTC.
- Every service exposes `GET /health` → `{"service":"<name>","status":"UP"}`.
- Errors always have the shape
  `{"error":{"code":"INVALID_PARAMETER","message":"limit must be between 1 and 100"}}` with codes
  `400 INVALID_PARAMETER`, `404 NOT_FOUND`, `409 CONFLICT`, `502 UPSTREAM_ERROR`,
  `503 UNAVAILABLE`, `500 INTERNAL_ERROR`.
- The full, versioned specification of every endpoint is `docs/openapi.yaml` (OpenAPI 3.0). Each
  service owner adds and updates their own routes there, in the same pull request that changes them.

## 7. Fixed corpus

`samples/books.txt` (20 Gutenberg books), `samples/words.txt` (10 query words) and the bundled
books in `samples/books/` are used by every test, API check and benchmark.
