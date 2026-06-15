package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformConfigLoaderTest {

    @Test
    void parsesInlineRename() {
        TransformSpec spec = TransformConfigLoader.parseInline("rename:old=new");
        assertThat(spec.type()).isEqualTo("rename");
        assertThat(spec.requireString("from")).isEqualTo("old");
        assertThat(spec.requireString("to")).isEqualTo("new");
    }

    @Test
    void parsesInlineDropList() {
        TransformSpec spec = TransformConfigLoader.parseInline("drop:a, b ,c");
        assertThat(spec.requireColumns("columns")).containsExactly("a", "b", "c");
    }

    @Test
    void parsesInlineMaskWithToken() {
        assertThat(TransformConfigLoader.parseInline("mask:email").requireString("column")).isEqualTo("email");
        assertThat(TransformConfigLoader.parseInline("mask:email=###").optionalString("mask", "x")).isEqualTo("###");
    }

    @Test
    void parsesInlineMap() {
        TransformSpec spec = TransformConfigLoader.parseInline("map:status=A>Active,C>Closed");
        assertThat(spec.requireString("column")).isEqualTo("status");
        assertThat(spec.requireStringMap("mapping")).containsEntry("A", "Active").containsEntry("C", "Closed");
    }

    @Test
    void parsesInlineTemplateWithBraces() {
        TransformSpec spec = TransformConfigLoader.parseInline("template:full={first} {last}");
        assertThat(spec.requireString("name")).isEqualTo("full");
        assertThat(spec.requireString("template")).isEqualTo("{first} {last}");
    }

    @Test
    void rejectsInlineWithoutColon() {
        assertThatThrownBy(() -> TransformConfigLoader.parseInline("renameoldnew"))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("expected <type>:<args>");
    }

    @Test
    void rejectsUnknownInlineType() {
        assertThatThrownBy(() -> TransformConfigLoader.parseInline("frobnicate:x=y"))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("Unknown inline transform");
    }

    @Test
    void loadsJsonArrayFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("t.json");
        Files.writeString(file, """
            [
              { "type": "rename", "from": "a", "to": "b" },
              { "type": "map", "column": "s", "mapping": { "A": "Active" }, "unmatched": "default", "default": "?" }
            ]
            """);
        List<TransformSpec> specs = TransformConfigLoader.load(file, null);
        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).type()).isEqualTo("rename");
        assertThat(specs.get(1).requireStringMap("mapping")).containsEntry("A", "Active");
    }

    @Test
    void loadsJsonObjectFileAndAppendsInline(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("t.json");
        Files.writeString(file, """
            { "transforms": [ { "type": "drop", "columns": ["x"] } ] }
            """);
        List<TransformSpec> specs = TransformConfigLoader.load(file, List.of("mask:email"));
        assertThat(specs).extracting(TransformSpec::type).containsExactly("drop", "mask");
    }

    @Test
    void loadsErrorStrategyAndContractFromFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("t.json");
        Files.writeString(file, """
            {
              "errorStrategy": "skipRow",
              "validateOutput": { "enabled": true, "requiredFields": ["id"], "failOnUnknownFields": true },
              "transforms": [ { "type": "rename", "from": "a", "to": "id" } ]
            }
            """);
        TransformConfig config = TransformConfigLoader.loadConfig(file, null, null);
        assertThat(config.errorStrategy()).isEqualTo(ErrorStrategy.SKIP_ROW);
        assertThat(config.outputContract()).isNotNull();
        assertThat(config.outputContract().requiredFields()).containsExactly("id");
        assertThat(config.outputContract().failOnUnknownFields()).isTrue();
    }

    @Test
    void cliErrorStrategyOverridesFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("t.json");
        Files.writeString(file, "{ \"errorStrategy\": \"skipRow\", \"transforms\": [] }");
        TransformConfig config = TransformConfigLoader.loadConfig(file, null, ErrorStrategy.KEEP_ORIGINAL);
        assertThat(config.errorStrategy()).isEqualTo(ErrorStrategy.KEEP_ORIGINAL);
    }

    @Test
    void rejectsMalformedJsonFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{ \"nope\": true }");
        assertThatThrownBy(() -> TransformConfigLoader.load(file, null))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("must be a JSON array");
    }
}
