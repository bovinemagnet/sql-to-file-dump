package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces the read-only product constraint: a single SELECT or WITH statement.
 *
 * <p>The check runs over a scrubbed copy of the SQL in which comments and quoted
 * text (string literals, dollar-quoted strings, delimited identifiers) have been
 * removed, so that neither can be used to smuggle a statement separator or a
 * data-modifying keyword past the validator.
 */
public final class SqlSafetyValidator {

    /**
     * Keywords that may never appear outside quoted text. Statement-level-only keywords are
     * deliberately absent: the single-statement rule already makes them unreachable, and several
     * of them (COPY, LOAD, CALL) are plausible column names.
     */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
        "insert", "update", "delete", "merge", "drop", "create", "alter",
        "truncate", "grant", "revoke", "execute", "commit", "rollback");

    private static final Pattern WORD = Pattern.compile("[a-z_][a-z0-9_]*");

    private static final Pattern LEADING_KEYWORD = Pattern.compile("^(select|with)\\b");

    private SqlSafetyValidator() {
    }

    public static void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new ExportException(ExitCodes.SQL_INPUT_ERROR, "SQL must not be empty.");
        }

        String scrubbed = scrub(sql);
        rejectExtraStatements(scrubbed);

        String lower = scrubbed.strip().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            throw new ExportException(ExitCodes.SQL_INPUT_ERROR, "SQL must not be empty.");
        }
        if (!LEADING_KEYWORD.matcher(lower).find()) {
            throw new ExportException(ExitCodes.SQL_INPUT_ERROR,
                "SQL must start with SELECT or WITH. This tool is read-only. Note: Only trusted SQL should be executed.");
        }
        rejectForbiddenKeywords(lower);
    }

    /**
     * Returns {@code sql} with comments replaced by a single space and every run of quoted text
     * replaced by an empty quoted token, preserving all other characters and their order.
     */
    private static String scrub(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = (end < 0) ? n : end;
                out.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                out.append(' ');
            } else if (c == '\'') {
                boolean escapeString = endsWithEscapeStringPrefix(out);
                i = skipSingleQuoted(sql, i, escapeString);
                out.append("''");
            } else if (c == '"') {
                i = skipDelimited(sql, i, '"', "identifier");
                out.append("\"x\"");
            } else if (c == '$' && dollarTagEnd(sql, i) > 0) {
                i = skipDollarQuoted(sql, i);
                out.append("''");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Returns the index just past the closing {@code *&#47;}, honouring nesting. */
    private static int skipBlockComment(String sql, int start) {
        int depth = 0;
        int i = start;
        int n = sql.length();
        while (i < n) {
            if (sql.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (sql.startsWith("*/", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        throw new ExportException(ExitCodes.SQL_INPUT_ERROR, "SQL contains an unterminated block comment.");
    }

    /**
     * Returns the index just past the closing quote. A doubled quote is an escaped quote; a
     * backslash escape is honoured only for a PostgreSQL {@code E'...'} string.
     */
    private static int skipSingleQuoted(String sql, int start, boolean escapeString) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (escapeString && c == '\\' && i + 1 < n) {
                i += 2;
            } else if (c == '\'') {
                if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        throw new ExportException(ExitCodes.SQL_INPUT_ERROR, "SQL contains an unterminated string literal.");
    }

    private static int skipDelimited(String sql, int start, char quote, String what) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        throw new ExportException(ExitCodes.SQL_INPUT_ERROR, "SQL contains an unterminated quoted " + what + ".");
    }

    /**
     * Returns the index just past the opening dollar-quote tag ({@code $$} or {@code $tag$}),
     * or -1 when the {@code $} at {@code start} is not one (e.g. the parameter marker {@code $1}).
     */
    private static int dollarTagEnd(String sql, int start) {
        int i = start + 1;
        int n = sql.length();
        while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            if (i == start + 1 && Character.isDigit(sql.charAt(i))) {
                return -1;
            }
            i++;
        }
        return (i < n && sql.charAt(i) == '$') ? i + 1 : -1;
    }

    private static int skipDollarQuoted(String sql, int start) {
        int bodyStart = dollarTagEnd(sql, start);
        String tag = sql.substring(start, bodyStart);
        int end = sql.indexOf(tag, bodyStart);
        if (end < 0) {
            throw new ExportException(ExitCodes.SQL_INPUT_ERROR, "SQL contains an unterminated dollar-quoted string.");
        }
        return end + tag.length();
    }

    /** A semicolon is permitted only as the final character of the statement. */
    private static void rejectExtraStatements(String scrubbed) {
        String trailingTrimmed = scrubbed.stripTrailing();
        int first = trailingTrimmed.indexOf(';');
        if (first >= 0 && first != trailingTrimmed.length() - 1) {
            throw new ExportException(ExitCodes.SQL_INPUT_ERROR,
                "SQL must be a single statement: a ';' separator was found before the end of the query.");
        }
    }

    private static void rejectForbiddenKeywords(String lowerScrubbed) {
        Matcher m = WORD.matcher(lowerScrubbed);
        while (m.find()) {
            String word = m.group();
            if (FORBIDDEN_KEYWORDS.contains(word)) {
                throw new ExportException(ExitCodes.SQL_INPUT_ERROR,
                    "SQL must not contain the " + word.toUpperCase(Locale.ROOT)
                        + " keyword. This tool is read-only.");
            }
        }
    }

    /** True when the text just written ends with an {@code E} string prefix, as in {@code E'...'}. */
    private static boolean endsWithEscapeStringPrefix(StringBuilder out) {
        int len = out.length();
        if (len == 0) {
            return false;
        }
        char last = out.charAt(len - 1);
        if (last != 'e' && last != 'E') {
            return false;
        }
        return len == 1 || !isWordChar(out.charAt(len - 2));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
