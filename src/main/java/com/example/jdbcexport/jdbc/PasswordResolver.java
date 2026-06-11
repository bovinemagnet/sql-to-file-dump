package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;

public final class PasswordResolver {

    private PasswordResolver() {
    }

    public static String resolve(String password, String passwordEnv) {
        if (password != null && passwordEnv != null) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS,
                "Specify either --password or --password-env, not both.");
        }
        if (passwordEnv != null) {
            String resolved = System.getenv(passwordEnv);
            if (resolved == null) {
                throw new ExportException(ExitCodes.INVALID_ARGUMENTS,
                    "Environment variable not found: " + passwordEnv);
            }
            return resolved;
        }
        return password;
    }
}
