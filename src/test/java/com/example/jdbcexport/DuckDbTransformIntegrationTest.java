package com.example.jdbcexport;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.example.jdbcexport.transform.ErrorStrategy;
import com.example.jdbcexport.transform.OutboundTransformer;
import com.example.jdbcexport.transform.TransformConfig;
import com.example.jdbcexport.transform.TransformContext;
import com.example.jdbcexport.transform.TransformInput;
import com.example.jdbcexport.transform.TransformPipeline;
import com.example.jdbcexport.transform.TransformRegistry;
import com.example.jdbcexport.transform.TransformResult;
import com.example.jdbcexport.transform.TransformSpec;
import com.example.jdbcexport.transform.builtin.MaskTransform;
import com.example.jdbcexport.writer.RowWriter;
import com.example.jdbcexport.writer.RowWriterFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuckDbTransformIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Test
    void maskWithKeepOriginalIsRejectedBeforeAnyRowsAreWritten(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.csv");
        // A masking pipeline under keepOriginal: on a step failure the pre-mask snapshot would leak the
        // unmasked value, so the pipeline must be rejected up front rather than exporting anything.
        TransformPipeline pipeline = new TransformRegistry().build(new TransformConfig(
            List.of(new TransformSpec("mask", Map.of("column", "booking_id"))),
            ErrorStrategy.KEEP_ORIGINAL, null));
        String sql = "SELECT booking_id, room_code FROM bookings ORDER BY booking_id";

        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            setupTable(connection);
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, sql, 1000);
            assertThatThrownBy(() -> pipeline.outputSchema(columns))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("keepOriginal")
                .hasMessageContaining("booking_id");
        }
        assertThat(Files.exists(output)).isFalse();
    }

    // --- Issue #40 item 3: error strategy end-to-end through a real CSV writer -------------

    @Test
    void failStrategyLeavesNoOutputFileWhenATransformStepFails(@TempDir Path tempDir) throws Exception {
        // A pipeline that masks booking_id and then fails on the B-202 room, under the default
        // "fail" strategy: atomic output (issue #24) writes to a temp sibling and renames onto
        // the target only on success, so even though two masked rows were already streamed to
        // the temp file before the third row failed, the target path must never appear.
        Path output = tempDir.resolve("fail-strategy.csv");
        TransformPipeline pipeline = maskThenFailOnRoomPipeline(ErrorStrategy.FAIL);
        String sql = "SELECT booking_id, room_code FROM bookings ORDER BY booking_id";

        assertThatThrownBy(() -> export(output, OutputFormat.CSV, sql, pipeline))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("fail-on-room");

        assertThat(output).doesNotExist();
    }

    @Test
    void skipRowStrategyDropsTheFailingRowAndKeepsMaskedRows(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("skip-row-strategy.csv");
        TransformPipeline pipeline = maskThenFailOnRoomPipeline(ErrorStrategy.SKIP_ROW);
        String sql = "SELECT booking_id, room_code FROM bookings ORDER BY booking_id";

        export(output, OutputFormat.CSV, sql, pipeline);

        List<String> lines = Files.readAllLines(output);
        assertThat(lines).containsExactly(
            "booking_id,room_code",
            "***,A-101",
            "***,A-101");
        assertThat(pipeline.metrics().snapshot().rowsDroppedByError()).isEqualTo(1);
    }

    @Test
    void keepOriginalStrategyIsRejectedEvenWhenAFailingStepIsPresent(@TempDir Path tempDir) throws Exception {
        // Same pipeline shape as the fail/skipRow tests above, but under keepOriginal the mask
        // (issue #17) must be rejected up front regardless of whether a later step ever fails.
        Path output = tempDir.resolve("keep-original-strategy.csv");
        TransformPipeline pipeline = maskThenFailOnRoomPipeline(ErrorStrategy.KEEP_ORIGINAL);
        String sql = "SELECT booking_id, room_code FROM bookings ORDER BY booking_id";

        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            setupTable(connection);
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, sql, 1000);
            assertThatThrownBy(() -> pipeline.outputSchema(columns))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("keepOriginal")
                .hasMessageContaining("booking_id");
        }
        assertThat(Files.exists(output)).isFalse();
    }

    private static TransformPipeline maskThenFailOnRoomPipeline(ErrorStrategy strategy) {
        return new TransformPipeline(List.of(
            new TransformPipeline.Step("mask",
                MaskTransform.PROVIDER.create(new TransformSpec("mask", Map.of("column", "booking_id")))),
            new TransformPipeline.Step("fail-on-room", failOnRoomCode("B-202"))
        ), strategy);
    }

    /** A pass-through-schema transformer that fails on rows matching a specific room code. */
    private static OutboundTransformer failOnRoomCode(String triggerRoomCode) {
        return new OutboundTransformer() {
            @Override
            public String name() {
                return "fail-on-room";
            }

            @Override
            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return columns;
            }

            @Override
            public TransformResult transform(TransformInput input, TransformContext context) {
                if (triggerRoomCode.equals(input.row().get("room_code"))) {
                    throw new IllegalStateException("simulated failure for room " + triggerRoomCode);
                }
                return TransformResult.keep(input.row());
            }
        };
    }

    // --- Issue #40 item 9: fast-path vs transform-path parity for JSON, and the documented
    // --- Parquet divergence (transform mode stores canonical strings, not native logical types) --

    @Test
    void fastPathAndTransformPathProduceEquivalentJsonValues(@TempDir Path tempDir) throws Exception {
        Path fastPathOutput = tempDir.resolve("fast.json");
        Path transformOutput = tempDir.resolve("transform.json");
        String sql = "SELECT booking_id, attendees, amount, cancelled FROM bookings ORDER BY booking_id";

        exportFastPath(fastPathOutput, OutputFormat.JSON, sql);
        // A rename is not a value-only no-op, but it touches only one column's name — every other
        // field, and the renamed field's value/type, must still match the fast path exactly.
        TransformPipeline pipeline = pipeline(new TransformSpec("rename", Map.of("from", "attendees", "to", "attendee_count")));
        export(transformOutput, OutputFormat.JSON, sql, pipeline);

        JsonNode fastRows = MAPPER.readTree(fastPathOutput.toFile());
        JsonNode transformRows = MAPPER.readTree(transformOutput.toFile());
        assertThat(transformRows).hasSize(fastRows.size());
        for (int i = 0; i < fastRows.size(); i++) {
            JsonNode fast = fastRows.get(i);
            JsonNode transformed = transformRows.get(i);
            assertThat(transformed.get("booking_id")).isEqualTo(fast.get("booking_id"));
            assertThat(transformed.get("amount")).isEqualTo(fast.get("amount"));
            assertThat(transformed.get("cancelled")).isEqualTo(fast.get("cancelled"));
            assertThat(transformed.get("attendee_count")).isEqualTo(fast.get("attendees"));
        }
    }

    @Test
    void parquetTransformPathStoresTimestampsAsCanonicalStringsUnlikeFastPath(@TempDir Path tempDir) throws Exception {
        // Documented caveat (see CLAUDE.md): in transform mode Parquet stores temporal values as
        // canonical strings rather than native Parquet logical types. This locks that divergence
        // in rather than silently expecting parity with the fast path.
        Path fastPathOutput = tempDir.resolve("fast-ts.parquet");
        Path transformOutput = tempDir.resolve("transform-ts.parquet");
        // A literal query, not the bookings table: setupTable() still runs (harmlessly) via the
        // export()/exportFastPath() helpers, but the row shape here is just the timestamp itself.
        String sql = "SELECT TIMESTAMP '2024-06-01 12:34:56' AS ts";

        exportFastPath(fastPathOutput, OutputFormat.PARQUET, sql);
        TransformPipeline pipeline = pipeline(new TransformSpec("rename", Map.of("from", "ts", "to", "ts_out")));
        export(transformOutput, OutputFormat.PARQUET, sql, pipeline);

        try (Connection verify = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = verify.createStatement()) {
            try (var rs = statement.executeQuery(
                "SELECT typeof(ts) FROM read_parquet('" + fastPathOutput.toAbsolutePath() + "') LIMIT 1")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("TIMESTAMP");
            }
            try (var rs = statement.executeQuery(
                "SELECT typeof(ts_out) FROM read_parquet('" + transformOutput.toAbsolutePath() + "') LIMIT 1")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("VARCHAR");
            }
        }
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
        return new ExportOptions("jdbc:duckdb:", "test", "sql", null, format, output.toString(),
            1000, null, null, false, false, false, false, false, true, "", false, false, "SNAPPY");
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
