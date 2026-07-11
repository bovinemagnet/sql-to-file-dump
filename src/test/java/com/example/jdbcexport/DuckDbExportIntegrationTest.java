package com.example.jdbcexport;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.example.jdbcexport.writer.RowWriter;
import com.example.jdbcexport.writer.RowWriterFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDbExportIntegrationTest {

    @BeforeAll
    static void registerDriver() throws Exception {
        // A @QuarkusTest elsewhere in the suite can trigger DriverManager's one-time
        // ServiceLoader scan under the Quarkus classloader, leaving the DuckDB driver
        // invisible to this classloader. Register it explicitly.
        DriverManager.registerDriver(new org.duckdb.DuckDBDriver());
    }

    @Test
    void exportsToCsv(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.csv");
        exportToFormat(output, OutputFormat.CSV, "SELECT booking_id, room_code, attendees FROM bookings ORDER BY booking_id");

        List<String> lines = Files.readAllLines(output);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo("booking_id,room_code,attendees");
        assertThat(lines.get(1)).startsWith("B001");
    }

    @Test
    void exportsToNdjson(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.ndjson");
        exportToFormat(output, OutputFormat.NDJSON, "SELECT booking_id, attendees FROM bookings ORDER BY booking_id");

        List<String> lines = Files.readAllLines(output);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("{");
        assertThat(lines.get(0)).endsWith("}");
    }

    @Test
    void exportsToJson(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.json");
        exportToFormat(output, OutputFormat.JSON, "SELECT booking_id, attendees FROM bookings ORDER BY booking_id");

        String content = Files.readString(output).trim();
        assertThat(content).startsWith("[");
        assertThat(content).endsWith("]");
    }

    @Test
    void exportsToTsv(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.tsv");
        exportToFormat(output, OutputFormat.TSV, "SELECT booking_id, room_code FROM bookings ORDER BY booking_id");

        List<String> lines = Files.readAllLines(output);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).contains("	");
    }

    @Test
    void exportsToParquet(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.parquet");
        exportToFormat(output, OutputFormat.PARQUET, "SELECT booking_id, attendees FROM bookings ORDER BY booking_id");

        assertThat(output).exists();
        try (Connection verifyConnection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (var statement = verifyConnection.createStatement();
                 var resultSet = statement.executeQuery("SELECT COUNT(*) FROM read_parquet('" + output.toAbsolutePath() + "')")) {
                resultSet.next();
                assertThat(resultSet.getLong(1)).isEqualTo(3L);
            }
        }
    }

    @Test
    void overwritesExistingParquetFile(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.parquet");
        // First export writes all three rows.
        exportToFormat(output, OutputFormat.PARQUET, "SELECT booking_id, attendees FROM bookings ORDER BY booking_id");
        assertThat(output).exists();

        // A second export to the same path must overwrite (not fail with FileAlreadyExistsException)
        // and truncate to the new, smaller result — matching how the CSV/JSON writers behave.
        exportToFormat(output, OutputFormat.PARQUET, "SELECT booking_id, attendees FROM bookings WHERE booking_id = 'B001'");

        try (Connection verifyConnection = DriverManager.getConnection("jdbc:duckdb:");
             var statement = verifyConnection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM read_parquet('" + output.toAbsolutePath() + "')")) {
            resultSet.next();
            assertThat(resultSet.getLong(1)).isEqualTo(1L);
        }
    }

    @Test
    void exportsZonelessTimestampToParquetHostIndependently(@TempDir Path tempDir) throws Exception {
        // A zone-less TIMESTAMP must round-trip to its wall-clock value regardless of the JVM zone
        // (local-timestamp-micros), rather than being shifted by the default time zone.
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Brisbane"));
        try {
            Path output = tempDir.resolve("ts.parquet");
            exportToFormat(output, OutputFormat.PARQUET, "SELECT TIMESTAMP '2024-06-01 12:34:56' AS ts");

            try (Connection verifyConnection = DriverManager.getConnection("jdbc:duckdb:");
                 var statement = verifyConnection.createStatement();
                 var resultSet = statement.executeQuery(
                     "SELECT ts::VARCHAR FROM read_parquet('" + output.toAbsolutePath() + "')")) {
                resultSet.next();
                assertThat(resultSet.getString(1)).isEqualTo("2024-06-01 12:34:56");
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void exportsTimestampToParquetWithMicrosecondPrecision(@TempDir Path tempDir) throws Exception {
        // Issue #21: sub-millisecond digits must round-trip; a millis-based conversion
        // would read back as 12:00:00.123 instead of 12:00:00.123456.
        Path output = tempDir.resolve("micros.parquet");
        exportToFormat(output, OutputFormat.PARQUET, "SELECT TIMESTAMP '2024-01-01 12:00:00.123456' AS ts");

        try (Connection verifyConnection = DriverManager.getConnection("jdbc:duckdb:");
             var statement = verifyConnection.createStatement();
             var resultSet = statement.executeQuery(
                 "SELECT ts::VARCHAR FROM read_parquet('" + output.toAbsolutePath() + "')")) {
            resultSet.next();
            assertThat(resultSet.getString(1)).isEqualTo("2024-01-01 12:00:00.123456");
        }
    }

    @Test
    void exportsNonAsciiAliasToParquet(@TempDir Path tempDir) throws Exception {
        // Issue #23: a legal SQL alias like "café" must sanitise to a valid Avro name
        // rather than blowing up in Avro's Schema name validation.
        Path output = tempDir.resolve("alias.parquet");
        exportToFormat(output, OutputFormat.PARQUET, "SELECT amount AS \"café\" FROM bookings WHERE booking_id = 'B001'");

        try (Connection verifyConnection = DriverManager.getConnection("jdbc:duckdb:");
             var statement = verifyConnection.createStatement();
             var resultSet = statement.executeQuery(
                 "SELECT caf_ FROM read_parquet('" + output.toAbsolutePath() + "')")) {
            resultSet.next();
            assertThat(resultSet.getString(1)).isEqualTo("123.45");
        }
    }

    private void exportToFormat(Path output, OutputFormat format, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            setupTable(connection);
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, sql, 1000);
            ExportOptions options = new ExportOptions(
                "jdbc:duckdb:",
                "test",
                null,
                sql,
                null,
                format,
                output.toString(),
                1000,
                null,
                null,
                false,
                false,
                false,
                false,
                false,
                true,
                "",
                "SNAPPY"
            );
            try (RowWriter writer = new RowWriterFactory().create(options, columns)) {
                new JdbcExporter().export(connection, sql, 1000, null, writer);
            }
        }
    }

    private void setupTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE bookings (booking_id VARCHAR, room_code VARCHAR, attendees INTEGER, cancelled BOOLEAN, amount NUMERIC(12,2))");
            statement.execute("INSERT INTO bookings VALUES " +
                "('B001', 'A-101', 10, false, 123.45), " +
                "('B002', 'A-101', 15, false, 456.78), " +
                "('B003', 'B-202', 4, true, NULL)");
        }
    }
}
