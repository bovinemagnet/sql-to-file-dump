package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;

public final class SqlSafetyValidator {

    private SqlSafetyValidator() {
    }

    public static void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new ExportException(ExitCodes.SQL_INPUT_ERROR, "SQL must not be empty.");
        }
        String trimmed = sql.strip().toLowerCase();
        if (!trimmed.startsWith("select") && !trimmed.startsWith("with")) {
            throw new ExportException(ExitCodes.SQL_INPUT_ERROR,
                "SQL must start with SELECT or WITH. This tool is read-only. Note: Only trusted SQL should be executed.");
        }
    }
}
