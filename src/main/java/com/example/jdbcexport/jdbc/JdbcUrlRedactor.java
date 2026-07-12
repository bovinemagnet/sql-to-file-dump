package com.example.jdbcexport.jdbc;

import java.util.regex.Pattern;

/**
 * Redacts credentials embedded in JDBC URLs (issue #30). The supported {@code --password-env}
 * design keeps secrets out of the tool's outputs, but nothing stops an operator supplying a URL
 * such as {@code jdbc:postgresql://user:secret@host/db} or {@code ...?password=secret}. Any
 * string that is persisted, logged, or returned by the daemon API must pass through
 * {@link #redact(String)} first — including driver error messages, which frequently echo the
 * full connection string.
 */
public final class JdbcUrlRedactor {

    private static final String MASK = "*****";

    /** {@code //user:password@} authority credentials inside a URL. */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(//[^/@:\\s]+:)[^@\\s]+(@)");

    /** {@code password=...} / {@code pwd=...} query or property parameters. */
    private static final Pattern PASSWORD_PROPERTY = Pattern.compile("(?i)\\b(password|pwd)=[^&;\\s]*");

    private JdbcUrlRedactor() {
    }

    /**
     * Masks any credentials found in {@code text}, which may be a bare JDBC URL or an arbitrary
     * message (for example a driver {@code SQLException} message) embedding one. Null-safe;
     * text without credentials is returned unchanged.
     */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = URL_CREDENTIALS.matcher(text).replaceAll("$1" + MASK + "$2");
        return PASSWORD_PROPERTY.matcher(redacted).replaceAll("$1=" + MASK);
    }
}
