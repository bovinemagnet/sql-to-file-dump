package com.example.jdbcexport.writer;

import com.example.jdbcexport.transform.Row;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

public interface RowWriter extends AutoCloseable {

    void start(ResultSetMetaData metaData) throws Exception;

    /** Fast path: write a row pulled directly from the result set (no transforms configured). */
    void writeRow(ResultSet resultSet) throws Exception;

    /**
     * Transform path: write an already-materialised, transformed row. Values are in the canonical
     * representation (see {@link com.example.jdbcexport.jdbc.ValueKind}) keyed by output column name.
     * Only invoked when a transform pipeline is active.
     */
    default void writeRow(Row row) throws Exception {
        throw new UnsupportedOperationException(
            getClass().getSimpleName() + " does not support transformed rows");
    }

    ExportWriteResult finish() throws Exception;

    @Override
    void close() throws Exception;
}
