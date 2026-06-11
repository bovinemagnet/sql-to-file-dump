package com.example.jdbcexport.cli;

public record ExportOptions(
    String jdbcUrl,
    String user,
    String password,
    String sql,
    String sqlFile,
    OutputFormat format,
    String output,
    int fetchSize,
    Long maxRows,
    String metadataPath,
    boolean overwrite,
    boolean dryRun,
    boolean describe,
    boolean verbose,
    boolean pretty,
    boolean includeHeader,
    String nullValue,
    String parquetCompression
) {
}
