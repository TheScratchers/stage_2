package com.thescratchers.searchengine.search.store;

import com.thescratchers.searchengine.common.BookRecord;
import com.thescratchers.searchengine.common.Sqlite;
import com.thescratchers.searchengine.search.model.BookDetails;
import com.thescratchers.searchengine.search.model.FilterValues;
import com.thescratchers.searchengine.search.model.FilterValues.LanguageCount;
import com.thescratchers.searchengine.search.model.FilterValues.YearCount;
import com.thescratchers.searchengine.search.model.SearchHit;
import com.thescratchers.searchengine.search.model.SearchPage;
import com.thescratchers.searchengine.search.query.SearchQuery;
import org.sqlite.Function;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Read-only access of the Search Service to the datamart ({@code datamart.db}) written by the
 * Indexing Service, following the schema of {@code docs/contracts.md}.
 *
 * <p>Every method opens its own read-only connection and runs all its statements in one read
 * transaction, so it sees a single committed state of the index even while Indexing is writing
 * (WAL). An instance holds no connection and can be shared by several threads.
 */
public final class DatamartReader {

    /**
     * SQL function that lower-cases text with {@link Locale#ROOT}. SQLite's {@code lower()} only
     * folds ASCII letters, so it would not match "BRONTË" with "Brontë".
     */
    private static final String CASEFOLD = "casefold";

    private static final String SELECT_BOOK =
            "SELECT book_id, title, author, language, year, indexed_at FROM books WHERE book_id = ?";
    private static final String COUNT_BOOKS = "SELECT COUNT(*) FROM books";
    private static final String COUNT_LANGUAGES =
            "SELECT language, COUNT(*) FROM books WHERE language IS NOT NULL GROUP BY language ORDER BY language";
    private static final String COUNT_YEARS =
            "SELECT year, COUNT(*) FROM books WHERE year IS NOT NULL GROUP BY year ORDER BY year";
    private static final String SELECT_LAST_UPDATE = "SELECT value FROM index_meta WHERE key = 'last_update'";

    private final Path dbFile;

    /** Reads the datamart at {@code dbFile}; the file does not need to exist yet. */
    public DatamartReader(Path dbFile) {
        this.dbFile = dbFile;
    }

