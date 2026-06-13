package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExportException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResolverTest {

    @Test
    void returnsDirectPassword() {
        assertThat(PasswordResolver.resolve("secret", null)).isEqualTo("secret");
    }

    @Test
    void throwsWhenBothSpecified() {
        assertThatThrownBy(() -> PasswordResolver.resolve("p1", "ENV_VAR"))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void returnsNullWhenNeitherSpecified() {
        assertThat(PasswordResolver.resolve(null, null)).isNull();
    }
}
