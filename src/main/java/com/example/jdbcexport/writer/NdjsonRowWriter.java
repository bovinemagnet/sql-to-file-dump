package com.example.jdbcexport.writer;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.JdbcValueReader;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

public class NdjsonRowWriter implements RowWriter {

    private final Path outputPath;
    private final List<ResultSetColumn> columns;
    private JsonFactory jsonFactory;
    private BufferedOutputStream outputStream;
    private long rowCount;

    public NdjsonRowWriter(Path outputPath, List<ResultSetColumn> columns) {
        this.outputPath = outputPath;
        this.columns = columns;
    }

    @Override
    public void start(ResultSetMetaData metaData) throws Exception {
        try {
            jsonFactory = new JsonFactory();
            outputStream = new BufferedOutputStream(Files.newOutputStream(outputPath));
        } catch (IOException e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR, "Failed to open output file: " + outputPath, e);
        }
    }

    @Override
    public void writeRow(ResultSet rs) throws Exception {
        try (JsonGenerator generator = jsonFactory.createGenerator(outputStream)) {
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            generator.writeStartObject();
            for (ResultSetColumn column : columns) {
                generator.writeFieldName(column.outputName());
                writeValue(generator, JdbcValueReader.readAsObject(rs, column.index(), column.jdbcType()));
            }
            generator.writeEndObject();
        }
        outputStream.write('\n');
        rowCount++;
    }

    @Override
    public void writeRow(com.example.jdbcexport.transform.Row row) throws Exception {
        try (JsonGenerator generator = jsonFactory.createGenerator(outputStream)) {
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            generator.writeStartObject();
            for (ResultSetColumn column : columns) {
                generator.writeFieldName(column.outputName());
                writeValue(generator, row.get(column.outputName()));
            }
            generator.writeEndObject();
        }
        outputStream.write('\n');
        rowCount++;
    }

    @Override
    public ExportWriteResult finish() throws Exception {
        close();
        return new ExportWriteResult(rowCount, outputPath);
    }

    @Override
    public void close() throws Exception {
        if (outputStream != null) {
            outputStream.close();
            outputStream = null;
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
