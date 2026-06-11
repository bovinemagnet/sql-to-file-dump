package com.example.jdbcexport.error;

public class ExportException extends RuntimeException {
    private final int exitCode;

    public ExportException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public ExportException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }
}
