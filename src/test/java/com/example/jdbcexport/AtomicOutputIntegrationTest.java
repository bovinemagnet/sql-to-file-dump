package com.example.jdbcexport;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.writer.ExportWriteResult;
import com.example.jdbcexport.writer.RowWriter;
import com.example.jdbcexport.writer.RowWriterFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #24: a failed export must never leave a syntactically valid but truncated output file,
 * and must never clobber a pre-existing target. Output is written to a temporary file and
 * renamed onto the target only on success.
 */
class AtomicOutputIntegrationTest {

    private static final String QUERY = "SELECT booking_id, room_code, attendees FROM bookings ORDER BY booking_id";

    @BeforeAll
    static void registerDriver() throws Exception {
        // A @QuarkusTest elsewhere in the suite can trigger DriverManager's one-time
        // ServiceLoader scan under the Quarkus classloader, leaving the DuckDB driver
        // invisible to this classloader. Register it explicitly.
        DriverManager.registerDriver(new org.duckdb.DuckDBDriver());
    }

    @ParameterizedTest
    @EnumSource(OutputFormat.class)
    void failedExportLeavesNoOutputFile(OutputFormat format, @TempDir Path tempDir) {
        Path output = tempDir.resolve("out." + extensionOf(format));

        assertThatThrownBy(() -> export(output, format, 1))
            .isInstanceOf(ExportException.class);

        assertThat(output).doesNotExist();
        assertThat(filesIn(tempDir)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(OutputFormat.class)
    void failedExportLeavesExistingTargetUntouched(OutputFormat format, @TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out." + extensionOf(format));
        Files.writeString(output, "yesterday's good export");

        assertThatThrownBy(() -> export(output, format, 1))
            .isInstanceOf(ExportException.class);

        assertThat(Files.readString(output)).isEqualTo("yesterday's good export");
        assertThat(filesIn(tempDir)).containsExactly(output);
    }

    @ParameterizedTest
    @EnumSource(OutputFormat.class)
    void successfulExportProducesOnlyTheTargetFile(OutputFormat format, @TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out." + extensionOf(format));

        export(output, format, -1);

        assertThat(output).exists();
        assertThat(filesIn(tempDir)).containsExactly(output);
    }

    @Test
    void successfulExportReplacesExistingTargetContent(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.csv");
        Files.writeString(output, "yesterday's good export");

        export(output, OutputFormat.CSV, -1);

        List<String> lines = Files.readAllLines(output);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo("booking_id,room_code,attendees");
    }

    @Test
    void writeResultReportsTheFinalPath(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.json");

        ExportWriteResult result = export(output, OutputFormat.JSON, -1);

        assertThat(result.output()).isEqualTo(output);
        assertThat(result.rowCount()).isEqualTo(3);
    }

    private static String extensionOf(OutputFormat format) {
        return format.name().toLowerCase(Locale.ROOT);
    }

    private static List<Path> filesIn(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Runs an export against an in-memory DuckDB table of three rows. When {@code failAfterRows}
     * is non-negative, the writer throws once that many rows have been written, simulating a
     * mid-stream failure (e.g. a dropped connection). Returns the captured write result.
     */
    private ExportWriteResult export(Path output, OutputFormat format, int failAfterRows) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            setupTable(connection);
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, QUERY, 1000);
            ExportOptions options = new ExportOptions(
                "jdbc:duckdb:", "test", QUERY, null, format, output.toString(),
                1000, null, null, true, false, false, false, false, true, "", "SNAPPY");
            try (ObservingWriter writer = new ObservingWriter(
                    new RowWriterFactory().create(options, columns), failAfterRows)) {
                new JdbcExporter().export(connection, QUERY, 1000, null, writer);
                return writer.result;
            }
        }
    }

    private void setupTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE bookings (booking_id VARCHAR, room_code VARCHAR, attendees INTEGER)");
            statement.execute("INSERT INTO bookings VALUES " +
                "('B001', 'A-101', 10), ('B002', 'A-101', 15), ('B003', 'B-202', 4)");
        }
    }

    /** Delegating writer that fails mid-stream after N rows and captures the finish result. */
    private static final class ObservingWriter implements RowWriter {

        private final RowWriter delegate;
        private final int failAfterRows;
        private int written;
        private ExportWriteResult result;

        ObservingWriter(RowWriter delegate, int failAfterRows) {
            this.delegate = delegate;
            this.failAfterRows = failAfterRows;
        }

        @Override
        public void start(ResultSetMetaData metaData) throws Exception {
            delegate.start(metaData);
        }

        @Override
        public void writeRow(ResultSet resultSet) throws Exception {
            if (failAfterRows >= 0 && written >= failAfterRows) {
                throw new IllegalStateException("Simulated mid-stream failure after " + written + " rows");
            }
            delegate.writeRow(resultSet);
            written++;
        }

        @Override
        public void writeRow(Row row) throws Exception {
            delegate.writeRow(row);
        }

        @Override
        public ExportWriteResult finish() throws Exception {
            result = delegate.finish();
            return result;
        }

        @Override
        public void close() throws Exception {
            delegate.close();
        }
    }
}
