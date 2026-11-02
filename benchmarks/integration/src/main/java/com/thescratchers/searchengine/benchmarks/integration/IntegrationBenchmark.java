package com.thescratchers.searchengine.benchmarks.integration;

import com.google.gson.JsonParser;
import com.thescratchers.searchengine.apitests.ApiCheck;
import com.thescratchers.searchengine.apitests.pipeline.LocalGutenberg;
import com.thescratchers.searchengine.apitests.pipeline.Pipeline;
import com.thescratchers.searchengine.apitests.pipeline.Pipeline.Topology;
import com.thescratchers.searchengine.benchmarks.integration.BrokerProbe.AvailableBook;
import com.thescratchers.searchengine.benchmarks.load.LevelSummary;
import com.thescratchers.searchengine.benchmarks.load.LoadOptions;
import com.thescratchers.searchengine.benchmarks.load.LoadRunner;
import com.thescratchers.searchengine.benchmarks.load.ResultWriter;
import com.thescratchers.searchengine.benchmarks.load.Sample;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs every integration benchmark of the Stage 2 report on the real system, each process started
 * from its jar, and writes one results directory per experiment under
 * {@code benchmarks/results/<yyyyMMdd>_<experiment>/}.
 *
 * <ol>
 *   <li>{@code search-latency-1-replica} and {@code search-latency-2-replicas}: closed-loop load on
 *       {@code GET /search} with 1 to 64 concurrent clients over the indexed corpus.</li>
 *   <li>{@code indexing-throughput}: {@code BookDownloaded} events sent straight to the queue and
 *       timed until every {@code BookIndexed} has arrived, with 1, 2 and 4 consumer threads and with
 *       two Indexing replicas.</li>
 *   <li>{@code coordination}: per book, the time from {@code POST /ingest} to its {@code BookIndexed}
 *       event and to its visibility in Search; and the same indexing work through the broker versus
 *       a direct {@code POST /index/update}.</li>
 * </ol>
 * Every experiment also records the CPU and memory of each process and the disk used by the
 * datalake, the datamart and the broker store. The books are served from {@code samples/books} by a
 * local stand-in for Project Gutenberg, so network latency is not part of any measurement.
 */
public final class IntegrationBenchmark {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final Settings settings;
    private final String[] args;
    private final List<Integer> bookIds;
    private final List<String> words;
    private final LocalGutenberg gutenberg;
    private final String day = Instant.now().atZone(ZoneOffset.UTC).format(DAY);

    /** Values that depend on {@code --quick}. */
    record Settings(List<Integer> clientLevels, Duration warmup, Duration duration, int repeats, int indexingRounds,
                    String heap, Path out, List<String> experiments) {

        static Settings parse(String... args) {
            List<String> list = List.of(args);
            boolean quick = list.contains("--quick");
            String heap = value(list, "--heap", "1g");
            Path out = Path.of(value(list, "--out", "benchmarks/results"));
            List<String> experiments = Arrays.asList(value(list, "--only", "latency,indexing,coordination").split(","));
            return quick
                    ? new Settings(List.of(1, 4, 16), Duration.ofSeconds(1), Duration.ofSeconds(3), 1, 2, heap, out, experiments)
                    : new Settings(List.of(1, 2, 4, 8, 16, 32, 64), Duration.ofSeconds(10), Duration.ofSeconds(30), 3, 10,
                    heap, out, experiments);
        }

        private static String value(List<String> args, String name, String fallback) {
            int index = args.indexOf(name);
            return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : fallback;
        }
    }

    private IntegrationBenchmark(String[] args) throws IOException {
        this.args = args;
        this.settings = Settings.parse(args);
        this.bookIds = ApiCheck.readBookIds(Path.of("samples", "books.txt"));
        this.words = LoadOptions.readQueries(Path.of("samples", "words.txt"));
        this.gutenberg = new LocalGutenberg(Path.of("samples", "books"));
    }

    public static void main(String[] args) throws Exception {
        if (!Files.isRegularFile(Path.of("samples", "books.txt"))) {
            System.err.println("Run the benchmarks from the repository root, after mvn clean verify.");
            System.exit(2);
        }
        IntegrationBenchmark benchmark = new IntegrationBenchmark(args);
        try {
            benchmark.run();
        } finally {
            benchmark.gutenberg.close();
        }
    }

    private void run() throws Exception {
        System.out.println("Results in " + settings.out().toAbsolutePath() + ", service heap -Xmx" + settings.heap());
        if (settings.experiments().contains("latency")) {
            searchLatency(1);
            searchLatency(2);
        }
        if (settings.experiments().contains("indexing")) {
            indexingThroughput();
        }
        if (settings.experiments().contains("coordination")) {
            coordination();
        }
        System.out.println("Done.");
    }

