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
    void jsonWritesNanAndInfinityAsNull(@TempDir Path tempDir) throws Exception {
        // Issue #38: Jackson's default quotes non-finite doubles as strings ("NaN"), silently
        // changing a numeric field's type per row. We write JSON null instead.
        Path output = tempDir.resolve("nonfinite.json");
        String sql = "SELECT 'NaN'::DOUBLE AS nan_col, 'Infinity'::DOUBLE AS pos_inf, "
            + "'-Infinity'::DOUBLE AS neg_inf, 1.25 AS finite_col";
        export(output, OutputFormat.JSON, sql);

        JsonNode row = MAPPER.readTree(output.toFile()).get(0);
        assertThat(row.get("nan_col").isNull()).isTrue();
        assertThat(row.get("pos_inf").isNull()).isTrue();
        assertThat(row.get("neg_inf").isNull()).isTrue();
        assertThat(row.get("finite_col").isNumber()).isTrue();
    }

    @Test
    void ndjsonWritesNanAndInfinityAsNull(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("nonfinite.ndjson");
        String sql = "SELECT 'NaN'::DOUBLE AS nan_col, 'Infinity'::DOUBLE AS pos_inf";
        export(output, OutputFormat.NDJSON, sql);

        JsonNode row = MAPPER.readTree(Files.readAllLines(output).get(0));
        assertThat(row.get("nan_col").isNull()).isTrue();
        assertThat(row.get("pos_inf").isNull()).isTrue();
    }

    @Test
    void jsonWritesBigDecimalsInPlainNotation(@TempDir Path tempDir) throws Exception {
        // Issue #38: writeNumber(BigDecimal) renders scientific notation (1E+2) while CSV uses
        // toPlainString() (100); WRITE_BIGDECIMAL_AS_PLAIN restores parity.
        Path output = tempDir.resolve("decimal.json");
        List<ResultSetColumn> columns = List.of(
            new ResultSetColumn(1, "amount", "amount", java.sql.Types.DECIMAL, "DECIMAL", 10, 0, true));
        JsonArrayRowWriter writer = new JsonArrayRowWriter(output, false, columns);
        writer.start(null);
        com.example.jdbcexport.transform.Row row = new com.example.jdbcexport.transform.Row();
        row.put("amount", new java.math.BigDecimal("1E+2"));
        writer.writeRow(row);
        writer.finish();

        assertThat(Files.readString(output)).isEqualTo("[{\"amount\":100}]");
    }

    @Test
    void ndjsonWritesBigDecimalsInPlainNotationAndNonFiniteFloatsAsNull(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("decimal.ndjson");
        List<ResultSetColumn> columns = List.of(
            new ResultSetColumn(1, "amount", "amount", java.sql.Types.DECIMAL, "DECIMAL", 10, 0, true),
            new ResultSetColumn(2, "ratio", "ratio", java.sql.Types.REAL, "REAL", 0, 0, true));
        NdjsonRowWriter writer = new NdjsonRowWriter(output, columns);
        writer.start(null);
        com.example.jdbcexport.transform.Row row = new com.example.jdbcexport.transform.Row();
        row.put("amount", new java.math.BigDecimal("1E+2"));
        row.put("ratio", Float.NaN);
        writer.writeRow(row);
        writer.finish();

        assertThat(Files.readString(output)).isEqualTo("{\"amount\":100,\"ratio\":null}\n");
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
                "jdbc:duckdb:", "test", sql, null, format, output.toString(),
                100, null, null, false, false, false, false, false, true, "", false, false, "SNAPPY");
            try (RowWriter writer = new RowWriterFactory().create(options, columns)) {
                new JdbcExporter().export(connection, sql, 100, null, writer);
            }
        }
    }
}
