# Product Requirements Document: JDBC SQL Export CLI

## 1. Product Name

`jdbc-export-cli`

Working command name:

```bash
jdbc-export
```

## 2. Purpose

Build a Java 25 command-line tool using Quarkus, Gradle, and Picocli that exports the result of a SQL query executed through JDBC into one of several file formats:

* JSON
* NDJSON
* Parquet
* CSV
* TSV

The tool is intended for operational exports, data migration support, reporting extracts, analytics handoff files, and repeatable batch jobs.

The first implementation should be a clean starter project with a solid architecture, not a full enterprise data platform.

## 3. Problem Statement

We need a reusable CLI utility that can:

1. Connect to a SQL database using JDBC.
2. Execute a user-provided SQL query.
3. Stream the result set without loading all rows into memory.
4. Write the rows to a chosen output format.
5. Support both human-readable formats and analytics-friendly formats.
6. Work well from shell scripts, schedulers, CI jobs, and container jobs.

Existing scripts and one-off exports are hard to reuse, difficult to test, and often duplicate database extraction logic across output formats.

## 4. Goals

### 4.1 Primary Goals

The generated starter project must:

* Use Java 25.
* Use Gradle with Kotlin DSL.
* Use Quarkus as a CLI application.
* Use Picocli for command-line parsing.
* Execute SQL through JDBC.
* Stream rows from `ResultSet`.
* Support output formats:

  * `json`
  * `ndjson`
  * `parquet`
  * `csv`
  * `tsv`
* Provide a pluggable writer abstraction so output formats share the same JDBC extraction path.
* Include tests for all output formats.
* Include clear README usage examples.
* Include sane defaults suitable for PostgreSQL and Oracle-style JDBC usage.

### 4.2 Secondary Goals

The starter should also include:

* Row count logging.
* Basic SQL safety checks.
* Duplicate column name detection.
* Metadata sidecar file support.
* Configurable JDBC fetch size.
* Configurable Parquet compression.
* Support for password from environment variable.
* A `--dry-run` or `--describe` mode to inspect the result-set schema without exporting rows.

## 5. Non-Goals

Version 1 should not attempt to implement:

* Incremental exports.
* CDC.
* Parallel partitioned exports.
* S3, Azure Blob, or GCS output.
* Database-specific SQL rewriting.
* Query scheduling.
* Full schema registry integration.
* Authentication vault integration.
* Native image support as a hard requirement.
* Data masking or privacy rules.
* Bidirectional import.
* Streaming to Kafka.
* Automatic SQL generation from table names.

These can be future enhancements.

## 6. Target Users

### 6.1 Primary User

A backend engineer, solution architect, data engineer, or support engineer who needs to export database query results in repeatable file formats.

### 6.2 Secondary Users

* Operations teams running scheduled exports.
* Analysts receiving Parquet, CSV, or JSON files.
* Developers needing a local tool for data inspection.
* CI/CD pipelines producing test datasets.

## 7. Example Usage

### 7.1 Export to Parquet

```bash
jdbc-export \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password-env DB_PASSWORD \
  --sql-file sql/bookings.sql \
  --format parquet \
  --output out/bookings.parquet \
  --fetch-size 5000 \
  --parquet-compression SNAPPY
```

### 7.2 Export to JSON Array

```bash
jdbc-export \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password-env DB_PASSWORD \
  --sql-file sql/bookings.sql \
  --format json \
  --output out/bookings.json \
  --pretty
```

### 7.3 Export to NDJSON

```bash
jdbc-export \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password-env DB_PASSWORD \
  --sql-file sql/bookings.sql \
  --format ndjson \
  --output out/bookings.ndjson
```

### 7.4 Export to CSV

```bash
jdbc-export \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password-env DB_PASSWORD \
  --sql-file sql/bookings.sql \
  --format csv \
  --output out/bookings.csv \
  --include-header
```

### 7.5 Export to TSV

