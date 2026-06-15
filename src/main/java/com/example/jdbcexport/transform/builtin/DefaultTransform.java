package com.example.jdbcexport.transform.builtin;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ValueKind;
import com.example.jdbcexport.transform.OutboundTransformer;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.transform.TransformContext;
import com.example.jdbcexport.transform.TransformInput;
import com.example.jdbcexport.transform.TransformProvider;
import com.example.jdbcexport.transform.TransformResult;
import com.example.jdbcexport.transform.TransformSpec;

import java.util.List;

/**
 * Substitutes a value for SQL nulls in a column, keeping the column's native type. Config:
 * {@code column}, {@code value}. The literal is validated against the column type up front.
 */
public final class DefaultTransform implements OutboundTransformer {

    public static final TransformProvider PROVIDER = new TransformProvider() {
        @Override
        public String type() {
            return "default";
        }

        @Override
        public OutboundTransformer create(TransformSpec spec) {
            return new DefaultTransform(spec.requireString("column"), spec.requireString("value"));
        }
    };

    private final String column;
    private final String literal;
    private Object parsedDefault;

    private DefaultTransform(String column, String literal) {
        this.column = column;
        this.literal = literal;
    }

    @Override
    public String name() {
        return "default";
    }

    @Override
    public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
        ResultSetColumn target = TransformColumns.require(columns, column, "default");
        ValueKind kind = ValueKind.fromJdbcType(target.jdbcType());
        parsedDefault = ValueCoercion.parse(literal, kind, "default", column);
        return columns;
    }

    @Override
    public TransformResult transform(TransformInput input, TransformContext context) {
        Row row = input.row();
        if (row.get(column) == null) {
            row.put(column, parsedDefault);
        }
        return TransformResult.keep(row);
    }
}
