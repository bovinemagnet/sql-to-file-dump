package com.example.jdbcexport.transform;

/** Helpers for exercising an {@link OutboundTransformer} directly in unit tests. */
public final class TransformTestSupport {

    public static final TransformContext CTX = new TransformContext(1, "test");

    private TransformTestSupport() {
    }

    /** Run the transformer and return its raw {@link TransformResult}. */
    public static TransformResult run(OutboundTransformer transformer, Row row) {
        return transformer.transform(new TransformInput(row), CTX);
    }

    /** Run the transformer, returning the kept row (built-ins mutate in place), or null if dropped/failed. */
    public static Row keep(OutboundTransformer transformer, Row row) {
        TransformResult result = run(transformer, row);
        return result instanceof TransformResult.Keep kept ? kept.row() : null;
    }
}
