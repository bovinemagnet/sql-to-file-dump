package com.example.jdbcexport.error;

public final class ExitCodes {
    public static final int SUCCESS = 0;
    public static final int INVALID_ARGUMENTS = 1;
    public static final int SQL_INPUT_ERROR = 2;
    public static final int DATABASE_ERROR = 3;
    public static final int OUTPUT_WRITE_ERROR = 4;
    public static final int SCHEMA_ERROR = 5;
    public static final int UNSUPPORTED_FORMAT = 6;
    public static final int TRANSFORM_ERROR = 7;
    public static final int UNEXPECTED_ERROR = 99;

    private ExitCodes() {
    }
}
