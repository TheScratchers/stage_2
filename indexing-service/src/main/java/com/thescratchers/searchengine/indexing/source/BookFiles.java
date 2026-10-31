package com.thescratchers.searchengine.indexing.source;

import java.nio.file.Path;

/**
 * Location in the datalake of a book that the Ingestion Service has marked as available.
 *
 * @param bookId     Project Gutenberg id
 * @param headerFile header file written by the Ingestion Service
 * @param bodyFile   body file written by the Ingestion Service
 */
public record BookFiles(int bookId, Path headerFile, Path bodyFile) {
}
