package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlInputResolverTest {

    @Test
    void resolvesInlineSql() {
        assertThat(SqlInputResolver.resolve("SELECT 1", null)).isEqualTo("SELECT 1");
    }

    @Test
    void resolvesSqlFromFile(@TempDir Path tempDir) throws IOException {
        Path sqlFile = tempDir.resolve("query.sql");
        Files.writeString(sqlFile, "SELECT * FROM foo");
        assertThat(SqlInputResolver.resolve(null, sqlFile.toString())).isEqualTo("SELECT * FROM foo");
    }

    @Test
    void throwsWhenBothNull() {
        assertThatThrownBy(() -> SqlInputResolver.resolve(null, null))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void throwsWhenFileNotFound() {
        assertThatThrownBy(() -> SqlInputResolver.resolve(null, "missing-query.sql"))
            .isInstanceOf(ExportException.class);
    }
}
