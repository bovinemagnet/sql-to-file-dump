package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative configuration for a single transform, before it is built into a {@link Transform}.
 *
 * <p>The typed accessors fail fast with {@link ExitCodes#TRANSFORM_ERROR} when a required key is
 * missing or has the wrong shape, so invalid configuration is rejected before any rows are read.
 */
public record TransformSpec(String type, Map<String, Object> config) {

    public TransformSpec {
        if (type == null || type.isBlank()) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR, "Transform is missing a \"type\".");
        }
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /** Optional human-friendly step name (config {@code name}); falls back to the type. */
    public String name() {
        String explicit = explicitName();
        return explicit == null ? type : explicit;
    }

    /** The explicitly configured {@code name}, or {@code null} if none was given. */
    public String explicitName() {
        Object value = config.get("name");
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    public String requireString(String key) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) {
            throw fail("requires a non-empty \"" + key + "\"");
        }
        return value.toString();
    }

    public String optionalString(String key, String fallback) {
        Object value = config.get(key);
        return value == null ? fallback : value.toString();
    }

    /** A list of column names, accepting either a JSON array or a comma-separated string. */
    public List<String> requireColumns(String key) {
        Object value = config.get(key);
        if (value == null) {
            throw fail("requires \"" + key + "\"");
        }
        List<String> columns = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    columns.add(item.toString());
                }
            }
        } else {
            for (String part : value.toString().split(",")) {
                if (!part.isBlank()) {
                    columns.add(part.trim());
                }
            }
        }
        if (columns.isEmpty()) {
            throw fail("requires at least one column in \"" + key + "\"");
        }
        return columns;
    }

    /** A string-to-string lookup table (used by {@code map}). */
    public Map<String, String> requireStringMap(String key) {
        Object value = config.get(key);
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            throw fail("requires a non-empty object \"" + key + "\"");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()),
                entry.getValue() == null ? null : entry.getValue().toString());
        }
        return result;
    }

    public ExportException fail(String detail) {
        return new ExportException(ExitCodes.TRANSFORM_ERROR,
            "Transform \"" + type + "\" " + detail + ".");
    }
}
