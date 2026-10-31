package com.thescratchers.searchengine.indexing.api;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.thescratchers.searchengine.indexing.api.ApiResponses.ErrorResponse;
import com.thescratchers.searchengine.indexing.api.ApiResponses.RebuildResponse;
import com.thescratchers.searchengine.indexing.api.ApiResponses.StatusResponse;
import com.thescratchers.searchengine.indexing.api.ApiResponses.UpdateResponse;
import com.thescratchers.searchengine.indexing.service.BookNotAvailableException;
import com.thescratchers.searchengine.indexing.service.IndexingService;
import com.thescratchers.searchengine.indexing.service.RebuildInProgressException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * HTTP layer of the administrative Indexing API ({@code POST /index/update/{book_id}},
 * {@code POST /index/rebuild} and {@code GET /index/status}). It only translates between HTTP and
 * {@link IndexingService}, and maps every failure to the common error envelope with the status
 * documented in {@code docs/openapi.yaml}. All operations are synchronous: a {@code 200} response
 * means the work has been committed.
 */
public final class IndexingController {

    private static final Logger LOG = LoggerFactory.getLogger(IndexingController.class);
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .serializeNulls()
            .create();
    private static final Pattern BOOK_ID_PATTERN = Pattern.compile("[1-9][0-9]{0,8}");

    private final IndexingService service;

    /** Creates the controller over {@code service}. */
    public IndexingController(IndexingService service) {
        this.service = service;
    }

    /** Registers the routes and the error mapping on {@code app}. */
    public void register(Javalin app) {
        app.post("/index/update/{book_id}", this::update);
        app.post("/index/rebuild", this::rebuild);
        app.get("/index/status", this::status);

        app.exception(BookNotAvailableException.class,
                (e, ctx) -> error(ctx, 404, "NOT_FOUND", e.getMessage()));
        app.exception(RebuildInProgressException.class,
                (e, ctx) -> error(ctx, 409, "CONFLICT", e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unexpected error on {} {}", ctx.method(), ctx.path(), e);
            error(ctx, 500, "INTERNAL_ERROR", "unexpected error");
        });
    }

    private void update(Context ctx) throws Exception {
        String rawId = ctx.pathParam("book_id");
        if (!BOOK_ID_PATTERN.matcher(rawId).matches()) {
            error(ctx, 400, "INVALID_PARAMETER", "book_id must be a positive integer");
            return;
        }
        json(ctx, 200, UpdateResponse.of(service.update(Integer.parseInt(rawId))));
    }

    private void rebuild(Context ctx) throws Exception {
        json(ctx, 200, RebuildResponse.of(service.rebuild()));
    }

    private void status(Context ctx) throws Exception {
        json(ctx, 200, StatusResponse.of(service.status()));
    }

    private static void error(Context ctx, int status, String code, String message) {
        json(ctx, status, ErrorResponse.of(code, message));
    }

    private static void json(Context ctx, int status, Object body) {
        ctx.status(status).contentType("application/json").result(GSON.toJson(body));
    }
}
