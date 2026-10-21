package com.thescratchers.searchengine.indexing;

import com.google.gson.Gson;
import com.thescratchers.searchengine.common.Config;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Entry point of the Indexing Service.
 *
 * <p>The HTTP port is read from {@code INDEXING_PORT} (default 7002). For now the service only exposes
 * {@code GET /health}; its business endpoints are added on top of this skeleton.
 */
public final class IndexingApp {

    /** Name reported by the health endpoint. */
    public static final String SERVICE_NAME = "indexing-service";

    private static final Logger LOG = LoggerFactory.getLogger(IndexingApp.class);
    private static final Gson GSON = new Gson();

    private IndexingApp() {
    }

    public static void main(String[] args) {
        start(Config.getInt("INDEXING_PORT", 7002));
    }

    /** Starts the HTTP server on {@code port} ({@code 0} picks a free port) and returns it running. */
    public static Javalin start(int port) {
        Javalin app = Javalin.create(config -> config.http.defaultContentType = "application/json");
        app.get("/health", ctx -> ctx.result(GSON.toJson(Map.of("service", SERVICE_NAME, "status", "UP"))));
        app.start(port);
        LOG.info("{} listening on port {}", SERVICE_NAME, app.port());
        return app;
    }
}
