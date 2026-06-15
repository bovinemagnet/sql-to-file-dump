package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an ordered list of {@link TransformSpec}s from CLI configuration: inline {@code --transform}
 * shorthand and/or a {@code --transforms-file} JSON document. File specs run first (in file order),
 * then inline specs (in argument order). Parse errors fail fast with {@link ExitCodes#TRANSFORM_ERROR}.
 */
public final class TransformConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransformConfigLoader() {
    }

    /** Returns just the ordered specs (file then inline); pipeline-wide settings are ignored. */
    public static List<TransformSpec> load(Path transformsFile, List<String> inlineSpecs) {
        return loadConfig(transformsFile, inlineSpecs, null).specs();
    }

    /**
     * Full configuration: ordered specs plus pipeline-wide {@code errorStrategy} and
     * {@code validateOutput} read from the file. {@code cliErrorStrategy}, when non-null, overrides
     * the file's error strategy.
     */
    public static TransformConfig loadConfig(Path transformsFile, List<String> inlineSpecs, ErrorStrategy cliErrorStrategy) {
        List<TransformSpec> specs = new ArrayList<>();
        ErrorStrategy errorStrategy = ErrorStrategy.FAIL;
        OutputContract contract = null;

        if (transformsFile != null) {
            JsonNode root = readRoot(transformsFile);
            JsonNode array = root.isArray() ? root : root.get("transforms");
            if (array == null || !array.isArray()) {
                throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                    "Transforms file " + transformsFile + " must be a JSON array or an object with a \"transforms\" array.");
            }
            for (JsonNode node : array) {
                specs.add(toSpec(node, transformsFile));
            }
            if (root.isObject()) {
                if (root.hasNonNull("errorStrategy")) {
                    errorStrategy = ErrorStrategy.fromConfig(root.get("errorStrategy").asText());
                }
                contract = parseContract(root.get("validateOutput"));
            }
        }
        if (inlineSpecs != null) {
            for (String inline : inlineSpecs) {
                specs.add(parseInline(inline));
            }
        }
        if (cliErrorStrategy != null) {
            errorStrategy = cliErrorStrategy;
        }
        return new TransformConfig(specs, errorStrategy, contract);
    }

    private static JsonNode readRoot(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Failed to read transforms file " + file + ": " + e.getMessage());
        }
    }

    private static OutputContract parseContract(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (node.hasNonNull("enabled") && !node.get("enabled").asBoolean()) {
            return null;
        }
        List<String> required = stringList(node.get("requiredFields"));
        List<String> optional = stringList(node.get("optionalFields"));
        boolean failOnUnknown = node.hasNonNull("failOnUnknownFields") && node.get("failOnUnknownFields").asBoolean();
        return new OutputContract(required, optional, failOnUnknown);
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private static TransformSpec toSpec(JsonNode node, Path file) {
        if (!node.isObject() || node.get("type") == null) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Each transform in " + file + " must be an object with a \"type\".");
        }
        Map<String, Object> map = MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
        String type = String.valueOf(map.remove("type"));
        return new TransformSpec(type, map);
    }

    /**
     * Parse inline shorthand, e.g. {@code rename:old=new}, {@code drop:a,b}, {@code keep:a,b},
     * {@code default:col=value}, {@code mask:email}, {@code mask:email=###},
     * {@code map:status=A>Active,C>Closed}, {@code template:full={first} {last}}.
     */
    static TransformSpec parseInline(String raw) {
        int colon = raw.indexOf(':');
        if (colon < 0) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Invalid --transform \"" + raw + "\"; expected <type>:<args>.");
        }
        String type = raw.substring(0, colon).trim();
        String args = raw.substring(colon + 1);
        Map<String, Object> config = new LinkedHashMap<>();
        switch (type) {
            case "rename" -> {
                String[] parts = splitPair(args, raw);
                config.put("from", parts[0]);
                config.put("to", parts[1]);
            }
            case "drop", "keep" -> config.put("columns", args);
            case "addStatic" -> {
                Map<String, String> fields = new LinkedHashMap<>();
                for (String entry : args.split(",")) {
                    String[] pair = splitPair(entry, raw);
                    fields.put(pair[0], pair[1]);
                }
                config.put("fields", fields);
            }
            case "default" -> {
                String[] parts = splitPair(args, raw);
                config.put("column", parts[0]);
                config.put("value", parts[1]);
            }
            case "mask" -> {
                int eq = args.indexOf('=');
                if (eq < 0) {
                    config.put("column", args.trim());
                } else {
                    config.put("column", args.substring(0, eq).trim());
                    config.put("mask", args.substring(eq + 1));
                }
            }
            case "map" -> {
                String[] parts = splitPair(args, raw);
                config.put("column", parts[0]);
                Map<String, String> mapping = new LinkedHashMap<>();
                for (String entry : parts[1].split(",")) {
                    int arrow = entry.indexOf('>');
                    if (arrow < 0) {
                        throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                            "Invalid map entry \"" + entry + "\" in --transform \"" + raw + "\"; expected key>value.");
                    }
                    mapping.put(entry.substring(0, arrow).trim(), entry.substring(arrow + 1).trim());
                }
                config.put("mapping", mapping);
            }
            case "template" -> {
                String[] parts = splitPair(args, raw);
                config.put("name", parts[0]);
                config.put("template", parts[1]);
            }
            case "expression" -> {
                String[] parts = splitPair(args, raw);
                config.put("outputField", parts[0]);
                config.put("expression", parts[1]);
            }
            default -> throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Unknown inline transform type \"" + type + "\". Use --transforms-file for advanced transforms.");
        }
        return new TransformSpec(type, config);
    }

    private static String[] splitPair(String args, String raw) {
        int eq = args.indexOf('=');
        if (eq < 0) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Invalid --transform \"" + raw + "\"; expected <key>=<value>.");
        }
        return new String[] {args.substring(0, eq).trim(), args.substring(eq + 1)};
    }
}
