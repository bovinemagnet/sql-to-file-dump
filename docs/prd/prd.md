Yes — this is a very good CLI use case.

Your process is essentially:

```text
SQL database
   |
   | JDBC
   v
ResultSet stream
   |
   +--> JSON writer
   |
   +--> Parquet writer
```

I would **not** create separate logic for JSON and Parquet. I would create one JDBC extraction pipeline and plug in different output writers based on a CLI flag.

My preferred design would be:

```text
db-export
  --url jdbc:postgresql://...
  --user ...
  --password ...
  --sql-file export.sql
  --format json | ndjson | parquet
  --output bookings.parquet
  --fetch-size 5000
```

Then internally:

```text
CliOptions
   |
   v
JdbcQueryRunner
   |
   v
ResultSet
   |
   v
RowWriter
   |
   +--> JsonArrayWriter
   +--> NdjsonWriter
   +--> ParquetWriter
```

## My recommended shape

Use these libraries:

| Area                        | Recommendation                                                              |
| --------------------------- | --------------------------------------------------------------------------- |
| CLI parsing                 | **Picocli**                                                                 |
| Spring Boot CLI entry point | `CommandLineRunner` or `ApplicationRunner`                                  |
| Quarkus CLI entry point     | Quarkus command mode + Picocli                                              |
| JDBC                        | Plain JDBC or Spring `JdbcTemplate` for setup, but stream using `ResultSet` |
| JSON output                 | Jackson `JsonGenerator`                                                     |
| Parquet output              | Apache Parquet Java + Avro                                                  |
| Large result sets           | JDBC fetch size / cursor-style fetching                                     |
| Validation/testing          | DuckDB for inspecting generated Parquet                                     |

Spring Boot’s `CommandLineRunner` is intended for code that runs when a `SpringApplication` starts, and Quarkus has a command-mode model for CLI-style applications; Quarkus also has official Picocli support for command-line applications. ([Home][1])

For the export itself, I would keep it mostly framework-neutral Java. Spring Boot or Quarkus should mainly provide configuration, dependency injection, logging, and packaging.

## CLI interface

I would make the CLI look something like this:

```bash
db-export \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password secret \
  --sql-file bookings.sql \
  --format parquet \
  --output bookings.parquet \
  --fetch-size 5000
```

For JSON:

```bash
db-export \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password secret \
  --sql-file bookings.sql \
  --format json \
  --output bookings.json \
  --fetch-size 5000
```

For NDJSON, which I would strongly consider for large exports:

```bash
db-export \
  --url jdbc:postgresql://localhost:5432/appdb \
  --user app \
  --password secret \
  --sql-file bookings.sql \
  --format ndjson \
  --output bookings.ndjson \
  --fetch-size 5000
```

My opinion: for small to medium files, `json` as an array is fine. For large exports, **NDJSON is usually better than one huge JSON array** because each row is independently readable and stream-friendly.

## Suggested CLI options

```java
public enum OutputFormat {
    JSON,
    NDJSON,
    PARQUET
}
```

```java
public record ExportOptions(
    String jdbcUrl,
    String username,
    String password,
    Path sqlFile,
    String sql,
    OutputFormat format,
    Path output,
    int fetchSize,
    boolean prettyJson,
    String parquetCompression
) {
}
```

I would allow either `--sql` or `--sql-file`, but for real use I prefer `--sql-file`.

```bash
db-export \
  --sql-file room-bookings.sql \
  --format parquet \
  --output room-bookings.parquet
```

SQL files are easier to version, test, review, and format.

## Core abstraction

The important design is this interface:

```java
public interface RowWriter extends AutoCloseable {

    void start(ResultSetMetaData metaData) throws Exception;

    void writeRow(ResultSet resultSet) throws Exception;

    void finish() throws Exception;

    @Override
    default void close() throws Exception {
        finish();
    }
}
```

Then your JDBC runner does not care whether the output is JSON or Parquet:

```java
public final class JdbcExporter {

    public void export(
        Connection connection,
        String sql,
        int fetchSize,
        RowWriter writer
    ) throws Exception {

        connection.setAutoCommit(false);

        try (
            PreparedStatement statement = connection.prepareStatement(
                sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
            )
        ) {
            statement.setFetchSize(fetchSize);

            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();

                writer.start(metaData);

                long rowCount = 0;

                while (rs.next()) {
                    writer.writeRow(rs);
                    rowCount++;
                }

                writer.finish();

                System.out.printf("Exported %,d rows%n", rowCount);
            }
        }
    }
}
```

