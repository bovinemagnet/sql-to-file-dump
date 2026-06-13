package com.example.jdbcexport.writer;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.parquet.AvroSchemaFactory;
import com.example.jdbcexport.parquet.AvroValueMapper;
import com.example.jdbcexport.parquet.LocalParquetOutputFile;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

public class ParquetRowWriter implements RowWriter {

    private final Path outputPath;
    private final List<ResultSetColumn> columns;
    private final String compression;
    private Schema schema;
    private List<String> fieldNames;
    private ParquetWriter<GenericRecord> writer;
    private long rowCount;

    public ParquetRowWriter(Path outputPath, List<ResultSetColumn> columns, String compression) {
        this.outputPath = outputPath;
        this.columns = columns;
        this.compression = compression;
    }

    @Override
    public void start(ResultSetMetaData metaData) throws Exception {
        try {
            AvroSchemaFactory.SchemaDefinition schemaDefinition = AvroSchemaFactory.buildSchema(columns);
            schema = schemaDefinition.schema();
            fieldNames = schemaDefinition.fieldNames();
            writer = AvroParquetWriter.<GenericRecord>builder(new LocalParquetOutputFile(outputPath))
                .withSchema(schema)
                .withCompressionCodec(parseCompression(compression))
                .build();
        } catch (ExportException e) {
            throw e;
        } catch (Exception e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                "Failed to create Parquet writer: " + e.getMessage(), e);
        }
    }

    @Override
    public void writeRow(ResultSet rs) throws Exception {
        GenericRecord record = new GenericData.Record(schema);
        for (int i = 0; i < columns.size(); i++) {
            record.put(fieldNames.get(i), AvroValueMapper.readValue(rs, columns.get(i)));
        }
        writer.write(record);
        rowCount++;
    }

    @Override
    public ExportWriteResult finish() throws Exception {
        close();
        return new ExportWriteResult(rowCount, outputPath);
    }

    @Override
    public void close() throws Exception {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    private static CompressionCodecName parseCompression(String compression) {
        try {
            return CompressionCodecName.valueOf(compression.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ExportException(ExitCodes.UNSUPPORTED_FORMAT,
                "Unsupported Parquet compression: " + compression + ". Valid values: SNAPPY, GZIP, ZSTD, UNCOMPRESSED");
        }
    }
}
