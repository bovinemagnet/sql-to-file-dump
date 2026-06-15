package com.example.jdbcexport;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.example.jdbcexport.transform.TransformPipeline;
import com.example.jdbcexport.transform.TransformRegistry;
import com.example.jdbcexport.transform.TransformSpec;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDbTransformIntegrationTest {

    @BeforeAll
    static void registerDriver() throws Exception {
        DriverManager.registerDriver(new org.duckdb.DuckDBDriver());
    }

    @Test
    void csvAppliesRenameDropTemplateAndMask(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.csv");
        TransformPipeline pipeline = pipeline(
            new TransformSpec("rename", Map.of("from", "room_code", "to", "room")),
            new TransformSpec("drop", Map.of("columns", List.of("cancelled"))),
            new TransformSpec("template", Map.of("name", "summary", "template", "{room} x{attendees}")),
            new TransformSpec("mask", Map.of("column", "booking_id"))
        );
        export(output, OutputFormat.CSV,
            "SELECT booking_id, room_code, attendees, cancelled FROM bookings ORDER BY booking_id", pipeline);

        List<String> lines = Files.readAllLines(output);
        assertThat(lines.get(0)).isEqualTo("booking_id,room,attendees,summary");
        assertThat(lines.get(1)).isEqualTo("***,A-101,10,A-101 x10");
        assertThat(pipeline.metrics().snapshot().rowsOut()).isEqualTo(3);
    }

    @Test
    void ndjsonAppliesDefaultAndMap(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.ndjson");
        TransformPipeline pipeline = pipeline(
            new TransformSpec("default", Map.of("column", "amount", "value", "0")),
            new TransformSpec("map", Map.of("column", "room_code", "mapping", Map.of("A-101", "Alpha")))
        );
        export(output, OutputFormat.NDJSON,
            "SELECT booking_id, room_code, amount FROM bookings ORDER BY booking_id", pipeline);

        List<String> lines = Files.readAllLines(output);
        assertThat(lines.get(0)).contains("\"room_code\":\"Alpha\"");
        // B003 has a null amount and an unmapped room code.
        assertThat(lines.get(2)).contains("\"amount\":0").contains("\"room_code\":\"B-202\"");
    }

    @Test
    void parquetAppliesTransforms(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.parquet");
        TransformPipeline pipeline = pipeline(
            new TransformSpec("rename", Map.of("from", "booking_id", "to", "id")),
            new TransformSpec("mask", Map.of("column", "room_code"))
        );
        export(output, OutputFormat.PARQUET,
            "SELECT booking_id, room_code FROM bookings ORDER BY booking_id", pipeline);

        try (Connection verify = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = verify.createStatement();
             var rs = statement.executeQuery(
                 "SELECT id, room_code FROM read_parquet('" + output.toAbsolutePath() + "') ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo("B001");
            assertThat(rs.getString("room_code")).isEqualTo("***");
        }
    }

    @Test
    void emptyPipelineMatchesFastPath(@TempDir Path tempDir) throws Exception {
        Path withEmpty = tempDir.resolve("empty.csv");
        Path direct = tempDir.resolve("direct.csv");
        String sql = "SELECT booking_id, attendees FROM bookings ORDER BY booking_id";

        export(withEmpty, OutputFormat.CSV, sql, TransformPipeline.empty());
        exportFastPath(direct, OutputFormat.CSV, sql);

        assertThat(Files.readAllLines(withEmpty)).isEqualTo(Files.readAllLines(direct));
    }

    private static TransformPipeline pipeline(TransformSpec... specs) {
        return new TransformRegistry().build(List.of(specs));
    }

    private void export(Path output, OutputFormat format, String sql, TransformPipeline pipeline) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            setupTable(connection);
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, sql, 1000);
            List<ResultSetColumn> outputColumns = pipeline.outputSchema(columns);
            ExportOptions options = options(output, format);
            try (RowWriter writer = new RowWriterFactory().create(options, outputColumns, !pipeline.isEmpty())) {
                new JdbcExporter().export(connection, sql, 1000, null, writer,
                    JdbcExporter.ProgressListener.NONE, columns, pipeline);
            }
        }
    }

    private void exportFastPath(Path output, OutputFormat format, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            setupTable(connection);
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, sql, 1000);
            try (RowWriter writer = new RowWriterFactory().create(options(output, format), columns)) {
                new JdbcExporter().export(connection, sql, 1000, null, writer);
            }
        }
    }

    private static ExportOptions options(Path output, OutputFormat format) {
        return new ExportOptions("jdbc:duckdb:", "test", null, "sql", null, format, output.toString(),
            1000, null, null, false, false, false, false, false, true, "", "SNAPPY");
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
