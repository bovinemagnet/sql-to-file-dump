package com.example.jdbcexport.daemon;

import java.time.Instant;

/**
 * A recurring or one-off export definition, persisted in {@code schedule.json}. A schedule
 * references a {@link SavedConnection} by id (so credentials and their {@code passwordEnv}
 * live only in the connections store, never here) and carries the SQL, output path pattern,
 * format and a trigger. Triggers are one of:
 * <ul>
 *   <li>{@code cron} — a 5-field UNIX cron expression in {@link #cron()}</li>
 *   <li>{@code interval} — every {@link #every()} {@link #unit()} (minute/hour/day)</li>
 *   <li>{@code once} — a single run at {@link #at()} ({@code yyyy-MM-dd HH:mm})</li>
 * </ul>
 */
public record Schedule(
    String id,
    String name,
    boolean enabled,
    String connectionId,
    String sql,
    String format,
    String compression,
    String outputPattern,
    boolean overwrite,
    String triggerType,
    String cron,
    Integer every,
    String unit,
    String at,
    Instant createdAt,
    Instant lastRunAt,
    String lastStatus,
    String lastJobId
) {
}
