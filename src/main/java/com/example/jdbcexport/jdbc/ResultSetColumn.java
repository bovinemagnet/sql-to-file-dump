package com.example.jdbcexport.jdbc;

public record ResultSetColumn(
    int index,
    String label,
    String outputName,
    int jdbcType,
    String jdbcTypeName,
    int precision,
    int scale,
    boolean nullable
) {
}
