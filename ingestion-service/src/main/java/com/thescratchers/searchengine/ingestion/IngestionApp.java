package com.thescratchers.searchengine.ingestion;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.thescratchers.searchengine.common.Config;
import com.thescratchers.searchengine.ingestion.broker.BookDownloadedPublisher;
import com.thescratchers.searchengine.ingestion.datalake.IngestionLogStore;
import com.thescratchers.searchengine.ingestion.gutenberg.DownloadException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import jakarta.jms.JMSException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Entry point and HTTP controller of the Ingestion Service.
 *
 * <p>Provides endpoints to ingest books into the datalake, query ingestion status, and list all
 * downloaded books, as specified in {@code docs/openapi.yaml}.
 */
public final class IngestionApp {

    /** Name reported by the health endpoint. */
    public static final String SERVICE_NAME = "ingestion-service";

    private static final Logger LOG = LoggerFactory.getLogger(IngestionApp.class);
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private IngestionApp() {
    }

    public static void main(String[] args) {
        int port = Config.getInt("INGESTION_PORT", 7001);
        try {
            IngestionService service = createDefaultService();
            start(port, service);
        } catch (Exception e) {
            LOG.error("Fatal error starting {}", SERVICE_NAME, e);
            System.exit(1);
        }
    }

    /** Starts the server on {@code port} with default service dependencies wired from configuration. */
    public static Javalin start(int port) {
        try {
            return start(port, createDefaultService());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize ingestion service dependencies", e);
        }
    }

    /** Starts the server on {@code port} using the provided {@link IngestionService}. */
    public static Javalin start(int port, IngestionService service) {
        Javalin app = createApp(service);
        app.start(port);
        LOG.info("{} listening on port {}", SERVICE_NAME, app.port());
        return app;
    }

    /** Creates and configures the {@link Javalin} application without starting it. */
    public static Javalin createApp(IngestionService service) {
        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        });

        // Operations
        app.get("/health", ctx -> ctx.result(GSON.toJson(Map.of("service", SERVICE_NAME, "status", "UP"))));

        // Ingestion endpoints
        app.post("/ingest/{book_id}", ctx -> handleIngest(ctx, service));
        app.get("/ingest/status/{book_id}", ctx -> handleStatus(ctx, service));
        app.get("/ingest/list", ctx -> handleList(ctx, service));

        return app;
    }

    private static void handleIngest(Context ctx, IngestionService service) {
        int bookId = parseBookId(ctx);
        if (bookId <= 0) {
            ctx.status(400).result(GSON.toJson(error("INVALID_PARAMETER", "book_id must be a positive integer")));
            return;
        }

        try {
            IngestResult result = service.ingest(bookId);
            ctx.status(200).result(GSON.toJson(result));
        } catch (DownloadException e) {
            if (e.reason() == DownloadException.Reason.NOT_FOUND) {
                LOG.info("Book {} does not exist on Project Gutenberg", bookId);
                ctx.status(404).result(GSON.toJson(
                        error("NOT_FOUND", "Book " + bookId + " does not exist on Project Gutenberg")));
                return;
            }
            LOG.warn("Upstream error while ingesting book {}: {}", bookId, e.getMessage());
            ctx.status(502).result(GSON.toJson(error("UPSTREAM_ERROR", e.getMessage())));
        } catch (JMSException e) {
            LOG.error("Broker unavailable while publishing BookDownloaded for book {}: {}", bookId, e.getMessage());
            ctx.status(503).result(GSON.toJson(error("UNAVAILABLE", "Message broker is unavailable")));
        } catch (IOException | SQLException e) {
            LOG.error("Internal error while ingesting book {}", bookId, e);
            ctx.status(500).result(GSON.toJson(error("INTERNAL_ERROR", "Internal server error")));
        } catch (Exception e) {
            LOG.error("Unexpected error while ingesting book {}", bookId, e);
            ctx.status(500).result(GSON.toJson(error("INTERNAL_ERROR", "Internal server error")));
        }
    }

    private static void handleStatus(Context ctx, IngestionService service) {
        int bookId = parseBookId(ctx);
        if (bookId <= 0) {
            ctx.status(400).result(GSON.toJson(error("INVALID_PARAMETER", "book_id must be a positive integer")));
            return;
        }

        try {
            Optional<String> status = service.getStatus(bookId);
            if (status.isPresent()) {
                ctx.status(200).result(GSON.toJson(Map.of("book_id", bookId, "status", status.get())));
            } else {
                ctx.status(404).result(GSON.toJson(error("NOT_FOUND", "Book " + bookId + " has not been ingested")));
            }
        } catch (SQLException e) {
            LOG.error("Internal error fetching status for book {}", bookId, e);
            ctx.status(500).result(GSON.toJson(error("INTERNAL_ERROR", "Internal server error")));
        } catch (Exception e) {
            LOG.error("Unexpected error fetching status for book {}", bookId, e);
            ctx.status(500).result(GSON.toJson(error("INTERNAL_ERROR", "Internal server error")));
        }
    }

    private static void handleList(Context ctx, IngestionService service) {
        try {
            List<Integer> books = service.listAvailable();
            Map<String, Object> response = Map.of(
                    "count", books.size(),
                    "books", books
            );
            ctx.status(200).result(GSON.toJson(response));
        } catch (SQLException e) {
            LOG.error("Internal error listing ingested books", e);
            ctx.status(500).result(GSON.toJson(error("INTERNAL_ERROR", "Internal server error")));
        } catch (Exception e) {
            LOG.error("Unexpected error listing ingested books", e);
            ctx.status(500).result(GSON.toJson(error("INTERNAL_ERROR", "Internal server error")));
        }
    }

    private static int parseBookId(Context ctx) {
        String param = ctx.pathParam("book_id");
        try {
            return Integer.parseInt(param);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message != null ? message : ""));
    }

    private static IngestionService createDefaultService() throws SQLException {
        BookDownloader downloader = BookDownloader.fromConfig();
        IngestionLogStore store = IngestionLogStore.fromConfig();
        BookDownloadedPublisher publisher = BookDownloadedPublisher.fromConfig();
        return new IngestionService(downloader, store, publisher);
    }
}