For PostgreSQL JDBC, fetch-size-based cursor behaviour requires attention: the driver documentation says setting fetch size enables cursor mode, and setting it back to `0` causes all rows to be cached; the example also calls out turning autocommit off. ([jdbc.postgresql.org][2]) Oracle JDBC also has configurable row fetch size, with Oracle documenting a default fetch size of 10 rows from the database cursor. ([Oracle Docs][3])

That `connection.setAutoCommit(false)` is especially important for PostgreSQL streaming.

## SQL style

For a tool like this, I would strongly recommend **requiring stable aliases** in the SQL.

Good:

```sql
select
    b.booking_id       as booking_id,
    r.room_code        as room_code,
    b.start_date       as start_date,
    b.attendees        as attendees,
    b.cancelled        as cancelled
from booking b
join room r on r.room_id = b.room_id
where b.start_date >= date '2026-01-01'
```

Avoid:

```sql
select *
from booking
```

For JSON, ugly column labels are survivable.

For Parquet through Avro, field names need to be stable and safe. So this is asking for trouble:

```sql
select
    b.booking_id as "Booking ID",
    r.room_code as "Room Code"
from booking b
join room r on r.room_id = b.room_id
```

Better:

```sql
select
    b.booking_id as booking_id,
    r.room_code as room_code
from booking b
join room r on r.room_id = b.room_id
```

My opinion: **make the SQL contract explicit**. The SQL result shape is the export contract.

## JSON writer

Use Jackson streaming, not `List<Map<String,Object>>`.

Jackson’s core streaming API exposes `JsonFactory`, `JsonParser`, and `JsonGenerator` for lower-level JSON processing. ([fasterxml.github.io][4])

```java
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.format.DateTimeFormatter;

public final class JsonArrayRowWriter implements RowWriter {

    private final Path output;
    private final boolean pretty;
    private JsonGenerator json;
    private int columnCount;
    private String[] columnNames;

    public JsonArrayRowWriter(Path output, boolean pretty) {
        this.output = output;
        this.pretty = pretty;
    }

    @Override
    public void start(ResultSetMetaData metaData) throws Exception {
        this.columnCount = metaData.getColumnCount();
        this.columnNames = new String[columnCount + 1];

        for (int i = 1; i <= columnCount; i++) {
            columnNames[i] = metaData.getColumnLabel(i);
        }

        OutputStream out = Files.newOutputStream(output);
        JsonFactory factory = new JsonFactory();
        this.json = factory.createGenerator(out);

        if (pretty) {
            this.json.useDefaultPrettyPrinter();
        }

        json.writeStartArray();
    }

    @Override
    public void writeRow(ResultSet rs) throws Exception {
        json.writeStartObject();

        for (int i = 1; i <= columnCount; i++) {
            json.writeFieldName(columnNames[i]);
            writeJsonValue(rs, i);
        }

        json.writeEndObject();
    }

    private void writeJsonValue(ResultSet rs, int columnIndex) throws Exception {
        Object value = rs.getObject(columnIndex);

        if (value == null) {
            json.writeNull();
            return;
        }

        switch (value) {
            case String s -> json.writeString(s);
            case Integer n -> json.writeNumber(n);
            case Long n -> json.writeNumber(n);
            case Short n -> json.writeNumber(n);
            case Byte n -> json.writeNumber(n);
            case Float n -> json.writeNumber(n);
            case Double n -> json.writeNumber(n);
            case java.math.BigDecimal n -> json.writeNumber(n);
            case Boolean b -> json.writeBoolean(b);
            case java.sql.Date d -> json.writeString(d.toLocalDate().toString());
            case java.sql.Time t -> json.writeString(t.toLocalTime().toString());
            case java.sql.Timestamp ts -> json.writeString(ts.toInstant().toString());
            case byte[] bytes -> json.writeBinary(bytes);
            default -> json.writeString(value.toString());
        }
    }

    @Override
    public void finish() throws Exception {
        if (json != null) {
            json.writeEndArray();
            json.close();
            json = null;
        }
    }
}
```

This writes:

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

## NDJSON writer

I would also add this.

