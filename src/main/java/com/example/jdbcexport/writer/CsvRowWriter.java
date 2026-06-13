package com.example.jdbcexport.writer;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.apache.commons.csv.CSVFormat;

import java.nio.file.Path;
import java.util.List;

public class CsvRowWriter extends DelimitedTextRowWriter {

    public CsvRowWriter(Path outputPath, List<ResultSetColumn> columns, boolean includeHeader, String nullValue) {
        super(outputPath, columns, includeHeader, nullValue);
    }

    @Override
    protected CSVFormat buildFormat() {
        CSVFormat.Builder builder = CSVFormat.DEFAULT.builder();
        if (includeHeader) {
            builder.setHeader(columns.stream().map(ResultSetColumn::outputName).toArray(String[]::new));
        }
        return builder.build();
    }
}
