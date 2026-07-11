package com.example.jdbcexport.metadata;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class ExportMetadataWriter {

    private ExportMetadataWriter() {
    }

    public static void write(ExportMetadata metadata, Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            ObjectNode root = mapper.createObjectNode();
            root.put("tool", metadata.tool());
            root.put("format", metadata.format());
            root.put("jdbcUrl", metadata.jdbcUrl());
            root.put("sqlSource", metadata.sqlSource());
            root.put("output", metadata.output());
            root.put("rowCount", metadata.rowCount());
            root.put("startedAt", metadata.startedAt());
            root.put("completedAt", metadata.completedAt());
            root.put("durationMillis", metadata.durationMillis());

            ArrayNode columns = mapper.createArrayNode();
            for (ResultSetColumn column : metadata.columns()) {
                ObjectNode entry = mapper.createObjectNode();
                entry.put("index", column.index());
                entry.put("jdbcLabel", column.label());
                entry.put("outputName", column.outputName());
                entry.put("jdbcType", column.jdbcTypeName());
                entry.put("nullable", column.nullable());
                entry.put("precision", column.precision());
                entry.put("scale", column.scale());
                columns.add(entry);
            }
            root.set("columns", columns);
            writeAtomically(mapper, root, path);
        } catch (Exception e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                "Failed to write metadata file: " + path, e);
        }
    }

    /**
     * Writes to a temporary sibling and renames onto the target (issue #24), so a failure never
     * leaves a truncated metadata file and an existing sidecar is replaced only by a complete one.
     */
    private static void writeAtomically(ObjectMapper mapper, ObjectNode root, Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path temporary = absolute.getParent()
            .resolve("." + absolute.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, absolute,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
