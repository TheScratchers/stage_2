# Integration benchmarks

Tools and raw results of the benchmarks that exercise the running services over HTTP. The
microbenchmarks of individual components use JMH and live in `src/test/java/.../benchmark/` of
each service; see the root README.

## Load client

[`load-client/`](load-client) is a closed-loop HTTP load generator for the Search API. Each client
is a thread with its own HTTP/1.1 connection that sends a request, waits for the whole response and
sends the next one, with no think time. Client `c` starts at query `c` of the list and at replica
`c`, then moves through both lists in order, so the workload is deterministic and every replica
receives the same share of requests. Each concurrency level runs a warm-up that is not recorded,
followed by the measurement.

```bash
mvn clean verify
java -jar benchmarks/load-client/target/load-client.jar \
  --targets http://localhost:7003,http://localhost:7013 \
  --clients 1,2,4,8,16,32,64 --warmup 10 --duration 30 \
  --out benchmarks/results/20261101_search-latency-2-replicas
```

| Option | Default | Meaning |
|---|---|---|
| `--targets` | `http://localhost:7003` | Search replicas, comma-separated; requests are spread round-robin |
| `--clients` | `1,2,4,8,16,32,64` | Concurrency levels, run one after another |
| `--warmup` | `10` | Seconds of warm-up per level, not recorded |
| `--duration` | `30` | Seconds of measurement per level |
| `--path` | `/search?q={q}` | Request path; `{q}` is replaced by the URL-encoded query word (e.g. `/search?q={q}&language=en`) |
| `--queries` | `samples/words.txt` | One query word per line; the fixed workload of the corpus by default |
| `--out` | `benchmarks/results/<UTC time>_search-load` | Results directory |

## Results directory

Every run writes one directory, so that each table of the report can link the raw data and the
exact command that produced it:

| File | Content |
|---|---|
| `raw.csv` | One row per measured request: `clients, client, target, query, status, start_us, latency_us, total` |
| `summary.csv` | One row per level: `clients, requests, errors, throughput_rps, mean_ms, p50_ms, p95_ms, p99_ms, max_ms` |
| `command.txt` | Exact command line, start time and resolved settings |
| `host.txt` | OS, CPU model, logical processors, memory and JVM of the machine that ran the client |

- `status` is `-1` when no response was received. Latency percentiles use the nearest-rank method
  over successful requests only; failed requests are counted in `errors`.
- `total` is the number of matches returned by each request. All rows of the same query must have
  the same `total`, which checks that every replica returns the same answer, not only how fast it
  answers.

Official runs are made on the team's reference machine and committed as
`benchmarks/results/<yyyyMMdd>_<experiment>/`. How the services were started for each experiment
(number of replicas, JVM heap, broker settings) is stated next to its table in the report.

## Integration benchmarks of the whole system

[`integration/`](integration) runs every integration experiment of the report with one command. It
starts the real system for each run (broker, Ingestion, Indexing and Search from their jars, each in
its own JVM with a fixed `-Xmx`), loads the fixed corpus, measures, stops everything and writes one
results directory per experiment. The books are served from `samples/books` by a local stand-in for
Project Gutenberg, so network latency is never part of a measurement.

| Directory | What it measures | Files |
|---|---|---|
| `<day>_search-latency-1-replica` | Latency (p50/p95/p99) and throughput of `GET /search` with 1, 2, 4, 8, 16, 32 and 64 concurrent clients, one Search replica | `raw.csv`, `summary.csv` (see the load client above) |
| `<day>_search-latency-2-replicas` | The same with two Search replicas behind round-robin clients | idem |
| `<day>_indexing-throughput` | Books indexed per second from the queue with 1, 2 and 4 consumer threads and with two Indexing replicas; 200 `BookDownloaded` events per run, 3 runs each; `index_ok` checks that re-indexing never duplicated a book | `raw.csv` |
| `<day>_coordination` | Per book: `POST /ingest` response, `BookIndexed` arrival and visibility in Search; and the same re-indexing through the broker versus a direct `POST /index/update` | `chain.csv`, `direct-vs-event.csv`, `summary.csv` |

Every directory also has `command.txt` (exact command, commit, topology, JVM heap, SQLite and broker
settings, method), `host.txt`, `resources*.csv` (CPU % and resident memory of each process, sampled
every second or half second; 100 % is one busy core) with `resources-summary.csv`, and `disk.csv`
(bytes of the datalake, the ingestion log, the datamart and the broker store for the corpus).

### Official run on the reference machine

The complete run takes about 20 minutes. All three runs of the report (P6, M6 and this one) use the
same machine, JDK and commit.

1. Check the environment: `java -version` and `mvn -v` must show Temurin 21.0.12 and Maven 3.9.16.
2. Use a clean clone outside OneDrive, on the agreed commit of `main`:
   `git clone https://github.com/TheScratchers/stage_2.git C:\bench\stage_2`, then `cd C:\bench\stage_2`.
3. Plug the computer in, select the maximum performance power plan, and close every other program
   (browser, games, chat, OneDrive, Steam). Pause Windows updates.
4. Build: `mvn clean verify` must end with `BUILD SUCCESS`.
5. Trial run (about 2 minutes, results in a throw-away folder):
   `java -jar benchmarks/integration/target/integration-bench.jar --quick --out trial-results`
6. Official run, from the repository root, without touching the computer while it runs:
   `java -jar benchmarks/integration/target/integration-bench.jar`
7. Delete `trial-results`. Commit the four new `benchmarks/results/<day>_*` directories on a branch
   `bench/integration` and open a pull request.

Options: `--quick` (levels 1, 4, 16 and short runs), `--only latency,indexing,coordination`,
`--heap 1g` (`-Xmx` of every service) and `--out DIR` (default `benchmarks/results`).
