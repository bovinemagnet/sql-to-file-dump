package com.example.jdbcexport.daemon;

import java.time.Instant;

/**
 * A reusable JDBC connection saved by the operator. Deliberately holds no password —
 * only the name of an environment variable ({@code passwordEnv}) that is resolved at
 * run time by {@code PasswordResolver}. Secrets are never written to disk.
 */
public record SavedConnection(
    String id,
    String name,
    String driver,
    String url,
    String user,
    String passwordEnv,
    Instant createdAt,
    Instant lastUsedAt
) {
}
