package com.thescratchers.searchengine.indexing.api;

import com.thescratchers.searchengine.indexing.BookIndexer.IndexResult;
import com.thescratchers.searchengine.indexing.service.IndexStatus;
import com.thescratchers.searchengine.indexing.service.RebuildResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * JSON bodies of the Indexing API, as specified in {@code docs/openapi.yaml}. Field names are
 * converted to {@code snake_case} by the serializer.
 */
final class ApiResponses {

    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private ApiResponses() {
    }

    /** Body of {@code POST /index/update/{book_id}}. */
    record UpdateResponse(int bookId, String index, int distinctTerms, int totalTokens) {

        static UpdateResponse of(IndexResult result) {
            return new UpdateResponse(result.bookId(), "updated", result.distinctTerms(), result.totalTokens());
        }
    }

    /** Body of {@code POST /index/rebuild}. */
    record RebuildResponse(int booksProcessed, List<Integer> failedBooks, int booksRemoved, String elapsedTime) {

        static RebuildResponse of(RebuildResult result) {
            String elapsed = String.format(Locale.ROOT, "%.1fs", result.elapsed().toMillis() / 1000.0);
            return new RebuildResponse(result.booksProcessed(), result.failedBooks(), result.booksRemoved(), elapsed);
        }
    }

    /** Body of {@code GET /index/status}. */
    record StatusResponse(int booksIndexed, String lastUpdate, double indexSizeMb) {

        static StatusResponse of(IndexStatus status) {
            double megabytes = BigDecimal.valueOf(status.sizeBytes() / BYTES_PER_MB)
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();
            return new StatusResponse(status.booksIndexed(), status.lastUpdate(), megabytes);
        }
    }

    /** Common error envelope: {@code {"error": {"code": ..., "message": ...}}}. */
    record ErrorResponse(Error error) {

        static ErrorResponse of(String code, String message) {
            return new ErrorResponse(new Error(code, message));
        }
    }

    /** Content of {@link ErrorResponse}. */
    record Error(String code, String message) {
    }
}
