package com.example.jdbcexport.writer;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.jdbc.ResultSetColumn;

import java.nio.file.Path;
import java.util.List;

public final class RowWriterFactory {

    public RowWriter create(ExportOptions options, List<ResultSetColumn> columns) {
        return create(options, columns, false);
    }

    /**
     * @param transforming whether an outbound transform pipeline is active. When {@code true} the
     *                     {@code columns} are the post-transform output columns and writers consume
     *                     rows via {@link RowWriter#writeRow(com.example.jdbcexport.transform.Row)}.
     */
    public RowWriter create(ExportOptions options, List<ResultSetColumn> columns, boolean transforming) {
        Path targetPath = Path.of(options.output());
        // Issue #24: stream to a temporary sibling and rename onto the target only on success,
        // so a mid-stream failure never leaves a truncated file or destroys an existing export.
        Path temporaryPath = AtomicRowWriter.temporaryPathFor(targetPath);
        RowWriter delegate = switch (options.format()) {
            case JSON -> new JsonArrayRowWriter(temporaryPath, options.pretty(), columns);
            case NDJSON -> new NdjsonRowWriter(temporaryPath, columns);
            case CSV -> new CsvRowWriter(temporaryPath, columns, options.includeHeader(), options.nullValue(),
                options.csvEscapeFormulas(), options.csvBom());
            case TSV -> new TsvRowWriter(temporaryPath, columns, options.includeHeader(), options.nullValue(),
                options.csvEscapeFormulas(), options.csvBom());
            case PARQUET -> new ParquetRowWriter(temporaryPath, columns, options.parquetCompression(), transforming);
        };
        return new AtomicRowWriter(delegate, temporaryPath, targetPath);
    }
}
