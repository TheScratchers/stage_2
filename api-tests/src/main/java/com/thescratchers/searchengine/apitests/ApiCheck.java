package com.thescratchers.searchengine.apitests;

import java.nio.file.Path;
import java.util.List;

/**
 * Command-line entry point of the API checks.
 *
 * <pre>
 * java -jar api-tests/target/api-tests.jar fixture &lt;DATA_DIR&gt;
 *     creates the fixture datamart in DATA_DIR/datamarts/datamart.db
 * java -jar api-tests/target/api-tests.jar check &lt;BASE_URL&gt; &lt;CASE_FILE&gt;...
 *     runs the cases against a running service; exit code 0 only if every case passes
 * </pre>
 */
public final class ApiCheck {

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
                System.out.println("== " + args[i] + " against " + args[1]);
                List<CaseRunner.Result> results = runner.run(Path.of(args[i]));
                for (CaseRunner.Result result : results) {
                    System.out.println((result.passed() ? "PASS  " : "FAIL  ") + result.name()
                            + result.failure().map(reason -> "\n      " + reason).orElse(""));
                }
                total += results.size();
                failed += (int) results.stream().filter(result -> !result.passed()).count();
            }
            System.out.println((total - failed) + "/" + total + " checks passed");
            System.exit(failed == 0 ? 0 : 1);
        }
        System.err.println("Usage:\n"
                + "  java -jar api-tests.jar fixture <DATA_DIR>\n"
                + "  java -jar api-tests.jar check <BASE_URL> <CASE_FILE>...");
        System.exit(2);
    }
}
