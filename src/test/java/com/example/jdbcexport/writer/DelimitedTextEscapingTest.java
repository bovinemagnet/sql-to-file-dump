package com.example.jdbcexport.writer;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DelimitedTextEscapingTest {

    @Test
    void csvQuotesCommasQuotesAndNewlines(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("escaped.csv");
        String sql = "SELECT 'a,b' AS with_comma, 'say \"hi\"' AS with_quote, 'l1' || chr(10) || 'l2' AS with_newline";
        export(output, OutputFormat.CSV, sql, true, "");

        String content = Files.readString(output);
        assertThat(content).contains("\"a,b\"");
        assertThat(content).contains("\"say \"\"hi\"\"\"");
        assertThat(content).contains("\"l1\nl2\"");
    }

    @Test
    void tsvEscapesEmbeddedTabs(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("escaped.tsv");
        String sql = "SELECT 'a' || chr(9) || 'b' AS with_tab, 'plain' AS plain_col";
        export(output, OutputFormat.TSV, sql, true, "");

        List<String> lines = Files.readAllLines(output);
        assertThat(lines.get(0)).isEqualTo("with_tab\tplain_col");
        assertThat(lines.get(1)).contains("\"a\tb\"");
    }

    @Test
    void csvUsesConfiguredNullValue(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("nulls.csv");
        String sql = "SELECT CAST(NULL AS VARCHAR) AS missing, 'present' AS present_col";
        export(output, OutputFormat.CSV, sql, true, "NULL");

        List<String> lines = Files.readAllLines(output);
        assertThat(lines.get(1)).isEqualTo("NULL,present");
    }

    @Test
    void csvOmitsHeaderWhenDisabled(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("no-header.csv");
        String sql = "SELECT 'B001' AS booking_id";
        export(output, OutputFormat.CSV, sql, false, "");

        List<String> lines = Files.readAllLines(output);
        assertThat(lines).containsExactly("B001");
    }

    private void export(Path output, OutputFormat format, String sql, boolean includeHeader, String nullValue) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, sql, 100);
            ExportOptions options = new ExportOptions(
                "jdbc:duckdb:", "test", null, sql, null, format, output.toString(),
                100, null, null, false, false, false, false, false, includeHeader, nullValue, "SNAPPY");
            try (RowWriter writer = new RowWriterFactory().create(options, columns)) {
                new JdbcExporter().export(connection, sql, 100, null, writer);
            }
        }
    }
}
