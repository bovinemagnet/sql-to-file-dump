package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.OutputFormat;

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

    private volatile Status status = Status.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile long rowCount;
    private volatile String error;

    ExportJob(String id, Instant submittedAt, ExportJobRequest request) {
        this.id = id;
        this.submittedAt = submittedAt;
        this.url = request.url();
        this.user = request.user();
        this.sql = request.sql();
        this.format = request.format();
        this.output = request.output();
    }

    void markRunning(Instant now) {
        status = Status.RUNNING;
        startedAt = now;
    }

    void markCompleted(Instant now, long rows) {
        status = Status.COMPLETED;
        completedAt = now;
        rowCount = rows;
    }

    void markFailed(Instant now, String message) {
        status = Status.FAILED;
        completedAt = now;
        error = message;
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
}
