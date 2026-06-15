package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.OutputFormat;

import java.util.List;

public record ExportJobRequest(
    String url,
    String user,
    String password,
    String passwordEnv,
    String sql,
    OutputFormat format,
    String output,
    boolean overwrite,
    String parquetCompression,
    List<String> transforms,
    String errorStrategy
) {

    private static final String DEFAULT_COMPRESSION = "SNAPPY";

    public ExportJobRequest {
        password = blankToNull(password);
        passwordEnv = blankToNull(passwordEnv);
        parquetCompression = parquetCompression == null || parquetCompression.isBlank()
            ? DEFAULT_COMPRESSION : parquetCompression;
        transforms = transforms == null ? List.of() : List.copyOf(transforms);
        errorStrategy = blankToNull(errorStrategy);
    }

    /** Connection/export fields with a Parquet codec but no transforms. */
    public ExportJobRequest(String url, String user, String password, String passwordEnv,
                            String sql, OutputFormat format, String output, boolean overwrite, String parquetCompression) {
        this(url, user, password, passwordEnv, sql, format, output, overwrite, parquetCompression, List.of(), null);
    }

    /** Backwards-compatible constructor for callers that do not specify a Parquet codec. */
    public ExportJobRequest(String url, String user, String password, String passwordEnv,
                            String sql, OutputFormat format, String output, boolean overwrite) {
        this(url, user, password, passwordEnv, sql, format, output, overwrite, DEFAULT_COMPRESSION, List.of(), null);
    }

    public boolean hasTransforms() {
        return !transforms.isEmpty();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