```bash
jdbc-export \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password-env DB_PASSWORD \
  --sql-file sql/bookings.sql \
  --format tsv \
  --output out/bookings.tsv \
  --include-header
```

### 7.6 Describe Query Output Without Exporting

```bash
jdbc-export \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password-env DB_PASSWORD \
  --sql-file sql/bookings.sql \
  --describe
```

Expected output:

```text
Columns:
  1. booking_id    JDBC VARCHAR    nullable=true   output=booking_id
  2. room_code     JDBC VARCHAR    nullable=true   output=room_code
  3. start_date    JDBC DATE       nullable=true   output=start_date
  4. attendees     JDBC INTEGER    nullable=true   output=attendees
  5. cancelled     JDBC BOOLEAN    nullable=true   output=cancelled
```

## 8. Functional Requirements

## 8.1 CLI Requirements

The CLI must support the following options.

### Required Connection Options

```text
--url <jdbc-url>
--user <username>
```

Password must be supplied by exactly one of:

```text
--password <password>
--password-env <environment-variable-name>
```

Prefer `--password-env` in documentation examples.

### SQL Input Options

The user must supply exactly one of:

```text
--sql <sql-text>
--sql-file <path>
```

If both are supplied, the command must fail with a clear error.

If neither is supplied, the command must fail with a clear error.

### Output Options

```text
--format <json|ndjson|parquet|csv|tsv>
--output <path>
```

If `--describe` is supplied, `--format` and `--output` are not required.

### General Options

```text
--fetch-size <number>
--max-rows <number>
--metadata <path>
--overwrite
--dry-run
--describe
--verbose
```

### JSON Options

```text
--pretty
```

Only applies to `json`.

### CSV / TSV Options

```text
--include-header
--null-value <text>
```

Default:

```text
--include-header = true
--null-value = empty string
```

CSV must use comma as delimiter.

TSV must use tab as delimiter.

Both CSV and TSV must quote/escape values correctly.

### Parquet Options

```text
--parquet-compression <SNAPPY|GZIP|ZSTD|UNCOMPRESSED>
```

Default:

```text
SNAPPY
```

## 8.2 SQL Execution Requirements

The tool must:

* Open a JDBC connection using the provided URL and credentials.
* Set the connection to read-only where supported.
* Use a forward-only, read-only `PreparedStatement`.
* Apply the configured fetch size.
* Stream rows from `ResultSet`.
* Avoid loading the entire result set into memory.
* Count exported rows.
* Close all JDBC resources reliably.

The initial version does not need to support SQL parameters.

## 8.3 SQL Safety Requirements

The tool should reject SQL that does not start with one of:

```text
select
with
```

The check should be case-insensitive and trim leading whitespace.

This is not a security boundary. It is a safety guard to avoid accidental mutation.

The tool should document that users must only run trusted SQL.

## 8.4 Column Name Requirements

The tool must use `ResultSetMetaData.getColumnLabel(index)` as the preferred output column name.

The tool must detect duplicate column labels after output-name normalization.

If duplicates are found, the tool should fail with a message such as:

```text
Duplicate output column name detected: id.
Use explicit SQL aliases, for example: select a.id as account_id, b.id as booking_id
```

The tool should strongly encourage explicit SQL aliases.

Recommended SQL style:

```sql
select
    b.booking_id as booking_id,
    r.room_code  as room_code,
    b.start_date as start_date
from booking b
join room r on r.room_id = b.room_id
```

Avoid:

```sql
select *
from booking
```

## 8.5 Output Format Requirements

### 8.5.1 JSON

JSON format must produce a single JSON array.

Example:

```json
[
  {
    "booking_id": "B001",
    "room_code": "A-101",
    "start_date": "2026-06-11",
    "attendees": 10,
    "cancelled": false
  },
  {
    "booking_id": "B002",
    "room_code": "A-101",
    "start_date": "2026-06-12",
    "attendees": 15,
    "cancelled": false
  }
]
```

