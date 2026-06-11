package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ResultSetSchemaReader {

    private ResultSetSchemaReader() {
    }

    public static List<ResultSetColumn> readColumns(Connection connection, String sql, int fetchSize) {
        try (PreparedStatement stmt = connection.prepareStatement(sql,
            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            stmt.setFetchSize(Math.max(1, fetchSize));
            stmt.setMaxRows(1);
            try (ResultSet rs = stmt.executeQuery()) {
                return readColumnsFromMeta(rs.getMetaData());
            }
        } catch (ExportException e) {
            throw e;
        } catch (SQLException e) {
            throw new ExportException(ExitCodes.DATABASE_ERROR,
                "Failed to read result set schema: " + e.getMessage(), e);
        }
    }

    public static List<ResultSetColumn> readColumnsFromMeta(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<ResultSetColumn> columns = new ArrayList<>(count);
        Set<String> seen = new HashSet<>();

        for (int i = 1; i <= count; i++) {
            String label = meta.getColumnLabel(i);
            String outputName = normalizeOutputName(label);
            if (!seen.add(outputName)) {
                throw new ExportException(ExitCodes.SCHEMA_ERROR,
                    "Duplicate output column name detected: " + outputName + ".\n"
                        + "Use explicit SQL aliases, for example: select a.id as account_id, b.id as booking_id");
            }
            columns.add(new ResultSetColumn(
                i,
                label,
                outputName,
                meta.getColumnType(i),
                meta.getColumnTypeName(i),
                meta.getPrecision(i),
                meta.getScale(i),
                meta.isNullable(i) != ResultSetMetaData.columnNoNulls
            ));
        }
        return columns;
    }

    private static String normalizeOutputName(String label) {
        return label == null ? "" : label.toLowerCase(Locale.ROOT).trim();
    }
}
