package com.example.jdbcexport.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;

class ValueKindTest {

    @Test
    void floatTypeMapsToDoubleBecauseJdbcFloatIsDoublePrecision() {
        assertThat(ValueKind.fromJdbcType(Types.FLOAT)).isEqualTo(ValueKind.DOUBLE);
    }

    @Test
    void realTypeStaysSinglePrecisionFloat() {
        assertThat(ValueKind.fromJdbcType(Types.REAL)).isEqualTo(ValueKind.FLOAT);
    }

    @Test
    void doubleTypeStaysDouble() {
        assertThat(ValueKind.fromJdbcType(Types.DOUBLE)).isEqualTo(ValueKind.DOUBLE);
    }
}
