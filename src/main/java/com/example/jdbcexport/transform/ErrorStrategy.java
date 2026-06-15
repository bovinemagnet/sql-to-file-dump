package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;

/**
 * What the pipeline does when a transform throws while processing a row. The default is
 * {@link #FAIL} — silent data corruption is worse than a visible failure.
 */
public enum ErrorStrategy {

    /** Abort the export. */
    FAIL("fail"),
    /** Drop the offending row and continue. */
    SKIP_ROW("skipRow"),
    /** Emit the row as it entered the pipeline and continue. */
    KEEP_ORIGINAL("keepOriginal");

    private final String configName;

    ErrorStrategy(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static ErrorStrategy fromConfig(String value) {
        for (ErrorStrategy strategy : values()) {
            if (strategy.configName.equalsIgnoreCase(value)) {
                return strategy;
            }
        }
        throw new ExportException(ExitCodes.TRANSFORM_ERROR,
            "Unknown errorStrategy \"" + value + "\". Valid values: fail, skipRow, keepOriginal.");
    }
}