    // ------------------------------------------------------------------ search latency

    private void searchLatency(int replicas) throws Exception {
        String name = "search-latency-" + replicas + (replicas == 1 ? "-replica" : "-replicas");
        Path dir = directory(name);
        Topology topology = new Topology(replicas, 1, 2, settings.heap());
        System.out.println("\n== " + name + " (" + replicas + " Search replica(s), levels " + settings.clientLevels() + ")");
        try (Pipeline pipeline = newPipeline(topology)) {
            pipeline.start();
            pipeline.loadCorpus(bookIds);
            writeCommand(dir, name, topology, "Closed-loop load on GET /search?q={word} for every word of samples/words.txt, "
                    + "spread round-robin over the Search replicas; one thread and HTTP/1.1 connection per client, no think "
                    + "time; warm-up " + settings.warmup().toSeconds() + " s and measurement " + settings.duration().toSeconds()
                    + " s per level.");
            LoadOptions options = new LoadOptions(pipeline.searchUrls(), settings.clientLevels(), settings.warmup(),
                    settings.duration(), "/search?q={q}", Path.of("samples", "words.txt"), words, dir);
            ResultWriter writer = new ResultWriter(dir);
            LoadRunner runner = new LoadRunner(options);
            System.out.println("clients  throughput_rps  p50_ms  p95_ms  p99_ms  errors");
            try (ResourceSampler sampler = new ResourceSampler(pipeline.processes(), dir.resolve("resources.csv"),
                    Duration.ofSeconds(1))) {
                for (int clients : settings.clientLevels()) {
                    List<Sample> samples = runner.run(clients);
                    LevelSummary summary = LevelSummary.of(clients, samples, settings.duration().toMillis() / 1000.0);
                    writer.append(samples, summary);
                    System.out.printf(Locale.ROOT, "%7d  %14.1f  %6.2f  %6.2f  %6.2f  %6d%n", clients,
                            summary.throughputRps(), summary.p50Ms(), summary.p95Ms(), summary.p99Ms(), summary.errors());
                }
                sampler.close();
                writeResourceSummary(dir, sampler);
            }
            writeDisk(dir, pipeline.dataDir());
        }
    }

    // ------------------------------------------------------------------ indexing throughput

