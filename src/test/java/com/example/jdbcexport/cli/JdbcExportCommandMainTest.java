package com.example.jdbcexport.cli;

import com.example.jdbcexport.error.ExitCodes;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusMainTest
class JdbcExportCommandMainTest {

    @Test
    @Launch(value = {
        "--url", "jdbc:postgresql://localhost:5432/appdb",
        "--user", "app",
        "--password", "secret",
        "--sql", "select 1 as v",
        "--format", "csv",
        "--output", "build/test-output/main-test-dry-run.csv",
        "--overwrite",
        "--dry-run"
    }, exitCode = ExitCodes.SUCCESS)
    void dryRunBindsOptionsThroughQuarkus(LaunchResult result) {
        assertThat(result.getOutput()).contains("Dry run complete");
    }

    @Test
    @Launch(value = {
        "--url", "jdbc:postgresql://localhost:5432/appdb",
        "--user", "app",
        "--password", "secret",
        "--sql", "delete from bookings",
        "--format", "csv",
        "--output", "build/test-output/main-test-rejected.csv",
        "--overwrite",
        "--dry-run"
    }, exitCode = ExitCodes.SQL_INPUT_ERROR)
    void rejectsNonSelectSqlThroughQuarkus(LaunchResult result) {
        assertThat(result.getErrorOutput()).contains("SELECT");
    }
}