```java
public final class NdjsonRowWriter implements RowWriter {

    private final Path output;
    private JsonGenerator json;
    private int columnCount;
    private String[] columnNames;

    public NdjsonRowWriter(Path output) {
        this.output = output;
    }

    @Override
    public void start(ResultSetMetaData metaData) throws Exception {
        this.columnCount = metaData.getColumnCount();
        this.columnNames = new String[columnCount + 1];

        for (int i = 1; i <= columnCount; i++) {
            columnNames[i] = metaData.getColumnLabel(i);
        }

        this.json = new JsonFactory().createGenerator(Files.newOutputStream(output));
    }

    @Override
    public void writeRow(ResultSet rs) throws Exception {
        json.writeStartObject();

        for (int i = 1; i <= columnCount; i++) {
            json.writeFieldName(columnNames[i]);

            Object value = rs.getObject(i);

            if (value == null) {
                json.writeNull();
            } else if (value instanceof Number n) {
                switch (n) {
                    case Integer x -> json.writeNumber(x);
                    case Long x -> json.writeNumber(x);
                    case Double x -> json.writeNumber(x);
                    case Float x -> json.writeNumber(x);
                    case java.math.BigDecimal x -> json.writeNumber(x);
                    default -> json.writeNumber(n.toString());
                }
            } else if (value instanceof Boolean b) {
                json.writeBoolean(b);
            } else if (value instanceof java.sql.Date d) {
                json.writeString(d.toLocalDate().toString());
            } else if (value instanceof java.sql.Timestamp ts) {
                json.writeString(ts.toInstant().toString());
            } else if (value instanceof byte[] bytes) {
                json.writeBinary(bytes);
            } else {
                json.writeString(value.toString());
            }
        }

        json.writeEndObject();
        json.writeRaw('\n');
    }

    @Override
    public void finish() throws Exception {
        if (json != null) {
            json.close();
            json = null;
        }
    }
}
```

For large files, this is often nicer:

```jsonl
{"booking_id":"B001","room_code":"A-101","start_date":"2026-06-11","attendees":10,"cancelled":false}
{"booking_id":"B002","room_code":"A-101","start_date":"2026-06-12","attendees":15,"cancelled":false}
```

## Parquet writer

For Parquet, I would use **Apache Parquet Java with Avro**.

Apache Parquet is a column-oriented data file format; the project documentation describes the file format and current implementation status, and `AvroParquetWriter` is the Java-side writer commonly used with Avro records. ([Parquet][5])

The shape is:

```text
ResultSetMetaData
   |
   v
Avro Schema
   |
   v
GenericRecord per row
   |
   v
AvroParquetWriter
```

### Type mapping

You need a JDBC-to-Avro mapping.

I would start with this:

| JDBC type                                             | Avro / Parquet representation                                  |
| ----------------------------------------------------- | -------------------------------------------------------------- |
| `CHAR`, `VARCHAR`, `LONGVARCHAR`, `NCHAR`, `NVARCHAR` | `string`                                                       |
| `INTEGER`, `SMALLINT`, `TINYINT`                      | `int`                                                          |
| `BIGINT`                                              | `long`                                                         |
| `FLOAT`, `REAL`                                       | `float`                                                        |
| `DOUBLE`                                              | `double`                                                       |
| `BOOLEAN`, `BIT`                                      | `boolean`                                                      |
| `DATE`                                                | `int` with Avro logical type `date`                            |
| `TIMESTAMP`                                           | `long` with logical type `timestamp-micros`                    |
| `DECIMAL`, `NUMERIC`                                  | ideally Avro decimal logical type; initially string is simpler |
| `BINARY`, `VARBINARY`, `BLOB`                         | `bytes`                                                        |
| unknown / database-specific                           | string fallback                                                |

For a first version, I would honestly store `DECIMAL` as string unless analytics consumers need numeric decimal semantics immediately. Correct decimal handling in Avro/Parquet is doable, but it adds scale/precision handling and byte encoding. Get the pipeline working first, then harden decimals.

### Nullable fields

Most database columns can be null, especially if the SQL expression is from a join or computed value. So generate nullable Avro fields:

```json
{
  "name": "room_code",
  "type": ["null", "string"],
  "default": null
}
```

### Parquet writer skeleton

