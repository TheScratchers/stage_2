# 3. System Architecture and Design

## 3.1 Global System Overview

The Stage 2 Search Engine is architected as an event-driven collection of autonomous microservices executing within independent Java Virtual Machines on a single host. Communication is strictly partitioned between two distinct tiers:

1. **Public Front-End Tier:** Exposed exclusively by the Search Service over HTTP REST (port 7003), providing read-only endpoints consumed by human readers, client applications, and front-end user interfaces.
2. **Internal Choreography Tier:** Coordinated asynchronously through an embedded Apache ActiveMQ message broker (TCP port 61616). Services never initiate direct inter-service HTTP calls; instead, business workflows progress through discrete, immutable events published onto persistent message queues and topics.

```mermaid
flowchart TD
    subgraph Clients["External & Management Clients"]
        User["Web Browser / Search Client"]
        Op["Operator / Ingestion Script"]
    end

    subgraph BrokerSystem["Choreography Infrastructure (Port 61616)"]
        Broker["ActiveMQ Message Broker\n(KahaDB Persistent Store)"]
        Q_Down["Queue: books.downloaded\n(Persistent, Competing Consumers)"]
        T_Idx["Topic: books.indexed\n(Pub/Sub Broadcast)"]
        DLQ["ActiveMQ.DLQ\n(Dead Letter Queue)"]
        Broker --> Q_Down
        Broker --> T_Idx
        Broker --> DLQ
    end

    subgraph ServiceTier["Service Tier (Independent JVMs)"]
        Ingest["Ingestion Service\n(Port 7001)"]
        Index["Indexing Service\n(Port 7002, 7012...)"]
        Search1["Search Service - Replica 1\n(Port 7003)"]
        Search2["Search Service - Replica 2\n(Port 7013)"]
    end

    subgraph StorageTier["Physical Storage Layer (DATA_DIR)"]
        Datalake["Datalake Directory\n(yyyyMMdd/HH/*.txt)"]
        IngestDB["ingestion.db (SQLite WAL)\nOwner: Ingestion"]
        DatamartDB["datamart.db (SQLite WAL)\nOwner: Indexing"]
    end

    %% Client Calls
    User -->|GET /search\nGET /books/{id}| Search1
    User -.->|Round-Robin / Load Balanced| Search2
    Op -->|POST /ingest| Ingest
    Op -->|POST /index/rebuild| Index

    %% Ingestion flow
    Ingest -->|1. Atomic File Write| Datalake
    Ingest -->|2. Log Status| IngestDB
    Ingest -->|3. Publish Event| Q_Down

    %% Indexing flow
    Q_Down -->|4. Consume BookDownloaded| Index
    Index -->|5. Read Raw Text| Datalake
    Index -->|6. Atomic Transaction| DatamartDB
    Index -->|7. Publish Event| T_Idx
    Index -.->|Read Paths for Rebuild| IngestDB

    %% Search flow
    T_Idx -->|8. Invalidate Cache| Search1
    T_Idx -->|8. Invalidate Cache| Search2
    DatamartDB -->|Read-Only Queries| Search1
    DatamartDB -->|Read-Only Queries| Search2
```

---

## 3.2 Event Choreography vs. Central Orchestration

A pivotal decision in Stage 2 was adopting an **event-driven choreography** rather than a centralized orchestrator (such as a master workflow coordinator issuing HTTP calls).

| Architectural Quality | Central HTTP Orchestrator | Event Choreography (ActiveMQ) |
|---|---|---|
| **Coupling** | High: orchestrator must know network addresses and ports of all downstream services. | Low: services only know the broker URL (`tcp://localhost:61616`) and event schemas. |
| **Resilience to Crashes** | Fragile: if the orchestrator crashes mid-pipeline, state is lost unless backed by external consensus. | Robust: messages remain safely persisted in KahaDB until explicitly acknowledged after downstream commit. |
| **Backpressure Handling** | Difficult: requires rate-limiting algorithms, circuit breakers, and complex HTTP retry loops. | Natural: consumers pull messages at their own processing rate (`prefetch=1`); queues absorb sudden traffic bursts. |
| **Horizontal Scaling** | Requires service discovery and client-side load balancers for internal endpoints. | Trivial: spinning up multiple Indexing or Search JVMs automatically balances load via competing consumers. |

### Messaging Topology and Semantics

The message broker enforces two distinct messaging patterns tailored to workflow requirements:

1. **`books.downloaded` (Queue - Point-to-Point):**
   - **Delivery Mode:** `PERSISTENT` (backed by KahaDB write-ahead logs on disk).
   - **Distribution:** Competing Consumers pattern. Worker threads across one or more Indexing processes compete for messages.
   - **Prefetch Policy:** Set to `prefetch=1`. This prevents a single fast thread or worker from hoarding batches of books while other workers sit idle, ensuring optimal load distribution across multi-threaded workers.
   - **Acknowledgement Mode:** Manual acknowledgement (`CLIENT_ACKNOWLEDGE` / transacted JMS session). The consumer explicitly acknowledges the message **only after** the SQLite transaction inserting metadata and inverted postings has successfully committed to disk. If the indexing process crashes or encounters an unhandled exception before commit, the broker detects the disconnection and redelivers the message to an available worker.
   - **Dead Letter Strategy:** ActiveMQ is configured with a `RedeliveryPolicy` of up to 5 redeliveries with exponential backoff. Poison pills (such as malformed files that crash parser threads) are automatically redirected to `ActiveMQ.DLQ` without halting the ingestion or indexing pipelines.

