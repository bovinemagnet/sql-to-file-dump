package com.example.jdbcexport.writer;

import java.nio.file.Path;

public record ExportWriteResult(long rowCount, Path output) {
}
