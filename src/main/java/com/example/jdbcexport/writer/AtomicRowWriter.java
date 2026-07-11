package com.example.jdbcexport.writer;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.transform.Row;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.UUID;

/**
 * Decorator that makes an export atomic (issue #24). The delegate writes to a temporary file in
 * the same directory as the target (same filesystem, so the final rename is atomic); the
 * temporary file is renamed onto the target only when {@link #finish()} completes successfully.
 * On failure the temporary file is removed by {@link #close()} and any pre-existing target file
 * is left untouched — a mid-stream error can no longer leave a syntactically valid but truncated
 * output file, nor destroy yesterday's good export under {@code --overwrite}.
 */
public final class AtomicRowWriter implements RowWriter {

    private final RowWriter delegate;
    private final Path temporaryPath;
    private final Path targetPath;
    private boolean committed;

    AtomicRowWriter(RowWriter delegate, Path temporaryPath, Path targetPath) {
        this.delegate = delegate;
        this.temporaryPath = temporaryPath;
        this.targetPath = targetPath;
    }

    /** Hidden sibling of the target so the rename never crosses a filesystem boundary. */
    static Path temporaryPathFor(Path targetPath) {
        Path absolute = targetPath.toAbsolutePath();
        String name = "." + absolute.getFileName() + "." + UUID.randomUUID() + ".tmp";
        return absolute.getParent().resolve(name);
    }

    /** The in-progress file being written, for live progress reporting (e.g. output bytes). */
    public Path temporaryPath() {
        return temporaryPath;
    }

    @Override
    public void start(ResultSetMetaData metaData) throws Exception {
        delegate.start(metaData);
    }

    @Override
    public void writeRow(ResultSet resultSet) throws Exception {
        delegate.writeRow(resultSet);
    }

    @Override
    public void writeRow(Row row) throws Exception {
        delegate.writeRow(row);
    }

    @Override
    public ExportWriteResult finish() throws Exception {
        ExportWriteResult result = delegate.finish();
        moveIntoPlace();
        committed = true;
        return new ExportWriteResult(result.rowCount(), targetPath);
    }

    @Override
    public void close() throws Exception {
        try {
            delegate.close();
        } finally {
            if (!committed) {
                Files.deleteIfExists(temporaryPath);
            }
        }
    }

    private void moveIntoPlace() {
        try {
            try {
                Files.move(temporaryPath, targetPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                "Failed to move completed export into place: " + targetPath, e);
        }
    }
}