Implementation requirement:

* Use Jackson streaming API.
* Do not build a `List<Map<String,Object>>`.
* Preserve numbers and booleans as JSON numbers and booleans.
* Output dates/times/timestamps as ISO-style strings.
* Output binary values as Base64 strings.
* Output SQL null as JSON null.

### 8.5.2 NDJSON

NDJSON format must produce one JSON object per line.

Example:

```jsonl
{"booking_id":"B001","room_code":"A-101","start_date":"2026-06-11","attendees":10,"cancelled":false}
{"booking_id":"B002","room_code":"A-101","start_date":"2026-06-12","attendees":15,"cancelled":false}
```

Implementation requirement:

* Use Jackson streaming API.
* Each row must be independently parseable as JSON.
* No wrapping array.
* Newline after each row.

### 8.5.3 CSV

CSV format must produce RFC-style comma-separated output.

Example:

```csv
booking_id,room_code,start_date,attendees,cancelled
B001,A-101,2026-06-11,10,false
B002,A-101,2026-06-12,15,false
```

Implementation requirement:

* Use Apache Commons CSV.
* Default to including a header row.
* Correctly quote commas, quotes, and newlines.
* Output SQL null using the configured null value.
* Output dates/times/timestamps as strings.
* Output binary values as Base64 strings.

### 8.5.4 TSV

TSV format must produce tab-separated output.

Example:

```tsv
booking_id	room_code	start_date	attendees	cancelled
B001	A-101	2026-06-11	10	false
B002	A-101	2026-06-12	15	false
```

Implementation requirement:

* Use Apache Commons CSV configured with tab delimiter.
* Default to including a header row.
* Correctly quote or escape values where required.
* Output SQL null using the configured null value.
* Output dates/times/timestamps as strings.
* Output binary values as Base64 strings.

### 8.5.5 Parquet

Parquet output must produce a valid `.parquet` file.

Implementation requirement:

* Use Apache Parquet Java.
* Prefer the Avro-backed writer path for the starter implementation.
* Build an Avro schema from `ResultSetMetaData`.
* Treat fields as nullable by default.
* Use safe Avro-compatible field names.
* Use configured compression.
* Default compression must be `SNAPPY`.

Initial type mapping:

| JDBC Type                                                                              | Output Mapping        |
| -------------------------------------------------------------------------------------- | --------------------- |
| `CHAR`, `VARCHAR`, `LONGVARCHAR`, `NCHAR`, `NVARCHAR`, `LONGNVARCHAR`, `CLOB`, `NCLOB` | string                |
| `INTEGER`, `SMALLINT`, `TINYINT`                                                       | int                   |
| `BIGINT`                                                                               | long                  |
| `FLOAT`, `REAL`                                                                        | float                 |
| `DOUBLE`                                                                               | double                |
| `BOOLEAN`, `BIT`                                                                       | boolean               |
| `DATE`                                                                                 | Avro logical date     |
| `TIMESTAMP`, `TIMESTAMP_WITH_TIMEZONE`                                                 | Avro timestamp micros |
| `BINARY`, `VARBINARY`, `LONGVARBINARY`, `BLOB`                                         | bytes                 |
| `DECIMAL`, `NUMERIC`                                                                   | string in v1          |
| unknown / database-specific                                                            | string                |

The starter should deliberately map `DECIMAL` and `NUMERIC` to strings in v1 to avoid precision and scale bugs. Add a TODO for future Avro decimal logical type support.

## 8.6 Metadata Sidecar Requirements

If `--metadata <path>` is provided, write a JSON metadata file.

Example:

