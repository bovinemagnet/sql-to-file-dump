package com.example.jdbcexport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcExportApplicationTest {

    private static final String[] HTTP_PROPERTIES = {
        "quarkus.http.host-enabled", "quarkus.http.host", "quarkus.http.port"
    };

    @BeforeEach
    @AfterEach
    void clearHttpProperties() {
        for (String property : HTTP_PROPERTIES) {
            System.clearProperty(property);
        }
    }

    @Test
    void loopbackHostsAreRecognised() {
        assertThat(JdbcExportApplication.isLoopbackHost("localhost")).isTrue();
        assertThat(JdbcExportApplication.isLoopbackHost("127.0.0.1")).isTrue();
        assertThat(JdbcExportApplication.isLoopbackHost("127.0.0.53")).isTrue();
        assertThat(JdbcExportApplication.isLoopbackHost("::1")).isTrue();
        assertThat(JdbcExportApplication.isLoopbackHost("[::1]")).isTrue();
        assertThat(JdbcExportApplication.isLoopbackHost(null)).isTrue();
        assertThat(JdbcExportApplication.isLoopbackHost("")).isTrue();
    }

    @Test
    void nonLoopbackHostsAreRejected() {
        assertThat(JdbcExportApplication.isLoopbackHost("0.0.0.0")).isFalse();
        assertThat(JdbcExportApplication.isLoopbackHost("192.168.1.10")).isFalse();
        assertThat(JdbcExportApplication.isLoopbackHost("10.0.0.5")).isFalse();
        assertThat(JdbcExportApplication.isLoopbackHost("example.com")).isFalse();
    }

    @Test
    void hostnamesResemblingLoopbackLiteralsAreRejected() {
        // A hostname must never pass as loopback on its spelling alone: DNS decides
        // where it points, so only genuine IP literals may satisfy the guard.
        assertThat(JdbcExportApplication.isLoopbackHost("127.evil.example")).isFalse();
        assertThat(JdbcExportApplication.isLoopbackHost("127.0.0.1.evil.example")).isFalse();
        assertThat(JdbcExportApplication.isLoopbackHost("localhost.evil.example")).isFalse();
        assertThat(JdbcExportApplication.isLoopbackHost("::1.evil.example")).isFalse();
    }

    @Test
    void blankHostFallsBackToLoopbackDefault() {
        JdbcExportApplication.configureHttp(new String[] {"daemon", "--host="});

        // An empty quarkus.http.host means bind-all to the HTTP layer; it must never pass through.
        assertThat(System.getProperty("quarkus.http.host")).isEqualTo("localhost");
    }

    @Test
    void daemonAfterLeadingFlagIsDetected() {
        JdbcExportApplication.configureHttp(new String[] {"--verbose", "daemon"});

        assertThat(System.getProperty("quarkus.http.host-enabled")).isNull();
        assertThat(System.getProperty("quarkus.http.host")).isEqualTo("localhost");
    }

    @Test
    void daemonOptionsAreParsedWhenDaemonIsNotFirst() {
        JdbcExportApplication.configureHttp(new String[] {"--verbose", "daemon", "--port", "9099"});

        assertThat(System.getProperty("quarkus.http.port")).isEqualTo("9099");
        assertThat(System.getProperty("quarkus.http.host")).isEqualTo("localhost");
    }

    @Test
    void optionValueNamedDaemonDoesNotEnableDaemonMode() {
        JdbcExportApplication.configureHttp(new String[] {"--sql", "daemon", "--url", "jdbc:duckdb:"});

        assertThat(System.getProperty("quarkus.http.host-enabled")).isEqualTo("false");
        assertThat(System.getProperty("quarkus.http.host")).isNull();
    }

    @Test
    void plainCliExportStillDisablesHttp() {
        JdbcExportApplication.configureHttp(new String[] {"--url", "jdbc:duckdb:", "--sql", "select 1"});

        assertThat(System.getProperty("quarkus.http.host-enabled")).isEqualTo("false");
    }
}
