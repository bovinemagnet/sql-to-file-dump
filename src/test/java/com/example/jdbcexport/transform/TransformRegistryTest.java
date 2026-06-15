package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.builtin.RenameTransform;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformRegistryTest {

    private static TransformProvider provider(String type) {
        return new TransformProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public OutboundTransformer create(TransformSpec spec) {
                return RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "a", "to", "b")));
            }
        };
    }

    @Test
    void unknownTypeFailsFast() {
        TransformRegistry registry = new TransformRegistry(List.of());
        assertThatThrownBy(() -> registry.create(new TransformSpec("nope", Map.of())))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("Unknown transform type");
    }

    @Test
    void externalProviderTypeCollisionFailsFast() {
        assertThatThrownBy(() -> new TransformRegistry(List.of(provider("rename"))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("conflict");
    }

    @Test
    void loadsExternalProviderViaServiceLoader() {
        // META-INF/services registers UppercaseTransformProvider for the test classpath.
        TransformRegistry registry = new TransformRegistry();
        OutboundTransformer transformer = registry.create(new TransformSpec("uppercase", Map.of("column", "name")));
        Row row = new Row(Map.of("name", "ada"));
        TransformTestSupport.run(transformer, row);
        assertThat(row.get("name")).isEqualTo("ADA");
    }

    @Test
    void duplicateExplicitNamesFailFast() {
        TransformRegistry registry = new TransformRegistry(List.of());
        assertThatThrownBy(() -> registry.build(new TransformConfig(List.of(
            new TransformSpec("rename", Map.of("from", "a", "to", "b", "name", "step1")),
            new TransformSpec("rename", Map.of("from", "b", "to", "c", "name", "step1"))),
            ErrorStrategy.FAIL, null)))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("Duplicate transform name");
    }

    @Test
    void repeatedUnnamedTransformsAreAllowed() {
        TransformRegistry registry = new TransformRegistry(List.of());
        TransformPipeline pipeline = registry.build(List.of(
            new TransformSpec("rename", Map.of("from", "a", "to", "b")),
            new TransformSpec("rename", Map.of("from", "b", "to", "c"))));
        assertThat(pipeline.outputSchema(
            List.of(new ResultSetColumn(1, "a", "a", java.sql.Types.VARCHAR, "", 0, 0, true)))
            .get(0).outputName()).isEqualTo("c");
    }

    @Test
    void buildsPipelineFromSpecs() {
        TransformRegistry registry = new TransformRegistry(List.of());
        TransformPipeline pipeline = registry.build(List.of(
            new TransformSpec("rename", Map.of("from", "a", "to", "b"))));
        List<ResultSetColumn> schema = pipeline.outputSchema(
            List.of(new ResultSetColumn(1, "a", "a", java.sql.Types.VARCHAR, "", 0, 0, true)));
        assertThat(schema.get(0).outputName()).isEqualTo("b");
    }
}