```json
{
  "tool": "jdbc-export-cli",
  "format": "parquet",
  "jdbcUrl": "jdbc:postgresql://localhost:5432/appdb",
  "sqlSource": "sql/bookings.sql",
  "output": "out/bookings.parquet",
  "rowCount": 12345,
  "startedAt": "2026-06-11T00:00:00Z",
  "completedAt": "2026-06-11T00:00:04Z",
  "durationMillis": 4000,
  "columns": [
    {
      "index": 1,
      "jdbcLabel": "booking_id",
      "outputName": "booking_id",
      "jdbcType": "VARCHAR",
      "nullable": true,
      "precision": 255,
      "scale": 0
    }
  ]
}
```

Do not include the password in metadata.

## 8.7 Overwrite Behaviour

If the output file already exists and `--overwrite` is not supplied, the command must fail.

If `--overwrite` is supplied, replace the file.

The same behaviour applies to the metadata file.

## 8.8 Exit Codes

Use explicit exit codes.

| Exit Code | Meaning                                |
| --------: | -------------------------------------- |
|       `0` | Success                                |
|       `1` | Invalid CLI arguments                  |
|       `2` | SQL input error                        |
|       `3` | Database connection or query error     |
|       `4` | Output write error                     |
|       `5` | Schema or column mapping error         |
|       `6` | Unsupported format or unsupported type |
|      `99` | Unexpected error                       |

## 9. Technical Requirements

## 9.1 Language and Runtime

* Java 25.
* Gradle wrapper included.
* Gradle Kotlin DSL.
* Quarkus CLI application.
* Picocli for command parsing.

## 9.2 Suggested Dependencies

Use Gradle Kotlin DSL.

Expected dependency families:

```kotlin
dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:<quarkusVersion>"))

    implementation("io.quarkus:quarkus-picocli")
    implementation("io.quarkus:quarkus-arc")

    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("org.apache.commons:commons-csv:<commonsCsvVersion>")

    implementation("org.apache.parquet:parquet-avro:<parquetVersion>")
    implementation("org.apache.hadoop:hadoop-common:<hadoopVersion>")

    runtimeOnly("org.postgresql:postgresql:<postgresVersion>")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:<oracleJdbcVersion>")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:<assertjVersion>")
    testImplementation("org.testcontainers:junit-jupiter:<testcontainersVersion>")
    testImplementation("org.testcontainers:postgresql:<testcontainersVersion>")
    testImplementation("org.duckdb:duckdb_jdbc:<duckDbVersion>")
}
```

Copilot should choose current stable dependency versions compatible with Java 25 and Quarkus.

## 9.3 Gradle Requirements

Create:

```text
settings.gradle.kts
build.gradle.kts
gradle/wrapper/...
```

Configure Java toolchain:

```kotlin
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
```

Configure tests with JUnit Platform.

Provide tasks:

```bash
./gradlew test
./gradlew quarkusDev
./gradlew build
```

The final application should be runnable with:

```bash
java -jar build/quarkus-app/quarkus-run.jar --help
```

## 10. Architecture

## 10.1 Package Structure

Use the following package structure:

```text
com.example.jdbcexport
  JdbcExportApplication.java

com.example.jdbcexport.cli
  JdbcExportCommand.java
  OutputFormat.java
  ExportOptions.java

com.example.jdbcexport.jdbc
  JdbcExporter.java
  JdbcConnectionFactory.java
  SqlInputResolver.java
  SqlSafetyValidator.java
  ResultSetColumn.java
  ResultSetSchemaReader.java
  JdbcValueReader.java

com.example.jdbcexport.writer
  RowWriter.java
  RowWriterFactory.java
  JsonArrayRowWriter.java
  NdjsonRowWriter.java
  CsvRowWriter.java
  TsvRowWriter.java
  ParquetRowWriter.java

com.example.jdbcexport.parquet
  AvroSchemaFactory.java
  AvroValueMapper.java
  AvroFieldNameSanitizer.java

com.example.jdbcexport.metadata
  ExportMetadata.java
  ExportMetadataWriter.java

com.example.jdbcexport.error
  ExitCodes.java
  ExportException.java
```

