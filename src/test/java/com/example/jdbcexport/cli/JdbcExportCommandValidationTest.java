package com.example.jdbcexport.cli;

import com.example.jdbcexport.error.ExitCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcExportCommandValidationTest {

    private JdbcExportCommand validCommand(Path tempDir) {
        JdbcExportCommand command = new JdbcExportCommand();
        command.url = "jdbc:duckdb:";
        command.user = "ignored";
        command.password = "secret";
        command.sql = "SELECT 1 AS value";
        command.format = "csv";
        command.output = tempDir.resolve("out.csv").toString();
        command.fetchSize = 1000;
        command.includeHeader = true;
        command.nullValue = "";
        command.parquetCompression = "SNAPPY";
        return command;
    }

    @Test
    void dryRunWithValidOptionsSucceeds(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.dryRun = true;

        assertThat(command.call()).isEqualTo(ExitCodes.SUCCESS);
    }

    @Test
    void failsWhenBothSqlAndSqlFileSupplied(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.sqlFile = "query.sql";

        assertThat(command.call()).isEqualTo(ExitCodes.SQL_INPUT_ERROR);
    }

    @Test
    void failsWhenNeitherSqlNorSqlFileSupplied(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.sql = null;

        assertThat(command.call()).isEqualTo(ExitCodes.SQL_INPUT_ERROR);
    }

    @Test
    void failsWhenFormatMissingWithoutDescribe(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.format = null;

        assertThat(command.call()).isEqualTo(ExitCodes.INVALID_ARGUMENTS);
    }

    @Test
    void failsOnUnsupportedFormat(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.format = "xml";

        assertThat(command.call()).isEqualTo(ExitCodes.UNSUPPORTED_FORMAT);
    }

    @Test
    void failsWhenOutputExistsWithoutOverwrite(@TempDir Path tempDir) throws Exception {
        JdbcExportCommand command = validCommand(tempDir);
        command.dryRun = true;
        Files.writeString(Path.of(command.output), "existing");

        assertThat(command.call()).isEqualTo(ExitCodes.OUTPUT_WRITE_ERROR);
    }

    @Test
    void allowsExistingOutputWithOverwrite(@TempDir Path tempDir) throws Exception {
        JdbcExportCommand command = validCommand(tempDir);
        command.dryRun = true;
        command.overwrite = true;
        Files.writeString(Path.of(command.output), "existing");

        assertThat(command.call()).isEqualTo(ExitCodes.SUCCESS);
    }

    @Test
    void failsWhenMetadataExistsWithoutOverwrite(@TempDir Path tempDir) throws Exception {
        JdbcExportCommand command = validCommand(tempDir);
        command.dryRun = true;
        command.metadata = tempDir.resolve("meta.json").toString();
        Files.writeString(Path.of(command.metadata), "existing");

        assertThat(command.call()).isEqualTo(ExitCodes.OUTPUT_WRITE_ERROR);
    }

    @Test
    void failsOnNonPositiveFetchSize(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.fetchSize = 0;

        assertThat(command.call()).isEqualTo(ExitCodes.INVALID_ARGUMENTS);
    }

    @Test
    void failsOnNonPositiveMaxRows(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.maxRows = 0L;

        assertThat(command.call()).isEqualTo(ExitCodes.INVALID_ARGUMENTS);
    }

    @Test
    void failsWhenUrlMissing(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.url = null;

        assertThat(command.call()).isEqualTo(ExitCodes.INVALID_ARGUMENTS);
    }

    @Test
    void failsWhenUserMissing(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.user = null;

        assertThat(command.call()).isEqualTo(ExitCodes.INVALID_ARGUMENTS);
    }

    @Test
    void daemonSubcommandParsesWithoutParentRequiredOptions() {
        new picocli.CommandLine(new JdbcExportCommand()).parseArgs("daemon");
    }

    @Test
    void rejectsNonSelectSql(@TempDir Path tempDir) {
        JdbcExportCommand command = validCommand(tempDir);
        command.sql = "DELETE FROM bookings";

        assertThat(command.call()).isNotEqualTo(ExitCodes.SUCCESS);
    }
}