    private void indexingThroughput() throws Exception {
        String name = "indexing-throughput";
        Path dir = directory(name);
        int[][] configurations = {{1, 1}, {1, 2}, {1, 4}, {2, 2}};
        int events = settings.indexingRounds() * bookIds.size();
        writeCommand(dir, name, null, "For each configuration (Indexing replicas x INDEXING_WORKERS) and repetition: a fresh "
                + "system loads the corpus (which also warms the JVMs), then " + events + " BookDownloaded events ("
                + settings.indexingRounds() + " rounds of the " + bookIds.size() + " corpus books) are sent straight to the "
                + "books.downloaded queue and timed until all " + events + " BookIndexed events have arrived. Re-indexing a "
                + "book replaces it, so the index must still hold " + bookIds.size() + " books afterwards (index_ok).");
        List<String> rows = new ArrayList<>(List.of("replicas,workers,repeat,events,seconds,books_per_s,index_ok"));
        System.out.println("\n== " + name + " (" + events + " events per run, " + settings.repeats() + " repetition(s))");
        System.out.println("replicas  workers  repeat  seconds  books_per_s  index_ok");
        for (int[] configuration : configurations) {
            int replicas = configuration[0];
            int workers = configuration[1];
            for (int repeat = 1; repeat <= settings.repeats(); repeat++) {
                Topology topology = new Topology(1, replicas, workers, settings.heap());
                try (Pipeline pipeline = newPipeline(topology)) {
                    pipeline.start();
                    pipeline.loadCorpus(bookIds);
                    List<AvailableBook> books = BrokerProbe.availableBooks(
                            pipeline.dataDir().resolve("datalake").resolve("ingestion.db"));
                    try (BrokerProbe probe = new BrokerProbe(pipeline.brokerUrl());
                         ResourceSampler sampler = new ResourceSampler(pipeline.processes(),
                                 dir.resolve("resources-r" + replicas + "-w" + workers + "-" + repeat + ".csv"),
                                 Duration.ofMillis(500))) {
                        long start = System.nanoTime();
                        for (int round = 0; round < settings.indexingRounds(); round++) {
                            for (AvailableBook book : books) {
                                probe.sendDownloaded(book);
                            }
                        }
                        probe.awaitIndexedEvents(events, Duration.ofMinutes(10));
                        double seconds = (System.nanoTime() - start) / 1e9;
                        boolean indexOk = intField(pipeline.indexingUrl() + "/index/status", "books_indexed") == bookIds.size()
                                && intField(pipeline.searchUrl() + "/search?q=darcy", "total") == 1;
                        rows.add(String.format(Locale.ROOT, "%d,%d,%d,%d,%.3f,%.1f,%s", replicas, workers, repeat, events,
                                seconds, events / seconds, indexOk));
                        System.out.printf(Locale.ROOT, "%8d  %7d  %6d  %7.2f  %11.1f  %s%n", replicas, workers, repeat,
                                seconds, events / seconds, indexOk);
                    }
                }
            }
        }
        Files.write(dir.resolve("raw.csv"), rows, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ coordination cost

    private void coordination() throws Exception {
        String name = "coordination";
        Path dir = directory(name);
        writeCommand(dir, name, Topology.DEFAULT.withHeap(settings.heap()), "Books are processed one at a time, so each "
                + "measurement is unloaded. chain.csv: for each corpus book on a fresh, empty system, the time from sending "
                + "POST /ingest/{id} until its response (download from the local Gutenberg, split, atomic write, ingestion "
                + "log, publish), until its BookIndexed event arrives, and until GET /books/{id} answers 200 in Search. "
                + "direct-vs-event.csv: once the corpus is ingested, the same re-indexing of each book done synchronously "
                + "with POST /index/update/{id} and through the broker (BookDownloaded sent to the queue until its "
                + "BookIndexed arrives); the difference is the cost of event coordination.");
        List<String> chain = new ArrayList<>(List.of("repeat,book_id,ingest_response_ms,indexed_event_ms,searchable_ms"));
        List<String> direct = new ArrayList<>(List.of("repeat,book_id,http_update_ms,event_path_ms"));
        System.out.println("\n== " + name + " (" + settings.repeats() + " repetition(s))");
        for (int repeat = 1; repeat <= settings.repeats(); repeat++) {
            try (Pipeline pipeline = newPipeline(Topology.DEFAULT.withHeap(settings.heap()));
                 ResourceSamplerHolder holder = new ResourceSamplerHolder()) {
                pipeline.start();
                holder.sampler = new ResourceSampler(pipeline.processes(), dir.resolve("resources-" + repeat + ".csv"),
                        Duration.ofMillis(500));
                try (BrokerProbe probe = new BrokerProbe(pipeline.brokerUrl())) {
                    for (int bookId : bookIds) {
                        long t0 = System.nanoTime();
                        int status = send("POST", pipeline.ingestionUrl() + "/ingest/" + bookId);
                        long t1 = System.nanoTime();
                        if (status != 200) {
                            throw new IllegalStateException("POST /ingest/" + bookId + " answered " + status);
                        }
                        long t2 = probe.awaitIndexed(bookId, t0, Duration.ofMinutes(2));
                        while (send("GET", pipeline.searchUrl() + "/books/" + bookId) != 200) {
                            Thread.sleep(1);
                        }
                        long t3 = System.nanoTime();
                        chain.add(String.format(Locale.ROOT, "%d,%d,%.2f,%.2f,%.2f", repeat, bookId, ms(t1 - t0), ms(t2 - t0),
                                ms(t3 - t0)));
                    }
                    for (AvailableBook book : BrokerProbe.availableBooks(
                            pipeline.dataDir().resolve("datalake").resolve("ingestion.db"))) {
                        long t0 = System.nanoTime();
                        int status = send("POST", pipeline.indexingUrl() + "/index/update/" + book.bookId());
                        long t1 = System.nanoTime();
                        if (status != 200) {
                            throw new IllegalStateException("POST /index/update/" + book.bookId() + " answered " + status);
                        }
                        Thread.sleep(50);
                        long t2 = System.nanoTime();
                        probe.sendDownloaded(book);
                        long t3 = probe.awaitIndexed(book.bookId(), t2, Duration.ofMinutes(2));
                        direct.add(String.format(Locale.ROOT, "%d,%d,%.2f,%.2f", repeat, book.bookId(), ms(t1 - t0),
                                ms(t3 - t2)));
                    }
                }
                holder.sampler.close();
                if (repeat == settings.repeats()) {
                    writeResourceSummary(dir, holder.sampler);
                    writeDisk(dir, pipeline.dataDir());
                }
            }
        }
        Files.write(dir.resolve("chain.csv"), chain, StandardCharsets.UTF_8);
        Files.write(dir.resolve("direct-vs-event.csv"), direct, StandardCharsets.UTF_8);
        List<String> summary = new ArrayList<>(List.of("measure,median_ms,p95_ms,max_ms"));
        summary.add(summaryRow("ingest_response", column(chain, 2)));
        summary.add(summaryRow("indexed_event", column(chain, 3)));
        summary.add(summaryRow("searchable", column(chain, 4)));
        summary.add(summaryRow("http_update", column(direct, 2)));
        summary.add(summaryRow("event_path", column(direct, 3)));
        Files.write(dir.resolve("summary.csv"), summary, StandardCharsets.UTF_8);
        summary.forEach(System.out::println);
    }

    /** Closes a sampler that is created after the pipeline has started. */
    private static final class ResourceSamplerHolder implements AutoCloseable {
        private ResourceSampler sampler;

        @Override
        public void close() throws IOException {
            if (sampler != null) {
                sampler.close();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private Pipeline newPipeline(Topology topology) throws IOException {
        Path dataDir = Files.createTempDirectory("stage2-bench-");
        return new Pipeline(Path.of("."), dataDir, gutenberg.baseUrl(), topology);
    }

    private Path directory(String experiment) throws IOException {
        Path dir = settings.out().resolve(day + "_" + experiment);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("host.txt"), ResultWriter.hostDescription(), StandardCharsets.UTF_8);
        return dir;
    }

    private void writeCommand(Path dir, String experiment, Topology topology, String method) throws IOException {
        String text = "java -jar benchmarks/integration/target/integration-bench.jar " + String.join(" ", args) + "\n\n"
                + "experiment: " + experiment + "\n"
                + "started_utc: " + Instant.now() + "\n"
                + "commit: " + gitCommit() + "\n"
                + (topology == null ? "" : "topology: " + topology.searchReplicas() + " Search, " + topology.indexingReplicas()
                + " Indexing x " + topology.indexingWorkers() + " workers, 1 Ingestion, 1 broker\n")
                + "service_jvm: -Xmx" + settings.heap() + "\n"
                + "corpus: samples/books.txt (" + bookIds.size() + " books, served by a local Gutenberg stand-in)\n"
                + "queries: samples/words.txt " + words + "\n"
                + "sqlite: journal_mode=WAL, synchronous=NORMAL, busy_timeout=5000 (common.Sqlite)\n"
                + "broker: ActiveMQ 6.3.2 embedded, persistent KahaDB, 5 redeliveries then ActiveMQ.DLQ\n"
                + "method: " + method + "\n";
        Files.writeString(dir.resolve("command.txt"), text, StandardCharsets.UTF_8);
    }

    private static void writeResourceSummary(Path dir, ResourceSampler sampler) throws IOException {
        List<String> rows = new ArrayList<>(List.of("process,mean_cpu_percent,max_cpu_percent,max_rss_mb"));
        Map<String, ResourceSampler.Stats> stats = new LinkedHashMap<>(sampler.stats());
        stats.forEach((process, s) -> rows.add(String.format(Locale.ROOT, "%s,%.1f,%.1f,%.1f", process, s.meanCpu(),
                s.maxCpu(), s.maxRssMb())));
        Files.write(dir.resolve("resources-summary.csv"), rows, StandardCharsets.UTF_8);
    }

    private static void writeDisk(Path dir, Path dataDir) throws IOException {
        List<String> rows = new ArrayList<>(List.of("store,bytes"));
        rows.add("datalake_books," + size(dataDir.resolve("datalake"), "ingestion.db"));
        rows.add("ingestion_log," + size(dataDir.resolve("datalake"), null, "ingestion.db"));
        rows.add("datamart," + size(dataDir.resolve("datamarts"), null));
        rows.add("broker_store," + size(dataDir.resolve("broker"), null));
        Files.write(dir.resolve("disk.csv"), rows, StandardCharsets.UTF_8);
    }

    /** Bytes under {@code root}, excluding files whose name starts with {@code exclude}, or only those starting with {@code only}. */
    private static long size(Path root, String exclude, String... only) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> exclude == null || !f.getFileName().toString().startsWith(exclude))
                    .filter(f -> only.length == 0 || f.getFileName().toString().startsWith(only[0]))
                    .mapToLong(f -> f.toFile().length()).sum();
        }
    }

    private static int send(String method, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static int intField(String url, String field) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return JsonParser.parseString(response.body()).getAsJsonObject().get(field).getAsInt();
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private static double[] column(List<String> rows, int index) {
        return rows.stream().skip(1).mapToDouble(row -> Double.parseDouble(row.split(",")[index])).sorted().toArray();
    }

    static String summaryRow(String measure, double[] sorted) {
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f", measure, percentile(sorted, 50), percentile(sorted, 95),
                sorted.length == 0 ? 0 : sorted[sorted.length - 1]);
    }

    /** Nearest-rank percentile of sorted values. */
    static double percentile(double[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }

    private static String gitCommit() {
        try {
            Process git = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return git.waitFor(5, TimeUnit.SECONDS) && git.exitValue() == 0 ? out : "unknown";
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
