package com.thescratchers.searchengine.indexing.service;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of a full rebuild of the index.
 *
 * @param booksProcessed books indexed successfully
 * @param failedBooks    ids of available books that could not be indexed (their previous version,
 *                       if any, is kept)
 * @param booksRemoved   indexed books removed because they are no longer available in the datalake
 * @param elapsed        wall-clock duration of the rebuild
 */
public record RebuildResult(int booksProcessed, List<Integer> failedBooks, int booksRemoved, Duration elapsed) {
}
