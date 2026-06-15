package com.example.jdbcexport.transform.testing;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.OutboundTransformer;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.transform.TransformContext;
import com.example.jdbcexport.transform.TransformInput;
import com.example.jdbcexport.transform.TransformResult;
import com.example.jdbcexport.transform.TransformSpec;
import com.example.jdbcexport.transform.builtin.RenameTransform;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformTestHarnessTest {

    private static OutboundTransformer dropAll() {
        return new OutboundTransformer() {
            public String name() {
                return "drop-all";
            }

            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return columns;
            }

            public TransformResult transform(TransformInput input, TransformContext context) {
                return TransformResult.drop("all");
            }
        };
    }

    @Test
    void verifiesTransformOutput() {
        TransformTestHarness.forTransform(
                RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "first_name", "to", "firstName"))))
            .withInput(Map.of("first_name", "Jane"))
            .expectOutput(Map.of("firstName", "Jane"));
    }

    @Test
    void detectsWrongOutput() {
        assertThatThrownBy(() -> TransformTestHarness.forTransform(
                RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "a", "to", "b"))))
            .withInput(Map.of("a", "x"))
            .expectOutput(Map.of("b", "WRONG")))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void verifiesDroppedRow() {
        TransformTestHarness.forTransform(dropAll())
            .withInput(Map.of("a", "x"))
            .expectDropped();
    }

    @Test
    void runsJsonFixture() throws Exception {
        Path fixture = Path.of(getClass().getResource("/fixtures/rename-and-static.json").toURI());
        assertThatCode(() -> TransformFixtureRunner.run(fixture)).doesNotThrowAnyException();
    }
}
