# 8.x Conclusions and future improvements: towards the Stage 3 cluster

Stage 2 runs every process on one host. The architecture was chosen so that most of it carries over
to a cluster: services that talk only through HTTP and broker events, idempotent indexing,
acknowledgement after commit, and every data file owned by exactly one writer. Several assumptions,
however, only hold because everything shares one machine. This section lists them and how each one
will change.

## Assumptions that depend on a single host

| Assumption in Stage 2 | Where | Why it breaks on a cluster |
|---|---|---|
| Every address is `localhost` with a fixed port (`BROKER_URL`, 7001–7003, replica ports) | Configuration, scripts, load client | Processes run on different machines |
| The datalake is a local directory, and events carry **absolute local paths** (`header_path`, `body_path`) | Ingestion → `BookDownloaded` → Indexing | An Indexing node cannot open a path on another node's disk |
| One `datamart.db` file is written by Indexing and read by every Search replica | Indexing, Search | SQLite is an embedded database without a server; it needs the file on the same machine |
| WAL readers see a consistent snapshot through shared memory (the `-shm` file) | SQLite readers and writer | WAL needs every process on the same host; it does not work over a network file system |
| Indexing opens `ingestion.db` read-only from the datalake disk | Indexing administrative endpoints | Same as above |
| SQLite serialises writers with a file lock, so concurrent Indexing replicas are safe | Indexing replicas | The lock does not coordinate writers on different machines |
| "Only one rebuild at a time" is an in-process flag | `IndexingService` | It does not stop a rebuild running on another node |
| One embedded broker with a local KahaDB store | Broker | A single point of failure; its store is lost if that machine is lost |
| Search replicas are reached by round-robin clients that know every port | Load client | Real clients need one entry point |

## How the architecture will evolve

**Communication.** The services already exchange only HTTP requests and versioned JSON events, so
their code does not change. The broker URL becomes a list of addresses with ActiveMQ's `failover:`
transport, which every client already uses, so a client moves to another broker when one fails.
The broker itself becomes a replicated pair or network of brokers with replicated storage, so
accepted events survive the loss of a node.

**Storage access.**
- **Datalake.** It moves from local paths to shared storage reachable from every node, such as a
  distributed file system or an object store. `BookDownloaded` would then carry a storage key
  instead of an absolute path. This is the one contract change, and the event `version` field
  exists for it.
- **Datamart.** A single SQLite file cannot be shared, so the inverted index has to be distributed:
  - a server database shared by all nodes, or
  - an index partitioned by term or by book over several nodes, each partition with one owner that
    writes it and replicas that serve reads.

  Partitioning by term keeps a one-word query on one partition but spreads multi-word queries.
  Partitioning by book makes indexing local but sends each query to every partition and merges the
  partial top-k results. Since queries greatly outnumber book additions, the trade-off will be
  decided by measuring both on the cluster.

**Distribution of work.**
- **Indexing.** The queue already distributes the books: competing consumers on any node take one
  message at a time (prefetch 1), so adding Indexing nodes needs no change. With a partitioned index,
  each book's postings are routed to their partitions.
- **Search.** Search replicas are stateless apart from their cache, so they go behind a load
  balancer or an API gateway, which also becomes the single address for the front-end and the place
  to configure CORS.

**Consistency.** Indexing is at-least-once and idempotent, and a book becomes visible in one commit,
so duplicates and retries cannot corrupt the index. On a cluster the same guarantees must hold per
partition: a book's postings in several partitions have to become visible together, or the API must
state that a newly indexed book may briefly be found by some words before others. Search caches stay
correct because they are emptied on every `BookIndexed` and disabled while their node is cut off
from the broker. This rule already holds on one host.

**Recovery from unavailable nodes.**
- **What already carries over.**
  - A failed Indexing node leaves its unacknowledged message to be redelivered to another consumer,
    as the crash test of the Indexing suite shows on one host.
  - Ingestion answers `503` when it cannot publish, and the client retries.
  - Search keeps answering while the broker is unavailable.
- **What is new.** Timeouts between nodes have to be explicit and short, so that a slow node is
  treated as unavailable. Health checks have to move from "the process answers" to "the process can
  reach its storage and broker", so the load balancer stops routing to a node that is up but cut
  off.
- **Locks.** Any coordination that relies on in-process locks (the rebuild flag) or on file locks
  (the SQLite writer) has to become a distributed lock or a single elected owner per partition.

## What the Stage 2 measurements tell us

The integration benchmarks were designed to test three hypotheses about where the single host
limits the design. The official figures of section 7 settle them as follows.

- **Indexing is bounded by the single SQLite writer: confirmed.**
  - Throughput rises from 12.3 to 18.0 books per second with a second thread, then stays at 18.5
    with 4 threads and 18.4 with two processes (Table 7.x.2).
  - The extra threads do not use more CPU: about 140 % against 134 % with 2 threads. They wait for
    the write lock.
  - The JMH results show the same split without the broker: analysing books scales with threads,
    persisting them does not (section 7.y).
  - On the cluster, more writers on the same file cannot raise this limit, but an index partitioned
    over several machines, each partition with its own writer, can. This is the main reason to
    distribute the datamart.
- **Search is bounded by the host, not by one Search process: partly confirmed.**
  - Throughput peaks at about 5,500 requests per second with 16 clients, and a second replica does
    not raise that peak (Table 7.x.1).
  - The Search processes then used about 9 of the 24 logical processors, and two replicas only split
    that work between them.
  - The limit is therefore shared by the whole machine, where the load generator also runs. It
    could not be attributed to one component with the evidence of a single host.
  - On the cluster, clients and replicas on different machines will show whether Search replicas
    scale out, which is what the stateless design of the service is meant for.
- **Coordination through the broker costs little: confirmed.**
  - Re-indexing a book through the broker took a median of 3.8 ms more than doing it directly, about
    5 % of the cost of indexing the book (Table 7.x.3).
  - A new book is searchable about 64 ms after it is requested.
  - The broker is therefore not the bottleneck of the pipeline, and its cost buys what a cluster
    needs most: decoupled services, redelivery after a crash and no lost book.

**Strengths.**
- Correct results under every tested configuration: no failed request among 1.47 million, the same
  answers from both replicas, and identical indexes after duplicates, crashes, more threads or more
  processes.
- Low latency below saturation: about 1–2.5 ms at the median.
- A choreography whose overhead is small.

**Weaknesses.**
- The single SQLite writer caps indexing at about 18.5 books per second on this host.
- Each `DatamartStore` call opens a new SQLite connection, which adds a WAL checkpoint per book with
  a single worker (section 7.y). Keeping one connection per worker is the first optimisation to make.
- The search ceiling could not be traced to a single component on one machine.
