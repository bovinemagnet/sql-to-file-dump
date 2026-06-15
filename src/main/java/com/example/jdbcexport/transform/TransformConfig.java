package com.example.jdbcexport.transform;

import java.util.List;

/**
 * The fully-parsed transform configuration: the ordered specs plus pipeline-wide settings
 * (error strategy and optional output contract). Built by {@link TransformConfigLoader} and turned
 * into a {@link TransformPipeline} by {@link TransformRegistry#build(TransformConfig)}.
 */
public record TransformConfig(List<TransformSpec> specs, ErrorStrategy errorStrategy, OutputContract outputContract) {

    public boolean isEmpty() {
        return specs.isEmpty();
    }
}
