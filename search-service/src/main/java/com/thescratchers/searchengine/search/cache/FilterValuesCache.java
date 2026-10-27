package com.thescratchers.searchengine.search.cache;

import com.thescratchers.searchengine.search.model.FilterValues;
import com.thescratchers.searchengine.search.store.DatamartReader;
import com.thescratchers.searchengine.search.store.IndexUnavailableException;

import java.sql.SQLException;

/**
 * Keeps the last {@link FilterValues} read from the datamart, so that {@code GET /search/filters}
 * does not scan the {@code books} table on every request.
 *
 * <p>The cache is only trusted while the Search Service is subscribed to {@code BookIndexed}
 * events, because each event is what tells it that the index has changed. While the subscription
 * is down the cache is {@linkplain #disable() disabled} and every call reads the datamart, so stale
 * values are never served. Thread-safe: a value read concurrently with an invalidation is
 * discarded instead of being cached.
 */
public final class FilterValuesCache {

    private final DatamartReader reader;

    private boolean enabled;
    private long generation;
    private FilterValues cached;

    public FilterValuesCache(DatamartReader reader) {
        this.reader = reader;
    }

    /**
     * Returns the current filter values, from the cache when it is enabled and filled, otherwise
     * from the datamart.
     *
     * @throws IndexUnavailableException if the datamart does not exist yet or has no schema
     * @throws SQLException              if the database cannot be read
     */
    public FilterValues get() throws IndexUnavailableException, SQLException {
        long readGeneration;
        synchronized (this) {
            if (enabled && cached != null) {
                return cached;
            }
            readGeneration = generation;
        }
        FilterValues values = reader.filterValues();
        synchronized (this) {
            if (enabled && generation == readGeneration) {
                cached = values;
            }
        }
        return values;
    }

    /** Drops the cached values; the next call to {@link #get()} reads the datamart again. */
    public synchronized void invalidate() {
        generation++;
        cached = null;
    }

    /** Starts caching, after the subscription to index events is established. */
    public synchronized void enable() {
        invalidate();
        enabled = true;
    }

    /** Stops caching, because index events may be missed from now on. */
    public synchronized void disable() {
        invalidate();
        enabled = false;
    }

    /** Whether values are currently being cached. */
    public synchronized boolean isEnabled() {
        return enabled;
    }
}
