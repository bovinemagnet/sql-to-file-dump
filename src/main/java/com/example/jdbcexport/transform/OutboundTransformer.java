package com.example.jdbcexport.transform;

import com.example.jdbcexport.jdbc.ResultSetColumn;

import java.util.List;

/**
 * One step in the outbound transformation pipeline.
 *
 * <p>A transformer reshapes both the schema (once, up front, so writers know the output columns)
 * and each row (on the hot path). It returns a {@link TransformResult} — {@code Keep}, {@code Drop}
 * or {@code Fail} — rather than relying on {@code null}/exception conventions. Schema and row
 * reshaping must stay consistent: if {@link #transform} can write a value of a different kind into a
 * column, {@link #transformSchema} must declare that column with a compatible type.
 *
 * <p>Implementations must not log or otherwise leak row values. They are trusted, deployed
 * extensions — never untrusted code evaluated at run time.
 */
public interface OutboundTransformer {

    /** Stable identity used in configuration ({@code type}), the registry, metrics and errors. */
    String name();

    /** Behavioural metadata; defaults to a pure per-row transform that does not change the column set. */
    default TransformCapabilities capabilities() {
        return TransformCapabilities.rowLevelDeterministic();
    }

    /**
     * Compute the output columns given the current columns. Called once before any rows are read,
     * so column-reference errors fail fast. Must not mutate the input list.
     */
    List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns);

    /**
     * Reshape a single row. Return {@link TransformResult#keep} (optionally reshaping the input
     * {@link Row} in place), {@link TransformResult#drop} to discard it, or {@link TransformResult#fail}.
     */
    TransformResult transform(TransformInput input, TransformContext context);
}