```java
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class ParquetRowWriter implements RowWriter {

    private final java.nio.file.Path output;
    private final CompressionCodecName compression;

    private Schema schema;
    private ParquetWriter<GenericRecord> writer;
    private List<ColumnMapping> columns;

    public ParquetRowWriter(
        java.nio.file.Path output,
        CompressionCodecName compression
    ) {
        this.output = output;
        this.compression = compression;
    }

    @Override
    public void start(ResultSetMetaData metaData) throws Exception {
        this.columns = buildColumnMappings(metaData);
        this.schema = buildAvroSchema(columns);

        Path parquetPath = new Path(output.toUri());

        this.writer = AvroParquetWriter.<GenericRecord>builder(parquetPath)
            .withSchema(schema)
            .withConf(new Configuration())
            .withCompressionCodec(compression)
            .build();
    }

    @Override
    public void writeRow(ResultSet rs) throws Exception {
        GenericRecord record = new GenericData.Record(schema);

        for (ColumnMapping column : columns) {
            Object value = readValue(rs, column);
            record.put(column.avroName(), value);
        }

        writer.write(record);
    }

    @Override
    public void finish() throws Exception {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    private static List<ColumnMapping> buildColumnMappings(
        ResultSetMetaData metaData
    ) throws SQLException {
        int columnCount = metaData.getColumnCount();
        List<ColumnMapping> mappings = new ArrayList<>();

        for (int i = 1; i <= columnCount; i++) {
            String jdbcLabel = metaData.getColumnLabel(i);
            String avroName = toSafeAvroName(jdbcLabel, i);
            int jdbcType = metaData.getColumnType(i);
            int precision = metaData.getPrecision(i);
            int scale = metaData.getScale(i);

            mappings.add(new ColumnMapping(
                i,
                jdbcLabel,
                avroName,
                jdbcType,
                precision,
                scale
            ));
        }

        return mappings;
    }

    private static Schema buildAvroSchema(List<ColumnMapping> columns) {
        List<Schema.Field> fields = new ArrayList<>();

        for (ColumnMapping column : columns) {
            Schema fieldSchema = avroSchemaFor(column);

            Schema nullableSchema = Schema.createUnion(
                List.of(
                    Schema.create(Schema.Type.NULL),
                    fieldSchema
                )
            );

            Schema.Field field = new Schema.Field(
                column.avroName(),
                nullableSchema,
                "Source column: " + column.jdbcLabel(),
                Schema.Field.NULL_DEFAULT_VALUE
            );

            fields.add(field);
        }

        Schema schema = Schema.createRecord(
            "SqlExportRow",
            "Generated from JDBC ResultSet metadata",
            "export",
            false
        );

        schema.setFields(fields);

        return schema;
    }

    private static Schema avroSchemaFor(ColumnMapping column) {
        return switch (column.jdbcType()) {
            case Types.CHAR,
                 Types.VARCHAR,
                 Types.LONGVARCHAR,
                 Types.NCHAR,
                 Types.NVARCHAR,
                 Types.LONGNVARCHAR,
                 Types.CLOB,
                 Types.NCLOB -> Schema.create(Schema.Type.STRING);

            case Types.INTEGER,
                 Types.SMALLINT,
                 Types.TINYINT -> Schema.create(Schema.Type.INT);

            case Types.BIGINT -> Schema.create(Schema.Type.LONG);

            case Types.FLOAT,
                 Types.REAL -> Schema.create(Schema.Type.FLOAT);

            case Types.DOUBLE -> Schema.create(Schema.Type.DOUBLE);

            case Types.BOOLEAN,
                 Types.BIT -> Schema.create(Schema.Type.BOOLEAN);

            case Types.DATE -> org.apache.avro.LogicalTypes.date()
                .addToSchema(Schema.create(Schema.Type.INT));

            case Types.TIMESTAMP,
                 Types.TIMESTAMP_WITH_TIMEZONE -> org.apache.avro.LogicalTypes
                .timestampMicros()
                .addToSchema(Schema.create(Schema.Type.LONG));

            case Types.BINARY,
                 Types.VARBINARY,
                 Types.LONGVARBINARY,
                 Types.BLOB -> Schema.create(Schema.Type.BYTES);

            /*
             * Conservative first version:
             * Store decimals as string to avoid precision/scale bugs.
             * You can upgrade this later to Avro decimal logical type.
             */
            case Types.NUMERIC,
                 Types.DECIMAL -> Schema.create(Schema.Type.STRING);

            default -> Schema.create(Schema.Type.STRING);
        };
    }

    private static Object readValue(ResultSet rs, ColumnMapping column) throws SQLException {
        int i = column.index();

        Object raw = rs.getObject(i);

        if (raw == null) {
            return null;
        }

        return switch (column.jdbcType()) {
            case Types.CHAR,
                 Types.VARCHAR,
                 Types.LONGVARCHAR,
                 Types.NCHAR,
                 Types.NVARCHAR,
                 Types.LONGNVARCHAR,
                 Types.CLOB,
                 Types.NCLOB -> raw.toString();

            case Types.INTEGER,
                 Types.SMALLINT,
                 Types.TINYINT -> rs.getInt(i);

            case Types.BIGINT -> rs.getLong(i);

            case Types.FLOAT,
                 Types.REAL -> rs.getFloat(i);

            case Types.DOUBLE -> rs.getDouble(i);

            case Types.BOOLEAN,
                 Types.BIT -> rs.getBoolean(i);

            case Types.DATE -> rs.getDate(i).toLocalDate().toEpochDay();

            case Types.TIMESTAMP,
                 Types.TIMESTAMP_WITH_TIMEZONE -> {
                Timestamp timestamp = rs.getTimestamp(i);
                yield timestamp.toInstant().getEpochSecond() * 1_000_000L
                    + timestamp.toInstant().getNano() / 1_000L;
            }

            case Types.BINARY,
                 Types.VARBINARY,
                 Types.LONGVARBINARY,
                 Types.BLOB -> rs.getBytes(i);

            case Types.NUMERIC,
                 Types.DECIMAL -> rs.getBigDecimal(i).toPlainString();

            default -> raw.toString();
        };
    }

    private static String toSafeAvroName(String input, int index) {
        if (input == null || input.isBlank()) {
            return "column_" + index;
        }

        String name = input
            .trim()
            .replaceAll("[^A-Za-z0-9_]", "_");

        if (!name.matches("[A-Za-z_].*")) {
            name = "_" + name;
        }

        if (name.isBlank()) {
            return "column_" + index;
        }

        return name;
    }

    private record ColumnMapping(
        int index,
        String jdbcLabel,
        String avroName,
        int jdbcType,
        int precision,
        int scale
    ) {
    }
}
```

