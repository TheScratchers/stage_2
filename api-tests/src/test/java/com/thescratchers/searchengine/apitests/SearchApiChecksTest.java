package com.thescratchers.searchengine.apitests;

import com.thescratchers.searchengine.search.SearchApp;
import com.thescratchers.searchengine.search.cache.FilterValuesCache;
import com.thescratchers.searchengine.search.store.DatamartReader;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every case of {@code cases/search.json} against a Search Service started in-process over
 * the fixture datamart, so that {@code mvn verify} checks the documented contract on every build.
 * Each case is reported as its own test.
 */
class SearchApiChecksTest {

    private static final Path CASES = Path.of("cases", "search.json");

    @TempDir
    static Path dataDir;

    private static Javalin app;

    @BeforeAll
    static void startSearchOverTheFixture() throws Exception {
        DatamartReader reader = new DatamartReader(FixtureDatamart.create(dataDir));
        app = SearchApp.start(0, reader, new FilterValuesCache(reader));
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop();
        }
    }

    @TestFactory
    Stream<DynamicTest> searchApiCases() throws Exception {
        List<CaseRunner.Result> results = new CaseRunner("http://localhost:" + app.port()).run(CASES);
        assertTrue(results.size() >= 40, "expected the full case file, found " + results.size() + " cases");
        return results.stream().map(result -> DynamicTest.dynamicTest(result.name(),
                () -> result.failure().ifPresent(reason -> fail(reason))));
    }
}
