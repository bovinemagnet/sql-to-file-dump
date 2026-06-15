package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.transform.TransformPipeline;
import com.example.jdbcexport.writer.RowWriter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public final class JdbcExporter {

    /** Notifies how many rows have been written so far, so callers can surface live progress. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long rowCount);

        ProgressListener NONE = rowCount -> {
        };
    }

    /** Report progress at most this often, to keep the hot row loop cheap. */
    private static final int PROGRESS_INTERVAL = 256;

    public record ExportResult(long rowCount, long durationMillis) {
    }

    public ExportResult export(Connection connection, String sql, int fetchSize, Long maxRows, RowWriter writer) {
        return export(connection, sql, fetchSize, maxRows, writer, ProgressListener.NONE);
    }

    public ExportResult export(Connection connection, String sql, int fetchSize, Long maxRows, RowWriter writer,
                               ProgressListener progress) {
        long startMs = System.currentTimeMillis();
        long rowCount = 0;

        try (PreparedStatement stmt = connection.prepareStatement(sql,
            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            stmt.setFetchSize(fetchSize);

            try (ResultSet rs = stmt.executeQuery()) {
                writer.start(rs.getMetaData());
                while (rs.next()) {
                    if (maxRows != null && rowCount >= maxRows) {
                        break;
                    }
                    writer.writeRow(rs);
                    rowCount++;
                    if (rowCount % PROGRESS_INTERVAL == 0) {
                        progress.onProgress(rowCount);
                    }
                }
                writer.finish();
                progress.onProgress(rowCount);
            }
        } catch (ExportException e) {
            throw e;
        } catch (SQLException e) {
            throw new ExportException(ExitCodes.DATABASE_ERROR, "Export failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR, "Failed while writing export output: " + e.getMessage(), e);
        }

        return new ExportResult(rowCount, System.currentTimeMillis() - startMs);
    }

    /**
     * Export through an outbound transform pipeline. When {@code pipeline} is null or empty this is
     * the same fast path as {@link #export(Connection, String, int, Long, RowWriter, ProgressListener)};
     * otherwise each row is materialised into a {@link Row}, transformed, and written via
     * {@link RowWriter#writeRow(Row)}. {@code maxRows} bounds the rows read from the result set;
     * the returned count is the number of rows actually written (after any drops).
     */
    public ExportResult export(Connection connection, String sql, int fetchSize, Long maxRows, RowWriter writer,
                               ProgressListener progress, List<ResultSetColumn> inputColumns, TransformPipeline pipeline) {
        if (pipeline == null || pipeline.isEmpty()) {
            return export(connection, sql, fetchSize, maxRows, writer, progress);
        }

        long startMs = System.currentTimeMillis();
        long readCount = 0;
        long writtenCount = 0;

        try (PreparedStatement stmt = connection.prepareStatement(sql,
            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            stmt.setFetchSize(fetchSize);

            try (ResultSet rs = stmt.executeQuery()) {
                writer.start(rs.getMetaData());
                while (rs.next()) {
                    if (maxRows != null && readCount >= maxRows) {
                        break;
                    }
                    readCount++;
                    Row row = new Row();
                    for (ResultSetColumn column : inputColumns) {
                        row.put(column.outputName(), JdbcValueReader.readAsObject(rs, column.index(), column.jdbcType()));
                    }
                    Row transformed = pipeline.transform(row);
                    if (transformed != null) {
                        writer.writeRow(transformed);
                        writtenCount++;
                        if (writtenCount % PROGRESS_INTERVAL == 0) {
                            progress.onProgress(writtenCount);
                        }
                    }
                }
                writer.finish();
                progress.onProgress(writtenCount);
            }
        } catch (ExportException e) {
            throw e;
        } catch (SQLException e) {
            throw new ExportException(ExitCodes.DATABASE_ERROR, "Export failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR, "Failed while writing export output: " + e.getMessage(), e);
        }

        return new ExportResult(writtenCount, System.currentTimeMillis() - startMs);
    }
}
