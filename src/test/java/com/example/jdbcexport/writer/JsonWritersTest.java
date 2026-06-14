package com.example.jdbcexport.writer;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonWritersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void jsonWritesNullsNumbersAndBooleansNatively(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("values.json");
        String sql = "SELECT CAST(NULL AS VARCHAR) AS missing, 42 AS answer, true AS flag, CAST(1.5 AS DOUBLE) AS ratio";
        export(output, OutputFormat.JSON, sql);

        JsonNode root = MAPPER.readTree(output.toFile());
        assertThat(root.isArray()).isTrue();
        JsonNode row = root.get(0);
        assertThat(row.get("missing").isNull()).isTrue();
        assertThat(row.get("answer").isInt()).isTrue();
        assertThat(row.get("answer").intValue()).isEqualTo(42);
        assertThat(row.get("flag").isBoolean()).isTrue();
        assertThat(row.get("flag").booleanValue()).isTrue();
        assertThat(row.get("ratio").isNumber()).isTrue();
        assertThat(row.get("ratio").doubleValue()).isEqualTo(1.5);
    }

    @Test
    void ndjsonWritesOneParseableObjectPerLine(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("rows.ndjson");
        String sql = "SELECT * FROM (VALUES ('B001', 10), ('B002', 15), ('B003', 4)) AS t(booking_id, attendees)";
        export(output, OutputFormat.NDJSON, sql);

        List<String> lines = Files.readAllLines(output);
        assertThat(lines).hasSize(3);
        for (String line : lines) {
            JsonNode row = MAPPER.readTree(line);
            assertThat(row.isObject()).isTrue();
            assertThat(row.has("booking_id")).isTrue();
            assertThat(row.get("attendees").isInt()).isTrue();
        }
    }

    @Test
    void jsonWritesEmptyArrayForEmptyResultSet(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("empty.json");
        String sql = "SELECT 1 AS value WHERE 1 = 0";
        export(output, OutputFormat.JSON, sql);

        JsonNode root = MAPPER.readTree(output.toFile());
        assertThat(root.isArray()).isTrue();
        assertThat(root).isEmpty();
    }

    private void export(Path output, OutputFormat format, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, sql, 100);
            ExportOptions options = new ExportOptions(
                "jdbc:duckdb:", "test", null, sql, null, format, output.toString(),
                100, null, null, false, false, false, false, false, true, "", "SNAPPY");
            try (RowWriter writer = new RowWriterFactory().create(options, columns)) {
                new JdbcExporter().export(connection, sql, 100, null, writer);
            }
        }
    }
}
