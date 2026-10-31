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
