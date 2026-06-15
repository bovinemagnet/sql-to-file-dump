package com.example.jdbcexport.transform.builtin;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.JdbcValueReader;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds a computed string column from a template with {@code {column}} placeholders, e.g.
 * {@code "{first} {last}"}. This is the safe, declarative form of a computed field — no expression
 * engine and no arbitrary code execution. Config: {@code name}, {@code template}. Placeholder
 * columns are validated up front; a null value renders as the empty string.
 */
public final class TemplateTransform implements OutboundTransformer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)\\}");

    public static final TransformProvider PROVIDER = new TransformProvider() {
        @Override
        public String type() {
            return "template";
        }

        @Override
        public OutboundTransformer create(TransformSpec spec) {
            return new TemplateTransform(spec.requireString("name"), spec.requireString("template"));
        }
    };

    private final String column;
    private final String template;
    private final Set<String> referenced = new LinkedHashSet<>();

    private TemplateTransform(String column, String template) {
        this.column = column;
        this.template = template;
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            referenced.add(matcher.group(1));
        }
    }

    @Override
    public String name() {
        return "template";
    }

    @Override
    public TransformCapabilities capabilities() {
        return TransformCapabilities.columnChanging();
    }

    @Override
    public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
        if (TransformColumns.exists(columns, column)) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Transform \"template\" would create a duplicate column \"" + column + "\".");
        }
        for (String referencedColumn : referenced) {
            TransformColumns.require(columns, referencedColumn, "template");
        }
        List<ResultSetColumn> result = new ArrayList<>(columns);
        result.add(TransformColumns.string(column));
        return result;
    }

    @Override
    public TransformResult transform(TransformInput input, TransformContext context) {
        Row row = input.row();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = JdbcValueReader.stringify(row.get(matcher.group(1)), "");
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        row.put(column, rendered.toString());
        return TransformResult.keep(row);
    }
}
