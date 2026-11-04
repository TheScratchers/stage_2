# Indexing Service

Builds the inverted index of the datamart from the books of the datalake. API: `docs/openapi.yaml`;
events and storage: `docs/contracts.md`.

## Microbenchmarks (JMH)

The JMH benchmarks live in `src/test/java/.../indexing/benchmark/` and are never part of the
service jar.

| Benchmark | What one operation is | Configurations |
|---|---|---|
| `IndexingBenchmark.tokenize` / `parseHeader` / `indexBook` | Tokenizing the body, parsing the header and storing book 1342 | average time |
| `CorpusIndexingBenchmark.analyseCorpus` | **Construction**: read, parse, tokenize and count the 20 corpus books, in memory | 1, 2, 4 threads |
| `CorpusIndexingBenchmark.persistCorpus` | **Persistence**: store the 20 precomputed books in an empty datamart | 1, 2, 4 threads |
| `CorpusIndexingBenchmark.buildCorpus` | Construction and persistence from the files into an empty datamart | 1, 2, 4 threads |
| `CorpusIndexingBenchmark.updateBatch` | **Incremental update**: re-index an identical batch (the corpus) into a datamart that already holds it | 1, 2, 4 threads |
| `CorpusIndexingBenchmark.rebuild` | **Complete rebuild** of `POST /index/rebuild` over a full datamart | sequential, as the service does it |

The threads play the role of the `INDEXING_WORKERS` consumer threads: each one takes the next book
from a shared queue. The corpus benchmarks use single-shot time (5 warm-up and 10 measured passes,
2 minutes at most per pass); every benchmark JVM runs with `-Xms2G -Xmx2G` and the GC profiler.
`footprint.csv` adds the size of `datamart.db` and the Java heap used while indexing the corpus.

### Official run on the reference machine

The official numbers of the report are measured on Pablo's desktop computer, the team's reference
machine (team guide 5.4), on the same commit as the other benchmarks. It takes about 15 minutes.

1. `java -version` and `mvn -v` must show Temurin 21.0.12 and Maven 3.9.16.
2. Use a clean clone outside OneDrive, on the agreed commit of `main`
   (`git clone https://github.com/TheScratchers/stage_2.git C:\bench\stage_2`, then `cd C:\bench\stage_2`).
3. Plug the computer in, select the maximum performance power plan, close every other program and
   pause Windows updates.
4. Build: `mvn -q install -DskipTests`.
5. Trial run (about 2 minutes, results in `indexing-service/target/jmh-trial`, not valid for the report):

   ```
   mvn -q -pl indexing-service exec:exec -Dexec.executable=java -Dexec.classpathScope=test "-Dexec.args=-Xms2G -Xmx2G -classpath %classpath com.thescratchers.searchengine.indexing.benchmark.IndexingBenchmarkRun --quick"
   ```

6. Official run, without touching the computer while it runs:

   ```
   mvn -q -pl indexing-service exec:exec -Dexec.executable=java -Dexec.classpathScope=test "-Dexec.args=-Xms2G -Xmx2G -classpath %classpath com.thescratchers.searchengine.indexing.benchmark.IndexingBenchmarkRun"
   ```

7. Commit the new `benchmarks/results/<yyyyMMdd>_indexing-jmh/` directory on a branch and open a
   pull request.

### Results directory

| File | Content |
|---|---|
| `jmh.csv` | Raw JMH results: one row per benchmark and thread count, plus the `gc.*` rows of the GC profiler (`gc.alloc.rate.norm` = bytes allocated per operation) |
| `jmh.txt` | Full JMH log, with every iteration |
| `footprint.csv` | `threads, metric, value, unit, includes`: corpus size, `books` and `postings` rows, size of `datamart.db` after a WAL checkpoint, and Java heap before, during (peak) and after indexing the corpus |
| `command.txt` | Exact command, commit, start and end time, JMH, JVM and SQLite settings |
| `host.txt` | OS, CPU, logical processors, memory and JVM of the machine |

An existing, non-empty results directory is never overwritten; `--out DIR` chooses another one.
