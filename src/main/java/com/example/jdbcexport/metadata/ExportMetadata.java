package com.example.jdbcexport.metadata;

import com.example.jdbcexport.jdbc.ResultSetColumn;

import java.util.List;

public record ExportMetadata(
    String tool,
    String format,
    String jdbcUrl,
    String sqlSource,
    String output,
    long rowCount,
    String startedAt,
    String completedAt,
    long durationMillis,
    List<ResultSetColumn> columns
) {
}
