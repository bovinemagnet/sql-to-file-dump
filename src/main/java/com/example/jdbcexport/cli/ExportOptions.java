package com.example.jdbcexport.cli;

import com.example.jdbcexport.jdbc.JdbcUrlRedactor;

/**
 * Snapshot of the parsed options passed to the writer factory and writers. Deliberately does
 * not carry the resolved password (issue #30): connections are opened before this record is
 * built, and a record's generated {@code toString()} would otherwise leak the secret into any
 * stray log statement.
 */
public record ExportOptions(
    String jdbcUrl,
    String user,
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

    /** Redacts credentials embedded in the JDBC URL (issue #30). */
    @Override
    public String toString() {
        return "ExportOptions[jdbcUrl=" + JdbcUrlRedactor.redact(jdbcUrl)
            + ", user=" + user
            + ", sql=" + sql
            + ", sqlFile=" + sqlFile
            + ", format=" + format
            + ", output=" + output
            + ", fetchSize=" + fetchSize
            + ", maxRows=" + maxRows
            + ", metadataPath=" + metadataPath
            + ", overwrite=" + overwrite
            + ", dryRun=" + dryRun
            + ", describe=" + describe
            + ", verbose=" + verbose
            + ", pretty=" + pretty
            + ", includeHeader=" + includeHeader
            + ", nullValue=" + nullValue
            + ", parquetCompression=" + parquetCompression
            + "]";
    }
}
