package com.thescratchers.searchengine.indexing.benchmark;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Describes the machine and the source version of a benchmark run, with the same fields as the
 * {@code host.txt} of the integration benchmarks, so that every results directory can be checked
 * against the reference machine (team guide 5.4).
 */
final class HostInfo {

    private static final long COMMAND_TIMEOUT_S = 10;

    private HostInfo() {
    }

    /** Content of {@code host.txt}: OS, CPU, logical processors, memory and JVM of this machine. */
    static String describe() {
        com.sun.management.OperatingSystemMXBean os =
                ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        return "os: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")\n"
                + "cpu: " + cpuModel() + "\n"
                + "logical_processors: " + Runtime.getRuntime().availableProcessors() + "\n"
                + String.format(Locale.ROOT, "memory_gb: %.1f%n", os.getTotalMemorySize() / (1024.0 * 1024 * 1024))
                + "java: " + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.runtime.version") + "\n";
    }

    /**
     * Commit of the working tree, marked {@code (uncommitted changes)} when it differs from that
     * commit, so that an official run on modified sources is visible in its results.
     */
    static String gitCommit() {
        String commit = run("git", "rev-parse", "--short", "HEAD");
        if (commit == null) {
            return "unknown";
        }
        String changes = run("git", "status", "--porcelain", "--untracked-files=no");
        return changes == null || changes.isEmpty() ? commit : commit + " (uncommitted changes)";
    }

    private static String cpuModel() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            String out = run("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                    "/v", "ProcessorNameString");
            int index = out == null ? -1 : out.indexOf("REG_SZ");
            return index < 0 ? "unknown" : out.substring(index + "REG_SZ".length()).strip();
        }
        if (osName.contains("mac")) {
            String out = run("sysctl", "-n", "machdep.cpu.brand_string");
            return out == null ? "unknown" : out;
        }
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).strip();
                }
            }
        } catch (IOException e) {
            return "unknown (" + e.getMessage() + ")";
        }
        return "unknown";
    }

    /** Output of a command, stripped, or {@code null} if it cannot be run or fails. */
    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            boolean done = process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS);
            return done && process.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
