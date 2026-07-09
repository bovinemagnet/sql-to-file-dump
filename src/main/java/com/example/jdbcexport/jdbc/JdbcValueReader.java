package com.example.jdbcexport.jdbc;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Base64;

public final class JdbcValueReader {

    private JdbcValueReader() {
    }

    public static Object readAsObject(ResultSet rs, int columnIndex, int jdbcType) throws SQLException {
        return switch (jdbcType) {
            case Types.NULL -> null;
            case Types.BOOLEAN, Types.BIT -> {
                boolean value = rs.getBoolean(columnIndex);
                yield rs.wasNull() ? null : value;
            }
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> {
                int value = rs.getInt(columnIndex);
                yield rs.wasNull() ? null : value;
            }
            case Types.BIGINT -> {
                long value = rs.getLong(columnIndex);
                yield rs.wasNull() ? null : value;
            }
            case Types.REAL -> {
                float value = rs.getFloat(columnIndex);
                yield rs.wasNull() ? null : value;
            }
            case Types.FLOAT, Types.DOUBLE -> {
                // JDBC maps SQL FLOAT to double precision (only REAL is single); reading FLOAT via
                // getFloat would silently truncate an 8-byte column.
                double value = rs.getDouble(columnIndex);
                yield rs.wasNull() ? null : value;
            }
            case Types.DECIMAL, Types.NUMERIC -> rs.getBigDecimal(columnIndex);
            case Types.DATE -> {
                Date value = rs.getDate(columnIndex);
                yield value == null ? null : value.toLocalDate().toString();
            }
            case Types.TIME -> {
                Time value = rs.getTime(columnIndex);
                yield value == null ? null : value.toLocalTime().toString();
            }
            case Types.TIME_WITH_TIMEZONE -> {
                OffsetTime value = rs.getObject(columnIndex, OffsetTime.class);
                yield value == null ? null : value.toString();
            }
            case Types.TIMESTAMP -> {
                // Zone-less: read the wall-clock directly so the output does not depend on the JVM
                // default time zone (getTimestamp().toInstant() would shift it and add a false 'Z').
                LocalDateTime value = rs.getObject(columnIndex, LocalDateTime.class);
                yield value == null ? null : value.toString();
            }
            case Types.TIMESTAMP_WITH_TIMEZONE -> {
                OffsetDateTime value = rs.getObject(columnIndex, OffsetDateTime.class);
                yield value == null ? null : value.toString();
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] value = rs.getBytes(columnIndex);
                yield value == null ? null : Base64.getEncoder().encodeToString(value);
            }
            default -> {
                String value = rs.getString(columnIndex);
                yield rs.wasNull() ? null : value;
            }
        };
    }

    public static String readAsString(ResultSet rs, int columnIndex, int jdbcType, String nullValue) throws SQLException {
        Object value = readAsObject(rs, columnIndex, jdbcType);
        return stringify(value, nullValue);
    }

    /** Render a canonical value (see {@link #readAsObject}) as a string, using {@code nullValue} for nulls. */
    public static String stringify(Object value, String nullValue) {
        return value == null ? nullValue : stringify(value);
    }

    private static String stringify(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }
}