This is a good starting point, but I would add duplicate-name handling. For example, this SQL:

```sql
select
    a.id,
    b.id
from a
join b on b.a_id = a.id
```

may produce duplicate labels:

```text
id
id
```

So either force aliases:

```sql
select
    a.id as a_id,
    b.id as b_id
from a
join b on b.a_id = a.id
```

or make your tool detect duplicates and fail with a helpful error.

My preference: **fail fast** and tell the user to alias the SQL properly.

## Writer factory

Then choose the writer from the CLI flag:

```java
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

public final class RowWriterFactory {

    public RowWriter create(ExportOptions options) {
        return switch (options.format()) {
            case JSON -> new JsonArrayRowWriter(
                options.output(),
                options.prettyJson()
            );

            case NDJSON -> new NdjsonRowWriter(
                options.output()
            );

            case PARQUET -> new ParquetRowWriter(
                options.output(),
                compression(options.parquetCompression())
            );
        };
    }

    private CompressionCodecName compression(String value) {
        if (value == null || value.isBlank()) {
            return CompressionCodecName.SNAPPY;
        }

        return CompressionCodecName.valueOf(value.toUpperCase());
    }
}
```

For Parquet, I would default to `SNAPPY`.

## Picocli command

Picocli works well here because the same command class can be used from a plain Java main method, Spring Boot, or Quarkus.

```java
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.Callable;

@Command(
    name = "db-export",
    mixinStandardHelpOptions = true,
    description = "Export a JDBC SQL query to JSON, NDJSON, or Parquet"
)
public class DbExportCommand implements Callable<Integer> {

    @Option(names = "--url", required = true)
    String jdbcUrl;

    @Option(names = "--user", required = true)
    String username;

    @Option(names = "--password", required = true)
    String password;

    @Option(names = "--sql")
    String sql;

    @Option(names = "--sql-file")
    Path sqlFile;

    @Option(names = "--format", required = true)
    OutputFormat format;

    @Option(names = "--output", required = true)
    Path output;

    @Option(names = "--fetch-size", defaultValue = "5000")
    int fetchSize;

    @Option(names = "--pretty-json", defaultValue = "false")
    boolean prettyJson;

    @Option(names = "--parquet-compression", defaultValue = "SNAPPY")
    String parquetCompression;

    @Override
    public Integer call() throws Exception {
        String actualSql = resolveSql();

        ExportOptions options = new ExportOptions(
            jdbcUrl,
            username,
            password,
            sqlFile,
            sql,
            format,
            output,
            fetchSize,
            prettyJson,
            parquetCompression
        );

        try (Connection connection = DriverManager.getConnection(
            jdbcUrl,
            username,
            password
        )) {
            RowWriter writer = new RowWriterFactory().create(options);

            new JdbcExporter().export(
                connection,
                actualSql,
                fetchSize,
                writer
            );
        }

        return 0;
    }

    private String resolveSql() throws Exception {
        if (sql != null && !sql.isBlank() && sqlFile != null) {
            throw new IllegalArgumentException("Use either --sql or --sql-file, not both.");
        }

        if (sql != null && !sql.isBlank()) {
            return sql;
        }

        if (sqlFile != null) {
            return Files.readString(sqlFile);
        }

        throw new IllegalArgumentException("Either --sql or --sql-file is required.");
    }
}
```

