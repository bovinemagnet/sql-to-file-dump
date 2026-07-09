package com.example.jdbcexport.jdbc;

import java.sql.Types;

/**
 * The canonical Java shape a JDBC value takes once read via {@link JdbcValueReader#readAsObject}.
 *
 * <p>This is the representation seen by the outbound transformation pipeline: dates, times,
 * timestamps and binary values are normalised to {@link #STRING} (ISO-8601 / Base64) and decimals
 * to {@link #DECIMAL} (a {@link java.math.BigDecimal}). Keeping a single canonical shape means
 * transform authors and every writer agree on the value type for a column without re-reading the
 * {@code ResultSet}.
 */
public enum ValueKind {
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    DECIMAL,
    STRING;

    public static ValueKind fromJdbcType(int jdbcType) {
        return switch (jdbcType) {
            case Types.BOOLEAN, Types.BIT -> BOOLEAN;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> INT;
            case Types.BIGINT -> LONG;
            case Types.REAL -> FLOAT;
            case Types.FLOAT, Types.DOUBLE -> DOUBLE;
            case Types.DECIMAL, Types.NUMERIC -> DECIMAL;
            default -> STRING;
        };
    }
}