2. **`books.indexed` (Topic - Publish/Subscribe):**
   - **Delivery Mode:** Broadcast to all active subscribers.
   - **Distribution:** Fan-out to every running Search Service replica.
   - **Purpose:** Informs Search replicas that a new book has entered the datamart, prompting them to invalidate their in-memory filter caches (distinct languages and publication years). Because Search is stateless and queries read committed SQLite WAL snapshots directly from disk, missing a broadcast topic message during Search restart only causes a momentary cache stale read that refreshes upon the next periodic check.

---

## 3.3 Storage Isolation and the Single-Writer Principle

Data storage is partitioned into two physically isolated layers located under `DATA_DIR`:

```
DATA_DIR/
├── broker/                  (ActiveMQ KahaDB journal files and indexes)
│   ├── db-1.log
│   └── db.data
├── datalake/                (Owned exclusively by Ingestion Service)
│   ├── ingestion.db         (SQLite database: book_id, status, paths, timestamp)
│   ├── ingestion.db-wal
│   ├── ingestion.db-shm
│   └── yyyyMMdd/
│       └── HH/
│           ├── 1342.header.txt
│           └── 1342.body.txt
└── datamarts/               (Owned exclusively by Indexing Service)
    ├── datamart.db          (SQLite database: books, postings, index_meta)
    ├── datamart.db-wal
    └── datamart.db-shm
```

### The Single-Writer Principle
Concurrent file modification across autonomous processes is a notorious source of silent data corruption. Stage 2 resolves this by enforcing that **every file and database has exactly one designated writer process**:
- **Ingestion Service:** Only writer for the datalake text files and `ingestion.db`.
- **Indexing Service:** Only writer for `datamart.db`. It opens `ingestion.db` strictly in read-only mode (`PRAGMA query_only = ON`) solely during explicit maintenance operations (`POST /index/rebuild`).
- **Search Service:** Strictly read-only consumer of `datamart.db`. It never writes to disk.
- **ActiveMQ Broker:** Only writer for the `broker/` directory.

### SQLite WAL Mode Concurrency
Both `ingestion.db` and `datamart.db` are configured with Write-Ahead Logging (`PRAGMA journal_mode=WAL`), `PRAGMA synchronous=NORMAL`, and `PRAGMA busy_timeout=5000`. WAL mode provides essential concurrency guarantees:
- **Readers never block writers**, and **writers never block readers**.
- Multiple Search service replicas can query `datamart.db` concurrently without experiencing lock contention while Indexing commits new book postings in the background.
- Concurrent SQLite writers are serialised via internal file locks. As established in the official benchmarks (Section 7), scaling Indexing worker threads beyond 2 workers saturates the single-writer lock; thus, true parallelism occurs during in-memory text tokenization, while database commits queue safely.

---

## 3.4 Concurrency and Threading Architecture

Every service runs in its own JVM process with a dedicated threading model tailored to its operational workload:

```mermaid
flowchart LR
    subgraph IngestionJVM["Ingestion Service JVM"]
        JavalinIngest["Javalin / Jetty Thread Pool\n(HTTP POST /ingest)"]
        HTTPClient["HTTP Downloader\n(Async / Retrying)"]
        AtomicWriter["Datalake Atomic Writer\n(Temp File + Move)"]
        JMSProd["JMS Producer\n(Thread-Safe)"]
        JavalinIngest --> HTTPClient --> AtomicWriter --> JMSProd
    end

    subgraph IndexingJVM["Indexing Service JVM"]
        BrokerCons["JMS Message Consumers\n(INDEXING_WORKERS = 2)"]
        TokenPool["Text Tokenizer & Parser\n(CPU-Bound, Parallel)"]
        SQLiteWriter["Single SQLite Transaction\n(I/O-Bound, Serialized Lock)"]
        TopicProd["JMS Topic Publisher"]
        BrokerCons --> TokenPool --> SQLiteWriter --> TopicProd
    end

    subgraph SearchJVM["Search Service JVM"]
        JavalinSearch["Javalin / Jetty Thread Pool\n(HTTP GET /search)"]
        QueryParser["Query Validator & Tokenizer"]
        SQLiteReaders["SQLite Read Connections\n(Parallel Concurrent WAL Reads)"]
        TopicCons["Topic Listener\n(Cache Invalidation)"]
        JavalinSearch --> QueryParser --> SQLiteReaders
        TopicCons -.-> JavalinSearch
    end
```

1. **Ingestion Service:**
   - Jetty HTTP server manages incoming client ingestion requests across an adaptive thread pool.
   - Text downloading and file writing are stateless and execute concurrently across threads.
   - Each thread obtains its own connection to `ingestion.db`, relying on SQLite WAL mode to coordinate the brief record inserts.
2. **Indexing Service:**
   - Spawns `INDEXING_WORKERS` concurrent JMS consumer sessions (default: 2 threads).
   - Each worker retrieves one `BookDownloaded` event (`prefetch=1`), independently reads the text files from the datalake, and tokenizes terms in memory.
   - SQLite insertions are executed in single-statement transactions, queuing automatically if another worker is currently holding the write transaction lock.
3. **Search Service:**
   - Handles read traffic using Jetty's worker threads.
   - Because queries only issue `SELECT` statements, SQLite WAL mode serves concurrent readers simultaneously through shared memory (`.shm`), delivering stable sub-millisecond query execution.
