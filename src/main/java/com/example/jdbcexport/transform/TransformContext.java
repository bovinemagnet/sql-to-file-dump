package com.example.jdbcexport.transform;

/**
 * Per-row context passed to {@link OutboundTransformer#transform}. Carries non-row metadata such as
 * the 1-based row number and the pipeline name, so transforms (and error messages) can reference
 * them without the pipeline threading state through globals. Never contains row values.
 */
public record TransformContext(long rowNumber, String pipeline) {
}
