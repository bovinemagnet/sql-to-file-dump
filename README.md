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
- `daemon` mode serving **Sluice**, a browser ops console for submitting and
  monitoring exports, backed by a JSON API at `/api`
- Live run metrics (rows streamed, throughput, output size, JVM heap, fetch-batch
  progress) captured from the real export pipeline
- A persisted, operator-managed registry of saved JDBC connections (passwords kept
  as environment-variable references only — never written to disk)

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
jdbc-export daemon [--port 8080] [--host localhost] [--allow-remote]
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

## Daemon mode and the Sluice ops console

```bash
java -jar build/quarkus-app/quarkus-run.jar daemon --port 8080
```

Daemon mode keeps the process running and serves **Sluice**, a browser ops console, at
`http://localhost:8080/`. Sluice is a single-page app with a side-rail of four views:

- **Dashboard** — submit exports (with the same inputs as the CLI, or by picking a
  saved connection), watch a KPI strip (uptime, jobs, rows exported, active runs,
  success rate), and browse a filterable job queue and history with status and format
  badges. `Describe schema` previews a query's columns without exporting.
- **Live Run** — a metrics cockpit for the running job: rows streamed, throughput
  (rows/second) chart, elapsed time and output size, JDBC fetch-batch progress, a JVM
  heap gauge, connection and query state, a live log, and the running SQL.
- **Schedules** — create, edit, run, and enable/disable recurring or one-off exports
  (cron, fixed interval, or run-once). Schedules persist to `~/.sluice/schedule.json`
  and are fired by a built-in scheduler while the daemon runs. See "Schedules" below.
- **Connections** — manage saved JDBC connections (add, edit, delete, and test). See
  "Saved connections" below.

The console has a dark/light theme toggle, and the selected view and theme persist
across reloads. Everything it shows is served by a JSON API under `/api` (jobs, job
detail, submit, describe, daemon metrics, and connection CRUD/test).

During development, run the console with hot reload — with no arguments, dev mode
starts the daemon automatically:

```bash
./gradlew quarkusDev
```

The console is then served at `http://localhost:8080/` (Quarkus Dev UI at
`http://localhost:8080/q/dev-ui`), and edits to templates, static assets, and Java
code reload live.

Notes:

- The console has **no authentication** and binds to `localhost` by default. Binding to
  a non-loopback host requires `--allow-remote`, and should only be done on trusted
  networks.
- State-changing requests require an `X-Requested-By: jdbc-export` header, which the
  console sends automatically. This blocks cross-site request forgery from other pages
  in your browser; it is not a substitute for authentication.
- Job history is in memory only and is lost when the daemon stops.
- Passwords submitted through the form are used for the connection only; they are never
  stored on the job or rendered back.
- Normal (non-daemon) CLI runs do not open any network listener.

### Saved connections

The daemon keeps an operator-managed registry of reusable JDBC connections, persisted
as a JSON array (default `~/.sluice/connections.json`, configurable with
`sluice.connections.file`). A saved connection stores only the **name** of an
environment variable for its password (`passwordEnv`) — never a password value. The
password is resolved from that variable at run time, exactly as the CLI's
`--password-env` works. The Dashboard's connection picker fills the export form from a
saved connection, and the Connections view can test reachability on demand.

### Schedules

Recurring and one-off exports are persisted to `~/.sluice/schedule.json` (configurable
with `sluice.schedule.file`) and fired by a scheduler that runs inside the daemon. Each
schedule references a saved connection by id (so no credentials live in the file), and
carries the SQL, format, an output path pattern, and a trigger:

- **cron** — a 5-field UNIX expression in the daemon's time zone (e.g. `0 2 * * *`)
- **interval** — every N minutes, hours, or days
- **once** — a single run at `yyyy-MM-dd HH:mm`

Output patterns expand `{date}`, `{time}`, `{datetime}`, and `{ts}`/`{timestamp}` at
fire time (e.g. `exports/orders_{date}.parquet`). Manage schedules from the Schedules
tab (create/edit/delete/enable/run-now) or the `/api/schedules` API. Because the
scheduler lives in the daemon, the daemon must be running for schedules to fire; for
fully unattended scheduling without a long-running daemon, drive the CLI from cron or a
systemd timer instead (see `examples/scheduled-export.sh`).

### Configuration

| Setting | Default | Purpose |
| ------- | ------- | ------- |
| `quarkus.http.port` (or `daemon --port`) | `8080` | Console HTTP port |
| `quarkus.http.host` (or `daemon --host`) | `localhost` | Bind address |
| `daemon --allow-remote` | off | Permit binding a non-loopback host (no auth — trusted networks only) |
| `sluice.connections.file` | `~/.sluice/connections.json` | Saved-connections store path |
| `sluice.schedule.file` | `~/.sluice/schedule.json` | Schedules store path |
| `quarkus.log.level` | `INFO` | Log level |

Configuration can be supplied in `src/main/resources/application.properties`, as JVM
system properties (`-Dsluice.connections.file=...`), or via environment variables;
system properties and environment variables override `application.properties`. Password
environment variables (referenced by `--password-env` or a connection's `passwordEnv`)
must be present in the daemon's environment, for example:

```bash
export DB_PASSWORD='secret'
java -jar build/quarkus-app/quarkus-run.jar daemon --port 9090 --host 0.0.0.0 --allow-remote
```

## Documentation

Full documentation is maintained as an Antora site under `src/docs`. Build it with:

```bash
./gradlew antora
```

The generated site is written to `build/site` (open `build/site/index.html`). It covers
the CLI reference, the daemon and Sluice console, configuration, saved connections, the
REST API, security, and the architecture.

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
