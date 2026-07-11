package com.example.jdbcexport.parquet;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AvroSchemaFactory {

    public record SchemaDefinition(Schema schema, List<String> fieldNames) {
    }

    private AvroSchemaFactory() {
    }

    public static SchemaDefinition buildSchema(List<ResultSetColumn> columns) {
        return build(columns, false);
    }

    /**
     * Build a schema for the transform path, where row values are in the canonical representation
     * (see {@link com.example.jdbcexport.jdbc.ValueKind}). Dates, times, timestamps and binary are
     * stored as strings and decimals as strings, matching what transforms and the text writers see.
     */
    public static SchemaDefinition buildCanonicalSchema(List<ResultSetColumn> columns) {
        return build(columns, true);
    }

    private static SchemaDefinition build(List<ResultSetColumn> columns, boolean canonical) {
        var fields = SchemaBuilder.record("ExportRecord")
            .namespace("com.example.jdbcexport")
            .fields();
        List<String> fieldNames = new ArrayList<>(columns.size());
        Set<String> seen = new HashSet<>();

        for (ResultSetColumn column : columns) {
            String fieldName = AvroFieldNameSanitizer.sanitize(column.outputName());
            if (!seen.add(fieldName)) {
                throw new ExportException(ExitCodes.SCHEMA_ERROR,
                    "Duplicate Parquet field name detected after Avro sanitization: " + fieldName + ". Use explicit SQL aliases.");
            }
            fieldNames.add(fieldName);
            Schema fieldSchema = canonical ? toCanonicalSchema(column.jdbcType()) : toAvroSchema(column);
            fields.name(fieldName)
                .type(nullable(fieldSchema))
                .withDefault(null);
        }

        return new SchemaDefinition(fields.endRecord(), fieldNames);
    }

    private static Schema toCanonicalSchema(int jdbcType) {
        return switch (com.example.jdbcexport.jdbc.ValueKind.fromJdbcType(jdbcType)) {
            case BOOLEAN -> Schema.create(Schema.Type.BOOLEAN);
            case INT -> Schema.create(Schema.Type.INT);
            case LONG -> Schema.create(Schema.Type.LONG);
            case FLOAT -> Schema.create(Schema.Type.FLOAT);
            case DOUBLE -> Schema.create(Schema.Type.DOUBLE);
            case DECIMAL, STRING -> Schema.create(Schema.Type.STRING);
        };
    }

    private static Schema nullable(Schema schema) {
        return Schema.createUnion(List.of(Schema.create(Schema.Type.NULL), schema));
    }

    /**
     * Whether the fast path stores this column with the Avro decimal logical type.
     * Requires driver-reported precision/scale that satisfy Avro's decimal rules
     * (precision >= 1, 0 <= scale <= precision); otherwise the column falls back to
     * the lossless string form. {@link AvroValueMapper} keys off the same check so
     * the schema and the written values always agree.
     */
    public static boolean usesDecimalLogicalType(ResultSetColumn column) {
        return (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC)
            && column.precision() >= 1
            && column.scale() >= 0
            && column.scale() <= column.precision();
    }

    private static Schema toAvroSchema(ResultSetColumn column) {
        return switch (column.jdbcType()) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Schema.create(Schema.Type.INT);
            case Types.BIGINT -> Schema.create(Schema.Type.LONG);
            case Types.REAL -> Schema.create(Schema.Type.FLOAT);
            case Types.FLOAT, Types.DOUBLE -> Schema.create(Schema.Type.DOUBLE);
            case Types.BOOLEAN, Types.BIT -> Schema.create(Schema.Type.BOOLEAN);
            case Types.DATE -> LogicalTypes.date().addToSchema(Schema.create(Schema.Type.INT));
            case Types.TIMESTAMP -> LogicalTypes.localTimestampMicros().addToSchema(Schema.create(Schema.Type.LONG));
            case Types.TIMESTAMP_WITH_TIMEZONE -> LogicalTypes.timestampMicros().addToSchema(Schema.create(Schema.Type.LONG));
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Schema.create(Schema.Type.BYTES);
            case Types.DECIMAL, Types.NUMERIC -> usesDecimalLogicalType(column)
                ? LogicalTypes.decimal(column.precision(), column.scale()).addToSchema(Schema.create(Schema.Type.BYTES))
                : Schema.create(Schema.Type.STRING);
            default -> Schema.create(Schema.Type.STRING);
        };
    }
}
