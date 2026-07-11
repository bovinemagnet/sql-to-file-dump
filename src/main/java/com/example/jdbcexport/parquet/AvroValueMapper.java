package com.example.jdbcexport.parquet;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.apache.avro.util.Utf8;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class AvroValueMapper {

    private AvroValueMapper() {
    }

    /**
     * Map a canonical transform-path value (see {@link com.example.jdbcexport.jdbc.ValueKind}) to its
     * Avro representation for the schema built by
     * {@link AvroSchemaFactory#buildCanonicalSchema(java.util.List)}.
     */
    public static Object mapCanonical(Object value, com.example.jdbcexport.jdbc.ValueKind kind) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case BOOLEAN -> value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
            case INT -> ((Number) value).intValue();
            case LONG -> ((Number) value).longValue();
            case FLOAT -> ((Number) value).floatValue();
            case DOUBLE -> ((Number) value).doubleValue();
            case DECIMAL -> new Utf8(value instanceof BigDecimal bd ? bd.toPlainString() : value.toString());
            case STRING -> new Utf8(value.toString());
        };
    }

    public static Object readValue(ResultSet rs, ResultSetColumn column) throws SQLException {
        int jdbcType = column.jdbcType();
        int index = column.index();

        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> {
                int value = rs.getInt(index);
                yield rs.wasNull() ? null : value;
            }
            case Types.BIGINT -> {
                long value = rs.getLong(index);
                yield rs.wasNull() ? null : value;
            }
            case Types.REAL -> {
                float value = rs.getFloat(index);
                yield rs.wasNull() ? null : value;
            }
            case Types.FLOAT, Types.DOUBLE -> {
                // SQL FLOAT is double precision (only REAL is single); getFloat would truncate it.
                double value = rs.getDouble(index);
                yield rs.wasNull() ? null : value;
            }
            case Types.BOOLEAN, Types.BIT -> {
                boolean value = rs.getBoolean(index);
                yield rs.wasNull() ? null : value;
            }
            case Types.DATE -> {
                Date value = rs.getDate(index);
                yield value == null ? null : (int) value.toLocalDate().toEpochDay();
            }
            case Types.TIMESTAMP -> {
                // Zone-less wall-clock: read as LocalDateTime and encode as local-timestamp-micros
                // in a fixed UTC reference so the result never depends on the JVM default zone.
                LocalDateTime value = rs.getObject(index, LocalDateTime.class);
                yield value == null ? null
                    : value.toEpochSecond(ZoneOffset.UTC) * 1_000_000L + value.getNano() / 1000L;
            }
            case Types.TIMESTAMP_WITH_TIMEZONE -> {
                OffsetDateTime value = rs.getObject(index, OffsetDateTime.class);
                if (value == null) {
                    yield null;
                }
                Instant instant = value.toInstant();
                yield instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1000L;
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] value = rs.getBytes(index);
                yield value == null ? null : ByteBuffer.wrap(value);
            }
            case Types.DECIMAL, Types.NUMERIC -> {
                BigDecimal value = rs.getBigDecimal(index);
                if (value == null) {
                    yield null;
                }
                if (!AvroSchemaFactory.usesDecimalLogicalType(column)) {
                    yield new Utf8(value.toPlainString());
                }
                // Decimal logical type: unscaled two's-complement bytes at the declared
                // scale. Rescaling is exact (drivers may strip trailing zeros); a value
                // that genuinely exceeds the declared scale must fail, not round silently.
                yield ByteBuffer.wrap(value.setScale(column.scale(), RoundingMode.UNNECESSARY)
                    .unscaledValue().toByteArray());
            }
            default -> {
                String value = rs.getString(index);
                yield rs.wasNull() ? null : new Utf8(value);
            }
        };
    }
}
