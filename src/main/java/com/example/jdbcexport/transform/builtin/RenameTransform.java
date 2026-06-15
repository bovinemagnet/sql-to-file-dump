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
import java.util.List;

/** Renames a column. Config: {@code from}, {@code to}. */
public final class RenameTransform implements OutboundTransformer {

    public static final TransformProvider PROVIDER = new TransformProvider() {
        @Override
        public String type() {
            return "rename";
        }

        @Override
        public OutboundTransformer create(TransformSpec spec) {
            return new RenameTransform(spec.requireString("from"), spec.requireString("to"));
        }
    };

    private final String from;
    private final String to;

    private RenameTransform(String from, String to) {
        this.from = from;
        this.to = to;
    }

    @Override
    public String name() {
        return "rename";
    }

    @Override
    public TransformCapabilities capabilities() {
        return TransformCapabilities.columnChanging();
    }

    @Override
    public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
        ResultSetColumn source = TransformColumns.require(columns, from, "rename");
        if (!from.equals(to) && TransformColumns.exists(columns, to)) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Transform \"rename\" would create a duplicate column \"" + to + "\".");
        }
        List<ResultSetColumn> result = new ArrayList<>(columns.size());
        for (ResultSetColumn column : columns) {
            result.add(column == source ? TransformColumns.rename(column, to) : column);
        }
        return result;
    }

    @Override
    public TransformResult transform(TransformInput input, TransformContext context) {
        Row row = input.row();
        row.rename(from, to);
        return TransformResult.keep(row);
    }
}
