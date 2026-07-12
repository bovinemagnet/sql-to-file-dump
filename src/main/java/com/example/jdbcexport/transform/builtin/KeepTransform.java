package com.example.jdbcexport.transform.builtin;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.OutboundTransformer;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.transform.TransformCapabilities;
import com.example.jdbcexport.transform.TransformContext;
import com.example.jdbcexport.transform.TransformInput;
import com.example.jdbcexport.transform.TransformProvider;
import com.example.jdbcexport.transform.TransformResult;
import com.example.jdbcexport.transform.TransformSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps only the listed columns, in the listed order. Config: {@code columns}. */
public final class KeepTransform implements OutboundTransformer {

    public static final TransformProvider PROVIDER = new TransformProvider() {
        @Override
        public String type() {
            return "keep";
        }

        @Override
        public OutboundTransformer create(TransformSpec spec) {
            return new KeepTransform(spec.requireColumns("columns"));
        }
    };

    private final List<String> columns;

    private KeepTransform(List<String> columns) {
        this.columns = columns;
    }

    @Override
    public String name() {
        return "keep";
    }

    @Override
    public TransformCapabilities capabilities() {
        return TransformCapabilities.columnChanging();
    }

    @Override
    public List<ResultSetColumn> transformSchema(List<ResultSetColumn> input) {
        List<ResultSetColumn> result = new ArrayList<>(columns.size());
        Set<String> seen = new HashSet<>(columns.size());
        for (String column : columns) {
            // Silent de-duplication would hide a config typo and emit a duplicated schema column.
            if (!seen.add(column)) {
                throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                    "Transform \"keep\" lists duplicate column \"" + column + "\".");
            }
            result.add(TransformColumns.require(input, column, "keep"));
        }
        return result;
    }

    @Override
    public TransformResult transform(TransformInput input, TransformContext context) {
        Row row = input.row();
        List<String> toRemove = new ArrayList<>();
        for (String name : row.names()) {
            if (!columns.contains(name)) {
                toRemove.add(name);
            }
        }
        toRemove.forEach(row::remove);
        return TransformResult.keep(row);
    }
}
