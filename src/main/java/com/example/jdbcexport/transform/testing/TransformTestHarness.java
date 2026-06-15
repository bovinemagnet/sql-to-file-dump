package com.example.jdbcexport.transform.testing;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.OutboundTransformer;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.transform.TransformPipeline;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A tiny, framework-agnostic harness for testing a single {@link Transform} or a whole
 * {@link TransformPipeline} against a fixture-style input and expected output. Plugin authors can
 * use it to test a transform without starting the application or touching a database.
 *
 * <pre>{@code
 * TransformTestHarness.forTransform(new StaffDisplayTransformer())
 *     .withInput(Map.of("first_name", "Jane", "last_name", "Citizen"))
 *     .expectOutput(Map.of("displayName", "Citizen, Jane"))
 *     .run();
 * }</pre>
 *
 * Assertions throw {@link AssertionError} so the harness has no test-framework dependency.
 */
public final class TransformTestHarness {

    private final TransformPipeline pipeline;
    private Map<String, Object> input = Map.of();
    private List<ResultSetColumn> schema;

    private TransformTestHarness(TransformPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public static TransformTestHarness forTransform(OutboundTransformer transformer) {
        return new TransformTestHarness(new TransformPipeline(List.of(transformer)));
    }

    public static TransformTestHarness forPipeline(TransformPipeline pipeline) {
        return new TransformTestHarness(pipeline);
    }

    public TransformTestHarness withInput(Map<String, Object> input) {
        this.input = new LinkedHashMap<>(input);
        return this;
    }

    /** Override the inferred input schema (otherwise every input key is treated as a string column). */
    public TransformTestHarness withSchema(List<ResultSetColumn> schema) {
        this.schema = schema;
        return this;
    }

    /** Assert the row survives and matches every entry in {@code expected} (extra columns ignored). */
    public void expectOutput(Map<String, Object> expected) {
        Row result = execute();
        if (result == null) {
            throw new AssertionError("Expected an output row but the row was dropped.");
        }
        expected.forEach((key, value) -> {
            Object actual = result.get(key);
            if (!Objects.equals(value, actual)) {
                throw new AssertionError("Field \"" + key + "\": expected <" + value + "> but was <" + actual + ">.");
            }
        });
    }

    /** Assert the row is dropped. */
    public void expectDropped() {
        if (execute() != null) {
            throw new AssertionError("Expected the row to be dropped but it was emitted.");
        }
    }

    /** Assert the pipeline throws (fail-fast strategy). */
    public void expectFailure() {
        try {
            execute();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("Expected the transform to fail but it succeeded.");
    }

    /** Run and return the transformed row (or null if dropped). */
    public Row run() {
        return execute();
    }

    private Row execute() {
        List<ResultSetColumn> effectiveSchema = schema != null ? schema : inferSchema(input);
        pipeline.outputSchema(effectiveSchema);
        return pipeline.transform(new Row(input));
    }

    private static List<ResultSetColumn> inferSchema(Map<String, Object> input) {
        List<ResultSetColumn> columns = new ArrayList<>(input.size());
        int index = 1;
        for (String name : input.keySet()) {
            columns.add(new ResultSetColumn(index++, name, name, Types.VARCHAR, "VARCHAR", 0, 0, true));
        }
        return columns;
    }
}
