package com.example.jdbcexport.transform.builtin;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;

import java.sql.Types;
import java.util.List;

/** Helpers for the built-in transforms to look up and synthesise {@link ResultSetColumn}s. */
final class TransformColumns {

    private TransformColumns() {
    }

    /** A synthetic string-typed column for a computed/coerced field (no backing result-set index). */
    static ResultSetColumn string(String name) {
        return new ResultSetColumn(-1, name, name, Types.VARCHAR, "VARCHAR", 0, 0, true);
    }

    /** Same column, re-typed as string (used when a transform writes string values into it). */
    static ResultSetColumn asString(ResultSetColumn column) {
        return new ResultSetColumn(column.index(), column.label(), column.outputName(),
            Types.VARCHAR, "VARCHAR", 0, 0, true);
    }

    static ResultSetColumn rename(ResultSetColumn column, String newName) {
        return new ResultSetColumn(column.index(), column.label(), newName,
            column.jdbcType(), column.jdbcTypeName(), column.precision(), column.scale(), column.nullable());
    }

    static ResultSetColumn require(List<ResultSetColumn> columns, String outputName, String transformType) {
        for (ResultSetColumn column : columns) {
            if (column.outputName().equals(outputName)) {
                return column;
            }
        }
        throw new ExportException(ExitCodes.TRANSFORM_ERROR,
            "Transform \"" + transformType + "\" references unknown column \"" + outputName + "\".");
    }

    static boolean exists(List<ResultSetColumn> columns, String outputName) {
        return columns.stream().anyMatch(column -> column.outputName().equals(outputName));
    }
}
