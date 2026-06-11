package com.example.jdbcexport.parquet;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.apache.avro.util.Utf8;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

public final class AvroValueMapper {

    private AvroValueMapper() {
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
            case Types.FLOAT, Types.REAL -> {
                float value = rs.getFloat(index);
                yield rs.wasNull() ? null : value;
            }
            case Types.DOUBLE -> {
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
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                Timestamp value = rs.getTimestamp(index);
                yield value == null ? null : value.toInstant().toEpochMilli() * 1000L;
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] value = rs.getBytes(index);
                yield value == null ? null : ByteBuffer.wrap(value);
            }
            case Types.DECIMAL, Types.NUMERIC -> {
                BigDecimal value = rs.getBigDecimal(index);
                yield value == null ? null : new Utf8(value.toPlainString());
            }
            default -> {
                String value = rs.getString(index);
                yield rs.wasNull() ? null : new Utf8(value);
            }
        };
    }
}
