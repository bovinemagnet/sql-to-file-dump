package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.jdbc.JdbcUrlRedactor;
import com.example.jdbcexport.transform.TransformMetrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ExportJob {

    private static final DateTimeFormatter DISPLAY_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public enum Status {
        QUEUED, RUNNING, COMPLETED, FAILED
    }

    private final String id;
    private final Instant submittedAt;
    private final String url;
    private final String user;
    private final String sql;
    private final OutputFormat format;
    private final String output;
    private final String driver;
    private final int fetchSize;
    private final String compression;

    private volatile Status status = Status.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile long rowCount;
    private volatile String writePath;
    private volatile int columnCount;
    private volatile String serverInfo;
    private volatile String error;

    private final boolean transformsEnabled;
    private final String errorStrategy;
    private volatile TransformMetrics.Snapshot transformMetrics;
    private volatile long slowTransformThresholdMs = 50;

    ExportJob(String id, Instant submittedAt, ExportJobRequest request, int fetchSize) {
        this.id = id;
        this.submittedAt = submittedAt;
        // Issue #30: the URL is display-only here and is echoed by the JSON API, so any
        // inline credentials are redacted at the point of storage.
        this.url = JdbcUrlRedactor.redact(request.url());
        this.user = request.user();
        this.sql = request.sql();
        this.format = request.format();
        this.output = request.output();
        this.driver = driverOf(request.url());
        this.fetchSize = fetchSize;
        this.compression = request.parquetCompression();
        this.transformsEnabled = request.hasTransforms();
        this.errorStrategy = request.errorStrategy();
    }

    /** Best-effort driver family from a JDBC URL (jdbc:<driver>:...), for display only. */
    static String driverOf(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "jdbc";
        }
        String[] parts = jdbcUrl.split(":", 3);
        return parts.length >= 2 ? parts[1].toLowerCase(java.util.Locale.ROOT) : "jdbc";
    }

    void markRunning(Instant now) {
        status = Status.RUNNING;
        startedAt = now;
    }

    void recordProgress(long rows) {
        rowCount = rows;
    }

    /** Records the temporary file being streamed to, so live output bytes stay real (issue #24). */
    void recordWritePath(String path) {
        writePath = path;
    }

    void recordSchema(int columns, String server) {
        columnCount = columns;
        serverInfo = server;
    }

    void markCompleted(Instant now, long rows) {
        status = Status.COMPLETED;
        completedAt = now;
        rowCount = rows;
    }

    void markFailed(Instant now, String message) {
        status = Status.FAILED;
        completedAt = now;
        // Issue #30: driver failure messages frequently echo the full connection string.
        error = JdbcUrlRedactor.redact(message);
    }

    void recordTransformMetrics(TransformMetrics.Snapshot snapshot, long slowThresholdMs) {
        this.transformMetrics = snapshot;
        this.slowTransformThresholdMs = slowThresholdMs;
    }

    public boolean isTransformsEnabled() {
        return transformsEnabled;
    }

    public String getErrorStrategy() {
        return errorStrategy;
    }

    public TransformMetrics.Snapshot getTransformMetrics() {
        return transformMetrics;
    }

    public long getSlowTransformThresholdMs() {
        return slowTransformThresholdMs;
    }

    public String getId() {
        return id;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getUrl() {
        return url;
    }

    public String getUser() {
        return user;
    }

    public String getSql() {
        return sql;
    }

    public OutputFormat getFormat() {
        return format;
    }

    public String getOutput() {
        return output;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getRowCount() {
        return rowCount;
    }

    public String getError() {
        return error;
    }

    public boolean isFinished() {
        return status == Status.COMPLETED || status == Status.FAILED;
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public String getSubmittedAtDisplay() {
        return DISPLAY_FORMAT.format(submittedAt);
    }

    public Long getDurationMillis() {
        Instant started = startedAt;
        Instant completed = completedAt;
        if (started == null || completed == null) {
            return null;
        }
        return completed.toEpochMilli() - started.toEpochMilli();
    }

    public String getDriver() {
        return driver;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public String getCompression() {
        return compression;
    }

    public int getColumnCount() {
        return columnCount;
    }

    public String getServerInfo() {
        return serverInfo;
    }

    /** Wall-clock time since the run started; 0 before it starts. Stops growing once finished. */
    public long getElapsedMillis() {
        Instant started = startedAt;
        if (started == null) {
            return 0L;
        }
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Math.max(0L, end.toEpochMilli() - started.toEpochMilli());
    }

    /** Average rows per second over the elapsed run; 0 until enough time has passed. */
    public double getThroughputRowsPerSecond() {
        long elapsed = getElapsedMillis();
        return elapsed > 0 ? rowCount * 1000.0 / elapsed : 0.0;
    }

    /**
     * Current size of the output file on disk, or 0 if it does not exist yet. While the job is
     * running the export streams to a temporary file (issue #24), so that file is measured instead.
     */
    public long getOutputBytes() {
        String measured = status == Status.RUNNING && writePath != null ? writePath : output;
        if (measured == null) {
            return 0L;
        }
        try {
            Path path = Path.of(measured);
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}
