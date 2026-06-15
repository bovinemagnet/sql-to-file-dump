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
                case BOOLEAN -> Boolean.parseBoolean(literal);
                case INT -> Integer.parseInt(literal.trim());
                case LONG -> Long.parseLong(literal.trim());
                case FLOAT -> Float.parseFloat(literal.trim());
                case DOUBLE -> Double.parseDouble(literal.trim());
                case DECIMAL -> new BigDecimal(literal.trim());
                case STRING -> literal;
            };
        } catch (NumberFormatException e) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Transform \"" + transformType + "\" value \"" + literal + "\" is not valid for column \""
                    + column + "\" (" + kind + ").");
        }
    }
}
