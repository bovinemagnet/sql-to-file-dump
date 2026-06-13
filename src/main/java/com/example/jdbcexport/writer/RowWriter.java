package com.example.jdbcexport.writer;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

public interface RowWriter extends AutoCloseable {

    void start(ResultSetMetaData metaData) throws Exception;

    void writeRow(ResultSet resultSet) throws Exception;

    ExportWriteResult finish() throws Exception;

    @Override
    void close() throws Exception;
}
