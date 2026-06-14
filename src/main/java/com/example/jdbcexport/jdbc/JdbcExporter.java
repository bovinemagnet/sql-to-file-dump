package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.writer.RowWriter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

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
}
