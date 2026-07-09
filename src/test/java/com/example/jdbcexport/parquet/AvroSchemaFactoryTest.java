package com.example.jdbcexport.parquet;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvroSchemaFactoryTest {

    @Test
    void zonelessTimestampUsesLocalTimestampMicros() {
        Schema field = nonNullFieldSchema(Types.TIMESTAMP, "TIMESTAMP");
        assertThat(field.getLogicalType()).isEqualTo(LogicalTypes.localTimestampMicros());
    }

    @Test
    void timestampWithTimeZoneUsesTimestampMicros() {
        Schema field = nonNullFieldSchema(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE");
        assertThat(field.getLogicalType()).isEqualTo(LogicalTypes.timestampMicros());
    }

    @Test
    void floatTypeUsesDoubleSchemaBecauseJdbcFloatIsDoublePrecision() {
        Schema field = nonNullFieldSchema(Types.FLOAT, "FLOAT");
        assertThat(field.getType()).isEqualTo(Schema.Type.DOUBLE);
    }

    @Test
    void realTypeUsesFloatSchema() {
        Schema field = nonNullFieldSchema(Types.REAL, "REAL");
        assertThat(field.getType()).isEqualTo(Schema.Type.FLOAT);
    }

    private static Schema nonNullFieldSchema(int jdbcType, String typeName) {
        ResultSetColumn column = new ResultSetColumn(1, "c", "c", jdbcType, typeName, 0, 0, true);
        Schema schema = AvroSchemaFactory.buildSchema(List.of(column)).schema();
        Schema fieldSchema = schema.getField("c").schema();
        // Fields are a nullable union [null, X]; return the non-null branch.
        return fieldSchema.getTypes().stream()
            .filter(t -> t.getType() != Schema.Type.NULL)
            .findFirst()
            .orElseThrow();
    }
}
