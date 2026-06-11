package com.example.jdbcexport.cli;

import java.util.Locale;

public enum OutputFormat {
    JSON,
    NDJSON,
    PARQUET,
    CSV,
    TSV;

    public static OutputFormat fromString(String value) {
        return OutputFormat.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
