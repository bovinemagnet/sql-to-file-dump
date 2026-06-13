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
                if (Character.isLetter(current) || current == '_') {
                    sanitized.append(current);
                } else {
                    sanitized.append('_');
                    if (Character.isDigit(current)) {
                        sanitized.append(current);
                    }
                }
            } else if (Character.isLetterOrDigit(current) || current == '_') {
                sanitized.append(current);
            } else {
                sanitized.append('_');
            }
        }
        return sanitized.isEmpty() ? "_column" : sanitized.toString();
    }
}
