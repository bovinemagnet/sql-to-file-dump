package com.example.jdbcexport.writer;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.JdbcValueReader;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

public class JsonArrayRowWriter implements RowWriter {

    private final Path outputPath;
    private final boolean pretty;
    private final List<ResultSetColumn> columns;
    private JsonGenerator generator;
    private long rowCount;

    public JsonArrayRowWriter(Path outputPath, boolean pretty, List<ResultSetColumn> columns) {
        this.outputPath = outputPath;
        this.pretty = pretty;
        this.columns = columns;
    }

    @Override
    public void start(ResultSetMetaData metaData) throws Exception {
        try {
            generator = new JsonFactory().createGenerator(new BufferedOutputStream(Files.newOutputStream(outputPath)));
            if (pretty) {
                DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
                printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
                generator.setPrettyPrinter(printer);
            }
            generator.writeStartArray();
        } catch (IOException e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR, "Failed to open output file: " + outputPath, e);
        }
    }

    @Override
    public void writeRow(ResultSet rs) throws Exception {
        generator.writeStartObject();
        for (ResultSetColumn column : columns) {
            generator.writeFieldName(column.outputName());
            writeValue(generator, JdbcValueReader.readAsObject(rs, column.index(), column.jdbcType()));
        }
        generator.writeEndObject();
        rowCount++;
    }

    @Override
    public void writeRow(com.example.jdbcexport.transform.Row row) throws Exception {
        generator.writeStartObject();
        for (ResultSetColumn column : columns) {
            generator.writeFieldName(column.outputName());
            writeValue(generator, row.get(column.outputName()));
        }
        generator.writeEndObject();
        rowCount++;
    }

    @Override
    public ExportWriteResult finish() throws Exception {
        if (generator != null) {
            generator.writeEndArray();
            generator.close();
            generator = null;
        }
        return new ExportWriteResult(rowCount, outputPath);
    }

    @Override
    public void close() throws Exception {
        if (generator != null) {
            generator.close();
            generator = null;
        }
    }

    private static void writeValue(JsonGenerator generator, Object value) throws IOException {
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof Boolean booleanValue) {
            generator.writeBoolean(booleanValue);
        } else if (value instanceof Integer integerValue) {
            generator.writeNumber(integerValue);
        } else if (value instanceof Long longValue) {
            generator.writeNumber(longValue);
        } else if (value instanceof Float floatValue) {
            generator.writeNumber(floatValue);
        } else if (value instanceof Double doubleValue) {
            generator.writeNumber(doubleValue);
        } else if (value instanceof BigDecimal decimalValue) {
            generator.writeNumber(decimalValue);
        } else {
            generator.writeString(value.toString());
        }
    }
}
