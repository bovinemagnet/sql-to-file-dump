# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Quarkus + Picocli CLI (`jdbc-export`) that connects to a JDBC database, runs a read-only SQL query, and streams rows to JSON, NDJSON, CSV, TSV, or Parquet. A `daemon` subcommand serves an HTMX dashboard for submitting and monitoring export jobs. Java 25 (auto-downloaded via Gradle toolchain), Quarkus 3.24.x.

## Commands

Use `gradle21w` (on the PATH) instead of `./gradlew`:

```bash
gradle21w build                                                          # build (produces Quarkus runner jar)
gradle21w test                                                           # all tests
gradle21w test --tests "com.example.jdbcexport.jdbc.PasswordResolverTest"  # single test class
gradle21w test --tests "*DuckDbExportIntegrationTest"                    # pattern match

java -jar build/quarkus-app/quarkus-run.jar --help                       # run the CLI
```

`PostgresExportIntegrationTest` uses Testcontainers and requires Docker running. `DuckDbExportIntegrationTest` runs in-process with no external dependencies — prefer DuckDB for new integration coverage unless the behaviour is Postgres-specific.

## Architecture

Everything lives under `com.example.jdbcexport`. The execution pipeline is:

`JdbcExportApplication` (Quarkus main) → `cli/JdbcExportCommand` (`@TopCommand`, all Picocli options, validation, orchestration) → resolves inputs (`jdbc/PasswordResolver`, `jdbc/SqlInputResolver`, `jdbc/SqlSafetyValidator`) → `jdbc/JdbcConnectionFactory` → `jdbc/ResultSetSchemaReader` (reads column metadata up front as `List<ResultSetColumn>`) → `writer/RowWriterFactory` picks a writer → `jdbc/JdbcExporter` streams the forward-only, read-only `ResultSet` row by row into the writer.

Key contracts and conventions:

- **`writer/RowWriter`** is the central interface: `start(ResultSetMetaData)` / `writeRow(ResultSet)` / `finish()` / `close()`. One implementation per format; CSV and TSV share `DelimitedTextRowWriter`. All writers stream — never buffer full result sets.
- **`SqlSafetyValidator`** enforces read-only SQL (`SELECT`/`WITH` only). This is a deliberate product constraint, not incidental.
- **Errors**: throw `error/ExportException` carrying a code from `error/ExitCodes` (1=args, 2=SQL input, 3=database, 4=output write, 5=schema, 6=unsupported format, 99=unexpected). `JdbcExportCommand.call()` is the single place that catches and maps to the process exit code.
- **Parquet** goes through Avro (`parquet-avro`): `parquet/AvroSchemaFactory` builds the schema from JDBC columns, `AvroFieldNameSanitizer` rewrites column names into valid Avro identifiers, and duplicate sanitised names fail fast (users must alias in SQL). Hadoop is a dependency only because parquet-avro requires it; `LocalParquetOutputFile` avoids Hadoop filesystems.
- **`cli/ExportOptions`** is a record snapshot of the parsed options, passed to the factory/writers so they never see Picocli types.
- **Daemon mode** lives in `daemon/`: `DaemonCommand` (Picocli subcommand, blocks on `Quarkus.waitForExit()`), `ExportJobService` (in-memory job registry + virtual-thread executor reusing the CLI pipeline), `DashboardResource` (Qute templates in `src/main/resources/templates/`, HTMX via the `htmx.org` webjar, zero custom JS). HTTP lifecycle is decided in `JdbcExportApplication.configureHttp()` *before* `Quarkus.run()`: a real CLI export (args present, not `daemon`) sets `quarkus.http.host-enabled=false` so it never binds a port; `daemon --port/--host` are mapped to system properties there because the server starts before Picocli parses. No-args is left enabled — `JdbcExportApplication.run()` checks `LaunchMode.current() == DEVELOPMENT` (reliable post-boot) and rewrites empty args to `daemon`, so `gradle21w quarkusDev` serves the dashboard with live reload; the packaged jar with no args is a usage error that exits immediately. `--url`/`--user` are validated manually (not `required = true`) because Picocli would otherwise demand them for the `daemon` subcommand too.
- **Dashboard security** (no auth by design — local operator tool): `CsrfFilter` requires the `X-Requested-By: jdbc-export` header on all POST/PUT/PATCH/DELETE; HTMX supplies it via `hx-headers` on the `<body>`. `main()` refuses a non-loopback bind host unless `--allow-remote` is passed (`isLoopbackHost`). When changing the dashboard, keep state-changing endpoints behind the header and don't loosen the bind guard.
- **Plain (non-Quarkus) tests that use `DriverManager` with DuckDB** must register the driver explicitly (see `DuckDbExportIntegrationTest`): a `@QuarkusTest` in the same suite can trigger `DriverManager`'s one-time driver scan under the Quarkus classloader, leaving DuckDB invisible to the test classloader.
- **`JdbcExportCommand` must stay `@Dependent` scoped.** With `@ApplicationScoped`, Picocli binds option values to the CDI client proxy's fields while `call()` executes on the underlying bean with null fields — every option silently fails to bind. `JdbcExportCommandMainTest` (a `@QuarkusMainTest`) guards this; it needs the `--add-opens java.base/java.lang=ALL-UNNAMED` test JVM arg already set in `build.gradle.kts`.
- Postgres and Oracle JDBC drivers are `runtimeOnly`; DuckDB is test-only.
