package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.builtin.AddStaticTransform;
import com.example.jdbcexport.transform.builtin.DefaultTransform;
import com.example.jdbcexport.transform.builtin.DropTransform;
import com.example.jdbcexport.transform.builtin.KeepTransform;
import com.example.jdbcexport.transform.builtin.MapTransform;
import com.example.jdbcexport.transform.builtin.MaskTransform;
import com.example.jdbcexport.transform.builtin.RenameTransform;
import com.example.jdbcexport.transform.builtin.TemplateTransform;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformBuiltinsTest {

    private static ResultSetColumn col(int index, String name, int jdbcType) {
        return new ResultSetColumn(index, name, name, jdbcType, "", 0, 0, true);
    }

    private static List<String> names(List<ResultSetColumn> columns) {
        return columns.stream().map(ResultSetColumn::outputName).toList();
    }

    @Test
    void renameChangesSchemaAndRow() {
        OutboundTransformer t = RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "a", "to", "b")));
        List<ResultSetColumn> schema = t.transformSchema(List.of(col(1, "a", Types.INTEGER), col(2, "c", Types.VARCHAR)));
        assertThat(names(schema)).containsExactly("b", "c");

        Row row = new Row(Map.of("a", 1, "c", "x"));
        TransformTestSupport.run(t, row);
        assertThat(row.has("a")).isFalse();
        assertThat(row.get("b")).isEqualTo(1);
    }

    @Test
    void renameFailsOnUnknownColumn() {
        OutboundTransformer t = RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "x", "to", "y")));
        assertThatThrownBy(() -> t.transformSchema(List.of(col(1, "a", Types.INTEGER))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("unknown column");
    }

    @Test
    void renameFailsOnDuplicateTarget() {
        OutboundTransformer t = RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "a", "to", "b")));
        assertThatThrownBy(() -> t.transformSchema(List.of(col(1, "a", Types.INTEGER), col(2, "b", Types.VARCHAR))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("duplicate");
    }

    @Test
    void dropRemovesColumns() {
        OutboundTransformer t = DropTransform.PROVIDER.create(new TransformSpec("drop", Map.of("columns", List.of("b"))));
        assertThat(names(t.transformSchema(List.of(col(1, "a", Types.INTEGER), col(2, "b", Types.VARCHAR)))))
            .containsExactly("a");
        Row row = new Row(Map.of("a", 1, "b", 2));
        TransformTestSupport.run(t, row);
        assertThat(row.has("b")).isFalse();
    }

    @Test
    void keepRestrictsAndReordersColumns() {
        OutboundTransformer t = KeepTransform.PROVIDER.create(new TransformSpec("keep", Map.of("columns", List.of("c", "a"))));
        assertThat(names(t.transformSchema(List.of(col(1, "a", Types.INTEGER), col(2, "b", Types.VARCHAR), col(3, "c", Types.VARCHAR)))))
            .containsExactly("c", "a");
        Row row = new Row(Map.of("a", 1, "b", 2, "c", 3));
        TransformTestSupport.run(t, row);
        assertThat(row.names()).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void defaultFillsNullsKeepingNativeType() {
        OutboundTransformer t = DefaultTransform.PROVIDER.create(new TransformSpec("default", Map.of("column", "n", "value", "0")));
        List<ResultSetColumn> schema = t.transformSchema(List.of(col(1, "n", Types.INTEGER)));
        assertThat(schema.get(0).jdbcType()).isEqualTo(Types.INTEGER);

        Row withNull = new Row();
        withNull.put("n", null);
        TransformTestSupport.run(t, withNull);
        assertThat(withNull.get("n")).isEqualTo(0);

        Row present = new Row(Map.of("n", 7));
        TransformTestSupport.run(t, present);
        assertThat(present.get("n")).isEqualTo(7);
    }

    @Test
    void defaultFailsWhenValueIncompatibleWithColumnType() {
        OutboundTransformer t = DefaultTransform.PROVIDER.create(new TransformSpec("default", Map.of("column", "n", "value", "abc")));
        assertThatThrownBy(() -> t.transformSchema(List.of(col(1, "n", Types.INTEGER))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("not valid");
    }

    @Test
    void mapTranslatesValuesAndKeepsUnmatched() {
        OutboundTransformer t = MapTransform.PROVIDER.create(new TransformSpec("map",
            Map.of("column", "s", "mapping", Map.of("A", "Active", "C", "Closed"))));
        assertThat(t.transformSchema(List.of(col(1, "s", Types.VARCHAR))).get(0).jdbcType()).isEqualTo(Types.VARCHAR);

        Row matched = new Row(Map.of("s", "A"));
        TransformTestSupport.run(t, matched);
        assertThat(matched.get("s")).isEqualTo("Active");

        Row unmatched = new Row(Map.of("s", "Z"));
        TransformTestSupport.run(t, unmatched);
        assertThat(unmatched.get("s")).isEqualTo("Z");
    }

    @Test
    void mapFailPolicyReturnsFailOnUnmatched() {
        OutboundTransformer t = MapTransform.PROVIDER.create(new TransformSpec("map",
            Map.of("column", "s", "mapping", Map.of("A", "Active"), "unmatched", "fail")));
        t.transformSchema(List.of(col(1, "s", Types.VARCHAR)));
        TransformResult result = TransformTestSupport.run(t, new Row(Map.of("s", "Z")));
        assertThat(result).isInstanceOf(TransformResult.Fail.class);
        assertThat(((TransformResult.Fail) result).message()).contains("no mapping");
    }

    @Test
    void builtinsDeclareCapabilities() {
        // Column-set changers.
        assertThat(RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "a", "to", "b")))
            .capabilities().schemaChanging()).isTrue();
        assertThat(DropTransform.PROVIDER.create(new TransformSpec("drop", Map.of("columns", List.of("a"))))
            .capabilities().schemaChanging()).isTrue();
        assertThat(KeepTransform.PROVIDER.create(new TransformSpec("keep", Map.of("columns", List.of("a"))))
            .capabilities().schemaChanging()).isTrue();
        assertThat(AddStaticTransform.PROVIDER.create(new TransformSpec("addStatic", Map.of("fields", Map.of("s", "1"))))
            .capabilities().schemaChanging()).isTrue();
        assertThat(TemplateTransform.PROVIDER.create(new TransformSpec("template", Map.of("name", "f", "template", "{a}")))
            .capabilities().schemaChanging()).isTrue();

        // Value-only transforms keep the column set.
        OutboundTransformer mask = MaskTransform.PROVIDER.create(new TransformSpec("mask", Map.of("column", "a")));
        assertThat(mask.capabilities().schemaChanging()).isFalse();
        assertThat(mask.capabilities().rowLevel()).isTrue();
        assertThat(mask.capabilities().deterministic()).isTrue();
        assertThat(DefaultTransform.PROVIDER.create(new TransformSpec("default", Map.of("column", "a", "value", "x")))
            .capabilities().schemaChanging()).isFalse();
        assertThat(MapTransform.PROVIDER.create(new TransformSpec("map", Map.of("column", "a", "mapping", Map.of("A", "B"))))
            .capabilities().schemaChanging()).isFalse();
    }

    @Test
    void addStaticAddsConstantColumns() {
        OutboundTransformer t = AddStaticTransform.PROVIDER.create(new TransformSpec("addStatic",
            Map.of("fields", Map.of("source", "student-system", "version", "1"))));
        assertThat(names(t.transformSchema(List.of(col(1, "a", Types.INTEGER)))))
            .containsExactlyInAnyOrder("a", "source", "version");

        Row row = new Row(Map.of("a", 1));
        TransformTestSupport.run(t, row);
        assertThat(row.get("source")).isEqualTo("student-system");
        assertThat(row.get("version")).isEqualTo("1");
    }

    @Test
    void addStaticFailsOnDuplicateColumn() {
        OutboundTransformer t = AddStaticTransform.PROVIDER.create(new TransformSpec("addStatic",
            Map.of("fields", Map.of("a", "x"))));
        assertThatThrownBy(() -> t.transformSchema(List.of(col(1, "a", Types.INTEGER))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("duplicate");
    }

    @Test
    void maskReplacesNonNullValues() {
        OutboundTransformer t = MaskTransform.PROVIDER.create(new TransformSpec("mask", Map.of("column", "email")));
        Row row = new Row(Map.of("email", "a@b.com"));
        TransformTestSupport.run(t, row);
        assertThat(row.get("email")).isEqualTo("***");

        Row nullRow = new Row();
        nullRow.put("email", null);
        TransformTestSupport.run(t, nullRow);
        assertThat(nullRow.get("email")).isNull();
    }

    @Test
    void maskHandlesMultipleColumns() {
        OutboundTransformer t = MaskTransform.PROVIDER.create(new TransformSpec("mask",
            Map.of("columns", List.of("dob", "tfn"), "mask", "###")));
        t.transformSchema(List.of(col(1, "dob", Types.VARCHAR), col(2, "tfn", Types.VARCHAR), col(3, "name", Types.VARCHAR)));
        Row row = new Row(Map.of("dob", "2000-01-01", "tfn", "123", "name", "Ada"));
        TransformTestSupport.run(t, row);
        assertThat(row.get("dob")).isEqualTo("###");
        assertThat(row.get("tfn")).isEqualTo("###");
        assertThat(row.get("name")).isEqualTo("Ada");
    }

    @Test
    void templateAddsComputedColumn() {
        OutboundTransformer t = TemplateTransform.PROVIDER.create(new TransformSpec("template",
            Map.of("name", "full", "template", "{first} {last}")));
        List<ResultSetColumn> schema = t.transformSchema(List.of(col(1, "first", Types.VARCHAR), col(2, "last", Types.VARCHAR)));
        assertThat(names(schema)).containsExactly("first", "last", "full");

        Row row = new Row(Map.of("first", "Ada", "last", "Lovelace"));
        TransformTestSupport.run(t, row);
        assertThat(row.get("full")).isEqualTo("Ada Lovelace");
    }

    @Test
    void templateFailsOnUnknownPlaceholder() {
        OutboundTransformer t = TemplateTransform.PROVIDER.create(new TransformSpec("template",
            Map.of("name", "full", "template", "{first} {missing}")));
        assertThatThrownBy(() -> t.transformSchema(List.of(col(1, "first", Types.VARCHAR))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("unknown column");
    }

    @Test
    void missingRequiredConfigFailsFast() {
        assertThatThrownBy(() -> RenameTransform.PROVIDER.create(new TransformSpec("rename", Map.of("from", "a"))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("\"to\"");
    }
}
