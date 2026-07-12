package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An ordered chain of {@link OutboundTransformer}s applied to outbound rows.
 *
 * <p>When empty the pipeline is a pure pass-through and the export uses its fast path without ever
 * materialising a {@link Row}. When non-empty, {@link #outputSchema} is resolved once up front
 * (failing fast on bad column references) and {@link #transform} runs per row, recording
 * {@link TransformMetrics}. Each transformer returns a {@link TransformResult}; a {@code Fail} is
 * handled per the configured {@link ErrorStrategy}.
 *
 * <p>{@code transform(Row)} is the stable boundary used by the exporter, daemon and test harness —
 * the {@link TransformResult}/{@link TransformContext} machinery is internal to this class.
 */
public final class TransformPipeline {

    /** A named transform step; the name (config {@code name} or the type) labels metrics and errors. */
    public record Step(String name, OutboundTransformer transformer) {
    }

    private static final String PIPELINE_NAME = "default";

    private final List<Step> steps;
    private final ErrorStrategy errorStrategy;
    private final TransformMetrics metrics = new TransformMetrics();
    private long rowNumber;
    /** Per-step output column names resolved by {@link #outputSchema}; null until resolved. */
    private List<Set<String>> stepOutputNames;
    private boolean outputShapeVerified;

    /** Convenience: name each step by its transformer name, fail-fast on error. */
    public TransformPipeline(List<OutboundTransformer> transformers) {
        this(transformers.stream().map(t -> new Step(t.name(), t)).toList(), ErrorStrategy.FAIL);
    }

    public TransformPipeline(List<Step> steps, ErrorStrategy errorStrategy) {
        this.steps = List.copyOf(steps);
        this.errorStrategy = errorStrategy;
        for (Step step : this.steps) {
            metrics.registerStep(step.name(), step.transformer().name());
        }
    }

    public static TransformPipeline empty() {
        return new TransformPipeline(List.<Step>of(), ErrorStrategy.FAIL);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public ErrorStrategy errorStrategy() {
        return errorStrategy;
    }

    public TransformMetrics metrics() {
        return metrics;
    }

    /** Output columns this pipeline marks sensitive (e.g. masked), for redaction. */
    public Set<String> sensitiveColumns() {
        Set<String> sensitive = new LinkedHashSet<>();
        for (Step step : steps) {
            if (step.transformer() instanceof SensitiveColumns sc) {
                sensitive.addAll(sc.sensitiveColumns());
            }
        }
        return sensitive;
    }

    /**
     * Resolve the output columns by threading the input columns through each transformer's schema
     * step in order. Called once before any rows are read; column-reference errors surface here.
     */
    public List<ResultSetColumn> outputSchema(List<ResultSetColumn> inputColumns) {
        List<ResultSetColumn> columns = inputColumns;
        List<Set<String>> resolvedNames = new ArrayList<>(steps.size());
        for (Step step : steps) {
            columns = List.copyOf(step.transformer().transformSchema(columns));
            if (columns.isEmpty()) {
                throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                    "Transform \"" + step.name() + "\" removed all columns; nothing would be exported.");
            }
            Set<String> names = new LinkedHashSet<>();
            for (ResultSetColumn column : columns) {
                names.add(column.outputName());
            }
            resolvedNames.add(names);
        }
        validateKeepOriginalIsCoherent(inputColumns, columns);
        this.stepOutputNames = resolvedNames;
        return columns;
    }

    /**
     * {@link ErrorStrategy#KEEP_ORIGINAL} emits the untransformed input row when a step fails. That is
     * only coherent when the fallback row still conforms to the resolved output schema and carries no
     * sensitive values. It fails both ways otherwise: a renamed or added output column is absent from
     * the input snapshot (the writer, which keys by output name, would emit null), and a masked column
     * holds its unmasked value in the snapshot (a data leak, while {@link #sensitiveColumns()} still
     * reports it redacted). Dropping or reordering columns stays safe — the writer ignores the extra
     * snapshot keys. Rejected up front so a schedule or CLI run never silently corrupts or leaks a row.
     */
    private void validateKeepOriginalIsCoherent(List<ResultSetColumn> inputColumns,
                                                List<ResultSetColumn> outputColumns) {
        if (errorStrategy != ErrorStrategy.KEEP_ORIGINAL) {
            return;
        }
        Set<String> inputNames = new LinkedHashSet<>();
        for (ResultSetColumn column : inputColumns) {
            inputNames.add(column.outputName());
        }
        List<String> introduced = new ArrayList<>();
        for (ResultSetColumn column : outputColumns) {
            if (!inputNames.contains(column.outputName())) {
                introduced.add(column.outputName());
            }
        }
        List<String> sensitive = new ArrayList<>(sensitiveColumns());
        if (introduced.isEmpty() && sensitive.isEmpty()) {
            return;
        }
        StringBuilder reason = new StringBuilder();
        if (!introduced.isEmpty()) {
            reason.append(" renames or adds output columns ").append(introduced);
        }
        if (!sensitive.isEmpty()) {
            reason.append(reason.isEmpty() ? " masks columns " : " and masks columns ").append(sensitive);
        }
        throw new ExportException(ExitCodes.TRANSFORM_ERROR,
            "Error strategy \"keepOriginal\" cannot be used with a pipeline that" + reason
                + "; the original row would be emitted mis-shaped or with unmasked values. "
                + "Use \"skipRow\" or \"fail\" instead.");
    }

    /**
     * Apply every transformer to {@code row} in order. Returns the reshaped row, or {@code null} if
     * a transformer dropped it (or the row was skipped under {@link ErrorStrategy#SKIP_ROW}).
     */
    public Row transform(Row row) {
        metrics.recordRowIn();
        rowNumber++;
        Row original = errorStrategy == ErrorStrategy.KEEP_ORIGINAL ? row.copy() : null;
        Row current = row;
        TransformContext context = new TransformContext(rowNumber, PIPELINE_NAME);

        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            long start = System.nanoTime();
            TransformResult result;
            try {
                result = step.transformer().transform(new TransformInput(current), context);
            } catch (RuntimeException e) {
                // A transformer that throws rather than returning Fail is still handled gracefully.
                result = TransformResult.fail(step.name() + " threw", e);
            } finally {
                metrics.recordStep(i, System.nanoTime() - start);
            }

            if (result instanceof TransformResult.Keep keep) {
                current = keep.row();
                if (!outputShapeVerified && stepOutputNames != null) {
                    verifyRowShape(step, stepOutputNames.get(i), current);
                }
            } else if (result instanceof TransformResult.Drop) {
                metrics.recordRowDropped(false);
                return null;
            } else if (result instanceof TransformResult.Fail fail) {
                metrics.recordStepFailure(i);
                Row handled = handleFailure(step, fail, original);
                if (handled == null) {
                    metrics.recordRowDropped(true);
                    return null;
                }
                metrics.recordRowOut();
                return handled;
            }
        }
        outputShapeVerified = true;
        metrics.recordRowOut();
        return current;
    }

    /**
     * Verify a transformed row's key set against the step's schema resolved by {@link #outputSchema}.
     * Nothing else checks that {@code transform()} actually produces the declared columns — writers
     * null-fill missing keys and drop extras, so an external transform that renames or adds a field
     * only at runtime would otherwise export a whole column of nulls with exit 0. Checked on the
     * first row that completes the pipeline only: the defect is deterministic per pipeline, so a
     * per-row check would tax every export for no extra coverage. The mismatch throws regardless of
     * {@link ErrorStrategy} — skipRow would swallow the one checked row and re-hide the defect.
     */
    private void verifyRowShape(Step step, Set<String> expected, Row row) {
        if (expected.equals(row.names())) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String name : expected) {
            if (!row.has(name)) {
                missing.add("\"" + name + "\"");
            }
        }
        List<String> unexpected = new ArrayList<>();
        for (String name : row.names()) {
            if (!expected.contains(name)) {
                unexpected.add("\"" + name + "\"");
            }
        }
        StringBuilder detail = new StringBuilder();
        if (!missing.isEmpty()) {
            detail.append(" is missing declared columns ").append(missing);
        }
        if (!unexpected.isEmpty()) {
            detail.append(detail.isEmpty() ? " has" : " and has").append(" undeclared columns ").append(unexpected);
        }
        throw new ExportException(ExitCodes.TRANSFORM_ERROR,
            "Transform \"" + step.name() + "\" produced a row that does not match its declared schema: the row"
                + detail + ".");
    }

    /** Returns the row to emit, {@code null} to drop, or throws to abort — per the strategy. */
    private Row handleFailure(Step step, TransformResult.Fail fail, Row original) {
        switch (errorStrategy) {
            case SKIP_ROW:
                return null;
            case KEEP_ORIGINAL:
                return original;
            case FAIL:
            default:
                if (fail.cause() instanceof ExportException ee) {
                    throw ee;
                }
                // Only include the message for value-free built-in failures (no caught cause), so an
                // arbitrary exception's message can never leak row values.
                String detail = fail.cause() == null && fail.message() != null ? ": " + fail.message() : "";
                throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                    "Transform \"" + step.name() + "\" failed at row " + rowNumber + detail + ".");
        }
    }
}
