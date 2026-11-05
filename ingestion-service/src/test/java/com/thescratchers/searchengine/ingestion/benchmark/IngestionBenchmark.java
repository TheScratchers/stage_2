package com.thescratchers.searchengine.ingestion.benchmark;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.thescratchers.searchengine.ingestion.BookDownloader;
import com.thescratchers.searchengine.ingestion.IngestionApp;
import com.thescratchers.searchengine.ingestion.IngestionService;
import com.thescratchers.searchengine.ingestion.broker.BookDownloadedPublisher;
import com.thescratchers.searchengine.ingestion.datalake.DatalakeLayout;
import com.thescratchers.searchengine.ingestion.datalake.DatalakeWriter;
import com.thescratchers.searchengine.ingestion.datalake.IngestionLogStore;
import com.thescratchers.searchengine.ingestion.gutenberg.GutenbergClient;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Benchmark of the Ingestion Service: measures throughput (books/s) and response latency (p50/p95/p99)
 * for {@code POST /ingest/{id}} across the fixed 20-book corpus with 1, 2 and 4 concurrent clients.
 *
 * <p>Both fresh downloads (initial store and broker event dispatch) and idempotent re-ingestions
 * (already available in the datalake) are benchmarked. All measurements write raw and summary CSVs,
 * command invocation, host environment and resource utilization under
 * {@code benchmarks/results/<yyyyMMdd>_ingestion-throughput/}.
 */
public final class IngestionBenchmark {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String RAW_HEADER = "clients,run,mode,client_id,book_id,http_status,result_status,start_us,latency_us\n";
    private static final String SUMMARY_HEADER = "clients,run,mode,books,errors,duration_s,throughput_books_per_s,mean_ms,p50_ms,p95_ms,p99_ms,max_ms\n";

    private final Settings settings;
    private final List<Integer> bookIds;
    private final Path booksDir;
    private final String day;

    /** Configuration settings parsed from CLI arguments. */
    public record Settings(List<Integer> concurrencyLevels, int repeats, Path out, Path corpusFile, Path booksDir, boolean quick) {

        public static Settings parse(String... args) {
            List<String> list = List.of(args);
            boolean quick = list.contains("--quick");
            Path out = Path.of(value(list, "--out", "benchmarks/results"));
            Path corpus = resolveCorpus(value(list, "--corpus", "samples/books.txt"));
            Path books = resolveBooksDir(value(list, "--books-dir", "samples/books"));
            int repeats = quick ? 1 : Integer.parseInt(value(list, "--repeats", "3"));
            List<Integer> levels = quick
                    ? List.of(1, 2, 4)
                    : parseLevels(value(list, "--clients", "1,2,4"));
            return new Settings(levels, repeats, out, corpus, books, quick);
        }

        private static List<Integer> parseLevels(String csv) {
            return Arrays.stream(csv.split(",")).map(String::trim).map(Integer::parseInt).toList();
        }

        private static String value(List<String> args, String name, String fallback) {
            int index = args.indexOf(name);
            return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : fallback;
        }

        private static Path resolveCorpus(String pathStr) {
            Path p = Path.of(pathStr);
            if (Files.isRegularFile(p)) return p;
            Path fallback = Path.of("..", pathStr);
            if (Files.isRegularFile(fallback)) return fallback;
            return p;
        }

        private static Path resolveBooksDir(String pathStr) {
            Path p = Path.of(pathStr);
            if (Files.isDirectory(p)) return p;
            Path fallback = Path.of("..", pathStr);
            if (Files.isDirectory(fallback)) return fallback;
            return p;
        }
    }

    /** Single request sample. */
    public record Sample(int clients, int run, String mode, int clientId, int bookId, int httpStatus,
                         String resultStatus, long startUs, long latencyUs) {}

