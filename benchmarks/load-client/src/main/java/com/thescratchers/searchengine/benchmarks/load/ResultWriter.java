package com.thescratchers.searchengine.benchmarks.load;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Writes the files of one run into its results directory, as agreed for every benchmark:
 * {@code raw.csv} (one row per request), {@code summary.csv} (one row per concurrency level),
 * {@code command.txt} (exact command and settings) and {@code host.txt} (machine description).
 */
public final class ResultWriter {

    static final String RAW_HEADER = "clients,client,target,query,status,start_us,latency_us,total";
    static final String SUMMARY_HEADER = "clients,requests,errors,throughput_rps,mean_ms,p50_ms,p95_ms,p99_ms,max_ms";

    private final Path dir;

    /** Creates {@code dir} if needed and starts {@code raw.csv} and {@code summary.csv} with their headers. */
    public ResultWriter(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("raw.csv"), RAW_HEADER + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("summary.csv"), SUMMARY_HEADER + "\n", StandardCharsets.UTF_8);
    }

    /** Appends the samples and the summary of one level. */
    public void append(List<Sample> samples, LevelSummary summary) throws IOException {
        try (BufferedWriter raw = Files.newBufferedWriter(dir.resolve("raw.csv"), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            for (Sample s : samples) {
                raw.write(s.clients() + "," + s.client() + "," + csv(s.target()) + "," + csv(s.query()) + ","
                        + s.status() + "," + s.startMicros() + "," + s.latencyMicros() + "," + s.total() + "\n");
            }
        }
        Files.writeString(dir.resolve("summary.csv"), String.format(Locale.ROOT,
                        "%d,%d,%d,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f%n", summary.clients(), summary.requests(),
                        summary.errors(), summary.throughputRps(), summary.meanMs(), summary.p50Ms(),
                        summary.p95Ms(), summary.p99Ms(), summary.maxMs()),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /** Writes {@code command.txt} with the command line and the resolved settings. */
    public void writeCommand(String[] args, LoadOptions options) throws IOException {
        String text = "java -jar benchmarks/load-client/target/load-client.jar " + String.join(" ", args) + "\n"
                + "\n"
                + "started_utc: " + Instant.now() + "\n"
                + "targets: " + String.join(", ", options.targets()) + "\n"
                + "clients: " + options.clientLevels() + "\n"
                + "warmup_s: " + options.warmup().toSeconds() + "\n"
                + "duration_s: " + options.duration().toSeconds() + "\n"
                + "path: " + options.pathTemplate() + "\n"
                + "queries: " + options.queriesFile() + " " + options.queries() + "\n"
                + "model: closed loop, one thread and one HTTP/1.1 connection per client, no think time\n";
        Files.writeString(dir.resolve("command.txt"), text, StandardCharsets.UTF_8);
    }

    /** Writes {@code host.txt} with the machine and JVM that ran the load client. */
    public void writeHost() throws IOException {
        com.sun.management.OperatingSystemMXBean os =
                ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        String text = "os: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")\n"
                + "cpu: " + cpuModel() + "\n"
                + "logical_processors: " + Runtime.getRuntime().availableProcessors() + "\n"
                + String.format(Locale.ROOT, "memory_gb: %.1f%n", os.getTotalMemorySize() / (1024.0 * 1024 * 1024))
                + "java: " + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.runtime.version") + "\n"
                + "load_client_max_heap_mb: " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + "\n";
        Files.writeString(dir.resolve("host.txt"), text, StandardCharsets.UTF_8);
    }

    /** Quotes a CSV field when it contains a comma, a quote or a line break. */
    static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Best-effort CPU model name: registry on Windows, /proc/cpuinfo on Linux, sysctl on macOS. */
    private static String cpuModel() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        try {
            if (osName.contains("win")) {
                String out = run("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                        "/v", "ProcessorNameString");
                int index = out.indexOf("REG_SZ");
                if (index >= 0) {
                    return out.substring(index + "REG_SZ".length()).strip();
                }
            } else if (osName.contains("mac")) {
                return run("sysctl", "-n", "machdep.cpu.brand_string").strip();
            } else {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).strip();
                    }
                }
            }
        } catch (IOException e) {
            return "unknown (" + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "unknown";
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "";
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
