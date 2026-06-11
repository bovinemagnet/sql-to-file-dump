package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SqlInputResolver {

    private SqlInputResolver() {
    }

    public static String resolve(String sql, String sqlFile) {
        if (sql != null) {
            return sql.strip();
        }
        if (sqlFile != null) {
            try {
                return Files.readString(Path.of(sqlFile)).strip();
            } catch (IOException e) {
                throw new ExportException(ExitCodes.SQL_INPUT_ERROR,
                    "Failed to read SQL file: " + sqlFile, e);
            }
        }
        throw new ExportException(ExitCodes.SQL_INPUT_ERROR,
            "One of --sql or --sql-file is required.");
    }
}
