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

## Development notes

- The top-level Picocli command is discovered by Quarkus via `@TopCommand`.
- The application entry point delegates execution to Picocli while still using Quarkus dependency injection.
