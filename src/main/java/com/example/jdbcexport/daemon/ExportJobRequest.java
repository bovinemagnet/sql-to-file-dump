package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.OutputFormat;

public record ExportJobRequest(
    String url,
    String user,
    String password,
    String passwordEnv,
    String sql,
    OutputFormat format,
    String output,
    boolean overwrite
) {

    public ExportJobRequest {
        password = blankToNull(password);
        passwordEnv = blankToNull(passwordEnv);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