    /** Aggregated statistics for one concurrency run. */
    public record LevelSummary(int clients, int run, String mode, int books, int errors, double durationSeconds,
                               double throughput, double meanMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {}

    public IngestionBenchmark(Settings settings) throws IOException {
        this.settings = settings;
        this.bookIds = readBookIds(settings.corpusFile());
        this.booksDir = settings.booksDir();
        this.day = Instant.now().atZone(ZoneOffset.UTC).format(DAY);
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.parse(args);
        IngestionBenchmark benchmark = new IngestionBenchmark(settings);
        benchmark.run();
    }

    /** Executes the benchmark suite across all requested concurrency levels and writes official results. */
    public void run() throws Exception {
        Path resultDir = settings.out().resolve(day + "_ingestion-throughput");
        Files.createDirectories(resultDir);

        Path rawCsv = resultDir.resolve("raw.csv");
        Path summaryCsv = resultDir.resolve("summary.csv");
        Files.writeString(rawCsv, RAW_HEADER, StandardCharsets.UTF_8);
        Files.writeString(summaryCsv, SUMMARY_HEADER, StandardCharsets.UTF_8);

        writeHost(resultDir);
        writeCommand(resultDir);

        System.out.println("===============================================================================");
        System.out.println(" Ingestion Service Benchmark");
        System.out.println(" Output: " + resultDir.toAbsolutePath());
        System.out.println(" Corpus: " + bookIds.size() + " books from " + settings.corpusFile());
        System.out.println(" Concurrency levels: " + settings.concurrencyLevels() + ", repeats: " + settings.repeats());
        System.out.println("===============================================================================");

        ResourceSampler sampler = new ResourceSampler();
        sampler.start();

        long totalBooksProcessed = 0;
        try {
            for (int concurrency : settings.concurrencyLevels()) {
                System.out.printf(Locale.ROOT, "%n== Ingestion Throughput (Concurrency: %d client(s), %d repeat(s))%n",
                        concurrency, settings.repeats());
                System.out.println("run  mode         books  duration_s  books/s   p50_ms  p95_ms  p99_ms  errors");

                for (int run = 1; run <= settings.repeats(); run++) {
                    // 1. Fresh Ingestion test (new datalake directory)
                    Path tempDir = Files.createTempDirectory("ingest-bench-" + concurrency + "-r" + run);
                    try {
                        LevelSummary freshSummary = benchmarkRound(tempDir, concurrency, run, "fresh", rawCsv);
                        appendSummary(summaryCsv, freshSummary);
                        printSummaryRow(freshSummary);
                        totalBooksProcessed += freshSummary.books();

                        // Record disk usage on the first repetition of the highest concurrency level
                        if (run == 1 && concurrency == settings.concurrencyLevels().get(settings.concurrencyLevels().size() - 1)) {
                            writeDisk(resultDir, tempDir);
                        }

                        // 2. Idempotent Ingestion test (re-ingesting into existing populated datalake)
                        LevelSummary idempotentSummary = benchmarkRound(tempDir, concurrency, run, "idempotent", rawCsv);
                        appendSummary(summaryCsv, idempotentSummary);
                        printSummaryRow(idempotentSummary);
                        totalBooksProcessed += idempotentSummary.books();
                    } finally {
                        deleteRecursively(tempDir);
                    }
                }
            }
        } finally {
            sampler.stop();
            sampler.writeResources(resultDir);
        }

        System.out.println("%n===============================================================================");
        System.out.printf(Locale.ROOT, " Benchmark completed successfully. Total processed: %d book operations.%n", totalBooksProcessed);
        System.out.println(" Results saved in: " + resultDir.toAbsolutePath());
        System.out.println("===============================================================================");
    }

    /** Runs one benchmark repetition over the given temporary directory. */
    private LevelSummary benchmarkRound(Path dataDir, int concurrency, int run, String mode, Path rawCsv) throws Exception {
        try (LocalGutenberg gutenberg = new LocalGutenberg(booksDir)) {
            ActiveMQConnectionFactory brokerFactory = new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
            BookDownloadedPublisher publisher = new BookDownloadedPublisher(brokerFactory);

            GutenbergClient gutenbergClient = new GutenbergClient(gutenberg.baseUrl(), Duration.ofSeconds(10));
            DatalakeWriter writer = new DatalakeWriter(dataDir.resolve("datalake"), DatalakeLayout.TIME_BASED);
            BookDownloader downloader = new BookDownloader(gutenbergClient, writer, Clock.fixed(Instant.now(), ZoneOffset.UTC));
            IngestionLogStore store = new IngestionLogStore(dataDir.resolve("datalake/ingestion.db"));

            IngestionService service = new IngestionService(downloader, store, publisher);
            Javalin app = IngestionApp.start(0, service);
            String baseUrl = "http://localhost:" + app.port();

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            List<Callable<Sample>> tasks = new ArrayList<>();

            long roundStartNs = System.nanoTime();
            for (int i = 0; i < bookIds.size(); i++) {
                final int bookId = bookIds.get(i);
                final int clientId = i % concurrency;
                tasks.add(() -> {
                    long startUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime());
                    HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/ingest/" + bookId))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(15))
                            .build();

                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    long durationUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime()) - startUs;

                    String resultStatus = "error";
                    if (resp.statusCode() == 200) {
                        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                        resultStatus = json.has("status") ? json.get("status").getAsString() : "ok";
                    }
                    return new Sample(concurrency, run, mode, clientId, bookId, resp.statusCode(), resultStatus, startUs, durationUs);
                });
            }

            List<Future<Sample>> futures = executor.invokeAll(tasks);
            long roundDurationNs = System.nanoTime() - roundStartNs;

            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);

