package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional output-shape contract validated against the resolved post-transform schema, before any
 * rows are read. Validation messages name the offending field but never include row values.
 *
 * @param requiredFields       output columns that must be present
 * @param optionalFields       additional columns allowed when {@code failOnUnknownFields} is set
 * @param failOnUnknownFields  when true, every output column must be a required or optional field
 */
public record OutputContract(List<String> requiredFields, List<String> optionalFields, boolean failOnUnknownFields) {

    public OutputContract {
        requiredFields = List.copyOf(requiredFields == null ? List.of() : requiredFields);
        optionalFields = List.copyOf(optionalFields == null ? List.of() : optionalFields);
    }

    public void validate(List<ResultSetColumn> outputColumns) {
        Set<String> present = new LinkedHashSet<>();
        for (ResultSetColumn column : outputColumns) {
            present.add(column.outputName());
        }
        for (String required : requiredFields) {
            if (!present.contains(required)) {
                throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                    "Output contract violation: required field \"" + required + "\" is missing after transforms.");
            }
        }
        if (failOnUnknownFields) {
            Set<String> allowed = new LinkedHashSet<>(requiredFields);
            allowed.addAll(optionalFields);
            for (String name : present) {
                if (!allowed.contains(name)) {
                    throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                        "Output contract violation: unexpected field \"" + name + "\" in output.");
                }
            }
        }
    }
}
