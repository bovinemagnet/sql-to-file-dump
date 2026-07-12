package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void readsSchemaWithoutExecutingTheQuery() throws Exception {
        // Issue #31: pre-reading the schema by executing with setMaxRows(1) pays the full cost
        // of the query, which the export then pays again. PreparedStatement.getMetaData()
        // obtains the schema without any execution.
        AtomicInteger executions = new AtomicInteger();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            Connection counting = countingConnection(connection, executions, false);

            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(
                counting, "SELECT 1 AS first_col, 'x' AS second_col", 100);

            assertThat(columns).hasSize(2);
            assertThat(columns.get(0).outputName()).isEqualTo("first_col");
            assertThat(executions).hasValue(0);
        }
    }

    @Test
    void fallsBackToLimitedExecutionWhenPreparedMetadataIsUnavailable() throws Exception {
        // Some drivers return null from getMetaData() before execution; the reader must then
        // fall back to executing once with setMaxRows(1).
        AtomicInteger executions = new AtomicInteger();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            Connection counting = countingConnection(connection, executions, true);

            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(
                counting, "SELECT 1 AS first_col, 'x' AS second_col", 100);

            assertThat(columns).hasSize(2);
            assertThat(columns.get(1).outputName()).isEqualTo("second_col");
            assertThat(executions).hasValue(1);
        }
    }

    /**
     * Wraps a real DuckDB connection so prepared statements count {@code executeQuery} calls;
     * when {@code nullMetaData} is true, {@code getMetaData()} reports {@code null} to simulate
     * a driver that cannot describe a statement before execution.
     */
    private static Connection countingConnection(Connection real, AtomicInteger executions, boolean nullMetaData) {
        ClassLoader loader = ResultSetSchemaReaderTest.class.getClassLoader();
        return (Connection) Proxy.newProxyInstance(loader, new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            Object result = invoke(real, method, args);
            if (!"prepareStatement".equals(method.getName())) {
                return result;
            }
            PreparedStatement statement = (PreparedStatement) result;
            return Proxy.newProxyInstance(loader, new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                if (nullMetaData && "getMetaData".equals(m.getName())) {
                    return null;
                }
                if ("executeQuery".equals(m.getName())) {
                    executions.incrementAndGet();
                }
                return invoke(statement, m, a);
            });
        });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
