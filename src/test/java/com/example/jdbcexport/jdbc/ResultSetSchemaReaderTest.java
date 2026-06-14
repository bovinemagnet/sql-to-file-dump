package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultSetSchemaReaderTest {

    @Test
    void readsColumnMetadata() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(
                connection, "SELECT 1 AS first_col, 'x' AS second_col", 100);

            assertThat(columns).hasSize(2);
            assertThat(columns.get(0).index()).isEqualTo(1);
            assertThat(columns.get(0).outputName()).isEqualTo("first_col");
            assertThat(columns.get(1).outputName()).isEqualTo("second_col");
        }
    }

    @Test
    void failsOnDuplicateColumnLabels() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            assertThatThrownBy(() ->
                ResultSetSchemaReader.readColumns(connection, "SELECT 1 AS id, 2 AS id", 100))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("Duplicate output column name detected: id")
                .hasMessageContaining("explicit SQL aliases")
                .extracting(e -> ((ExportException) e).getExitCode())
                .isEqualTo(ExitCodes.SCHEMA_ERROR);
        }
    }

    @Test
    void detectsDuplicatesCaseInsensitively() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            assertThatThrownBy(() ->
                ResultSetSchemaReader.readColumns(connection, "SELECT 1 AS id, 2 AS \"ID\"", 100))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("Duplicate output column name detected");
        }
    }
}