            app.stop();
            publisher.close();

            List<Sample> samples = new ArrayList<>();
            int errors = 0;
            for (Future<Sample> f : futures) {
                Sample s = f.get();
                samples.add(s);
                if (s.httpStatus() != 200) {
                    errors++;
                }
            }

            appendSamples(rawCsv, samples);

            double durationSeconds = roundDurationNs / 1e9;
            double throughput = samples.size() / durationSeconds;

            double[] latencies = samples.stream()
                    .filter(s -> s.httpStatus() == 200)
                    .mapToDouble(s -> s.latencyUs() / 1000.0)
                    .sorted()
                    .toArray();

            double meanMs = latencies.length == 0 ? 0 : Arrays.stream(latencies).average().orElse(0);
            double p50Ms = percentile(latencies, 50.0);
            double p95Ms = percentile(latencies, 95.0);
            double p99Ms = percentile(latencies, 99.0);
            double maxMs = latencies.length == 0 ? 0 : latencies[latencies.length - 1];

            return new LevelSummary(concurrency, run, mode, samples.size(), errors, durationSeconds,
                    throughput, meanMs, p50Ms, p95Ms, p99Ms, maxMs);
        }
    }

    private static void appendSamples(Path rawCsv, List<Sample> samples) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Sample s : samples) {
            sb.append(s.clients()).append(',')
                    .append(s.run()).append(',')
                    .append(s.mode()).append(',')
                    .append(s.clientId()).append(',')
                    .append(s.bookId()).append(',')
                    .append(s.httpStatus()).append(',')
                    .append(s.resultStatus()).append(',')
                    .append(s.startUs()).append(',')
                    .append(s.latencyUs()).append('\n');
        }
        Files.writeString(rawCsv, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static void appendSummary(Path summaryCsv, LevelSummary s) throws IOException {
        String row = String.format(Locale.ROOT, "%d,%d,%s,%d,%d,%.3f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f%n",
                s.clients(), s.run(), s.mode(), s.books(), s.errors(), s.durationSeconds(),
                s.throughput(), s.meanMs(), s.p50Ms(), s.p95Ms(), s.p99Ms(), s.maxMs());
        Files.writeString(summaryCsv, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static void printSummaryRow(LevelSummary s) {
        System.out.printf(Locale.ROOT, "  %d  %-12s  %4d      %6.2f   %6.1f    %6.2f  %6.2f  %6.2f  %6d%n",
                s.run(), s.mode(), s.books(), s.durationSeconds(), s.throughput(), s.p50Ms(), s.p95Ms(), s.p99Ms(), s.errors());
    }

    private void writeHost(Path dir) throws IOException {
        com.sun.management.OperatingSystemMXBean os =
                ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        String host = "os: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")\n"
                + "cpu: " + cpuModel() + "\n"
                + "logical_processors: " + Runtime.getRuntime().availableProcessors() + "\n"
                + String.format(Locale.ROOT, "memory_gb: %.1f%n", os.getTotalMemorySize() / (1024.0 * 1024 * 1024))
                + "java: " + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.runtime.version") + "\n"
                + "max_heap_mb: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "\n";
        Files.writeString(dir.resolve("host.txt"), host, StandardCharsets.UTF_8);
    }

    private void writeCommand(Path dir) throws IOException {
        String cmd = "mvn -pl ingestion-service test-compile exec:java "
                + "-Dexec.mainClass=com.thescratchers.searchengine.ingestion.benchmark.IngestionBenchmark "
                + "-Dexec.classpathScope=test\n\n"
                + "experiment: ingestion-throughput\n"
                + "started_utc: " + Instant.now() + "\n"
                + "commit: " + gitCommit() + "\n"
                + "concurrency_levels: " + settings.concurrencyLevels() + "\n"
                + "repeats: " + settings.repeats() + "\n"
                + "corpus: " + settings.corpusFile() + " (" + bookIds.size() + " books)\n"
                + "sqlite: journal_mode=WAL, synchronous=NORMAL, busy_timeout=5000\n"
                + "broker: ActiveMQ VM embedded broker\n"
                + "modes: fresh (new datalake) and idempotent (cached)\n";
        Files.writeString(dir.resolve("command.txt"), cmd, StandardCharsets.UTF_8);
    }

    private static void writeDisk(Path dir, Path dataDir) throws IOException {
        long datalakeBytes = sizeOf(dataDir.resolve("datalake"), "ingestion.db");
        long dbBytes = sizeOf(dataDir.resolve("datalake/ingestion.db"));
        String disk = "store,bytes\n"
                + "datalake_books," + datalakeBytes + "\n"
                + "ingestion_log," + dbBytes + "\n";
        Files.writeString(dir.resolve("disk.csv"), disk, StandardCharsets.UTF_8);
    }

    private static long sizeOf(Path path, String excludePrefix) throws IOException {
        if (!Files.exists(path)) return 0;
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile)
                    .filter(f -> excludePrefix == null || !f.getFileName().toString().startsWith(excludePrefix))
                    .mapToLong(f -> f.toFile().length())
                    .sum();
        }
    }

    private static long sizeOf(Path file) {
        return Files.isRegularFile(file) ? file.toFile().length() : 0;
    }

    private static double percentile(double[] sorted, double pct) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(pct / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }

    private static String cpuModel() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        try {
            if (osName.contains("mac")) {
                Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                return out.isEmpty() ? "Apple Silicon / Mac CPU" : out;
            } else if (osName.contains("win")) {
                Process p = new ProcessBuilder("reg", "query",
                        "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString").start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int idx = out.indexOf("REG_SZ");
                return idx >= 0 ? out.substring(idx + 6).trim() : "Unknown Windows CPU";
            } else if (Files.exists(Path.of("/proc/cpuinfo"))) {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return System.getProperty("os.arch");
    }

    private static String gitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static List<Integer> readBookIds(Path corpusFile) throws IOException {
        List<Integer> ids = new ArrayList<>();
        for (String line : Files.readAllLines(corpusFile)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            ids.add(Integer.parseInt(trimmed));
        }
        return Collections.unmodifiableList(ids);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** Stand-in HTTP server for Project Gutenberg that serves books from samples/books. */
    static final class LocalGutenberg implements AutoCloseable {
        private static final Pattern BOOK_PATTERN = Pattern.compile("/cache/epub/(\\d+)/pg(\\d+)\\.txt");
        private final HttpServer server;
        private final Path booksDir;

        LocalGutenberg(Path booksDir) throws IOException {
            this.booksDir = booksDir;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/cache/epub/", this::handle);
            this.server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            Matcher matcher = BOOK_PATTERN.matcher(path);
            if (matcher.matches() && matcher.group(1).equals(matcher.group(2))) {
                int bookId = Integer.parseInt(matcher.group(1));
                Path headerFile = booksDir.resolve(bookId + ".header.txt");
                Path bodyFile = booksDir.resolve(bookId + ".body.txt");
                if (Files.isRegularFile(headerFile) && Files.isRegularFile(bodyFile)) {
                    String raw = Files.readString(headerFile, StandardCharsets.UTF_8)
                            + "\r\n\r\n*** START OF THE PROJECT GUTENBERG EBOOK SAMPLE " + bookId + " ***\r\n\r\n"
                            + Files.readString(bodyFile, StandardCharsets.UTF_8)
                            + "\r\n\r\n*** END OF THE PROJECT GUTENBERG EBOOK SAMPLE " + bookId + " ***\r\n\r\n";
                    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                    exchange.close();
                    return;
                }
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Simple resource sampler monitoring CPU and memory during the benchmark. */
    static final class ResourceSampler {
        private final com.sun.management.OperatingSystemMXBean os;
        private final List<Double> cpuSamples = new ArrayList<>();
        private final List<Double> memorySamples = new ArrayList<>();
        private volatile boolean running = false;
        private Thread thread;

        ResourceSampler() {
            this.os = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        }

        synchronized void start() {
            running = true;
            thread = new Thread(() -> {
                while (running) {
                    try {
                        double cpu = os.getProcessCpuLoad();
                        if (cpu >= 0) {
                            synchronized (cpuSamples) {
                                cpuSamples.add(cpu * 100.0);
                            }
                        }
                        long totalMem = Runtime.getRuntime().totalMemory();
                        long freeMem = Runtime.getRuntime().freeMemory();
                        double usedMb = (totalMem - freeMem) / (1024.0 * 1024.0);
                        synchronized (memorySamples) {
                            memorySamples.add(usedMb);
                        }
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "ingestion-resource-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        synchronized void stop() {
            running = false;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(2000);
                } catch (InterruptedException ignored) {}
            }
        }

        void writeResources(Path dir) throws IOException {
            List<Double> cpus;
            List<Double> mems;
            synchronized (cpuSamples) {
                cpus = new ArrayList<>(cpuSamples);
            }
            synchronized (memorySamples) {
                mems = new ArrayList<>(memorySamples);
            }

            StringBuilder raw = new StringBuilder("sample,cpu_percent,used_memory_mb\n");
            for (int i = 0; i < Math.max(cpus.size(), mems.size()); i++) {
                double cpu = i < cpus.size() ? cpus.get(i) : 0.0;
                double mem = i < mems.size() ? mems.get(i) : 0.0;
                raw.append(i + 1).append(',')
                        .append(String.format(Locale.ROOT, "%.2f", cpu)).append(',')
                        .append(String.format(Locale.ROOT, "%.2f\n", mem));
            }
            Files.writeString(dir.resolve("resources.csv"), raw.toString(), StandardCharsets.UTF_8);

            double meanCpu = cpus.isEmpty() ? 0 : cpus.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double maxCpu = cpus.isEmpty() ? 0 : cpus.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double maxMem = mems.isEmpty() ? 0 : mems.stream().mapToDouble(Double::doubleValue).max().orElse(0);

            String summary = "process,mean_cpu_percent,max_cpu_percent,max_rss_mb\n"
                    + String.format(Locale.ROOT, "ingestion-service,%.1f,%.1f,%.1f%n", meanCpu, maxCpu, maxMem);
            Files.writeString(dir.resolve("resources-summary.csv"), summary, StandardCharsets.UTF_8);
        }
    }
}
