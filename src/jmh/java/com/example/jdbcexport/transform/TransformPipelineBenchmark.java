package com.example.jdbcexport.transform;

import com.example.jdbcexport.transform.builtin.DropTransform;
import com.example.jdbcexport.transform.builtin.MapTransform;
import com.example.jdbcexport.transform.builtin.MaskTransform;
import com.example.jdbcexport.transform.builtin.RenameTransform;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Measures per-row transform overhead. The {@code rowConstructionOnly} benchmark is the lower-bound
 * baseline (the no-transform fast path never builds a pipeline at all); the {@code runPipeline}
 * cases show the cost of common pipelines over narrow (5-field) and wide (100-field) rows.
 *
 * <p>Run with: {@code gradle21w jmh}
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class TransformPipelineBenchmark {

    @Param({"5", "100"})
    public int width;

    @Param({"none", "rename", "renameDropMap", "mask"})
    public String pipelineName;

    private TransformPipeline pipeline;
    private Map<String, Object> rowData;

    @Setup
    public void setup() {
        rowData = new LinkedHashMap<>();
        for (int i = 0; i < width; i++) {
            rowData.put("f" + i, "value" + i);
        }
        pipeline = switch (pipelineName) {
            case "rename" -> new TransformPipeline(List.of(
                RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "f0", "to", "r0")))));
            case "renameDropMap" -> new TransformPipeline(List.of(
                RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "f0", "to", "r0"))),
                DropTransform.PROVIDER.create(new TransformSpec("drop", Map.of("columns", List.of("f1")))),
                MapTransform.PROVIDER.create(new TransformSpec("map", Map.of("column", "f2",
                    "mapping", Map.of("value2", "MAPPED"))))));
            case "mask" -> new TransformPipeline(List.of(
                MaskTransform.PROVIDER.create(new TransformSpec("mask", Map.of("columns", List.of("f0", "f1"))))));
            default -> TransformPipeline.empty();
        };
    }

    @Benchmark
    public Row rowConstructionOnly() {
        return new Row(rowData);
    }

    @Benchmark
    public Row runPipeline() {
        return pipeline.transform(new Row(rowData));
    }
}
