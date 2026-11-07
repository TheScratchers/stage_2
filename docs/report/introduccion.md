# 2. Introduction and Objectives

## 2.1 Architectural Context

In Stage 1 of this project, we designed and implemented a standalone data layer for a Gutenberg-based search engine. That initial system processed literary works sequentially through batch execution, demonstrating core text extraction, inverted indexing, and keyword search. However, a monolithic design inherently couples all stages into a single lifecycle: an error during text downloading aborts downstream indexing, concurrent read and write operations compete directly within the same runtime heap, and scaling individual components independently is impossible.

Stage 2 transforms that foundation into a robust **Service-Oriented Architecture (SOA)** composed of autonomous, cooperating microservices implemented in Java 17. The entire system is engineered to operate on a single host machine while adhering to strict distributed systems principles, establishing the exact communication patterns, data ownership boundaries, and fault-tolerance semantics required for seamless transition to a multi-node cluster in Stage 3.

```
       +------------------+         +------------------+         +------------------+
       | IngestionService |         | IndexingService  |         |  SearchService   |
       |   (Port 7001)    |         |   (Port 7002)    |         |   (Port 7003)    |
       +--------+---------+         +--------+---------+         +--------+---------+
                |                            |                            |
                |  books.downloaded (Queue)  |    books.indexed (Topic)   |
                +------------------->[ ActiveMQ ]------------------------>+
                                       Broker
```

The system is organized into three decoupled services:

1. **Ingestion Service:** Responsible for external data acquisition. It interfaces with Project Gutenberg (or a local mirror), fetches full literary texts, strips raw metadata from textual bodies, stores raw artifacts atomically into the datalake, and announces available books through the message broker.
2. **Indexing Service:** Responsible for text analysis and datamart generation. It consumes ingestion events, parses structural Gutenberg headers into standardized metadata (title, author, ISO language, publication year), tokenizes document bodies into lexical terms, and constructs an inverted index persisted into a local SQLite datamart.
3. **Search Service:** Responsible for serving user-facing search queries. It exposes a public RESTful API providing keyword search with multi-field filtering (author, language, year), relevance ranking, deterministic pagination, and hit details retrieval. It operates strictly read-only against the datamart and dynamically updates in-memory filter caches upon receiving indexing events.

An independent **ActiveMQ Message Broker** process provides asynchronous choreography, serving as the central nervous system that coordinates work between services without direct inter-service HTTP invocations.

---

## 2.2 System Objectives and Non-Functional Goals

The architectural design of Stage 2 was governed by a concrete set of functional and non-functional requirements:

### 1. Loose Coupling and Choreography
Services must operate without compile-time or runtime coupling to each other's network addresses or internal storage representations. Ingestion must not call Indexing over HTTP, nor should Indexing instruct Search to refresh. Instead, services communicate through strongly-typed, versioned event schemas transmitted over persistent message queues and publish-subscribe topics.

### 2. Strict Data Ownership (Single-Writer Principle)
To prevent concurrency hazards and uncoordinated file mutations, every persistent file on disk is assigned exactly one owning service:
- The **Datalake** (`DATA_DIR/datalake/`) and the state database `ingestion.db` are written exclusively by the Ingestion Service.
- The **Datamart** (`DATA_DIR/datamarts/datamart.db`) is written exclusively by the Indexing Service.
- The **Search Service** maintains zero persistent disk state and opens datamart files in read-only mode (`PRAGMA query_only = ON`).

### 3. End-to-End Idempotency and Fault Tolerance
In distributed environments, network blips, process crashes, and message duplicates are inevitable. The pipeline guarantees full idempotency across every stage:
- Repeated ingestion requests for the same book detect existing records in SQLite and bypass redundant downloads.
- Re-indexing an existing book executes inside an atomic SQLite transaction that deletes previous postings before inserting updated frequencies, ensuring that duplicate broker deliveries leave the index in a consistent state.
- Unacknowledged messages resulting from service crashes during indexing are automatically redelivered by ActiveMQ. After five consecutive failures, problematic messages are routed to `ActiveMQ.DLQ` (Dead Letter Queue) rather than blocking the pipeline.

### 4. High Concurrency and Predictable Latency
- The Search API must sustain concurrent user traffic with bounded response latencies (targeting sub-millisecond to low-millisecond latencies under moderate loads).
- The Ingestion pipeline must support parallel client requests without corrupting the datalake or starving system I/O.
- The Indexing Service must exploit multi-core architectures through concurrent worker pools while respecting SQLite's single-writer database lock.

### 5. Measurable Performance and Empirical Validation
Every architectural claim, concurrency speed-up, and resource footprint must be substantiated through repeatable, automated benchmarks executed against a fixed reference corpus (the 20 canonical books from Stage 1) on dedicated reference hardware.
