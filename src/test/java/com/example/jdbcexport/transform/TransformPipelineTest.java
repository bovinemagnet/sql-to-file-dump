package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.builtin.RenameTransform;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformPipelineTest {

    private static ResultSetColumn col(int index, String name) {
        return new ResultSetColumn(index, name, name, Types.VARCHAR, "", 0, 0, true);
    }

    /** A pass-through-schema transformer whose row behaviour is supplied as a lambda. */
    private static OutboundTransformer transformer(String name,
            BiFunction<TransformInput, TransformContext, TransformResult> fn) {
        return new OutboundTransformer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return columns;
            }

            @Override
            public TransformResult transform(TransformInput input, TransformContext context) {
                return fn.apply(input, context);
            }
        };
    }

    /** A drop filter so we can exercise the dropped-row path and metrics. */
    private static OutboundTransformer dropWhereNameIs(String value) {
        return new OutboundTransformer() {
            @Override
            public String name() {
                return "test-filter";
            }

            @Override
            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return columns;
            }

            @Override
            public TransformResult transform(TransformInput input, TransformContext context) {
                return value.equals(input.row().get("name"))
                    ? TransformResult.drop("name matched") : TransformResult.keep(input.row());
            }
        };
    }

    @Test
    void emptyPipelineIsPassThrough() {
        TransformPipeline pipeline = TransformPipeline.empty();
        assertThat(pipeline.isEmpty()).isTrue();
        List<ResultSetColumn> columns = List.of(col(1, "a"));
        assertThat(pipeline.outputSchema(columns)).isEqualTo(columns);
    }

    @Test
    void appliesTransformsInOrder() {
        TransformPipeline pipeline = new TransformPipeline(List.of(
            RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "a", "to", "b"))),
            RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "b", "to", "c")))
        ));
        assertThat(pipeline.outputSchema(List.of(col(1, "a"))).get(0).outputName()).isEqualTo("c");

        Row row = new Row(Map.of("a", "v"));
        Row out = pipeline.transform(row);
        assertThat(out.get("c")).isEqualTo("v");
    }

    @Test
    void dropsRowsAndRecordsMetrics() {
        TransformPipeline pipeline = new TransformPipeline(List.of(dropWhereNameIs("skip")));

        assertThat(pipeline.transform(new Row(Map.of("name", "keep")))).isNotNull();
        assertThat(pipeline.transform(new Row(Map.of("name", "skip")))).isNull();

        TransformMetrics.Snapshot snapshot = pipeline.metrics().snapshot();
        assertThat(snapshot.rowsIn()).isEqualTo(2);
        assertThat(snapshot.rowsOut()).isEqualTo(1);
        assertThat(snapshot.rowsDropped()).isEqualTo(1);
        assertThat(snapshot.steps()).singleElement()
            .satisfies(step -> assertThat(step.invocations()).isEqualTo(2));
    }

    @Test
    void runtimeFailureBecomesTransformErrorAndIsCounted() {
        OutboundTransformer boom = new OutboundTransformer() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return columns;
            }

            @Override
            public TransformResult transform(TransformInput input, TransformContext context) {
                throw new IllegalStateException("secret row value 12345");
            }
        };
        TransformPipeline pipeline = new TransformPipeline(List.of(boom));
        assertThatThrownBy(() -> pipeline.transform(new Row(Map.of("a", "x"))))
            .isInstanceOf(ExportException.class)
            // The arbitrary exception's message (which could carry row data) must not leak.
            .hasMessageNotContaining("12345")
            .hasMessageContaining("boom");
        assertThat(pipeline.metrics().snapshot().steps().get(0).failures()).isEqualTo(1);
    }

    private static OutboundTransformer boom() {
        return new OutboundTransformer() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return columns;
            }

            @Override
            public TransformResult transform(TransformInput input, TransformContext context) {
                throw new IllegalStateException("kaboom");
            }
        };
    }

    @Test
    void skipRowStrategyDropsFailedRowAndContinues() {
        TransformPipeline pipeline = new TransformPipeline(
            List.of(new TransformPipeline.Step("boom", boom())), ErrorStrategy.SKIP_ROW);
        assertThat(pipeline.transform(new Row(Map.of("a", "x")))).isNull();
        TransformMetrics.Snapshot snapshot = pipeline.metrics().snapshot();
        assertThat(snapshot.rowsDropped()).isEqualTo(1);
        assertThat(snapshot.steps().get(0).failures()).isEqualTo(1);
    }

    @Test
    void keepOriginalStrategyEmitsPrePipelineRow() {
        TransformPipeline pipeline = new TransformPipeline(List.of(
            new TransformPipeline.Step("rename",
                RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "a", "to", "b")))),
            new TransformPipeline.Step("boom", boom())
        ), ErrorStrategy.KEEP_ORIGINAL);

        Row out = pipeline.transform(new Row(Map.of("a", 1)));
        assertThat(out).isNotNull();
        // The original row (before the rename mutated it) is emitted.
        assertThat(out.get("a")).isEqualTo(1);
        assertThat(out.has("b")).isFalse();
        assertThat(pipeline.metrics().snapshot().rowsOut()).isEqualTo(1);
    }

    @Test
    void collectsSensitiveColumns() {
        TransformPipeline pipeline = new TransformRegistry().build(List.of(
            new TransformSpec("mask", Map.of("columns", List.of("email", "dob")))));
        assertThat(pipeline.sensitiveColumns()).containsExactlyInAnyOrder("email", "dob");
    }

    @Test
    void explicitFailResultIsSurfacedUnderFailStrategy() {
        TransformPipeline pipeline = new TransformPipeline(
            List.of(new TransformPipeline.Step("checker",
                transformer("checker", (in, ctx) -> TransformResult.fail("bad value")))),
            ErrorStrategy.FAIL);
        assertThatThrownBy(() -> pipeline.transform(new Row(Map.of("a", "x"))))
            .isInstanceOf(ExportException.class)
            // A value-free Fail message (no cause) is surfaced, with the row number.
            .hasMessageContaining("bad value")
            .hasMessageContaining("row 1");
    }

    @Test
    void explicitFailResultIsSkippedUnderSkipRow() {
        TransformPipeline pipeline = new TransformPipeline(
            List.of(new TransformPipeline.Step("checker",
                transformer("checker", (in, ctx) -> TransformResult.fail("bad value")))),
            ErrorStrategy.SKIP_ROW);
        assertThat(pipeline.transform(new Row(Map.of("a", "x")))).isNull();
        assertThat(pipeline.metrics().snapshot().rowsDroppedByError()).isEqualTo(1);
    }

    @Test
    void dropShortCircuitsLaterSteps() {
        TransformPipeline pipeline = new TransformPipeline(List.of(
            transformer("dropper", (in, ctx) -> TransformResult.drop("filtered")),
            boom() // would throw if reached
        ));
        assertThat(pipeline.transform(new Row(Map.of("a", "x")))).isNull();
        TransformMetrics.Snapshot snapshot = pipeline.metrics().snapshot();
        assertThat(snapshot.rowsDroppedByFilter()).isEqualTo(1);
        assertThat(snapshot.steps().get(1).invocations()).isZero(); // boom never ran
    }

    @Test
    void keepMayReplaceTheRow() {
        TransformPipeline pipeline = new TransformPipeline(List.of(
            transformer("replacer", (in, ctx) -> {
                Row replaced = new Row();
                replaced.put("x", "y");
                return TransformResult.keep(replaced);
            })));
        Row out = pipeline.transform(new Row(Map.of("a", "1")));
        assertThat(out.get("x")).isEqualTo("y");
        assertThat(out.has("a")).isFalse();
    }

    @Test
    void contextCarriesRowNumberAndInputExposesValues() {
        List<Long> rowNumbers = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        TransformPipeline pipeline = new TransformPipeline(List.of(
            transformer("recorder", (in, ctx) -> {
                rowNumbers.add(ctx.rowNumber());
                values.add(in.value("a"));
                assertThat(ctx.pipeline()).isNotBlank();
                return TransformResult.keep(in.row());
            })));
        pipeline.transform(new Row(Map.of("a", "first")));
        pipeline.transform(new Row(Map.of("a", "second")));

        assertThat(rowNumbers).containsExactly(1L, 2L);
        assertThat(values).containsExactly("first", "second");
    }

    @Test
    void outputSchemaFailsWhenAllColumnsRemoved() {
        TransformPipeline pipeline = new TransformPipeline(List.of(new OutboundTransformer() {
            @Override
            public String name() {
                return "wipe";
            }

            @Override
            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return List.of();
            }

            @Override
            public TransformResult transform(TransformInput input, TransformContext context) {
                return TransformResult.keep(input.row());
            }
        }));
        assertThatThrownBy(() -> pipeline.outputSchema(List.of(col(1, "a"))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("nothing would be exported");
    }
}
