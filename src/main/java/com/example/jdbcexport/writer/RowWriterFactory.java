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
        Path outputPath = Path.of(options.output());
        return switch (options.format()) {
            case JSON -> new JsonArrayRowWriter(outputPath, options.pretty(), columns);
            case NDJSON -> new NdjsonRowWriter(outputPath, columns);
            case CSV -> new CsvRowWriter(outputPath, columns, options.includeHeader(), options.nullValue());
            case TSV -> new TsvRowWriter(outputPath, columns, options.includeHeader(), options.nullValue());
            case PARQUET -> new ParquetRowWriter(outputPath, columns, options.parquetCompression(), transforming);
        };
    }
}
