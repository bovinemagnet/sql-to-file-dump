# sql-to-file-dump

`sql-to-file-dump` is a Quarkus-based CLI that connects to a JDBC database, runs a read-only SQL query, and streams rows to JSON, NDJSON, CSV, TSV, or Parquet.

## Features

- JDBC connection via URL, username, and password or password env var
- SQL from `--sql` or `--sql-file`
- Read-only SQL validation (`SELECT` / `WITH`)
- Streaming writers for JSON, NDJSON, CSV, TSV, and Parquet
- Optional metadata sidecar JSON
- Schema inspection with `--describe`
- DuckDB and PostgreSQL integration coverage in tests

## Requirements

- **Java 17+** to bootstrap the Gradle build (any locally installed JDK 17 or newer works)
- **Java 25** for compilation and runtime — the Gradle toolchain support will auto-download it if not already present
- Gradle wrapper included in this repository (no local Gradle installation needed)
- Docker only if you want to run the PostgreSQL Testcontainers integration test locally

## Build and test

```bash
./gradlew test
./gradlew build
```

## Run the CLI

Build the application:

```bash
./gradlew build
```

Run with the Quarkus runner jar:

```bash
java -jar build/quarkus-app/quarkus-run.jar --help
```

## Usage

```text
jdbc-export --url <jdbc-url> --user <username> (--sql <query> | --sql-file <file>) [options]
jdbc-export daemon [--port 8080] [--host localhost]
```

### Common options

- `--password <value>`: JDBC password
- `--password-env <ENV_NAME>`: read password from environment
- `--format <json|ndjson|csv|tsv|parquet>`: output format
- `--output <path>`: destination file path
- `--fetch-size <n>`: JDBC fetch size, default `1000`
- `--max-rows <n>`: optional row limit
- `--metadata <path>`: write metadata JSON sidecar
- `--overwrite`: replace existing output files
- `--dry-run`: validate options without connecting or exporting
- `--describe`: print schema instead of exporting
- `--pretty`: pretty-print JSON array output
- `--[no-]include-header`: include header row for CSV/TSV, default enabled
- `--null-value <value>`: replacement for SQL `NULL` in CSV/TSV
- `--parquet-compression <SNAPPY|GZIP|ZSTD|UNCOMPRESSED>`
- `--verbose`: print extra progress information

## Examples

Export to CSV:

```bash
java -jar build/quarkus-app/quarkus-run.jar   --url jdbc:postgresql://localhost:5432/app   --user app   --password-env DB_PASSWORD   --sql "select id, email from accounts order by id"   --format csv   --output exports/accounts.csv   --overwrite
```

Export to NDJSON from a SQL file:

```bash
java -jar build/quarkus-app/quarkus-run.jar   --url jdbc:duckdb:sample.db   --user ignored   --sql-file queries/bookings.sql   --format ndjson   --output exports/bookings.ndjson
```

Describe a query without exporting:

```bash
java -jar build/quarkus-app/quarkus-run.jar   --url jdbc:postgresql://localhost:5432/app   --user app   --password secret   --sql "select * from bookings"   --describe
```

Export to Parquet with metadata:

```bash
java -jar build/quarkus-app/quarkus-run.jar   --url jdbc:postgresql://localhost:5432/app   --user app   --password secret   --sql-file queries/report.sql   --format parquet   --output exports/report.parquet   --metadata exports/report.metadata.json   --parquet-compression zstd   --overwrite
```

## Output notes

- JSON writes a single array document.
- NDJSON writes one JSON object per line.
- CSV and TSV normalize SQL nulls with `--null-value`.
- Parquet uses an Avro-backed schema and sanitizes field names to valid Avro identifiers.
- Duplicate normalized output column names fail fast and should be fixed with explicit SQL aliases.

## Daemon mode and dashboard

```bash
java -jar build/quarkus-app/quarkus-run.jar daemon --port 8080
```

Daemon mode keeps the process running and serves a browser dashboard (HTMX, no custom
JavaScript) for submitting and monitoring export jobs:

- Submit exports with the same options as the CLI (URL, user, password or password
  environment variable, SQL, format, output path, overwrite).
- Watch job status (`QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`) with live updates,
  row counts, and durations.
- Inspect a job's SQL and error details.
- Preview a query's column schema without exporting (`Describe schema`).

During development, run the dashboard with hot reload — with no arguments, dev mode
starts the daemon automatically:

```bash
./gradlew quarkusDev
```

The dashboard is then served at `http://localhost:8080/` (Quarkus Dev UI at
`http://localhost:8080/q/dev-ui`), and edits to templates, static assets, and Java
code reload live.

Notes:

- The dashboard has **no authentication** and binds to `localhost` by default. Binding
  to a non-loopback host requires `--allow-remote`, and should only be done on trusted
  networks.
- State-changing requests require an `X-Requested-By: jdbc-export` header, which the
  dashboard's HTMX sends automatically. This blocks cross-site request forgery from
  other pages in your browser; it is not a substitute for authentication.
- Job history is in memory only and is lost when the daemon stops.
- Passwords submitted through the form are used for the connection only; they are
  never stored on the job or rendered back.
- Normal (non-daemon) CLI runs do not open any network listener.

## Choosing a format

| Format  | Use when                                                                |
| ------- | ----------------------------------------------------------------------- |
| JSON    | You need one human-readable file containing an array of objects         |
| NDJSON  | You need stream-friendly line-delimited JSON                            |
| CSV     | You need spreadsheet-friendly tabular output                            |
| TSV     | You need spreadsheet-friendly output where commas are common in values  |
| Parquet | You need compact analytics-friendly columnar output                     |

## Why explicit SQL aliases matter

The SQL result shape is the export contract. Column labels become JSON field names, CSV/TSV headers, and Parquet field names, so use explicit, stable aliases.

Good:

```sql
select
    b.booking_id as booking_id,
    r.room_code  as room_code
from booking b
join room r on r.room_id = b.room_id;
```

Avoid:

```sql
select *
from booking b
join room r on r.room_id = b.room_id;
```

Joins without aliases can produce duplicate column labels, which the tool rejects with an error. Parquet field names are additionally sanitized to valid Avro identifiers, so quoted labels with spaces or punctuation should be avoided.

## Passwords

Prefer `--password-env` over `--password`. Command-line arguments can leak through shell history, process listings, job logs, and monitoring tools:

```bash
export DB_PASSWORD='secret'
jdbc-export ... --password-env DB_PASSWORD
```

The password is never written to the metadata sidecar.

## Exit codes

| Exit code | Meaning                                |
| --------: | -------------------------------------- |
|       `0` | Success                                |
|       `1` | Invalid CLI arguments                  |
|       `2` | SQL input error                        |
|       `3` | Database connection or query error     |
|       `4` | Output write error                     |
|       `5` | Schema or column mapping error         |
|       `6` | Unsupported format or unsupported type |
|      `99` | Unexpected error                       |

## Known v1 limitations

- SQL parameters are not supported.
- `DECIMAL`/`NUMERIC` values are stored as strings in Parquet (avoids precision and scale bugs; Avro decimal logical type support is a future enhancement).
- Cloud object storage output (S3, Azure Blob, GCS) is not implemented.
- Parallel or partitioned exports are not implemented.
- Native image support is not guaranteed.
- Query cancellation and timeout support are future enhancements.
- The `SELECT`/`WITH` check is a safety guard against accidental mutation, not a security boundary — only run trusted SQL.

## Development notes

- The top-level Picocli command is discovered by Quarkus via `@TopCommand`.
- The application entry point delegates execution to Picocli while still using Quarkus dependency injection.
