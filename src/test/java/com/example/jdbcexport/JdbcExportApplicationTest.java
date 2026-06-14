package com.example.jdbcexport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcExportApplicationTest {

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
}
