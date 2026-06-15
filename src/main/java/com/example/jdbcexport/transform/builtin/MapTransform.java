package com.example.jdbcexport.transform.builtin;

import com.example.jdbcexport.jdbc.JdbcValueReader;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.OutboundTransformer;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.transform.TransformContext;
import com.example.jdbcexport.transform.TransformInput;
import com.example.jdbcexport.transform.TransformProvider;
import com.example.jdbcexport.transform.TransformResult;
import com.example.jdbcexport.transform.TransformSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a column's values via a lookup table (e.g. status codes to labels). Config:
 * {@code column}, {@code mapping} (object), optional {@code unmatched} ({@code keep}|{@code default}|{@code fail},
 * default {@code keep}) and {@code default}. The column becomes string-typed on output.
 */
public final class MapTransform implements OutboundTransformer {

    public static final TransformProvider PROVIDER = new TransformProvider() {
        @Override
        public String type() {
            return "map";
        }

        @Override
        public OutboundTransformer create(TransformSpec spec) {
            String column = spec.requireString("column");
            Map<String, String> mapping = spec.requireStringMap("mapping");
            String unmatched = spec.optionalString("unmatched", "keep");
            if (!List.of("keep", "default", "fail").contains(unmatched)) {
                throw spec.fail("\"unmatched\" must be one of keep, default, fail");
            }
            String fallback = unmatched.equals("default") ? spec.requireString("default") : null;
            return new MapTransform(column, mapping, unmatched, fallback);
        }
    };

    private final String column;
    private final Map<String, String> mapping;
    private final String unmatched;
    private final String fallback;

    private MapTransform(String column, Map<String, String> mapping, String unmatched, String fallback) {
        this.column = column;
        this.mapping = mapping;
        this.unmatched = unmatched;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return "map";
    }

    @Override
    public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
        ResultSetColumn target = TransformColumns.require(columns, column, "map");
        List<ResultSetColumn> result = new ArrayList<>(columns.size());
        for (ResultSetColumn current : columns) {
            result.add(current == target ? TransformColumns.asString(current) : current);
        }
        return result;
    }

    @Override
    public TransformResult transform(TransformInput input, TransformContext context) {
        Row row = input.row();
        Object value = row.get(column);
        if (value == null) {
            return TransformResult.keep(row);
        }
        String key = JdbcValueReader.stringify(value, "");
        if (mapping.containsKey(key)) {
            row.put(column, mapping.get(key));
        } else {
            switch (unmatched) {
                case "default" -> row.put(column, fallback);
                case "fail" -> {
                    return TransformResult.fail("no mapping for a value in column \"" + column + "\"");
                }
                default -> row.put(column, key);
            }
        }
        return TransformResult.keep(row);
    }
}
