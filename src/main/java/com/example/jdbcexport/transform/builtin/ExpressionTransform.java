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
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds a computed string column from a small, sandboxed expression over the row's fields, e.g.
 * {@code "lastName + ', ' + firstName"} or {@code "activeFlag == 'Y' ? 'ACTIVE' : 'INACTIVE'"}.
 * Config: {@code outputField}, {@code expression}.
 *
 * <p>Expressions are compiled once at build time (syntax errors fail fast) and evaluated with a
 * restricted JEXL engine: no reflection, class loading, IO, process execution, {@code new}, or
 * arbitrary Java method access. The result is stored string-typed; a runtime evaluation error is
 * returned as {@link TransformResult#fail} without exposing field values.
 */
public final class ExpressionTransform implements OutboundTransformer {

    // Disable everything an output expression never needs: method calls (blocks getClass()/forName()/
    // System.exit), object construction, loops, lambdas, side effects, annotations and pragmas. These
    // are rejected at parse time. RESTRICTED permissions add defence in depth.
    private static final JexlFeatures FEATURES = new JexlFeatures()
        .methodCall(false)
        .newInstance(false)
        .loops(false)
        .lambda(false)
        .sideEffect(false)
        .sideEffectGlobal(false)
        .annotation(false)
        .pragma(false);

    // RESTRICTED, further denying Object.getClass() so the "class" pseudo-property (x.class) cannot
    // hand out a Class object via property syntax — method calls are already disabled at parse time.
    private static final JexlPermissions PERMISSIONS = JexlPermissions.RESTRICTED
        .compose("java.lang { Object { getClass(); } }");

    private static final JexlEngine ENGINE = new JexlBuilder()
        .features(FEATURES)
        .permissions(PERMISSIONS)
        .strict(true)
        .silent(false)
        .create();

    public static final TransformProvider PROVIDER = new TransformProvider() {
        @Override
        public String type() {
            return "expression";
        }

        @Override
        public OutboundTransformer create(TransformSpec spec) {
            return new ExpressionTransform(spec.requireString("outputField"), spec.requireString("expression"));
        }
    };

    private final String outputField;
    private final String source;
    private final JexlExpression expression;
    private final Set<String> referenced = new LinkedHashSet<>();

    private ExpressionTransform(String outputField, String source) {
        this.outputField = outputField;
        this.source = source;
        try {
            this.expression = ENGINE.createExpression(source);
        } catch (JexlException e) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Transform \"expression\" has an invalid expression for \"" + outputField + "\": " + e.getMessage());
        }
        // The compiled expression exposes its referenced variables (names only, never values). Each
        // dotted reference like a.b arrives as a segment list; only the root names a column.
        if (expression instanceof JexlScript script) {
            for (List<String> variable : script.getVariables()) {
                if (!variable.isEmpty()) {
                    referenced.add(variable.get(0));
                }
            }
        }
    }

    @Override
    public String name() {
        return "expression";
    }

    @Override
    public TransformCapabilities capabilities() {
        return TransformCapabilities.columnChanging();
    }

    @Override
    public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
        if (TransformColumns.exists(columns, outputField)) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Transform \"expression\" would create a duplicate column \"" + outputField + "\".");
        }
        for (String variable : referenced) {
            TransformColumns.require(columns, variable, "expression");
        }
        List<ResultSetColumn> result = new ArrayList<>(columns);
        result.add(TransformColumns.string(outputField));
        return result;
    }

    @Override
    public TransformResult transform(TransformInput input, TransformContext context) {
        Row row = input.row();
        JexlContext jexl = new MapContext();
        for (String column : row.names()) {
            jexl.set(column, row.get(column));
        }
        Object value;
        try {
            value = expression.evaluate(jexl);
        } catch (JexlException.Variable e) {
            // Carries the variable *name* only (no values), so it is safe to surface as-is.
            return TransformResult.fail("expression \"" + outputField + "\" references "
                + (e.isUndefined() ? "undefined" : "null") + " variable \"" + e.getVariable() + "\"");
        } catch (JexlException e) {
            // The JEXL message can include field values, so pass it as the cause (never surfaced).
            return TransformResult.fail("expression \"" + outputField + "\" evaluation error", e);
        }
        row.put(outputField, JdbcValueReader.stringify(value, null));
        return TransformResult.keep(row);
    }

    String source() {
        return source;
    }
}
