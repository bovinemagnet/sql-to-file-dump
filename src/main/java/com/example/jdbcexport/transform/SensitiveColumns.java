package com.example.jdbcexport.transform;

import java.util.List;

/**
 * Implemented by transforms that produce sensitive output columns (e.g. masking). The pipeline
 * collects these so downstream surfaces (contract validation, the dashboard) can redact them and
 * never display their values.
 */
public interface SensitiveColumns {

    /** Output column names this transform marks as sensitive. */
    List<String> sensitiveColumns();
}
