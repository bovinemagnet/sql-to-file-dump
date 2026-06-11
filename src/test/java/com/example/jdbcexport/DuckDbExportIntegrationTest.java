package com.example.jdbcexport;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.example.jdbcexport.writer.RowWriter;
import com.example.jdbcexport.writer.RowWriterFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDbExportIntegrationTest {

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
