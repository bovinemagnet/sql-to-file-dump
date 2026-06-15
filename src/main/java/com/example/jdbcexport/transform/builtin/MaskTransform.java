package com.example.jdbcexport.transform.builtin;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.OutboundTransformer;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.transform.SensitiveColumns;
import com.example.jdbcexport.transform.TransformContext;
import com.example.jdbcexport.transform.TransformInput;
import com.example.jdbcexport.transform.TransformProvider;
import com.example.jdbcexport.transform.TransformResult;
import com.example.jdbcexport.transform.TransformSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces every non-null value in one or more columns with a fixed mask token, for redacting
 * sensitive data. Config: {@code column} (single) or {@code columns} (list), optional {@code mask}
 * (default {@code ***}). Nulls stay null. Masked columns are reported as sensitive.
 */
public final class MaskTransform implements OutboundTransformer, SensitiveColumns {

    public static final TransformProvider PROVIDER = new TransformProvider() {
        @Override
        public String type() {
            return "mask";
        }

        @Override
        public OutboundTransformer create(TransformSpec spec) {
            List<String> columns = spec.config().containsKey("columns")
                ? spec.requireColumns("columns")
                : List.of(spec.requireString("column"));
            return new MaskTransform(columns, spec.optionalString("mask", "***"));
        }
    };

    private final List<String> columns;
    private final String mask;

    private MaskTransform(List<String> columns, String mask) {
        this.columns = columns;
        this.mask = mask;
    }

    @Override
    public String name() {
        return "mask";
    }

    @Override
    public List<String> sensitiveColumns() {
        return columns;
    }

    @Override
    public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
        List<ResultSetColumn> result = new ArrayList<>(columns.size());
        for (ResultSetColumn current : columns) {
            result.add(this.columns.contains(current.outputName()) ? TransformColumns.asString(current) : current);
        }
        for (String name : this.columns) {
            TransformColumns.require(result, name, "mask");
        }
        return result;
    }

    @Override
    public TransformResult transform(TransformInput input, TransformContext context) {
        Row row = input.row();
        for (String column : columns) {
            if (row.get(column) != null) {
                row.put(column, mask);
            }
        }
        return TransformResult.keep(row);
    }
}
