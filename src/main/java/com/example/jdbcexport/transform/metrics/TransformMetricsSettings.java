package com.example.jdbcexport.transform.metrics;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Metrics toggles, read from configuration ({@code transform.metrics.*}) with safe defaults.
 *
 * @param enabled                  emit transform metrics at all
 * @param perTransform             emit a timer per transform step (not just the pipeline total)
 * @param slowTransformThresholdMs a step whose total time reaches this is logged/flagged as slow
 */
public record TransformMetricsSettings(boolean enabled, boolean perTransform, long slowTransformThresholdMs) {

    public static TransformMetricsSettings fromConfig() {
        var config = ConfigProvider.getConfig();
        boolean enabled = config.getOptionalValue("transform.metrics.enabled", Boolean.class).orElse(true);
        boolean perTransform = config.getOptionalValue("transform.metrics.perTransform", Boolean.class).orElse(true);
        long slow = config.getOptionalValue("transform.metrics.slowTransformThresholdMs", Long.class).orElse(50L);
        return new TransformMetricsSettings(enabled, perTransform, slow);
    }

    public boolean isSlow(long totalMillis) {
        return totalMillis >= slowTransformThresholdMs;
    }
}
