package com.thescratchers.searchengine.ingestion.datalake;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A book whose header and body files have been completely written to the datalake.
 *
 * @param bookId     Project Gutenberg id
 * @param directory  datalake directory that holds both files
 * @param headerFile {@code <id>.header.txt}
 * @param bodyFile   {@code <id>.body.txt}
 * @param ingestedAt moment the files were stored
 */
public record StoredBook(int bookId, Path directory, Path headerFile, Path bodyFile, Instant ingestedAt) {
}
