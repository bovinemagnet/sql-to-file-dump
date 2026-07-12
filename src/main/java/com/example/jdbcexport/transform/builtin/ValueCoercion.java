package com.example.jdbcexport.transform.builtin;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ValueKind;

import java.math.BigDecimal;

/** Parses a configured string literal into a column's canonical value type. */
final class ValueCoercion {

    private ValueCoercion() {
    }

    static Object parse(String literal, ValueKind kind, String transformType, String column) {
        try {
            return switch (kind) {
                case BOOLEAN -> parseBoolean(literal, kind, transformType, column);
                case INT -> Integer.parseInt(literal.trim());
                case LONG -> Long.parseLong(literal.trim());
                case FLOAT -> Float.parseFloat(literal.trim());
                case DOUBLE -> Double.parseDouble(literal.trim());
                case DECIMAL -> new BigDecimal(literal.trim());
                case STRING -> literal;
            };
        } catch (NumberFormatException e) {
            throw invalid(literal, kind, transformType, column);
        }
    }

    /**
     * {@link Boolean#parseBoolean} coerces anything that is not "true" to {@code false}, so a typo
     * like {@code yes} or {@code 1} would silently substitute the wrong value for every NULL.
     * Accept exactly true/false (any case, trimmed) and fail fast on everything else, matching the
     * numeric kinds.
     */
    private static Boolean parseBoolean(String literal, ValueKind kind, String transformType, String column) {
        String trimmed = literal.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        throw invalid(literal, kind, transformType, column);
    }

    private static ExportException invalid(String literal, ValueKind kind, String transformType, String column) {
        return new ExportException(ExitCodes.TRANSFORM_ERROR,
            "Transform \"" + transformType + "\" value \"" + literal + "\" is not valid for column \""
                + column + "\" (" + kind + ").");
    }
}
