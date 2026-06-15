package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.ExportOptions;
import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.JdbcConnectionFactory;
import com.example.jdbcexport.jdbc.JdbcExporter;
import com.example.jdbcexport.jdbc.PasswordResolver;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.jdbc.ResultSetSchemaReader;
import com.example.jdbcexport.jdbc.SqlSafetyValidator;
import com.example.jdbcexport.transform.ErrorStrategy;
import com.example.jdbcexport.transform.TransformConfig;
import com.example.jdbcexport.transform.TransformConfigLoader;
import com.example.jdbcexport.transform.TransformMetrics;
import com.example.jdbcexport.transform.TransformPipeline;
import com.example.jdbcexport.transform.TransformRegistry;
import com.example.jdbcexport.transform.metrics.TransformMetricsPublisher;
import com.example.jdbcexport.transform.metrics.TransformMetricsSettings;
import com.example.jdbcexport.writer.RowWriter;
import com.example.jdbcexport.writer.RowWriterFactory;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class ExportJobService {

    private static final int DEFAULT_FETCH_SIZE = 1000;

    private final Map<String, ExportJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ExportJob submit(ExportJobRequest request) {
        String resolvedPassword = validate(request);
        ExportJob job = new ExportJob(String.valueOf(sequence.incrementAndGet()), Instant.now(), request, DEFAULT_FETCH_SIZE);
        jobs.put(job.getId(), job);
        executor.submit(() -> run(job, request, resolvedPassword));
        return job;
    }

    /** Daemon-wide stats for the live dashboard (JVM heap, uptime, queue snapshot). */
    public DaemonMetrics metrics() {
        var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        long running = jobs.values().stream().filter(j -> j.getStatus() == ExportJob.Status.RUNNING).count();
        long queued = jobs.values().stream().filter(j -> j.getStatus() == ExportJob.Status.QUEUED).count();
        long completed = jobs.values().stream().filter(j -> j.getStatus() == ExportJob.Status.COMPLETED).count();
        long failed = jobs.values().stream().filter(j -> j.getStatus() == ExportJob.Status.FAILED).count();
        long rowsTotal = jobs.values().stream().mapToLong(ExportJob::getRowCount).sum();
        return new DaemonMetrics(heap.getUsed(), heap.getMax(), uptime,
            jobs.size(), running, queued, completed, failed, rowsTotal);
    }

    public record DaemonMetrics(long heapUsedBytes, long heapMaxBytes, long uptimeMillis,
                                int jobsTotal, long running, long queued, long completed, long failed,
                                long rowsTotal) {
    }

    public List<ResultSetColumn> describe(ExportJobRequest request) {
        requireConnectionFields(request);
        String resolvedPassword = PasswordResolver.resolve(request.password(), request.passwordEnv());
        SqlSafetyValidator.validate(request.sql());
        try (Connection connection = JdbcConnectionFactory.connect(request.url(), request.user(), resolvedPassword)) {
            return ResultSetSchemaReader.readColumns(connection, request.sql(), DEFAULT_FETCH_SIZE);
        } catch (ExportException e) {
            throw e;
        } catch (Exception e) {
            throw new ExportException(ExitCodes.DATABASE_ERROR, "Describe failed: " + e.getMessage(), e);
        }
    }

    public Optional<ExportJob> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<ExportJob> jobs() {
        return jobs.values().stream()
            .sorted(Comparator.comparingLong((ExportJob job) -> Long.parseLong(job.getId())).reversed())
            .toList();
    }

    private String validate(ExportJobRequest request) {
        requireConnectionFields(request);
        String resolvedPassword = PasswordResolver.resolve(request.password(), request.passwordEnv());
        SqlSafetyValidator.validate(request.sql());
        if (request.format() == null) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "Format is required.");
        }
        if (request.output() == null || request.output().isBlank()) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "Output path is required.");
        }
        Path outputPath = Path.of(request.output());
        if (Files.exists(outputPath) && !request.overwrite()) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                "Output file already exists: " + request.output() + ". Tick overwrite to replace it.");
        }
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                "Cannot create output directory for: " + request.output(), e);
        }
        return resolvedPassword;
    }

    private void requireConnectionFields(ExportJobRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "JDBC URL is required.");
        }
        if (request.user() == null || request.user().isBlank()) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "Database user is required.");
        }
    }

    private void run(ExportJob job, ExportJobRequest request, String resolvedPassword) {
        job.markRunning(Instant.now());
        TransformPipeline pipeline = null;
        try (Connection connection = JdbcConnectionFactory.connect(request.url(), request.user(), resolvedPassword)) {
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, request.sql(), DEFAULT_FETCH_SIZE);

            ErrorStrategy strategy = request.errorStrategy() == null ? null : ErrorStrategy.fromConfig(request.errorStrategy());
            TransformConfig transformConfig = TransformConfigLoader.loadConfig(null, request.transforms(), strategy);
            pipeline = new TransformRegistry().build(transformConfig);
            boolean transforming = !pipeline.isEmpty();
            List<ResultSetColumn> outputColumns = pipeline.outputSchema(columns);
            if (transformConfig.outputContract() != null) {
                transformConfig.outputContract().validate(outputColumns);
            }
            job.recordSchema(outputColumns.size(), describeServer(connection));

            ExportOptions options = new ExportOptions(
                request.url(), request.user(), resolvedPassword, request.sql(), null,
                request.format(), request.output(), DEFAULT_FETCH_SIZE, null, null,
                request.overwrite(), false, false, false, false, true, "", request.parquetCompression());
            try (RowWriter writer = new RowWriterFactory().create(options, outputColumns, transforming)) {
                JdbcExporter.ExportResult result =
                    new JdbcExporter().export(connection, request.sql(), DEFAULT_FETCH_SIZE, null, writer,
                        job::recordProgress, columns, pipeline);
                job.markCompleted(Instant.now(), result.rowCount());
            }
        } catch (Exception e) {
            job.markFailed(Instant.now(), e.getMessage());
        } finally {
            if (pipeline != null && !pipeline.isEmpty()) {
                recordTransformMetrics(job, request, pipeline);
            }
        }
    }

    private void recordTransformMetrics(ExportJob job, ExportJobRequest request, TransformPipeline pipeline) {
        TransformMetrics.Snapshot snapshot = pipeline.metrics().snapshot();
        TransformMetricsSettings settings = TransformMetricsSettings.fromConfig();
        job.recordTransformMetrics(snapshot, settings.slowTransformThresholdMs());
        String output = request.format() == null ? "unknown" : request.format().name().toLowerCase(Locale.ROOT);
        TransformMetricsPublisher.publish(Metrics.globalRegistry, "daemon", "daemon", output,
            snapshot, Duration.ofMillis(job.getElapsedMillis()), settings);
    }

    private static String describeServer(Connection connection) {
        try {
            var meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
