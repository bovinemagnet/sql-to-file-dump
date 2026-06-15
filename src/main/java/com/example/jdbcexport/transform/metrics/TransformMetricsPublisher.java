package com.example.jdbcexport.transform.metrics;

import com.example.jdbcexport.transform.TransformMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a completed pipeline's {@link TransformMetrics.Snapshot} to a Micrometer registry.
 *
 * <p>Metrics are aggregate (recorded once at the end of an export) to keep the per-row hot path
 * free of metrics work. Labels are bounded — pipeline name, transform name/type, status, drop
 * reason, error class — and never include row values, SQL text, or identifiers. Metric names follow
 * the {@code sql_transformer_transform_*} convention.
 */
public final class TransformMetricsPublisher {

    private TransformMetricsPublisher() {
    }

    public static void publish(MeterRegistry registry, String pipeline, String source, String output,
                               TransformMetrics.Snapshot snapshot, Duration totalDuration,
                               TransformMetricsSettings settings) {
        if (!settings.enabled()) {
            return;
        }

        Timer.builder("sql_transformer_transform_pipeline_duration_seconds")
            .tags("pipeline", pipeline, "source", source, "output", output, "status", "success")
            .register(registry)
            .record(totalDuration);

        registry.counter("sql_transformer_transform_rows_total", "pipeline", pipeline, "status", "success")
            .increment(snapshot.rowsOut());
        if (snapshot.rowsDroppedByFilter() > 0) {
            registry.counter("sql_transformer_transform_rows_dropped_total", "pipeline", pipeline, "reason", "filtered")
                .increment(snapshot.rowsDroppedByFilter());
        }
        if (snapshot.rowsDroppedByError() > 0) {
            registry.counter("sql_transformer_transform_rows_dropped_total", "pipeline", pipeline, "reason", "error")
                .increment(snapshot.rowsDroppedByError());
        }

        for (TransformMetrics.StepMetrics step : snapshot.steps()) {
            if (settings.perTransform()) {
                Timer.builder("sql_transformer_transform_duration_seconds")
                    .tags("pipeline", pipeline, "transform", step.name(), "type", step.type(), "status", "success")
                    .register(registry)
                    .record(step.totalNanos(), TimeUnit.NANOSECONDS);
            }
            if (step.failures() > 0) {
                registry.counter("sql_transformer_transform_errors_total",
                        "pipeline", pipeline, "transform", step.name(), "type", step.type(),
                        "error_class", "TransformException")
                    .increment(step.failures());
            }
        }
    }
}
