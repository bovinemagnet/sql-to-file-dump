package com.example.jdbcexport.transform;

/**
 * The outcome of a single {@link OutboundTransformer#transform} call. A sealed type makes the three
 * outcomes explicit and self-documenting, instead of overloading {@code null} (drop) and thrown
 * exceptions (fail).
 *
 * <ul>
 *   <li>{@link Keep} — emit this (possibly reshaped) row and continue.</li>
 *   <li>{@link Drop} — discard the row; {@code reason} is for metrics/observability only.</li>
 *   <li>{@link Fail} — the transform could not process the row; the pipeline applies its
 *       {@link ErrorStrategy}. A {@code cause} (a caught exception) is treated as potentially
 *       containing row data and is never surfaced in error messages.</li>
 * </ul>
 */
public sealed interface TransformResult permits TransformResult.Keep, TransformResult.Drop, TransformResult.Fail {

    record Keep(Row row) implements TransformResult {
    }

    record Drop(String reason) implements TransformResult {
    }

    record Fail(String message, Throwable cause) implements TransformResult {
    }

    static TransformResult keep(Row row) {
        return new Keep(row);
    }

    static TransformResult drop(String reason) {
        return new Drop(reason);
    }

    /** A failure with a safe, value-free message (no underlying exception). */
    static TransformResult fail(String message) {
        return new Fail(message, null);
    }

    /** A failure wrapping a caught exception; the cause's message is never exposed. */
    static TransformResult fail(String message, Throwable cause) {
        return new Fail(message, cause);
    }
}
