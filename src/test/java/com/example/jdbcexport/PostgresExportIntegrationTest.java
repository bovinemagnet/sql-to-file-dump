package com.example.jdbcexport;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.jdbc.JdbcConnectionFactory;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.example.jdbcexport.writer.RowWriter;
import com.example.jdbcexport.writer.RowWriterFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresExportIntegrationTest {

    @Test
    void exportsCsvFromPostgres(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("postgres.csv");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();

            try (Connection setupConnection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var statement = setupConnection.createStatement()) {
                    statement.execute("CREATE TABLE bookings (booking_id TEXT PRIMARY KEY, attendees INTEGER)");
                    statement.execute("INSERT INTO bookings VALUES ('P001', 2), ('P002', 5)");
                }
            }

            try (Connection connection = JdbcConnectionFactory.connect(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                String sql = "SELECT booking_id, attendees FROM bookings ORDER BY booking_id";
                List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, sql, 1000);
                ExportOptions options = new ExportOptions(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    sql,
                    null,
                    OutputFormat.CSV,
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

        List<String> lines = Files.readAllLines(output);
        assertThat(lines).containsExactly(
            "booking_id,attendees",
            "P001,2",
            "P002,5"
        );
    }
}
