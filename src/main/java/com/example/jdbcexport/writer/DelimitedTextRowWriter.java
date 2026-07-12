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
    private final boolean escapeFormulas;
    private final boolean writeBom;
    private Writer writer;
    private CSVPrinter printer;
    private long rowCount;

    protected DelimitedTextRowWriter(Path outputPath, List<ResultSetColumn> columns, boolean includeHeader,
                                     String nullValue, boolean escapeFormulas, boolean writeBom) {
        this.outputPath = outputPath;
        this.columns = columns;
        this.includeHeader = includeHeader;
        this.nullValue = nullValue;
        this.escapeFormulas = escapeFormulas;
        this.writeBom = writeBom;
    }

    protected abstract CSVFormat buildFormat();

    @Override
    public void start(ResultSetMetaData metaData) throws Exception {
        try {
            writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8);
            if (writeBom) {
                // Issue #38: opt-in UTF-8 BOM so Excel decodes non-ASCII content correctly.
                writer.write('\uFEFF');
            }
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
            values[i] = guard(JdbcValueReader.readAsObject(rs, column.index(), column.jdbcType()));
        }
        printer.printRecord(values);
        rowCount++;
    }

    @Override
    public void writeRow(com.example.jdbcexport.transform.Row row) throws Exception {
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ResultSetColumn column = columns.get(i);
            values[i] = guard(row.get(column.outputName()));
        }
        printer.printRecord(values);
        rowCount++;
    }

    /**
     * Renders a canonical value as its cell string, applying the opt-in OWASP formula-injection
     * mitigation (issue #38): data cells beginning with {@code =}, {@code +}, {@code -},
     * {@code @}, tab, or CR are prefixed with a single quote so spreadsheet applications treat
     * them as text. SQL nulls render the configured null placeholder untouched — it is operator
     * configuration, not data.
     */
    private String guard(Object value) {
        if (value == null) {
            return nullValue;
        }
        String cell = JdbcValueReader.stringify(value, nullValue);
        if (escapeFormulas && !cell.isEmpty()) {
            char first = cell.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                return "'" + cell;
            }
        }
        return cell;
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
