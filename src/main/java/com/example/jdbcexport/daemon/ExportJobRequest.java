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
    boolean overwrite,
    String parquetCompression
) {

    private static final String DEFAULT_COMPRESSION = "SNAPPY";

    public ExportJobRequest {
        password = blankToNull(password);
        passwordEnv = blankToNull(passwordEnv);
        parquetCompression = parquetCompression == null || parquetCompression.isBlank()
            ? DEFAULT_COMPRESSION : parquetCompression;
    }

    /** Backwards-compatible constructor for callers that do not specify a Parquet codec. */
    public ExportJobRequest(String url, String user, String password, String passwordEnv,
                            String sql, OutputFormat format, String output, boolean overwrite) {
        this(url, user, password, passwordEnv, sql, format, output, overwrite, DEFAULT_COMPRESSION);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
