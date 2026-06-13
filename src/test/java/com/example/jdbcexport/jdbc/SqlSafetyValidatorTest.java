package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExportException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSafetyValidatorTest {

    @Test
    void acceptsSelectQuery() {
        assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("SELECT * FROM foo"));
    }

    @Test
    void acceptsLowercaseSelect() {
        assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("select id from foo"));
    }

    @Test
    void acceptsWithQuery() {
        assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("WITH cte AS (SELECT 1) SELECT * FROM cte"));
    }

    @Test
    void rejectsInsert() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("INSERT INTO foo VALUES (1)"))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void rejectsDelete() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("DELETE FROM foo"))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate(""))
            .isInstanceOf(ExportException.class);
    }
}
