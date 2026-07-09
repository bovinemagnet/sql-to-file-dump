package com.example.jdbcexport.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TIME WITH TIME ZONE coverage for {@link JdbcValueReader} (issue #18, fix #3). DuckDB cannot read
 * {@code TIMETZ} over JDBC, so PostgreSQL (via Testcontainers) is the only available driver that
 * both understands {@link Types#TIME_WITH_TIMEZONE} and exposes {@code getObject(OffsetTime.class)}.
 *
 * <p>Requires Docker; skipped when unavailable, matching {@code PostgresExportIntegrationTest}.
 */
class JdbcValueReaderPostgresTest {

    private TimeZone originalZone;

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
    void timeWithTimeZoneRetainsOffset() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT TIME WITH TIME ZONE '12:34:56+10:00' AS tmtz")) {
                rs.next();
                Object value = JdbcValueReader.readAsObject(rs, 1, Types.TIME_WITH_TIMEZONE);
                assertThat(value).isEqualTo("12:34:56+10:00");
            }
        }
    }
}
