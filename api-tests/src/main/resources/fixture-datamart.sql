-- Fixture datamart for the Search API checks (cases/search.json).
-- Schema of docs/contracts.md, section 2. Small enough to work out every expected result by hand:
--
-- book | title                  | author           | lang | year | postings (term = frequency)
-- -----|------------------------|------------------|------|------|------------------------------------
--    1 | Life on the River      | Mark Twain       | en   | 2004 | river 5, raft 3, adventure 2
--    2 | Tales of Adventure     | Mark Twain       | en   | 2004 | adventure 4, river 1
--    3 | Moorland Stories       | Charlotte Brontë | en   | 1998 | adventure 2, moor 7
--    4 | Voyage sur la rivière  | (none)           | fr   | 1998 | adventure 4, rivière 2
--    5 | Le Fleuve              | Jules Verne      | fr   | -    | adventure 1, river 9
--    6 | Untitled               | Anonymous        | -    | 2010 | moor 1
--
-- Statements are separated by ";" at the end of a line.

CREATE TABLE books (
  book_id    INTEGER PRIMARY KEY,
  title      TEXT NOT NULL,
  author     TEXT,
  language   TEXT,
  year       INTEGER,
  indexed_at TEXT NOT NULL
);

CREATE TABLE postings (
  term      TEXT    NOT NULL,
  book_id   INTEGER NOT NULL,
  frequency INTEGER NOT NULL,
  PRIMARY KEY (term, book_id)
);

CREATE INDEX idx_postings_book ON postings(book_id);

CREATE TABLE index_meta (key TEXT PRIMARY KEY, value TEXT);

INSERT INTO books VALUES
  (1, 'Life on the River',     'Mark Twain',       'en', 2004, '2026-10-25T10:00:01Z'),
  (2, 'Tales of Adventure',    'Mark Twain',       'en', 2004, '2026-10-25T10:00:02Z'),
  (3, 'Moorland Stories',      'Charlotte Brontë', 'en', 1998, '2026-10-25T10:00:03Z'),
  (4, 'Voyage sur la rivière', NULL,               'fr', 1998, '2026-10-25T10:00:04Z'),
  (5, 'Le Fleuve',             'Jules Verne',      'fr', NULL, '2026-10-25T10:00:05Z'),
  (6, 'Untitled',              'Anonymous',        NULL, 2010, '2026-10-25T10:00:06Z');

INSERT INTO postings VALUES
  ('river', 1, 5), ('raft', 1, 3), ('adventure', 1, 2),
  ('adventure', 2, 4), ('river', 2, 1),
  ('adventure', 3, 2), ('moor', 3, 7),
  ('adventure', 4, 4), ('rivière', 4, 2),
  ('adventure', 5, 1), ('river', 5, 9),
  ('moor', 6, 1);

INSERT INTO index_meta VALUES ('last_update', '2026-10-25T12:00:00Z');
