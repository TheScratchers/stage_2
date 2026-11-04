package com.thescratchers.searchengine.indexing.events;

import com.thescratchers.searchengine.indexing.IndexingApp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The real Indexing Service ({@link IndexingApp#main}) running in a child JVM, so that a test can
 * kill it abruptly, as a crash or {@code kill -9} would, with no shutdown hook and no chance to
 * finish its current message. Its standard output and error go to a log file.
 */
final class IndexingProcess implements AutoCloseable {

    private static final String HEAP = "-Xmx256m";
    private static final String LOG_LINE_OF_AN_INDEXED_BOOK = "Indexed book ";

    private final Process process;
    private final Path log;
    private boolean killed;

    /**
     * Starts the service with its own HTTP port chosen by the system.
     *
     * @param dataDir   {@code DATA_DIR} of the service; the datamart is {@code dataDir/datamarts/datamart.db}
     * @param brokerUrl {@code BROKER_URL}
     * @param workers   {@code INDEXING_WORKERS}
     * @param log       file that receives the output of the process
     */
    IndexingProcess(Path dataDir, String brokerUrl, int workers, Path log) throws IOException {
        this.log = log;
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                java.toString(), HEAP, "-cp", classPath(), IndexingApp.class.getName()));
        Map<String, String> env = builder.environment();
        env.put("DATA_DIR", dataDir.toAbsolutePath().toString());
        env.put("BROKER_URL", brokerUrl);
        env.put("INDEXING_WORKERS", Integer.toString(workers));
        env.put("INDEXING_PORT", "0");
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        process = builder.start();
    }

    /**
     * Class path of the tests, which contains the service and all its dependencies. Surefire
     * publishes it in {@code surefire.test.class.path}; outside Maven it is {@code java.class.path}.
     */
    private static String classPath() {
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null ? surefire : System.getProperty("java.class.path");
    }

    /** Fails with the end of the log if the process has exited, for example because it did not start. */
    void requireAlive() throws IOException {
        if (!process.isAlive()) {
            throw new AssertionError("Indexing process exited with code " + process.exitValue()
                    + "; its log ends with:\n" + tail());
        }
    }

    /** Kills the process at once, without running its shutdown hook, and waits until it has died. */
    void kill() throws InterruptedException {
        killed = true;
        process.destroyForcibly();
        process.waitFor(30, TimeUnit.SECONDS);
    }

    /** Whether the test has killed this process on purpose. */
    boolean wasKilled() {
        return killed;
    }

    /** Number of books this process has logged as indexed, including re-indexed duplicates. */
    long indexedBooks() throws IOException {
        return lines().stream().filter(line -> line.contains(LOG_LINE_OF_AN_INDEXED_BOOK)).count();
    }

    private List<String> lines() throws IOException {
        return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
    }

    private String tail() throws IOException {
        List<String> lines = lines();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
    }

    @Override
    public void close() throws InterruptedException {
        if (process.isAlive()) {
            kill();
        }
    }
}
