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
import java.util.Map;

/** Adds constant string-valued columns to every row. Config: {@code fields} (object of name -> value). */
public final class AddStaticTransform implements OutboundTransformer {

    public static final TransformProvider PROVIDER = new TransformProvider() {
        @Override
        public String type() {
            return "addStatic";
        }

        @Override
        public OutboundTransformer create(TransformSpec spec) {
            return new AddStaticTransform(spec.requireStringMap("fields"));
        }
    };

    private final Map<String, String> fields;

    private AddStaticTransform(Map<String, String> fields) {
        this.fields = fields;
    }

    @Override
    public String name() {
        return "addStatic";
    }

    @Override
    public TransformCapabilities capabilities() {
        return TransformCapabilities.columnChanging();
    }

    @Override
    public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
        for (String name : fields.keySet()) {
            if (TransformColumns.exists(columns, name)) {
                throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                    "Transform \"addStatic\" would create a duplicate column \"" + name + "\".");
            }
        }
        List<ResultSetColumn> result = new ArrayList<>(columns);
        for (String name : fields.keySet()) {
            result.add(TransformColumns.string(name));
        }
        return result;
    }

    @Override
    public TransformResult transform(TransformInput input, TransformContext context) {
        Row row = input.row();
        fields.forEach(row::put);
        return TransformResult.keep(row);
    }
}
