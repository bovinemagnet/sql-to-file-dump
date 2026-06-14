package com.example.jdbcexport.metadata;

import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExportMetadataWriterTest {

    @Test
    void writesRowCountAndColumnInformation(@TempDir Path tempDir) throws Exception {
        Path metadataPath = tempDir.resolve("export.metadata.json");
        ExportMetadata metadata = new ExportMetadata(
            "jdbc-export-cli",
            "csv",
            "jdbc:postgresql://localhost:5432/appdb",
            "sql/bookings.sql",
            "out/bookings.csv",
            3,
            "2026-06-13T00:00:00Z",
            "2026-06-13T00:00:04Z",
            4000,
            List.of(new ResultSetColumn(1, "booking_id", "booking_id", Types.VARCHAR, "VARCHAR", 255, 0, true))
        );

        ExportMetadataWriter.write(metadata, metadataPath);

        JsonNode root = new ObjectMapper().readTree(metadataPath.toFile());
        assertThat(root.get("tool").asText()).isEqualTo("jdbc-export-cli");
        assertThat(root.get("format").asText()).isEqualTo("csv");
        assertThat(root.get("rowCount").asLong()).isEqualTo(3);
        assertThat(root.get("durationMillis").asLong()).isEqualTo(4000);

        JsonNode column = root.get("columns").get(0);
        assertThat(column.get("index").asInt()).isEqualTo(1);
        assertThat(column.get("jdbcLabel").asText()).isEqualTo("booking_id");
        assertThat(column.get("outputName").asText()).isEqualTo("booking_id");
        assertThat(column.get("jdbcType").asText()).isEqualTo("VARCHAR");
        assertThat(column.get("nullable").asBoolean()).isTrue();
    }

    @Test
    void createsParentDirectories(@TempDir Path tempDir) throws Exception {
        Path metadataPath = tempDir.resolve("nested/dir/export.metadata.json");
        ExportMetadata metadata = new ExportMetadata(
            "jdbc-export-cli", "json", "jdbc:duckdb:", "<inline>", "out.json",
            0, "2026-06-13T00:00:00Z", "2026-06-13T00:00:00Z", 0, List.of());

        ExportMetadataWriter.write(metadata, metadataPath);

        assertThat(Files.exists(metadataPath)).isTrue();
    }
}
