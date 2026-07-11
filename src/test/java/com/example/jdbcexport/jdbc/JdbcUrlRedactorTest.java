package com.example.jdbcexport.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcUrlRedactorTest {

    @Test
    void redactsInlineUserPasswordCredentials() {
        assertThat(JdbcUrlRedactor.redact("jdbc:postgresql://bob:hunter2@db:5432/appdb"))
            .isEqualTo("jdbc:postgresql://bob:*****@db:5432/appdb");
    }

    @Test
    void redactsPasswordQueryParameter() {
        assertThat(JdbcUrlRedactor.redact("jdbc:postgresql://db/appdb?password=hunter2&ssl=true"))
            .isEqualTo("jdbc:postgresql://db/appdb?password=*****&ssl=true");
    }

    @Test
    void redactsSemicolonDelimitedPasswordProperty() {
        assertThat(JdbcUrlRedactor.redact("jdbc:sqlserver://db;user=sa;password=hunter2;encrypt=true"))
            .isEqualTo("jdbc:sqlserver://db;user=sa;password=*****;encrypt=true");
    }

    @Test
    void redactsPwdPropertyCaseInsensitively() {
        assertThat(JdbcUrlRedactor.redact("jdbc:sqlserver://db;PWD=hunter2"))
            .isEqualTo("jdbc:sqlserver://db;PWD=*****");
    }

    @Test
    void redactsCredentialsEmbeddedInErrorMessages() {
        String message = "FATAL: connection refused for jdbc:postgresql://bob:hunter2@db:5432/appdb (timeout)";
        assertThat(JdbcUrlRedactor.redact(message))
            .isEqualTo("FATAL: connection refused for jdbc:postgresql://bob:*****@db:5432/appdb (timeout)")
            .doesNotContain("hunter2");
    }

    @Test
    void leavesUrlsWithoutCredentialsUntouched() {
        assertThat(JdbcUrlRedactor.redact("jdbc:postgresql://db:5432/appdb"))
            .isEqualTo("jdbc:postgresql://db:5432/appdb");
        assertThat(JdbcUrlRedactor.redact("jdbc:duckdb:")).isEqualTo("jdbc:duckdb:");
    }

    @Test
    void isNullSafe() {
        assertThat(JdbcUrlRedactor.redact(null)).isNull();
    }
}
