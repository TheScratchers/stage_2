package com.thescratchers.searchengine.benchmarks.integration;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Samples the CPU and memory of each service process once per interval and writes them to a CSV
 * file ({@code elapsed_s, process, pid, cpu_percent, rss_mb}).
 *
 * <p>{@code cpu_percent} is the CPU time the process used during the interval divided by the
 * interval, so 100 means one fully busy core. {@code rss_mb} is the resident memory (working set on
 * Windows), which includes the JVM heap actually in use, its metaspace and native memory. CPU time
 * comes from {@link ProcessHandle}; resident memory from {@code tasklist} on Windows, {@code /proc}
 * on Linux and {@code ps} on macOS.
 */
public final class ResourceSampler implements AutoCloseable {

    private final Map<String, Process> processes;
    private final BufferedWriter out;
    private final Duration interval;
    private final Thread thread;
    private final Map<String, Stats> stats = new LinkedHashMap<>();
    private volatile boolean running = true;

    /** Per-process aggregate of the samples. */
    public static final class Stats {
        private double cpuSum;
        private double cpuMax;
        private double rssMax;
        private int samples;

        public double meanCpu() {
            return samples == 0 ? 0 : cpuSum / samples;
        }

        public double maxCpu() {
            return cpuMax;
        }

        public double maxRssMb() {
            return rssMax;
        }
    }

    /** Starts sampling {@code processes} (name → process) into {@code csv}. */
    public ResourceSampler(Map<String, Process> processes, Path csv, Duration interval) throws IOException {
        this.processes = new LinkedHashMap<>(processes);
        this.interval = interval;
        Files.createDirectories(csv.toAbsolutePath().getParent());
        this.out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8);
        this.out.write("elapsed_s,process,pid,cpu_percent,rss_mb\n");
        this.processes.keySet().forEach(name -> stats.put(name, new Stats()));
        this.thread = new Thread(this::run, "resource-sampler");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void run() {
        long start = System.nanoTime();
        Map<String, Long> lastCpu = new HashMap<>();
        long lastWall = start;
        processes.forEach((name, process) -> lastCpu.put(name, cpuNanos(process)));
        while (running) {
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            Map<Long, Long> rssKb = residentMemoryKb();
            StringBuilder rows = new StringBuilder();
            for (Map.Entry<String, Process> entry : processes.entrySet()) {
                Process process = entry.getValue();
                if (!process.isAlive()) {
                    continue;
                }
                long cpu = cpuNanos(process);
                double cpuPercent = 100.0 * (cpu - lastCpu.get(entry.getKey())) / (now - lastWall);
                lastCpu.put(entry.getKey(), cpu);
                double rssMb = rssKb.getOrDefault(process.pid(), -1L) / 1024.0;
                Stats s = stats.get(entry.getKey());
                synchronized (s) {
                    s.cpuSum += cpuPercent;
                    s.cpuMax = Math.max(s.cpuMax, cpuPercent);
                    s.rssMax = Math.max(s.rssMax, rssMb);
                    s.samples++;
                }
                rows.append(String.format(Locale.ROOT, "%.1f,%s,%d,%.1f,%.1f%n", (now - start) / 1e9,
                        entry.getKey(), process.pid(), cpuPercent, rssMb));
            }
            lastWall = now;
            try {
                out.write(rows.toString());
                out.flush();
            } catch (IOException e) {
                return;
            }
        }
    }

    /** Aggregates per process name, available after {@link #close()}. */
    public Map<String, Stats> stats() {
        return Map.copyOf(stats);
    }

    @Override
    public void close() throws IOException {
        running = false;
        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.close();
    }

    private static long cpuNanos(Process process) {
        return process.info().totalCpuDuration().map(Duration::toNanos).orElse(0L);
    }

    /** Resident memory of every process, by pid, in KiB; empty if it cannot be read. */
    static Map<Long, Long> residentMemoryKb() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return parseTasklist(run("tasklist", "/FO", "CSV", "/NH", "/FI", "IMAGENAME eq java.exe"));
            }
            if (os.contains("mac")) {
                Map<Long, Long> rss = new HashMap<>();
                for (String line : run("ps", "-A", "-o", "pid=,rss=").split("\\R")) {
                    String[] fields = line.strip().split("\\s+");
                    if (fields.length == 2) {
                        rss.put(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
                    }
                }
                return rss;
            }
            Map<Long, Long> rss = new HashMap<>();
            try (var dirs = Files.list(Path.of("/proc"))) {
                for (Path dir : dirs.filter(d -> d.getFileName().toString().matches("\\d+")).toList()) {
                    try {
                        for (String line : Files.readAllLines(dir.resolve("status"))) {
                            if (line.startsWith("VmRSS:")) {
                                rss.put(Long.parseLong(dir.getFileName().toString()),
                                        Long.parseLong(line.replaceAll("\\D", "")));
                            }
                        }
                    } catch (IOException ignored) {
                        // The process ended between listing and reading.
                    }
                }
            }
            return rss;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    /**
     * Parses {@code tasklist /FO CSV /NH}: {@code "java.exe","1234","Console","1","123.456 KB"}. The
     * memory column uses the locale's thousands separator, so only its digits are kept.
     */
    static Map<Long, Long> parseTasklist(String output) {
        Map<Long, Long> rss = new HashMap<>();
        for (String line : output.split("\\R")) {
            String[] fields = line.split("\",\"");
            if (fields.length >= 5) {
                String pid = fields[1].replaceAll("\\D", "");
                String memory = fields[4].replaceAll("\\D", "");
                if (!pid.isEmpty() && !memory.isEmpty()) {
                    rss.put(Long.parseLong(pid), Long.parseLong(memory));
                }
            }
        }
        return rss;
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        process.waitFor(10, TimeUnit.SECONDS);
        return new String(output, StandardCharsets.UTF_8);
    }
}
