package com.example.jdbcexport.transform;

/**
 * Declarative metadata about how an {@link OutboundTransformer} behaves. Lets the engine and tooling
 * reason about a transform without running it.
 *
 * @param schemaChanging adds, removes or renames columns (as opposed to changing values in place)
 * @param rowLevel       operates on one row at a time (no cross-row state)
 * @param deterministic  the same input always yields the same output
 */
public record TransformCapabilities(boolean schemaChanging, boolean rowLevel, boolean deterministic) {

    /** The common case: a pure, per-row transform that does not change the column set. */
    public static TransformCapabilities rowLevelDeterministic() {
        return new TransformCapabilities(false, true, true);
    }

    /** A pure, per-row transform that adds, removes or renames columns. */
    public static TransformCapabilities columnChanging() {
        return new TransformCapabilities(true, true, true);
    }
}
