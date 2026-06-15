package com.example.jdbcexport.transform.testing;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.transform.ErrorStrategy;
import com.example.jdbcexport.transform.TransformConfig;
import com.example.jdbcexport.transform.TransformPipeline;
import com.example.jdbcexport.transform.TransformRegistry;
import com.example.jdbcexport.transform.TransformSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a JSON pipeline fixture: a {@code transforms} array plus an {@code input} row and either an
 * {@code expected} row or {@code "dropped": true}. Useful for declarative, data-driven tests of a
 * full pipeline. Mismatches throw {@link AssertionError}.
 *
 * <pre>{@code
 * {
 *   "transforms": [ { "type": "rename", "from": "staff_id", "to": "staffId" } ],
 *   "input":    { "staff_id": 123 },
 *   "expected": { "staffId": 123 }
 * }
 * }</pre>
 */
public final class TransformFixtureRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransformFixtureRunner() {
    }

    public static void run(Path fixtureFile) {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(fixtureFile));
        } catch (IOException e) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR, "Failed to read fixture " + fixtureFile + ": " + e.getMessage());
        }
        run(root);
    }

    static void run(JsonNode root) {
        List<TransformSpec> specs = new ArrayList<>();
        JsonNode transforms = root.get("transforms");
        if (transforms != null && transforms.isArray()) {
            for (JsonNode node : transforms) {
                Map<String, Object> map = MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {
                });
                String type = String.valueOf(map.remove("type"));
                specs.add(new TransformSpec(type, map));
            }
        }
        ErrorStrategy strategy = root.hasNonNull("errorStrategy")
            ? ErrorStrategy.fromConfig(root.get("errorStrategy").asText())
            : ErrorStrategy.FAIL;
        TransformPipeline pipeline = new TransformRegistry().build(new TransformConfig(specs, strategy, null));

        Map<String, Object> input = MAPPER.convertValue(root.get("input"), new TypeReference<Map<String, Object>>() {
        });
        TransformTestHarness harness = TransformTestHarness.forPipeline(pipeline).withInput(input);

        if (root.path("dropped").asBoolean(false)) {
            harness.expectDropped();
            return;
        }
        Map<String, Object> expected = MAPPER.convertValue(root.get("expected"), new TypeReference<Map<String, Object>>() {
        });
        harness.expectOutput(expected);
    }
}
