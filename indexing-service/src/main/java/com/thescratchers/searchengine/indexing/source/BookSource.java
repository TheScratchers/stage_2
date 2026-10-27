package com.thescratchers.searchengine.indexing.source;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Tells the Indexing Service which books are ready to be indexed and where their files are.
 *
 * <p>Used by the administrative endpoints, which receive only a book id. Books announced through
 * {@code BookDownloaded} events carry their paths in the event and do not need this lookup.
 */
public interface BookSource {

    /** Returns the files of {@code bookId} if it is available for indexing, or empty otherwise. */
    Optional<BookFiles> find(int bookId) throws SQLException;

    /** Returns every book available for indexing, ordered by book id. */
    List<BookFiles> listAvailable() throws SQLException;
}
