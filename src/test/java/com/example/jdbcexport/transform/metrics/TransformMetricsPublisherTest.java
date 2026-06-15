package com.example.jdbcexport.transform.metrics;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.ErrorStrategy;
import com.example.jdbcexport.transform.OutboundTransformer;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.transform.TransformContext;
import com.example.jdbcexport.transform.TransformInput;
import com.example.jdbcexport.transform.TransformPipeline;
import com.example.jdbcexport.transform.TransformResult;
import com.example.jdbcexport.transform.TransformSpec;
import com.example.jdbcexport.transform.builtin.RenameTransform;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransformMetricsPublisherTest {

    private static final TransformMetricsSettings ALL_ON = new TransformMetricsSettings(true, true, 50);

    private static TransformPipeline renamePipeline() {
        return new TransformPipeline(List.of(
            RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "a", "to", "b")))));
    }

    private static OutboundTransformer boom() {
        return new OutboundTransformer() {
            public String name() {
                return "boom";
            }

            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return columns;
            }

            public TransformResult transform(TransformInput input, TransformContext context) {
                throw new IllegalStateException("boom");
            }
        };
    }

    @Test
    void emitsPipelineTimerAndRowCounters() {
        TransformPipeline pipeline = renamePipeline();
        pipeline.transform(new Row(Map.of("a", 1)));
        pipeline.transform(new Row(Map.of("a", 2)));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransformMetricsPublisher.publish(registry, "p1", "cli", "csv",
            pipeline.metrics().snapshot(), Duration.ofMillis(5), ALL_ON);

        assertThat(registry.find("sql_transformer_transform_pipeline_duration_seconds").timer().count()).isEqualTo(1);
        assertThat(registry.find("sql_transformer_transform_rows_total").tag("status", "success").counter().count())
            .isEqualTo(2.0);
        assertThat(registry.find("sql_transformer_transform_duration_seconds").tag("transform", "rename").timer())
            .isNotNull();
    }

    @Test
    void perTransformFalseSkipsStepTimers() {
        TransformPipeline pipeline = renamePipeline();
        pipeline.transform(new Row(Map.of("a", 1)));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransformMetricsPublisher.publish(registry, "p1", "cli", "csv",
            pipeline.metrics().snapshot(), Duration.ofMillis(5), new TransformMetricsSettings(true, false, 50));

        assertThat(registry.find("sql_transformer_transform_duration_seconds").timer()).isNull();
        assertThat(registry.find("sql_transformer_transform_pipeline_duration_seconds").timer()).isNotNull();
    }

    @Test
    void disabledEmitsNothing() {
        TransformPipeline pipeline = renamePipeline();
        pipeline.transform(new Row(Map.of("a", 1)));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransformMetricsPublisher.publish(registry, "p1", "cli", "csv",
            pipeline.metrics().snapshot(), Duration.ofMillis(5), new TransformMetricsSettings(false, true, 50));

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void emitsFilteredDropReason() {
        OutboundTransformer dropper = new OutboundTransformer() {
            public String name() {
                return "filter";
            }

            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return columns;
            }

            public TransformResult transform(TransformInput input, TransformContext context) {
                return TransformResult.drop("filtered");
            }
        };
        TransformPipeline pipeline = new TransformPipeline(List.of(dropper));
        pipeline.transform(new Row(Map.of("a", 1)));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransformMetricsPublisher.publish(registry, "p1", "cli", "csv",
            pipeline.metrics().snapshot(), Duration.ofMillis(5), ALL_ON);

        assertThat(registry.find("sql_transformer_transform_rows_dropped_total").tag("reason", "filtered").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void emitsErrorAndDroppedCounters() {
        TransformPipeline pipeline = new TransformPipeline(
            List.of(new TransformPipeline.Step("boom", boom())), ErrorStrategy.SKIP_ROW);
        pipeline.transform(new Row(Map.of("a", 1)));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransformMetricsPublisher.publish(registry, "p1", "cli", "csv",
            pipeline.metrics().snapshot(), Duration.ofMillis(5), ALL_ON);

        assertThat(registry.find("sql_transformer_transform_rows_dropped_total").tag("reason", "error").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.find("sql_transformer_transform_errors_total").tag("transform", "boom").counter().count())
            .isEqualTo(1.0);
    }
}
