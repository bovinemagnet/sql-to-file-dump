package com.example.jdbcexport.cli;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.JdbcConnectionFactory;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.PasswordResolver;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.example.jdbcexport.jdbc.SqlInputResolver;
import com.example.jdbcexport.jdbc.SqlSafetyValidator;
import com.example.jdbcexport.metadata.ExportMetadata;
import com.example.jdbcexport.metadata.ExportMetadataWriter;
import com.example.jdbcexport.transform.ErrorStrategy;
import com.example.jdbcexport.transform.TransformConfig;
import com.example.jdbcexport.transform.TransformConfigLoader;
import com.example.jdbcexport.transform.TransformMetrics;
import com.example.jdbcexport.transform.TransformPipeline;
import com.example.jdbcexport.transform.TransformRegistry;
import com.example.jdbcexport.transform.metrics.TransformMetricsPublisher;
import com.example.jdbcexport.transform.metrics.TransformMetricsSettings;
import io.micrometer.core.instrument.Metrics;
import com.example.jdbcexport.writer.RowWriter;
import com.example.jdbcexport.writer.RowWriterFactory;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

@TopCommand
@Dependent
@Command(
    name = "jdbc-export",
    description = "Export SQL query results to JSON, NDJSON, CSV, TSV, or Parquet",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    subcommands = com.example.jdbcexport.daemon.DaemonCommand.class
)
public class JdbcExportCommand implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(JdbcExportCommand.class.getName());

    // Not annotation-required: that would also make them mandatory for the daemon subcommand.
    @Option(names = "--url", description = "JDBC URL (required)")
    String url;

    @Option(names = "--user", description = "Database username (required)")
    String user;

    @Option(names = "--password", description = "Database password")
    String password;

    @Option(names = "--password-env", description = "Environment variable name containing the password")
    String passwordEnv;

    @Option(names = "--sql", description = "SQL query string")
    String sql;

    @Option(names = "--sql-file", description = "Path to SQL file")
    String sqlFile;

    @Option(names = "--format", description = "Output format: json, ndjson, parquet, csv, tsv")
    String format;

    @Option(names = "--output", description = "Output file path")
    String output;

    @Option(names = "--fetch-size", description = "JDBC fetch size", defaultValue = "1000")
    int fetchSize;

    @Option(names = "--max-rows", description = "Maximum number of rows to export")
    Long maxRows;

    @Option(names = "--metadata", description = "Path to write metadata JSON sidecar")
    String metadata;

    @Option(names = "--overwrite", description = "Overwrite output file if it exists")
    boolean overwrite;

    @Option(names = "--dry-run", description = "Validate options without executing")
    boolean dryRun;

    @Option(names = "--describe", description = "Print column schema without exporting")
    boolean describe;

    @Option(names = "--verbose", description = "Enable verbose logging")
    boolean verbose;

    @Option(names = "--pretty", description = "Pretty-print JSON output")
    boolean pretty;

    @Option(names = "--include-header", description = "Include header row in CSV/TSV", defaultValue = "true", negatable = true)
    boolean includeHeader;

    @Option(names = "--null-value", description = "String to use for SQL null values in CSV/TSV", defaultValue = "")
    String nullValue;

    @Option(names = "--parquet-compression", description = "Parquet compression: SNAPPY, GZIP, ZSTD, UNCOMPRESSED", defaultValue = "SNAPPY")
    String parquetCompression;

    @Option(names = "--transform", arity = "1",
        description = "Outbound transform, repeatable. e.g. rename:old=new, drop:a,b, keep:a,b, "
            + "default:col=value, mask:email, map:status=A>Active,C>Closed, template:full={first} {last}")
    java.util.List<String> transforms;

    @Option(names = "--transforms-file", description = "Path to a JSON file describing the outbound transform pipeline")
    String transformsFile;

    @Option(names = "--on-transform-error",
        description = "Behaviour when a transform fails on a row: fail (default), skipRow, keepOriginal")
    String onTransformError;

    @Override
    public Integer call() {
        try {
            execute();
            return ExitCodes.SUCCESS;
        } catch (ExportException e) {
            System.err.println("ERROR: " + e.getMessage());
            if (verbose && e.getCause() != null) {
                e.getCause().printStackTrace(System.err);
            }
            return e.getExitCode();
        } catch (Exception e) {
            System.err.println("UNEXPECTED ERROR: " + e.getMessage());
            if (verbose) {
                e.printStackTrace(System.err);
            }
            return ExitCodes.UNEXPECTED_ERROR;
        }
    }

    private void execute() throws Exception {
        validateRequest();
        String resolvedPassword = PasswordResolver.resolve(password, passwordEnv);
        String resolvedSql = SqlInputResolver.resolve(sql, sqlFile);
        SqlSafetyValidator.validate(resolvedSql);
        OutputFormat outputFormat = format == null ? null : parseFormat(format);

        // Build the transform pipeline up front so configuration errors fail fast (before connecting).
        ErrorStrategy cliErrorStrategy = onTransformError == null ? null : ErrorStrategy.fromConfig(onTransformError);
        TransformConfig transformConfig = TransformConfigLoader.loadConfig(
            transformsFile == null ? null : Path.of(transformsFile), transforms, cliErrorStrategy);
        TransformPipeline pipeline = new TransformRegistry().build(transformConfig);

        if (!describe) {
            validateOutputRequirements();
        }
        validatePaths();

        if (dryRun) {
            System.out.println("Dry run complete. All options are valid.");
            return;
        }

        if (verbose) {
            LOG.info(() -> "Connecting to " + url);
        }

        try (Connection connection = JdbcConnectionFactory.connect(url, user, resolvedPassword)) {
            if (describe) {
                describeQuery(connection, resolvedSql);
                return;
            }

            ExportOptions options = new ExportOptions(
                url,
                user,
                resolvedPassword,
                sql,
                sqlFile,
                outputFormat,
                output,
                fetchSize,
                maxRows,
                metadata,
                overwrite,
                dryRun,
                describe,
                verbose,
                pretty,
                includeHeader,
                nullValue,
                parquetCompression
            );

            Instant startedAt = Instant.now();
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, resolvedSql, fetchSize);
            boolean transforming = !pipeline.isEmpty();
            List<ResultSetColumn> outputColumns = pipeline.outputSchema(columns);
            if (transformConfig.outputContract() != null) {
                transformConfig.outputContract().validate(outputColumns);
            }

            try (RowWriter writer = new RowWriterFactory().create(options, outputColumns, transforming)) {
                JdbcExporter.ExportResult result = new JdbcExporter().export(
                    connection, resolvedSql, fetchSize, maxRows, writer,
                    JdbcExporter.ProgressListener.NONE, columns, pipeline);
                Instant completedAt = Instant.now();
                long durationMillis = completedAt.toEpochMilli() - startedAt.toEpochMilli();

                if (verbose) {
                    System.out.printf("Exported %d rows in %d ms%n", result.rowCount(), durationMillis);
                }
                System.out.printf("Export complete: %d rows -> %s%n", result.rowCount(), output);
                if (transforming) {
                    System.out.println(pipeline.metrics().summary());
                    publishTransformMetrics(pipeline, outputFormat, durationMillis);
                }

                if (metadata != null) {
                    ExportMetadataWriter.write(
                        new ExportMetadata(
                            "jdbc-export-cli",
                            outputFormat.name().toLowerCase(),
                            url,
                            sqlFile != null ? sqlFile : "<inline>",
                            output,
                            result.rowCount(),
                            startedAt.toString(),
                            completedAt.toString(),
                            durationMillis,
                            outputColumns
                        ),
                        Path.of(metadata)
                    );
                }
            }
        }
    }

    private void publishTransformMetrics(TransformPipeline pipeline, OutputFormat outputFormat, long durationMillis) {
        TransformMetrics.Snapshot snapshot = pipeline.metrics().snapshot();
        TransformMetricsSettings settings = TransformMetricsSettings.fromConfig();
        TransformMetricsPublisher.publish(Metrics.globalRegistry, "cli", "cli",
            outputFormat.name().toLowerCase(), snapshot, java.time.Duration.ofMillis(durationMillis), settings);
        if (settings.enabled()) {
            for (TransformMetrics.StepMetrics step : snapshot.steps()) {
                if (settings.isSlow(step.totalMillis())) {
                    LOG.warning("Slow transform \"" + step.name() + "\": " + step.totalMillis() + " ms");
                }
            }
        }
    }

    private void validateRequest() {
        if (url == null || url.isBlank()) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "Missing required option: --url");
        }
        if (user == null || user.isBlank()) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "Missing required option: --user");
        }
        if (sql != null && sqlFile != null) {
            throw new ExportException(ExitCodes.SQL_INPUT_ERROR, "Specify either --sql or --sql-file, not both.");
        }
        if (sql == null && sqlFile == null) {
            throw new ExportException(ExitCodes.SQL_INPUT_ERROR, "One of --sql or --sql-file is required.");
        }
        if (fetchSize <= 0) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "--fetch-size must be greater than zero.");
        }
        if (maxRows != null && maxRows <= 0) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "--max-rows must be greater than zero.");
        }
    }

    private void validateOutputRequirements() {
        if (format == null) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "--format is required unless --describe is specified.");
        }
        if (output == null || output.isBlank()) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "--output is required unless --describe is specified.");
        }
    }

    private void validatePaths() throws Exception {
        if (output != null) {
            Path outputPath = Path.of(output);
            if (Files.exists(outputPath) && !overwrite) {
                throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                    "Output file already exists: " + output + ". Use --overwrite to replace it.");
            }
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
        if (metadata != null) {
            Path metadataPath = Path.of(metadata);
            if (Files.exists(metadataPath) && !overwrite) {
                throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                    "Metadata file already exists: " + metadata + ". Use --overwrite to replace it.");
            }
            Path parent = metadataPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
    }

    private OutputFormat parseFormat(String rawFormat) {
        try {
            return OutputFormat.fromString(rawFormat);
        } catch (IllegalArgumentException e) {
            throw new ExportException(
                ExitCodes.UNSUPPORTED_FORMAT,
                "Unsupported format: " + rawFormat + ". Valid values: json, ndjson, csv, tsv, parquet"
            );
        }
    }

    private void describeQuery(Connection connection, String resolvedSql) throws Exception {
        List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, resolvedSql, fetchSize);
        System.out.println("Columns:");
        for (ResultSetColumn col : columns) {
            System.out.printf(
                "  %d. %-20s JDBC %-15s nullable=%-5s output=%s%n",
                col.index(),
                col.label(),
                col.jdbcTypeName(),
                col.nullable(),
                col.outputName()
            );
        }
    }
}
