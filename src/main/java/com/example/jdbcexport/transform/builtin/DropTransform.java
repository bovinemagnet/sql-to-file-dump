package com.example.jdbcexport.transform.builtin;

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
import java.util.List;

/** Removes columns. Config: {@code columns} (array or comma-separated string). */
public final class DropTransform implements OutboundTransformer {

    public static final TransformProvider PROVIDER = new TransformProvider() {
        @Override
        public String type() {
            return "drop";
        }

        @Override
        public OutboundTransformer create(TransformSpec spec) {
            return new DropTransform(spec.requireColumns("columns"));
        }
    };

    private final List<String> columns;

    private DropTransform(List<String> columns) {
        this.columns = columns;
    }

    @Override
    public String name() {
        return "drop";
    }

    @Override
    public TransformCapabilities capabilities() {
        return TransformCapabilities.columnChanging();
    }

    @Override
    public List<ResultSetColumn> transformSchema(List<ResultSetColumn> input) {
        for (String column : columns) {
            TransformColumns.require(input, column, "drop");
        }
        List<ResultSetColumn> result = new ArrayList<>(input.size());
        for (ResultSetColumn column : input) {
            if (!columns.contains(column.outputName())) {
                result.add(column);
            }
        }
        return result;
    }

    @Override
    public TransformResult transform(TransformInput input, TransformContext context) {
        Row row = input.row();
        for (String column : columns) {
            row.remove(column);
        }
        return TransformResult.keep(row);
    }
}