You would also need:

```java
import java.sql.DriverManager;
```

## Spring Boot entry point

In Spring Boot, you can wire Picocli through a `CommandLineRunner`.

```java
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;

@SpringBootApplication
public class DbExportApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(DbExportApplication.class)
            .web(WebApplicationType.NONE)
            .run(args);
    }

    @Bean
    CommandLineRunner commandLineRunner(DbExportCommand command) {
        return args -> {
            int exitCode = new CommandLine(command).execute(args);
            System.exit(exitCode);
        };
    }
}
```

Make `DbExportCommand` a Spring bean:

```java
import org.springframework.stereotype.Component;

@Component
public class DbExportCommand implements Callable<Integer> {
    // same command as above
}
```

If your CLI does not need much Spring infrastructure, you can also skip Spring Boot entirely for this tool. But if you already have Spring-based CLI tooling, this is fine.

## Quarkus entry point

In Quarkus, I would use the Quarkus Picocli extension rather than manually parsing args.

The Quarkus Picocli guide describes Picocli as an open-source tool for rich command-line applications and documents Quarkus support for it. ([Quarkus][6])

Conceptually:

```java
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.enterprise.context.ApplicationScoped;
import picocli.CommandLine.Command;

@TopCommand
@Command(
    name = "db-export",
    mixinStandardHelpOptions = true,
    description = "Export a JDBC SQL query to JSON, NDJSON, or Parquet"
)
@ApplicationScoped
public class DbExportCommand implements Callable<Integer> {
    // same command logic
}
```

With Quarkus, be more careful if you compile to native image. JSON and JDBC are fine, but Parquet/Hadoop/Avro dependencies may need extra testing because they can involve reflection-heavy code paths. I would get the JVM mode version correct first.

## Dependencies

### Gradle-style sketch

```groovy
dependencies {
    implementation 'info.picocli:picocli'

    implementation 'com.fasterxml.jackson.core:jackson-core'
    implementation 'com.fasterxml.jackson.core:jackson-databind'

    implementation 'org.apache.parquet:parquet-avro'
    implementation 'org.apache.hadoop:hadoop-common'

    runtimeOnly 'org.postgresql:postgresql'
    runtimeOnly 'com.oracle.database.jdbc:ojdbc11'
}
```

In Spring Boot, Jackson is usually already present if you have the JSON/web starters. In Quarkus, use the relevant REST/Jackson extension if this tool also exposes endpoints, but for a pure CLI writer, plain Jackson dependencies are enough.

I would pin Parquet/Hadoop versions deliberately in your build rather than hoping framework dependency management gives you exactly what you want.

## Important production concerns

### 1. Do not load the whole result set

Avoid this:

```java
List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
```

That is fine for small admin queries, but it is the wrong base for a general export tool.

Prefer:

```java
while (rs.next()) {
    writer.writeRow(rs);
}
```

That keeps memory roughly constant.

### 2. Use fetch size

Expose this:

```bash
--fetch-size 5000
```

But make it database-aware if necessary.

For PostgreSQL, I would use:

```java
connection.setAutoCommit(false);
statement.setFetchSize(fetchSize);
```

For Oracle, `setFetchSize` is also relevant, but the behaviour and optimal value differ. Oracle documents row fetch size as the number of rows retrieved with each trip to the database cursor. ([Oracle Docs][3])

### 3. Keep SQL read-only

I would guard against accidental mutation.

At minimum:

```java
connection.setReadOnly(true);
```

And optionally reject obvious non-select SQL:

```java
String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);

if (!normalized.startsWith("select") && !normalized.startsWith("with")) {
    throw new IllegalArgumentException("Only SELECT/WITH queries are allowed.");
}
```

