package com.example.jdbcexport.parquet;

public final class AvroFieldNameSanitizer {

    private AvroFieldNameSanitizer() {
    }

    public static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "_column";
        }
        StringBuilder sanitized = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (i == 0) {
                if (isAsciiLetter(current) || current == '_') {
                    sanitized.append(current);
                } else {
                    sanitized.append('_');
                    if (isAsciiDigit(current)) {
                        sanitized.append(current);
                    }
                }
            } else if (isAsciiLetter(current) || isAsciiDigit(current) || current == '_') {
                sanitized.append(current);
            } else {
                sanitized.append('_');
            }
        }
        return sanitized.isEmpty() ? "_column" : sanitized.toString();
    }

    // Avro names must match [A-Za-z_][A-Za-z0-9_]*; Character.isLetter/isLetterOrDigit
    // accept non-ASCII (e.g. 'é', Arabic-Indic digits) that Avro's Schema rejects.
    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
