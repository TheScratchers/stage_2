# API checks

Automated HTTP checks of the service APIs, so that the contract in
[`docs/openapi.yaml`](../docs/openapi.yaml) can be assessed without a front-end. Each check sends one
request and compares the status code, selected response headers and a fragment of the JSON body
with the expected result written in a case file.

| Case file | Service | Data | Checks |
|---|---|---|---|
| [`cases/search.json`](cases/search.json) | Search (front-end API) | Fixture datamart | 44 |

## How to run

**Automatically, on every build.** `mvn clean verify` starts the Search Service in-process over the
fixture datamart and runs every case of `cases/search.json`; each case appears as its own test
in `api-tests/target/surefire-reports/`.

**Against a running service.** From the repository root, after `mvn clean verify`:

```bash
# 1. Create the fixture datamart in a separate data directory
java -jar api-tests/target/api-tests.jar fixture api-check-data

# 2. Start the Search Service over it (PowerShell: $env:DATA_DIR="api-check-data"; $env:SEARCH_PORT=7003)
DATA_DIR=api-check-data SEARCH_PORT=7003 java -jar search-service/target/search-service.jar

# 3. In another terminal, run the checks
java -jar api-tests/target/api-tests.jar check http://localhost:7003 api-tests/cases/search.json
```

Expected output: one `PASS` line per case and `44/44 checks passed`, with exit code `0`. A failing
case prints `FAIL`, the JSON path of the first difference and both values, and the exit code is `1`.

## Fixture datamart

[`fixture-datamart.sql`](src/main/resources/fixture-datamart.sql) creates a datamart with the
contract schema and six books, small enough to work out every expected result by hand:

| book | author | language | year | postings (term = frequency) |
|---|---|---|---|---|
| 1 | Mark Twain | en | 2004 | river 5, raft 3, adventure 2 |
| 2 | Mark Twain | en | 2004 | adventure 4, river 1 |
| 3 | Charlotte Brontë | en | 1998 | adventure 2, moor 7 |
| 4 | (none) | fr | 1998 | adventure 4, rivière 2 |
| 5 | Jules Verne | fr | (none) | adventure 1, river 9 |
| 6 | Anonymous | (none) | 2010 | moor 1 |

## What `cases/search.json` checks

| Area | Example request | Expected result |
|---|---|---|
| Keyword search | `/search?q=adventure` | `total` 5, `book_id`s 2, 4, 1, 3, 5 (score 4, 4, 2, 2, 1; ties by `book_id`) |
| Several terms (AND) | `/search?q=river adventure` | 5, 1, 2 with scores 10, 7, 5 |
| Normalisation | `/search?q=RIVER, Adventure! river` | terms `river`, `adventure`; same results |
| Single filters | `author=TWAIN`, `author=BRONTË`, `language=FR`, `year=1998` | 2, 1 · 3 · 4, 5 · 4, 3 |
| Combined filters | `author=twain&language=en&year=2004` | 2, 1; `language=fr&year=1998` gives 4 |
| Empty results | `/search?q=zxqv`, `author=twain&language=fr` | `200`, `total` 0, `results` `[]` |
| Pagination | `limit=2` with `offset` 0, 2, 4, 10 | 2, 4 · 1, 3 · 5 · `[]`, always `total` 5 |
| Invalid parameters | missing or blank `q`, `q=?? 1999`, too long `q`/`author`, `language=eng`, `year=98`, `offset=-1`, `limit=0`/`101` | `400` with `INVALID_PARAMETER` and the documented message |
| Book details | `/books/3`, `/books/999`, `/books/abc` | `200` with `indexed_at` · `404 NOT_FOUND` · `400` |
| Filter values | `/search/filters` | `en` 3, `fr` 2; years 1998 (2), 2004 (2), 2010 (1) |
| Null metadata | `/search?q=moor`, `/books/5` | `language`/`author`/`year` present as `null` |
| CORS and health | `Origin` header, preflight `OPTIONS`, `/health` | `Access-Control-Allow-Origin: *`; `UP` |

Behaviour that needs a missing or broken datamart (`503 UNAVAILABLE`, `500 INTERNAL_ERROR`) is
covered by the unit tests of `search-service` (`SearchControllerTest`).

## Case file format

```json
{
  "description": "...",
  "cases": [
    {
      "name": "filter author: case-insensitive substring",
      "method": "GET",
      "path": "/search?q=adventure&author=TWAIN",
      "headers": {"Origin": "http://frontend.example"},
      "status": 200,
      "response_headers": {"Access-Control-Allow-Origin": "*"},
      "body": {"total": 2, "results": [{"book_id": 2}, {"book_id": 1}]}
    }
  ]
}
```

`method` (default `GET`), `headers`, `response_headers` and `body` are optional. In `body`, an
object only constrains the keys it lists, an array must match in length and order, and scalars
(including `null`) must be equal. New case files, for example for the internal Ingestion and
Indexing endpoints, use the same format and are run with the same command.
