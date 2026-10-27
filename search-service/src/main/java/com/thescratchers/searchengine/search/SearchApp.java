package com.thescratchers.searchengine.search;

import com.google.gson.Gson;
import com.thescratchers.searchengine.common.Config;
import com.thescratchers.searchengine.search.api.SearchController;
import com.thescratchers.searchengine.search.cache.FilterValuesCache;
import com.thescratchers.searchengine.search.events.BookIndexedSubscriber;
import com.thescratchers.searchengine.search.store.DatamartReader;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Entry point of the Search Service: the front-end API over the datamart written by the Indexing
 * Service.
 *
 * <p>Configuration: {@code SEARCH_PORT} (default 7003), {@code DATA_DIR} (the datamart is
 * {@code DATA_DIR/datamarts/datamart.db}, opened read-only) and {@code BROKER_URL} (subscription to
 * {@code BookIndexed} events). The service starts and answers requests even if the datamart or
 * the broker are not available yet.
 */
public final class SearchApp {

    /** Name reported by the health endpoint. */
    public static final String SERVICE_NAME = "search-service";

    private static final Logger LOG = LoggerFactory.getLogger(SearchApp.class);
    private static final Gson GSON = new Gson();

    private SearchApp() {
    }

    public static void main(String[] args) {
        DatamartReader reader = new DatamartReader(Config.datamartDb());
        FilterValuesCache filterValues = new FilterValuesCache(reader);
        BookIndexedSubscriber subscriber =
                new BookIndexedSubscriber(Config.get("BROKER_URL", "tcp://localhost:61616"), filterValues);

        Javalin app = start(Config.getInt("SEARCH_PORT", 7003), reader, filterValues);
        subscriber.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            subscriber.close();
            app.stop();
        }, "search-shutdown"));
    }

    /**
     * Starts the HTTP server on {@code port} ({@code 0} picks a free port) over the datamart of
     * {@code DATA_DIR}, without subscribing to index events, and returns it running.
     */
    public static Javalin start(int port) {
        DatamartReader reader = new DatamartReader(Config.datamartDb());
        return start(port, reader, new FilterValuesCache(reader));
    }

    /** Starts the HTTP server on {@code port} with the given datamart reader and filter cache. */
    public static Javalin start(int port, DatamartReader reader, FilterValuesCache filterValues) {
        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
            // Browser front-ends may be served from another origin (see docs/openapi.yaml).
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });
        app.get("/health", ctx -> ctx.result(GSON.toJson(Map.of("service", SERVICE_NAME, "status", "UP"))));
        new SearchController(reader, filterValues).register(app);
        app.start(port);
        LOG.info("{} listening on port {}", SERVICE_NAME, app.port());
        return app;
    }
}