## 10.2 Core Interface

Create a common writer interface:

```java
public interface RowWriter extends AutoCloseable {

    void start(ResultSetMetaData metaData) throws Exception;

    void writeRow(ResultSet resultSet) throws Exception;

    ExportWriteResult finish() throws Exception;

    @Override
    default void close() throws Exception {
        finish();
    }
}
```

`ExportWriteResult` should include at least:

```java
public record ExportWriteResult(
    long rowCount,
    Path output
) {
}
```

Alternatively, row count may be managed by `JdbcExporter`.

## 10.3 JDBC Exporter

Create a single JDBC streaming implementation:

```java
public final class JdbcExporter {

    public ExportResult export(
        Connection connection,
        String sql,
        int fetchSize,
        Long maxRows,
        RowWriter writer
    ) {
        // implementation
    }
}
```

Requirements:

* Use one streaming path for all formats.
* Read metadata once.
* Call `writer.start(metaData)`.
* Loop through `ResultSet`.
* Call `writer.writeRow(rs)` for each row.
* Stop at `maxRows` if provided.
* Call `writer.finish()`.
* Return row count and timing.

## 10.4 Writer Factory

Create a writer factory:

```java
public final class RowWriterFactory {

    public RowWriter create(ExportOptions options, List<ResultSetColumn> columns) {
        return switch (options.format()) {
            case JSON -> new JsonArrayRowWriter(...);
            case NDJSON -> new NdjsonRowWriter(...);
            case CSV -> new CsvRowWriter(...);
            case TSV -> new TsvRowWriter(...);
            case PARQUET -> new ParquetRowWriter(...);
        };
    }
}
```

## 10.5 Output Format Enum

```java
public enum OutputFormat {
    JSON,
    NDJSON,
    PARQUET,
    CSV,
    TSV
}
```

CLI parsing should accept lowercase values.

## 11. Data Type Handling

## 11.1 JSON / NDJSON / CSV / TSV Value Rules

Create a shared `JdbcValueReader` or `JdbcScalarFormatter`.

Rules:

| JDBC Value               | JSON / NDJSON                         | CSV / TSV              |
| ------------------------ | ------------------------------------- | ---------------------- |
| null                     | JSON null                             | configured null string |
| string                   | string                                | string                 |
| integer / long           | number                                | string representation  |
| float / double / decimal | number where safe                     | string representation  |
| boolean                  | boolean                               | `true` / `false`       |
| date                     | ISO local date string                 | ISO local date string  |
| time                     | ISO local time string                 | ISO local time string  |
| timestamp                | ISO instant or local timestamp string | same string            |
| binary                   | Base64 string                         | Base64 string          |
| unknown                  | `toString()`                          | `toString()`           |

For JSON, `BigDecimal` should be written as a JSON number.

For CSV/TSV, everything is ultimately text.

## 11.2 Parquet Value Rules

Create Avro values matching generated Avro schema.

Use nullable fields.

Recommended first version:

* Date -> epoch day integer with Avro logical date.
* Timestamp -> epoch microseconds with Avro timestamp micros.
* Decimal/Numeric -> string.
* Binary -> bytes.
* Unknown -> string.

## 12. Tests

## 12.1 Unit Tests

Create tests for:

* CLI option validation.
* SQL input resolution.
* SQL safety validation.
* Duplicate column label detection.
* Avro field name sanitisation.
* CSV escaping.
* TSV escaping.
* JSON null handling.
* NDJSON one-row-per-line behaviour.
* Output overwrite protection.
* Password environment variable resolution.

## 12.2 Integration Tests

Use Testcontainers PostgreSQL for integration tests.

Test table:

```sql
create table bookings (
    booking_id varchar(20) primary key,
    room_code varchar(20),
    start_date date,
    attendees integer,
    cancelled boolean,
    amount numeric(12,2),
    created_at timestamp
);
```