This is not a security boundary, but it prevents mistakes.

### 4. Use explicit column aliases

This matters a lot for Parquet.

Require:

```sql
select
    person_id as person_id,
    given_name as given_name,
    family_name as family_name
from person
```

Avoid:

```sql
select *
from person
```

### 5. Decide how to handle decimals

For JSON:

```json
{
  "amount": 123.45
}
```

For Parquet, you need to choose:

```text
quick/simple: string
better analytics: decimal logical type
```

For a first version, I would use string for decimal if data correctness matters more than analytical type fidelity. Later, add a flag:

```bash
--decimal-mode string | logical
```

### 6. Decide timestamp semantics

This is one of the easiest places to create subtle bugs.

For JSON, I would output ISO-8601 strings:

```json
{
  "created_at": "2026-06-11T03:15:30Z"
}
```

For Parquet, I would use timestamp micros.

But you need to decide whether your database timestamps are:

```text
local timestamp
timestamp with timezone
UTC-normalised timestamp
```

For export tools, I prefer normalising to UTC unless the business meaning is explicitly local time.

### 7. Compression

For Parquet:

```bash
--parquet-compression SNAPPY
```

For JSON/NDJSON, you may also want:

```bash
--gzip
```

Then:

```text
bookings.json.gz
bookings.ndjson.gz
```

JSON compresses well because column names repeat constantly.

### 8. Emit sidecar metadata

This is useful.

For every export, write:

```text
bookings.parquet
bookings.parquet.meta.json
```

Sidecar metadata:

```json
{
  "sqlFile": "bookings.sql",
  "format": "PARQUET",
  "rowCount": 1234567,
  "startedAt": "2026-06-11T01:00:00Z",
  "completedAt": "2026-06-11T01:04:12Z",
  "columns": [
    {
      "index": 1,
      "jdbcLabel": "booking_id",
      "jdbcType": "VARCHAR",
      "nullable": true,
      "outputName": "booking_id"
    }
  ]
}
```

This makes support and debugging much easier.

## Suggested package structure

```text
com.example.dbexport
  DbExportApplication.java

com.example.dbexport.cli
  DbExportCommand.java
  ExportOptions.java
  OutputFormat.java

com.example.dbexport.jdbc
  JdbcExporter.java
  JdbcTypeMapper.java
  ColumnMapping.java

com.example.dbexport.writer
  RowWriter.java
  RowWriterFactory.java
  JsonArrayRowWriter.java
  NdjsonRowWriter.java
  ParquetRowWriter.java

com.example.dbexport.validation
  SqlValidator.java
  ColumnNameValidator.java

com.example.dbexport.metadata
  ExportMetadata.java
  ExportMetadataWriter.java
```

## Example command flow

```text
1. Parse CLI arguments.
2. Read SQL text from --sql or --sql-file.
3. Open JDBC connection.
4. Set connection read-only.
5. Disable autocommit for streaming where appropriate.
6. Prepare statement.
7. Set fetch size.
8. Execute query.
9. Read ResultSetMetaData.
10. Create output writer.
11. Stream each row into writer.
12. Close writer.
13. Write sidecar metadata.
14. Exit with useful status code.
```

## Exit codes

I would define explicit exit codes:

| Code | Meaning                         |
| ---: | ------------------------------- |
|  `0` | Success                         |
|  `1` | Invalid CLI arguments           |
|  `2` | SQL file/read error             |
|  `3` | Database connection/query error |
|  `4` | Output file/write error         |
|  `5` | Schema/type mapping error       |

This is useful if the tool runs in CI, cron, Control-M, Kubernetes jobs, Jenkins, GitHub Actions, etc.

## Testing approach

Use Testcontainers if possible.

Test matrix:

```text
PostgreSQL -> JSON
PostgreSQL -> NDJSON
PostgreSQL -> Parquet
Oracle -> JSON
Oracle -> Parquet
empty result set
null values
duplicate column names
decimal columns
date columns
timestamp columns
binary columns
large result set
```

For validating Parquet output, DuckDB is very convenient. DuckDB documents direct Parquet querying with `read_parquet`, and its Parquet support includes reading/writing Parquet with projection/filter pushdown. ([DuckDB][7])

For example:

```sql
select *
from read_parquet('bookings.parquet');
```

Or from a test:

```java
try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
    ResultSet rs = conn.createStatement().executeQuery("""
        select count(*) as row_count
        from read_parquet('build/test-output/bookings.parquet')
        """);

    rs.next();

    assertEquals(3, rs.getLong("row_count"));
}
```

