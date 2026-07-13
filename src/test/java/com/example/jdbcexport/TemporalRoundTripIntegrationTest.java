package com.example.jdbcexport;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.example.jdbcexport.writer.RowWriter;
import com.example.jdbcexport.writer.RowWriterFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #40 item 1: DATE/TIME/TIMESTAMP/TIMESTAMPTZ round-trips through the JSON, NDJSON and CSV
 * writers, with the JVM default zone forced to a non-UTC zone. Parquet fidelity for the same
 * values is already covered by {@link DuckDbExportIntegrationTest} and {@code AvroValueMapperTest};
 * this class fills the gap for the writers that go through {@link com.example.jdbcexport.jdbc.JdbcValueReader}'s
 * canonical string form.
 */
class TemporalRoundTripIntegrationTest {

    private static final String SQL = "SELECT DATE '2024-06-01' AS d, TIME '12:34:56' AS t, "
        + "TIMESTAMP '2024-06-01 12:34:56' AS ts, TIMESTAMPTZ '2024-06-01 12:34:56+10:00' AS tstz";
    private static final String EXPECTED_DATE = "2024-06-01";
    private static final String EXPECTED_TIME = "12:34:56";
    private static final String EXPECTED_TIMESTAMP = "2024-06-01T12:34:56";
    private static final String EXPECTED_TIMESTAMPTZ = "2024-06-01T02:34:56Z";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TimeZone originalZone;

    @BeforeAll
    static void registerDriver() throws Exception {
        // See DuckDbExportIntegrationTest: register explicitly so DriverManager's one-time scan
        // under a Quarkus classloader elsewhere in the suite does not hide the driver here.
        DriverManager.registerDriver(new org.duckdb.DuckDBDriver());
    }

    @BeforeEach
    void forceNonUtcZone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Brisbane"));
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    @Test
    void jsonPreservesTemporalValuesUnderNonUtcHostZone(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("temporal.json");
        export(output, OutputFormat.JSON);

        JsonNode row = MAPPER.readTree(output.toFile()).get(0);
        assertThat(row.get("d").asText()).isEqualTo(EXPECTED_DATE);
        assertThat(row.get("t").asText()).isEqualTo(EXPECTED_TIME);
        assertThat(row.get("ts").asText()).isEqualTo(EXPECTED_TIMESTAMP);
        assertThat(row.get("tstz").asText()).isEqualTo(EXPECTED_TIMESTAMPTZ);
    }

    @Test
    void ndjsonPreservesTemporalValuesUnderNonUtcHostZone(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("temporal.ndjson");
        export(output, OutputFormat.NDJSON);

        JsonNode row = MAPPER.readTree(Files.readAllLines(output).get(0));
        assertThat(row.get("d").asText()).isEqualTo(EXPECTED_DATE);
        assertThat(row.get("t").asText()).isEqualTo(EXPECTED_TIME);
        assertThat(row.get("ts").asText()).isEqualTo(EXPECTED_TIMESTAMP);
        assertThat(row.get("tstz").asText()).isEqualTo(EXPECTED_TIMESTAMPTZ);
    }

    @Test
    void csvPreservesTemporalValuesUnderNonUtcHostZone(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("temporal.csv");
        export(output, OutputFormat.CSV);

        List<String> lines = Files.readAllLines(output);
        assertThat(lines.get(0)).isEqualTo("d,t,ts,tstz");
        assertThat(lines.get(1)).isEqualTo(String.join(",",
            EXPECTED_DATE, EXPECTED_TIME, EXPECTED_TIMESTAMP, EXPECTED_TIMESTAMPTZ));
    }

    private void export(Path output, OutputFormat format) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, SQL, 100);
            ExportOptions options = new ExportOptions(
                "jdbc:duckdb:", "test", SQL, null, format, output.toString(),
                100, null, null, false, false, false, false, false, true, "", false, false, "SNAPPY");
            try (RowWriter writer = new RowWriterFactory().create(options, columns)) {
                new JdbcExporter().export(connection, SQL, 100, null, writer);
            }
        }
    }
}