Seed rows:

```sql
insert into bookings values
('B001', 'A-101', date '2026-06-11', 10, false, 123.45, timestamp '2026-06-11 10:00:00'),
('B002', 'A-101', date '2026-06-12', 15, false, 456.78, timestamp '2026-06-12 11:30:00'),
('B003', 'B-202', date '2026-06-12', 4, true, null, null);
```

Integration tests must verify:

* JSON file exists.
* JSON row count is correct.
* NDJSON has one line per row.
* CSV has a header and expected number of lines.
* TSV has tab delimiters.
* Parquet file is created.
* Parquet row count can be verified using DuckDB JDBC or Parquet reader.
* Metadata file contains row count and column information.

## 13. Acceptance Criteria

The starter project is complete when the following are true.

### 13.1 Build

```bash
./gradlew clean test
```

passes.

```bash
./gradlew build
```

passes.

### 13.2 Help

```bash
java -jar build/quarkus-app/quarkus-run.jar --help
```

prints useful command help.

### 13.3 JSON Export

Given a PostgreSQL database and a valid SQL file:

```bash
java -jar build/quarkus-app/quarkus-run.jar \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password-env DB_PASSWORD \
  --sql-file sql/bookings.sql \
  --format json \
  --output out/bookings.json \
  --overwrite
```

produces a valid JSON array.

### 13.4 NDJSON Export

```bash
--format ndjson
```

produces one JSON object per line.

### 13.5 CSV Export

```bash
--format csv
```

produces a valid comma-delimited file with a header row by default.

### 13.6 TSV Export

```bash
--format tsv
```

produces a valid tab-delimited file with a header row by default.

### 13.7 Parquet Export

```bash
--format parquet
```

produces a valid Parquet file readable by DuckDB, Spark, or a Parquet reader.

### 13.8 Streaming

The exporter must not collect all rows into memory.

The implementation must process rows in a loop from `ResultSet`.

### 13.9 Duplicate Column Names

Given SQL with duplicate output labels, the CLI must fail with a helpful message.

### 13.10 Existing Output Protection

If the output file already exists and `--overwrite` is not supplied, the CLI must fail.

### 13.11 Describe Mode

`--describe` must print column metadata without writing an output file.

## 14. Suggested Implementation Plan for Copilot

## Step 1: Generate Project Skeleton

Create a Quarkus Java 25 Gradle project with:

* Picocli extension.
* Arc/CDI support.
* JUnit 5 tests.
* Gradle wrapper.
* README.
* `.gitignore`.

## Step 2: Implement CLI Model

Create:

* `JdbcExportCommand`
* `ExportOptions`
* `OutputFormat`
* `ExitCodes`

Validate all CLI combinations.

## Step 3: Implement SQL Input and Password Handling

Create:

* `SqlInputResolver`
* `PasswordResolver`
* `SqlSafetyValidator`

Support:

* `--sql`
* `--sql-file`
* `--password`
* `--password-env`

## Step 4: Implement JDBC Schema Reader

Create:

* `ResultSetColumn`
* `ResultSetSchemaReader`

Capture:

* index
* column label
* normalized output name
* JDBC type
* JDBC type name
* precision
* scale
* nullable

Detect duplicate output names.

## Step 5: Implement Common JDBC Exporter

Create:

* `JdbcExporter`
* `ExportResult`

Ensure streaming behaviour.

## Step 6: Implement JSON Writer

Create:

* `JsonArrayRowWriter`

Use Jackson `JsonGenerator`.

## Step 7: Implement NDJSON Writer

Create:

* `NdjsonRowWriter`

Use Jackson `JsonGenerator`.

## Step 8: Implement CSV and TSV Writers

Create:

* `DelimitedTextRowWriter`
* `CsvRowWriter`
* `TsvRowWriter`

Use Apache Commons CSV.

CSV delimiter: comma.

TSV delimiter: tab.

