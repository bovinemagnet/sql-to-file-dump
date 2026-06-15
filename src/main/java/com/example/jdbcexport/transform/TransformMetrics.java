package com.example.jdbcexport.transform;

import java.util.ArrayList;
import java.util.List;

/**
 * Observability for a transform pipeline run. Records counts and per-step timing only — never row
 * values — so metrics are safe to log and surface. A single instance is owned by one
 * {@link TransformPipeline} and updated on its single export thread.
 */
public final class TransformMetrics {

    /** Per-step timing. A step is "slow" when its share of total transform time is high. */
    public record StepMetrics(String name, String type, long invocations, long failures, long totalNanos) {
        public long totalMillis() {
            return totalNanos / 1_000_000L;
        }

        public double averageMillisPerRow() {
            return invocations == 0 ? 0.0 : (totalNanos / 1_000_000.0) / invocations;
        }
    }

    public record Snapshot(long rowsIn, long rowsOut, long rowsDropped, long rowsDroppedByError,
                           List<StepMetrics> steps) {
        public boolean isEmpty() {
            return steps.isEmpty();
        }

        /** Rows dropped by a transform deliberately returning null (a filter), not by an error. */
        public long rowsDroppedByFilter() {
            return rowsDropped - rowsDroppedByError;
        }
    }

    private static final class MutableStep {
        final String name;
        final String type;
        long invocations;
        long failures;
        long totalNanos;

        MutableStep(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private final List<MutableStep> steps = new ArrayList<>();
    private long rowsIn;
    private long rowsOut;
    private long rowsDropped;
    private long rowsDroppedByError;

    int registerStep(String name, String type) {
        steps.add(new MutableStep(name, type));
        return steps.size() - 1;
    }

    void recordRowIn() {
        rowsIn++;
    }

    void recordRowOut() {
        rowsOut++;
    }

    void recordRowDropped(boolean dueToError) {
        rowsDropped++;
        if (dueToError) {
            rowsDroppedByError++;
        }
    }

    void recordStep(int index, long nanos) {
        MutableStep step = steps.get(index);
        step.invocations++;
        step.totalNanos += nanos;
    }

    void recordStepFailure(int index) {
        steps.get(index).failures++;
    }

    public Snapshot snapshot() {
        List<StepMetrics> stepSnapshots = new ArrayList<>(steps.size());
        for (MutableStep step : steps) {
            stepSnapshots.add(new StepMetrics(step.name, step.type, step.invocations, step.failures, step.totalNanos));
        }
        return new Snapshot(rowsIn, rowsOut, rowsDropped, rowsDroppedByError, List.copyOf(stepSnapshots));
    }

    /** Single-line, row-data-free summary suitable for logging. */
    public String summary() {
        Snapshot snapshot = snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("transforms: ").append(snapshot.rowsIn()).append(" in, ")
            .append(snapshot.rowsOut()).append(" out, ")
            .append(snapshot.rowsDropped()).append(" dropped");
        for (StepMetrics step : snapshot.steps()) {
            sb.append("; ").append(step.name()).append('=').append(step.totalMillis()).append("ms");
            if (step.failures() > 0) {
                sb.append(" (").append(step.failures()).append(" failed)");
            }
        }
        return sb.toString();
    }
}
