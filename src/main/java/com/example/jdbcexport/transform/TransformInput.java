package com.example.jdbcexport.transform;

/**
 * The input to a single {@link OutboundTransformer#transform} call: the current row, in the
 * canonical representation (see {@link com.example.jdbcexport.jdbc.ValueKind}). The {@link Row} is
 * mutable, so a transform may reshape it in place and return it inside {@link TransformResult.Keep},
 * or build a new one.
 */
public record TransformInput(Row row) {

    /** Convenience accessor for a single field's value. */
    public Object value(String column) {
        return row.get(column);
    }
}