## Step 9: Implement Parquet Writer

Create:

* `ParquetRowWriter`
* `AvroSchemaFactory`
* `AvroValueMapper`
* `AvroFieldNameSanitizer`

Use Apache Parquet Avro writer.

Map fields from JDBC metadata.

## Step 10: Implement Metadata Writer

Create:

* `ExportMetadata`
* `ExportMetadataWriter`

Write metadata JSON when `--metadata` is supplied.

## Step 11: Add Tests

Add unit and integration tests.

Use Testcontainers PostgreSQL.

Use DuckDB JDBC or a Parquet reader to validate Parquet row count.

## Step 12: Add README

README must include:

* Purpose.
* Build instructions.
* Java 25 requirement.
* Example SQL.
* Example commands for all output formats.
* Notes on SQL aliases.
* Notes on passwords.
* Notes on Parquet decimal handling.
* Exit codes.

## 15. README Content Requirements

The README should explain:

### 15.1 Why Explicit SQL Aliases Matter

Good:

```sql
select
    b.booking_id as booking_id,
    r.room_code as room_code
from booking b
join room r on r.room_id = b.room_id;
```

Bad:

```sql
select *
from booking b
join room r on r.room_id = b.room_id;
```

### 15.2 Format Selection Guidance

| Format  | Use When                                                               |
| ------- | ---------------------------------------------------------------------- |
| JSON    | You need one human-readable file containing an array of objects        |
| NDJSON  | You need stream-friendly line-delimited JSON                           |
| CSV     | You need spreadsheet-friendly tabular output                           |
| TSV     | You need spreadsheet-friendly output where commas are common in values |
| Parquet | You need compact analytics-friendly columnar output                    |

### 15.3 Known v1 Limitations

Document:

* SQL parameters are not supported.
* Decimal/Numeric are stored as strings in Parquet v1.
* Cloud object storage output is not implemented.
* Parallel exports are not implemented.
* Native image support is not guaranteed in v1.
* Query cancellation and timeout support are future enhancements.

## 16. Future Enhancements

Potential v2 features:

* `--query-timeout-seconds`
* SQL parameters from CLI or properties file.
* Config file support.
* Multiple named export jobs.
* Partitioned Parquet output.
* Multi-file rolling output.
* Gzip support for JSON, NDJSON, CSV, TSV.
* Avro decimal logical type support.
* Timestamp timezone policy flags.
* S3 / Azure Blob / GCS output.
* Native image build support.
* Progress logging every N rows.
* Column include/exclude options.
* Output schema override file.
* Secrets integration.
* Docker image.
* GitHub Actions workflow.
* Homebrew package.
* JReleaser packaging.

## 17. Strong Design Guidance

The most important architectural rule:

```text
Do not duplicate JDBC extraction logic per output format.
```

The correct shape is:

```text
CLI options
   |
   v
SQL resolver
   |
   v
JDBC connection
   |
   v
single streaming ResultSet loop
   |
   v
RowWriter interface
   |
   +--> JSON writer
   +--> NDJSON writer
   +--> CSV writer
   +--> TSV writer
   +--> Parquet writer
```

Avoid this anti-pattern:

```text
JsonExporter has its own JDBC query logic
CsvExporter has its own JDBC query logic
ParquetExporter has its own JDBC query logic
```

The first version should favour correctness, maintainability, and testability over cleverness.

## 18. Definition of Done

The project is done when:

1. The Gradle build passes.
2. Unit tests pass.
3. PostgreSQL integration tests pass.
4. JSON, NDJSON, CSV, TSV, and Parquet exports work.
5. The CLI help is useful.
6. The README explains all supported formats.
7. The code streams rows from JDBC.
8. Duplicate columns are handled safely.
9. Existing files are protected unless `--overwrite` is used.
10. Metadata sidecar support works.
11. The package structure is clean.
12. Copilot-generated code has been reviewed and simplified where needed.
