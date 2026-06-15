package com.example.jdbcexport.transform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A single mutable, ordered output row flowing through the {@link TransformPipeline}.
 *
 * <p>Keys are output column names; values are in the canonical representation produced by
 * {@link com.example.jdbcexport.jdbc.JdbcValueReader#readAsObject} (see
 * {@link com.example.jdbcexport.jdbc.ValueKind}). A {@code Row} is only ever created when a
 * transform pipeline is active — the no-transform fast path never materialises one.
 */
public final class Row {

    private final LinkedHashMap<String, Object> values;

    public Row() {
        this.values = new LinkedHashMap<>();
    }

    public Row(Map<String, Object> initial) {
        this.values = new LinkedHashMap<>(initial);
    }

    /** A shallow copy; canonical values are immutable so this is a safe snapshot. */
    public Row copy() {
        return new Row(values);
    }

    public Object get(String name) {
        return values.get(name);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public void put(String name, Object value) {
        values.put(name, value);
    }

    public void remove(String name) {
        values.remove(name);
    }

    /** Rename a key in place, preserving its position in iteration order. */
    public void rename(String from, String to) {
        if (!values.containsKey(from)) {
            return;
        }
        LinkedHashMap<String, Object> rebuilt = new LinkedHashMap<>(values.size());
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().equals(from)) {
                rebuilt.put(to, entry.getValue());
            } else {
                rebuilt.put(entry.getKey(), entry.getValue());
            }
        }
        values.clear();
        values.putAll(rebuilt);
    }

    public Set<String> names() {
        return values.keySet();
    }

    public int size() {
        return values.size();
    }
}
