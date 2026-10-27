package com.thescratchers.searchengine.search.api;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.thescratchers.searchengine.search.api.ApiResponses.BookResponse;
import com.thescratchers.searchengine.search.api.ApiResponses.ErrorResponse;
import com.thescratchers.searchengine.search.api.ApiResponses.SearchResponse;
import com.thescratchers.searchengine.search.cache.FilterValuesCache;
import com.thescratchers.searchengine.search.model.BookDetails;
import com.thescratchers.searchengine.search.model.SearchPage;
import com.thescratchers.searchengine.search.query.InvalidParameterException;
import com.thescratchers.searchengine.search.query.SearchQuery;
import com.thescratchers.searchengine.search.store.DatamartReader;
import com.thescratchers.searchengine.search.store.IndexUnavailableException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * HTTP layer of the front-end Search API ({@code GET /search}, {@code GET /books/{book_id}} and
 * {@code GET /search/filters}). It only translates between HTTP and the search core: parameters
 * are validated by {@link SearchQuery}, data is read by {@link DatamartReader}, and every failure
 * is mapped to the common error envelope with the status documented in {@code docs/openapi.yaml}.
 */
public final class SearchController {

    private static final Logger LOG = LoggerFactory.getLogger(SearchController.class);
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .serializeNulls()
            .create();
    private static final Pattern BOOK_ID_PATTERN = Pattern.compile("[1-9][0-9]{0,8}");

    private final DatamartReader reader;
    private final FilterValuesCache filterValues;

    public SearchController(DatamartReader reader, FilterValuesCache filterValues) {
        this.reader = reader;
        this.filterValues = filterValues;
    }

    /** Registers the routes and the error mapping on {@code app}. */
    public void register(Javalin app) {
        app.get("/search", this::search);
        app.get("/search/filters", this::filters);
        app.get("/books/{book_id}", this::book);

        app.exception(InvalidParameterException.class,
                (e, ctx) -> error(ctx, 400, "INVALID_PARAMETER", e.getMessage()));
        app.exception(IndexUnavailableException.class,
                (e, ctx) -> error(ctx, 503, "UNAVAILABLE", e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unexpected error on {} {}", ctx.method(), ctx.path(), e);
            error(ctx, 500, "INTERNAL_ERROR", "unexpected error");
        });
    }

    private void search(Context ctx) throws Exception {
        SearchQuery query = SearchQuery.parse(ctx.queryParam("q"), ctx.queryParam("author"),
                ctx.queryParam("language"), ctx.queryParam("year"), ctx.queryParam("offset"), ctx.queryParam("limit"));
        SearchPage page = reader.search(query);
        json(ctx, 200, SearchResponse.of(query, page));
    }

    private void book(Context ctx) throws Exception {
        String rawId = ctx.pathParam("book_id");
        if (!BOOK_ID_PATTERN.matcher(rawId).matches()) {
            throw new InvalidParameterException("book_id must be a positive integer");
        }
        int bookId = Integer.parseInt(rawId);
        Optional<BookDetails> book = reader.findBook(bookId);
        if (book.isPresent()) {
            json(ctx, 200, BookResponse.of(book.get()));
        } else {
            error(ctx, 404, "NOT_FOUND", "book " + bookId + " is not indexed");
        }
    }

    private void filters(Context ctx) throws Exception {
        json(ctx, 200, filterValues.get());
    }

    private static void error(Context ctx, int status, String code, String message) {
        json(ctx, status, ErrorResponse.of(code, message));
    }

    private static void json(Context ctx, int status, Object body) {
        ctx.status(status).contentType("application/json").result(GSON.toJson(body));
    }
}
