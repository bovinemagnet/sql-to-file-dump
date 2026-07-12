package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.builtin.ExpressionTransform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionTransformTest {

    private static OutboundTransformer expr(String outputField, String expression) {
        return ExpressionTransform.PROVIDER.create(new TransformSpec("expression",
            Map.of("outputField", outputField, "expression", expression)));
    }

    private static ResultSetColumn col(String name) {
        return new ResultSetColumn(1, name, name, Types.VARCHAR, "", 0, 0, true);
    }

    @Test
    void concatenatesFields() {
        OutboundTransformer t = expr("displayName", "lastName + ', ' + firstName");
        List<ResultSetColumn> schema = t.transformSchema(List.of(col("firstName"), col("lastName")));
        assertThat(schema).extracting(ResultSetColumn::outputName).contains("displayName");

        Row row = new Row(Map.of("firstName", "Jane", "lastName", "Citizen"));
        TransformTestSupport.run(t, row);
        assertThat(row.get("displayName")).isEqualTo("Citizen, Jane");
    }

    @Test
    void supportsConditional() {
        OutboundTransformer t = expr("status", "activeFlag == 'Y' ? 'ACTIVE' : 'INACTIVE'");
        t.transformSchema(List.of(col("activeFlag")));

        Row yes = new Row(Map.of("activeFlag", "Y"));
        TransformTestSupport.run(t, yes);
        assertThat(yes.get("status")).isEqualTo("ACTIVE");

        Row no = new Row(Map.of("activeFlag", "N"));
        TransformTestSupport.run(t, no);
        assertThat(no.get("status")).isEqualTo("INACTIVE");
    }

    @Test
    void invalidSyntaxFailsFastAtBuild() {
        assertThatThrownBy(() -> expr("x", "a +"))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("invalid expression");
    }

    @Test
    void unknownColumnReferenceFailsFastAtSchema() {
        // A typo in a referenced column must fail at schema resolution (before any rows), under
        // every error strategy — not abort opaquely at row 1 or silently drop every row.
        OutboundTransformer t = expr("full", "firstNam + lastName");
        assertThatThrownBy(() -> t.transformSchema(List.of(col("firstName"), col("lastName"))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("unknown column")
            .hasMessageContaining("firstNam");
    }

    @Test
    void dottedReferenceValidatesOnlyTheRootVariable() {
        // "a.b" references column "a"; the ".b" property lookup is not a column reference.
        OutboundTransformer t = expr("x", "a.b");
        List<ResultSetColumn> schema = t.transformSchema(List.of(col("a")));
        assertThat(schema).extracting(ResultSetColumn::outputName).contains("x");
    }

    @Test
    void undefinedVariableAtRuntimeFailsWithNameOnlyMessage() {
        // Bypasses transformSchema to force the runtime path. The variable *name* is safe to
        // surface (no row values); the message must carry it and no caught cause.
        OutboundTransformer t = expr("full", "firstNam + lastName");
        TransformResult result = TransformTestSupport.run(t,
            new Row(Map.of("firstName", "Jane", "lastName", "Citizen")));
        assertThat(result).isInstanceOf(TransformResult.Fail.class);
        TransformResult.Fail fail = (TransformResult.Fail) result;
        assertThat(fail.message()).contains("firstNam");
        assertThat(fail.message()).doesNotContain("Jane").doesNotContain("Citizen");
        assertThat(fail.cause()).as("name-only failure must be surfaceable (no suppressed cause)").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "''.getClass()",
        "''.getClass().forName('java.lang.Runtime')",
        "new('java.lang.String', 'x')",
        "System.exit(1)",
        "''.class",
        "a.class"
    })
    void blocksUnsafeJavaAccess(String dangerous) {
        // Either compilation rejects it, or evaluation is blocked by the sandbox — never executes.
        try {
            OutboundTransformer t = expr("x", dangerous);
            t.transformSchema(List.of(col("a")));
            TransformResult result = TransformTestSupport.run(t, new Row(Map.of("a", "v")));
            assertThat(result).isInstanceOf(TransformResult.Fail.class);
        } catch (ExportException buildFailure) {
            assertThat(buildFailure.getMessage()).containsAnyOf("invalid expression", "expression");
        }
    }
}
