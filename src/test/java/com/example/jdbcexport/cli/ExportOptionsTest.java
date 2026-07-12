package com.example.jdbcexport.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportOptionsTest {

    @Test
    void toStringRedactsInlineUrlCredentials() {
        // Issue #30: ExportOptions no longer carries the resolved password at all, and its
        // toString() must not echo credentials embedded in the JDBC URL either.
        ExportOptions options = new ExportOptions(
            "jdbc:postgresql://bob:hunter2@db:5432/appdb", "bob", "SELECT 1", null,
            OutputFormat.CSV, "out.csv", 100, null, null,
            false, false, false, false, false, true, "", false, false, "SNAPPY");

        assertThat(options.toString())
            .doesNotContain("hunter2")
            .contains("jdbc:postgresql://bob:*****@db:5432/appdb");
    }
}
