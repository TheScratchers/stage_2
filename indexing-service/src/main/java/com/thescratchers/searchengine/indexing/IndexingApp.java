package com.thescratchers.searchengine.indexing;

import com.google.gson.Gson;
import com.thescratchers.searchengine.common.Config;
import com.thescratchers.searchengine.indexing.api.IndexingController;
import com.thescratchers.searchengine.indexing.service.IndexingService;
import com.thescratchers.searchengine.indexing.source.IngestionLogReader;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Entry point of the Indexing Service.
 *
 * <p>Configuration: {@code INDEXING_PORT} (default 7002) and {@code DATA_DIR}. The service writes
 * the datamart {@code DATA_DIR/datamarts/datamart.db}, creating it on start-up, and reads the
 * ingestion log {@code DATA_DIR/datalake/ingestion.db} read-only to locate available books.
 */
public final class IndexingApp {

    /** Name reported by the health endpoint. */
    public static final String SERVICE_NAME = "indexing-service";

    /** File name of the ingestion log inside the datalake (owned by the Ingestion Service). */
    public static final String INGESTION_DB = "ingestion.db";

    private static final Logger LOG = LoggerFactory.getLogger(IndexingApp.class);
    private static final Gson GSON = new Gson();

    private IndexingApp() {
    }

    /** Starts the service wired from the configuration and stops it when the JVM shuts down. */
    public static void main(String[] args) throws SQLException {
        DatamartStore store = new DatamartStore(Config.datamartDb());
        IndexingService service = new IndexingService(
                new IngestionLogReader(Config.datalakeDir().resolve(INGESTION_DB)), new BookIndexer(store), store);
        Javalin app = start(Config.getInt("INDEXING_PORT", 7002), service);
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "indexing-shutdown"));
    }

    /** Starts the HTTP server on {@code port} ({@code 0} picks a free port) and returns it running. */
    public static Javalin start(int port, IndexingService service) {
        Javalin app = Javalin.create(config -> config.http.defaultContentType = "application/json");
        app.get("/health", ctx -> ctx.result(GSON.toJson(Map.of("service", SERVICE_NAME, "status", "UP"))));
        new IndexingController(service).register(app);
        app.start(port);
        LOG.info("{} listening on port {}", SERVICE_NAME, app.port());
        return app;
    }
}