    /**
     * Runs a search: books that contain every query term and pass every filter, ordered by score
     * (sum of the term frequencies) descending and then by {@code book_id} ascending.
     *
     * @throws IndexUnavailableException if the datamart does not exist yet or has no schema
     * @throws SQLException              if the database cannot be read
     */
    public SearchPage search(SearchQuery query) throws IndexUnavailableException, SQLException {
        Matches matches = Matches.of(query);
        return read(conn -> {
            Function.create(conn, CASEFOLD, new CaseFold(), 1, Function.FLAG_DETERMINISTIC);
            int total;
            try (PreparedStatement stmt = conn.prepareStatement(matches.countSql())) {
                matches.bind(stmt);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                }
            }
            if (query.offset() >= total) {
                return new SearchPage(total, Collections.emptyList());
            }
            List<SearchHit> hits = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(matches.pageSql())) {
                int next = matches.bind(stmt);
                stmt.setInt(next++, query.limit());
                stmt.setInt(next, query.offset());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        hits.add(new SearchHit(book(rs), rs.getLong("score")));
                    }
                }
            }
            return new SearchPage(total, hits);
        });
    }

    /**
     * Returns the metadata of an indexed book, or empty if no indexed book has this id.
     *
     * @throws IndexUnavailableException if the datamart does not exist yet or has no schema
     * @throws SQLException              if the database cannot be read
     */
    public Optional<BookDetails> findBook(int bookId) throws IndexUnavailableException, SQLException {
        return read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(SELECT_BOOK)) {
                stmt.setInt(1, bookId);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new BookDetails(book(rs), rs.getString("indexed_at")))
                            : Optional.empty();
                }
            }
        });
    }

    /**
     * Returns the languages and release years present in the index, with their number of books.
     *
     * @throws IndexUnavailableException if the datamart does not exist yet or has no schema
     * @throws SQLException              if the database cannot be read
     */
    public FilterValues filterValues() throws IndexUnavailableException, SQLException {
        return read(conn -> {
            List<LanguageCount> languages = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(COUNT_LANGUAGES);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    languages.add(new LanguageCount(rs.getString(1), rs.getInt(2)));
                }
            }
            List<YearCount> years = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(COUNT_YEARS);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    years.add(new YearCount(rs.getInt(1), rs.getInt(2)));
                }
            }
            return new FilterValues(singleInt(conn, COUNT_BOOKS), languages, years, lastUpdate(conn));
        });
    }

    /** Opens a read-only connection and runs {@code work} inside a single read transaction. */
    private <T> T read(ReadWork<T> work) throws IndexUnavailableException, SQLException {
        if (!Files.exists(dbFile)) {
            throw new IndexUnavailableException("the index is not available yet");
        }
        try (Connection conn = Sqlite.openReadOnly(dbFile)) {
            conn.setAutoCommit(false);
            T result = work.run(conn);
            conn.commit();
            return result;
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("no such table")) {
                throw new IndexUnavailableException("the index is not available yet", e);
            }
            throw e;
        }
    }

    private static BookRecord book(ResultSet rs) throws SQLException {
        int year = rs.getInt("year");
        Integer nullableYear = rs.wasNull() ? null : year;
        return new BookRecord(rs.getInt("book_id"), rs.getString("title"), rs.getString("author"),
                rs.getString("language"), nullableYear);
    }

    private static int singleInt(Connection conn, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String lastUpdate(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(SELECT_LAST_UPDATE); ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @FunctionalInterface
    private interface ReadWork<T> {
        T run(Connection conn) throws SQLException;
    }

    /** Implementation of the {@code casefold(text)} SQL function; {@code NULL} stays {@code NULL}. */
    private static final class CaseFold extends Function {
        @Override
        protected void xFunc() throws SQLException {
            String value = value_text(0);
            if (value == null) {
                result();
            } else {
                result(value.toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * SQL for the books that match a query. The {@code matches} subquery keeps the books whose
     * postings contain all the terms ({@code COUNT(*)} equals the number of distinct terms) and
     * sums their frequencies; the filters are then applied on {@code books}.
     */
    private record Matches(SearchQuery query, String fromWhere) {

        static Matches of(SearchQuery query) {
            String placeholders = String.join(", ", Collections.nCopies(query.terms().size(), "?"));
            StringBuilder sql = new StringBuilder()
                    .append(" FROM (SELECT book_id, SUM(frequency) AS score FROM postings")
                    .append(" WHERE term IN (").append(placeholders).append(")")
                    .append(" GROUP BY book_id HAVING COUNT(*) = ?) m")
                    .append(" JOIN books b ON b.book_id = m.book_id");
            List<String> conditions = new ArrayList<>();
            if (query.author() != null) {
                conditions.add("instr(" + CASEFOLD + "(b.author), ?) > 0");
            }
            if (query.language() != null) {
                conditions.add("b.language = ?");
            }
            if (query.year() != null) {
                conditions.add("b.year = ?");
            }
            if (!conditions.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", conditions));
            }
            return new Matches(query, sql.toString());
        }

        String countSql() {
            return "SELECT COUNT(*)" + fromWhere;
        }

        String pageSql() {
            return "SELECT b.book_id, b.title, b.author, b.language, b.year, m.score" + fromWhere
                    + " ORDER BY m.score DESC, b.book_id ASC LIMIT ? OFFSET ?";
        }

        /** Binds the terms and filters, and returns the index of the next free parameter. */
        int bind(PreparedStatement stmt) throws SQLException {
            int index = 1;
            for (String term : query.terms()) {
                stmt.setString(index++, term);
            }
            stmt.setInt(index++, query.terms().size());
            if (query.author() != null) {
                stmt.setString(index++, query.author().toLowerCase(Locale.ROOT));
            }
            if (query.language() != null) {
                stmt.setString(index++, query.language());
            }
            if (query.year() != null) {
                stmt.setInt(index++, query.year());
            }
            return index;
        }
    }
}