## A useful enhancement: schema preview

I would add:

```bash
db-export \
  --url jdbc:postgresql://localhost/app \
  --user app \
  --password secret \
  --sql-file bookings.sql \
  --format parquet \
  --describe
```

Output:

```text
Columns:
  1. booking_id    JDBC VARCHAR   -> Avro string
  2. room_code     JDBC VARCHAR   -> Avro string
  3. start_date    JDBC DATE      -> Avro int logical date
  4. attendees     JDBC INTEGER   -> Avro int
  5. cancelled     JDBC BOOLEAN   -> Avro boolean
```

This is extremely useful before running a massive export.

## Another useful enhancement: row limit

Add:

```bash
--limit 1000
```

But do not naively append `limit` to arbitrary SQL for every database. Instead, either:

1. Tell the user to include the limit in SQL, or
2. Wrap the SQL only per database dialect.

For a general tool, I would start with:

```bash
--max-rows 1000
```

and enforce it client-side:

```java
while (rs.next() && rowCount < maxRows) {
    writer.writeRow(rs);
    rowCount++;
}
```

That does not reduce DB work as much as a real SQL limit, but it is safe and database-neutral.

## My preferred first version

I would build version 1 with:

```text
Formats:
  json
  ndjson
  parquet

JSON:
  Jackson streaming
  ISO date/time strings
  optional pretty output

Parquet:
  Apache Parquet + Avro
  nullable fields
  SNAPPY compression
  simple type mapping
  decimals as strings initially

JDBC:
  forward-only ResultSet
  fetch size
  read-only connection
  explicit SQL file

Safety:
  SELECT/WITH only
  duplicate column label detection
  sidecar metadata file
```

Then later add:

```text
decimal logical type support
timestamp policy flags
S3/Azure/GCS output
partitioned Parquet output
multi-file output
schema override file
column include/exclude
config file support
```

## A realistic command design

```bash
db-export export \
  --url jdbc:postgresql://localhost:5432/app \
  --user app \
  --password-env DB_PASSWORD \
  --sql-file sql/bookings.sql \
  --format parquet \
  --output out/bookings.parquet \
  --fetch-size 5000 \
  --parquet-compression SNAPPY \
  --metadata out/bookings.parquet.meta.json
```

I would prefer `--password-env` over putting passwords directly on the command line:

```bash
export DB_PASSWORD='secret'
```

Then:

```bash
--password-env DB_PASSWORD
```

Command-line arguments can leak through shell history, process listings, job logs, and monitoring tools.

## Final recommendation

For your existing Spring Boot / Quarkus Java CLI tools, I would implement it like this:

```text
Picocli command
    |
    v
ExportOptions
    |
    v
JdbcExporter using streaming ResultSet
    |
    v
RowWriter interface
    |
    +--> JsonArrayRowWriter using Jackson JsonGenerator
    |
    +--> NdjsonRowWriter using Jackson JsonGenerator
    |
    +--> ParquetRowWriter using Apache Parquet + Avro
```

The most important rule is:

> **Stream rows once from JDBC and make the output format a pluggable writer.**

That gives you a clean tool that can grow from:

```bash
--format json
```

to:

```bash
--format parquet
```

without duplicating your database access logic.

[1]: https://docs.spring.io/spring-boot/api/java/org/springframework/boot/CommandLineRunner.html?utm_source=chatgpt.com "CommandLineRunner (Spring Boot 4.0.6 API)"
[2]: https://jdbc.postgresql.org/documentation/query/?utm_source=chatgpt.com "Issuing a Query and Processing the Result"
[3]: https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/resultset.html?source=%3Aem%3Anw%3Amt%3A%3Arc_wwmk170418p00047%3Ansl100815161&utm_source=chatgpt.com "17 Result Set"
[4]: https://fasterxml.github.io/jackson-core/javadoc/2.8/com/fasterxml/jackson/core/package-summary.html?utm_source=chatgpt.com "Package com.fasterxml.jackson.core"
[5]: https://parquet.apache.org/docs/?utm_source=chatgpt.com "Documentation - Apache Parquet"
[6]: https://quarkus.io/guides/picocli?utm_source=chatgpt.com "Command Mode with Picocli"
[7]: https://duckdb.org/docs/current/guides/file_formats/query_parquet.html?utm_source=chatgpt.com "Querying Parquet Files"
