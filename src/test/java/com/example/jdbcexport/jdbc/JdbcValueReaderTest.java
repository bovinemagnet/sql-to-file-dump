package com.example.jdbcexport.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link JdbcValueReader} temporal and numeric handling (issue #18).
 *
 * <p>Every test runs with the default JVM time zone forced to a non-UTC zone so that any
 * host-dependent conversion is exposed: the canonical output must be identical regardless of
 * the JVM zone.
 */
class JdbcValueReaderTest {

    private static final TimeZone NON_UTC = TimeZone.getTimeZone("Australia/Brisbane");

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
        TimeZone.setDefault(NON_UTC);
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    @Test
    void zonelessTimestampIsHostIndependentWallClock() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT TIMESTAMP '2024-06-01 12:34:56' AS ts")) {
            rs.next();
            Object value = JdbcValueReader.readAsObject(rs, 1, Types.TIMESTAMP);
            assertThat(value).isEqualTo("2024-06-01T12:34:56");
        }
    }

    @Test
    void timestampWithTimeZoneNormalisedToInstant() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT TIMESTAMPTZ '2024-06-01 12:34:56+10:00' AS ts")) {
            rs.next();
            Object value = JdbcValueReader.readAsObject(rs, 1, Types.TIMESTAMP_WITH_TIMEZONE);
            assertThat(value).isEqualTo("2024-06-01T02:34:56Z");
        }
    }

    @Test
    void plainTimeIsUnchanged() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT TIME '12:34:56' AS tm")) {
            rs.next();
            Object value = JdbcValueReader.readAsObject(rs, 1, Types.TIME);
            assertThat(value).isEqualTo("12:34:56");
        }
    }

    @Test
    void floatTypeIsReadAtDoublePrecision() throws Exception {
        // JDBC maps SQL FLOAT to double precision; reading it via getFloat silently truncates.
        // The column is 8-byte here and reported to the reader as Types.FLOAT.
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 3.141592653589793::DOUBLE AS d")) {
            rs.next();
            Object value = JdbcValueReader.readAsObject(rs, 1, Types.FLOAT);
            assertThat(value).isInstanceOf(Double.class).isEqualTo(3.141592653589793);
        }
    }

    @Test
    void realTypeStaysSinglePrecision() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 3.141592653589793::DOUBLE AS d")) {
            rs.next();
            Object value = JdbcValueReader.readAsObject(rs, 1, Types.REAL);
            assertThat(value).isInstanceOf(Float.class);
        }
    }
}
