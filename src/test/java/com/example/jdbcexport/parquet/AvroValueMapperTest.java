package com.example.jdbcexport.parquet;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the Parquet fast-path value mapping (issue #18). Timestamps must map to
 * host-independent micros; SQL FLOAT must keep double precision. The JVM zone is forced to a
 * non-UTC zone so a host-dependent conversion would produce a different value.
 */
class AvroValueMapperTest {

    private static final TimeZone NON_UTC = TimeZone.getTimeZone("Australia/Brisbane");

    private TimeZone originalZone;

    @BeforeAll
    static void registerDriver() throws Exception {
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
    void zonelessTimestampMapsToHostIndependentLocalMicros() throws Exception {
        long expected = LocalDateTime.of(2024, 6, 1, 12, 34, 56).toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT TIMESTAMP '2024-06-01 12:34:56' AS ts")) {
            rs.next();
            Object value = AvroValueMapper.readValue(rs, column(Types.TIMESTAMP, "TIMESTAMP"));
            assertThat(value).isEqualTo(expected);
        }
    }

    @Test
    void timestampWithTimeZoneMapsToInstantMicros() throws Exception {
        long expected = Instant.parse("2024-06-01T02:34:56Z").getEpochSecond() * 1_000_000L;
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT TIMESTAMPTZ '2024-06-01 12:34:56+10:00' AS ts")) {
            rs.next();
            Object value = AvroValueMapper.readValue(rs, column(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE"));
            assertThat(value).isEqualTo(expected);
        }
    }

    @Test
    void zonelessTimestampKeepsMicrosecondPrecision() throws Exception {
        // Issue #21: sub-millisecond digits must survive; a millis-based conversion would
        // truncate .123456 to .123000.
        long expected = LocalDateTime.of(2024, 1, 1, 12, 0, 0, 123_456_000).toEpochSecond(ZoneOffset.UTC) * 1_000_000L + 123_456L;
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT TIMESTAMP '2024-01-01 12:00:00.123456' AS ts")) {
            rs.next();
            Object value = AvroValueMapper.readValue(rs, column(Types.TIMESTAMP, "TIMESTAMP"));
            assertThat(value).isEqualTo(expected);
        }
    }

    @Test
    void timestampWithTimeZoneKeepsMicrosecondPrecision() throws Exception {
        long expected = Instant.parse("2024-01-01T12:00:00.123456Z").getEpochSecond() * 1_000_000L + 123_456L;
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT TIMESTAMPTZ '2024-01-01 12:00:00.123456+00:00' AS ts")) {
            rs.next();
            Object value = AvroValueMapper.readValue(rs, column(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE"));
            assertThat(value).isEqualTo(expected);
        }
    }

    @Test
    void floatTypeMapsAtDoublePrecision() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 3.141592653589793::DOUBLE AS d")) {
            rs.next();
            Object value = AvroValueMapper.readValue(rs, column(Types.FLOAT, "FLOAT"));
            assertThat(value).isInstanceOf(Double.class).isEqualTo(3.141592653589793);
        }
    }

    private static ResultSetColumn column(int jdbcType, String typeName) {
        return new ResultSetColumn(1, "c", "c", jdbcType, typeName, 0, 0, true);
    }
}
