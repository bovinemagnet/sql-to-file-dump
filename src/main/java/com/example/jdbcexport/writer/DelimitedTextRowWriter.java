package com.example.jdbcexport.writer;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.JdbcValueReader;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

public abstract class DelimitedTextRowWriter implements RowWriter {

    protected final Path outputPath;
    protected final List<ResultSetColumn> columns;
    protected final boolean includeHeader;
    protected final String nullValue;
    private Writer writer;
    private CSVPrinter printer;
    private long rowCount;

    protected DelimitedTextRowWriter(Path outputPath, List<ResultSetColumn> columns, boolean includeHeader, String nullValue) {
        this.outputPath = outputPath;
        this.columns = columns;
        this.includeHeader = includeHeader;
        this.nullValue = nullValue;
    }

    protected abstract CSVFormat buildFormat();

    @Override
    public void start(ResultSetMetaData metaData) throws Exception {
        try {
            writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8);
            printer = new CSVPrinter(writer, buildFormat());
        } catch (IOException e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                "Failed to open output file: " + outputPath, e);
        }
    }

    @Override
    public void writeRow(ResultSet rs) throws Exception {
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ResultSetColumn column = columns.get(i);
            values[i] = JdbcValueReader.readAsString(rs, column.index(), column.jdbcType(), nullValue);
        }
        printer.printRecord(values);
        rowCount++;
    }

    @Override
    public void writeRow(com.example.jdbcexport.transform.Row row) throws Exception {
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ResultSetColumn column = columns.get(i);
            values[i] = JdbcValueReader.stringify(row.get(column.outputName()), nullValue);
        }
        printer.printRecord(values);
        rowCount++;
    }

    @Override
    public ExportWriteResult finish() throws Exception {
        close();
        return new ExportWriteResult(rowCount, outputPath);
    }

    @Override
    public void close() throws Exception {
        if (printer != null) {
            printer.close(true);
            printer = null;
        } else if (writer != null) {
            writer.close();
        }
        writer = null;
    }
}
