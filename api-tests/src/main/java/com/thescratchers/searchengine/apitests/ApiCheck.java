package com.thescratchers.searchengine.apitests;

import com.thescratchers.searchengine.apitests.pipeline.LocalGutenberg;
import com.thescratchers.searchengine.apitests.pipeline.Pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command-line entry point of the API checks. It prints its report to standard output.
 *
 * <pre>
 * java -jar api-tests/target/api-tests.jar fixture &lt;DATA_DIR&gt;
 *     creates the fixture datamart in DATA_DIR/datamarts/datamart.db
 * java -jar api-tests/target/api-tests.jar check &lt;BASE_URL&gt; &lt;CASE_FILE&gt;...
 *     runs the cases against a running service; exit code 0 only if every case passes
 * java -jar api-tests/target/api-tests.jar pipeline [--gutenberg] [--keep]
 *     from the repository root: starts broker, Ingestion, Indexing and Search from their jars,
 *     loads samples/books.txt and runs the pipeline case files against the three services
 * </pre>
 */
public final class ApiCheck {

    /** Case files of the pipeline checks and the service each one is run against. */
    static final Map<String, String> PIPELINE_CASES = Map.of(
            "corpus-search.json", "search",
            "ingestion.json", "ingestion",
            "indexing.json", "indexing");

    private ApiCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("fixture")) {
            System.out.println("Fixture datamart created at " + FixtureDatamart.create(Path.of(args[1])).toAbsolutePath());
            return;
        }
        if (args.length >= 3 && args[0].equals("check")) {
            CaseRunner runner = new CaseRunner(args[1]);
            int failed = 0;
            int total = 0;
            for (int i = 2; i < args.length; i++) {
                List<CaseRunner.Result> results = runner.run(Path.of(args[i]));
                failed += report(args[i] + " against " + args[1], results);
                total += results.size();
            }
            System.out.println((total - failed) + "/" + total + " checks passed");
            System.exit(failed == 0 ? 0 : 1);
        }
        if (args.length >= 1 && args[0].equals("pipeline")) {
            List<String> options = List.of(args).subList(1, args.length);
            System.exit(pipeline(options.contains("--gutenberg"), options.contains("--keep")));
        }
        System.err.println("Usage:\n"
                + "  java -jar api-tests.jar fixture <DATA_DIR>\n"
                + "  java -jar api-tests.jar check <BASE_URL> <CASE_FILE>...\n"
                + "  java -jar api-tests.jar pipeline [--gutenberg] [--keep]");
        System.exit(2);
    }

    /** Runs the end-to-end checks; returns the process exit code. */
    private static int pipeline(boolean realGutenberg, boolean keep) throws Exception {
        Path books = Path.of("samples", "books.txt");
        if (!Files.isRegularFile(books)) {
            System.err.println("Run the pipeline checks from the repository root (samples/books.txt not found)");
            return 2;
        }
        List<Integer> bookIds = readBookIds(books);
        Path dataDir = Files.createTempDirectory("stage2-pipeline-");
        LocalGutenberg gutenberg = realGutenberg ? null : new LocalGutenberg(Path.of("samples", "books"));
        String gutenbergUrl = realGutenberg ? "https://www.gutenberg.org" : gutenberg.baseUrl();
        Pipeline pipeline = new Pipeline(Path.of("."), dataDir, gutenbergUrl);
        try {
            System.out.println("DATA_DIR " + dataDir + " (logs in " + dataDir.resolve("logs") + ")");
            System.out.println("Books served by " + gutenbergUrl);
            pipeline.start();
            long start = System.nanoTime();
            pipeline.loadCorpus(bookIds);
            System.out.printf(Locale.ROOT, "Ingested and indexed %d books in %.1f s%n", bookIds.size(),
                    (System.nanoTime() - start) / 1e9);

            Map<String, String> urls = Map.of("search", pipeline.searchUrl(), "ingestion", pipeline.ingestionUrl(),
                    "indexing", pipeline.indexingUrl());
            int failed = 0;
            int total = 0;
            for (String file : List.of("corpus-search.json", "ingestion.json", "indexing.json")) {
                Path caseFile = Path.of("api-tests", "cases", file);
                String url = urls.get(PIPELINE_CASES.get(file));
                List<CaseRunner.Result> results = new CaseRunner(url).run(caseFile);
                failed += report(caseFile + " against " + url, results);
                total += results.size();
            }
            System.out.println((total - failed) + "/" + total + " checks passed");
            if (keep) {
                System.out.println("Services left running: search " + pipeline.searchUrl() + ", ingestion "
                        + pipeline.ingestionUrl() + ", indexing " + pipeline.indexingUrl());
            }
            return failed == 0 ? 0 : 1;
        } finally {
            if (!keep) {
                pipeline.close();
            }
            if (gutenberg != null && !keep) {
                gutenberg.close();
            }
        }
    }

    /** Prints one line per case and returns the number of failures. */
    private static int report(String title, List<CaseRunner.Result> results) {
        System.out.println("== " + title);
        int failed = 0;
        for (CaseRunner.Result result : results) {
            System.out.println((result.passed() ? "PASS  " : "FAIL  ") + result.name()
                    + result.failure().map(reason -> "\n      " + reason).orElse(""));
            if (!result.passed()) {
                failed++;
            }
        }
        return failed;
    }

    /** Reads one Gutenberg id per line, skipping blank lines and {@code #} comments. */
    public static List<Integer> readBookIds(Path file) throws IOException {
        List<Integer> ids = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                ids.add(Integer.parseInt(trimmed));
            }
        }
        return ids;
    }
}
