package com.example.jdbcexport.parquet;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void decimalUsesDecimalLogicalType() {
        // Issue #22: NUMERIC(12,2) must carry decimal(12,2), not fall through to string.
        ResultSetColumn column = new ResultSetColumn(1, "c", "c", Types.NUMERIC, "NUMERIC", 12, 2, true);
        Schema field = nonNullFieldSchema(column);
        assertThat(field.getType()).isEqualTo(Schema.Type.BYTES);
        assertThat(field.getLogicalType()).isEqualTo(LogicalTypes.decimal(12, 2));
    }

    @Test
    void decimalWithoutUsablePrecisionFallsBackToString() {
        // Some drivers report precision 0 for unconstrained NUMBER/NUMERIC; a decimal
        // logical type cannot be built from that, so keep the lossless string form.
        ResultSetColumn column = new ResultSetColumn(1, "c", "c", Types.NUMERIC, "NUMERIC", 0, 0, true);
        Schema field = nonNullFieldSchema(column);
        assertThat(field.getType()).isEqualTo(Schema.Type.STRING);
    }

    @Test
    void nonAsciiAliasSanitizesToValidAvroName() {
        ResultSetColumn column = new ResultSetColumn(1, "café", "café", Types.VARCHAR, "VARCHAR", 0, 0, true);
        Schema schema = AvroSchemaFactory.buildSchema(List.of(column)).schema();
        assertThat(schema.getField("caf_")).isNotNull();
    }

    @Test
    void collidingSanitizedNamesFailWithSchemaError() {
        ResultSetColumn first = new ResultSetColumn(1, "café", "café", Types.VARCHAR, "VARCHAR", 0, 0, true);
        ResultSetColumn second = new ResultSetColumn(2, "caf_", "caf_", Types.VARCHAR, "VARCHAR", 0, 0, true);
        assertThatThrownBy(() -> AvroSchemaFactory.buildSchema(List.of(first, second)))
            .isInstanceOf(ExportException.class)
            .hasFieldOrPropertyWithValue("exitCode", ExitCodes.SCHEMA_ERROR)
            .hasMessageContaining("caf_");
    }

    private static Schema nonNullFieldSchema(int jdbcType, String typeName) {
        return nonNullFieldSchema(new ResultSetColumn(1, "c", "c", jdbcType, typeName, 0, 0, true));
    }

    private static Schema nonNullFieldSchema(ResultSetColumn column) {
        Schema schema = AvroSchemaFactory.buildSchema(List.of(column)).schema();
        Schema fieldSchema = schema.getField("c").schema();
        // Fields are a nullable union [null, X]; return the non-null branch.
        return fieldSchema.getTypes().stream()
            .filter(t -> t.getType() != Schema.Type.NULL)
            .findFirst()
            .orElseThrow();
    }
}
