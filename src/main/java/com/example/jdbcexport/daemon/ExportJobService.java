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
import com.example.jdbcexport.writer.AtomicRowWriter;
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

    /**
     * How many finished (completed or failed) jobs are retained for the dashboard's
     * history. Without a cap, a schedule firing every minute grows the in-memory
     * registry until the daemon runs out of heap. RUNNING/QUEUED jobs are never evicted.
     */
    private static final int DEFAULT_MAX_FINISHED_JOBS = 200;

    private final Map<String, ExportJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final int maxFinishedJobs;

    public ExportJobService() {
        this(DEFAULT_MAX_FINISHED_JOBS);
    }

    /** Test constructor: explicit finished-job retention cap. */
    ExportJobService(int maxFinishedJobs) {
        this.maxFinishedJobs = maxFinishedJobs;
    }

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
        long running = 0;
        long queued = 0;
        long completed = 0;
        long failed = 0;
        long rowsTotal = 0;
        for (ExportJob job : jobs.values()) {
            switch (job.getStatus()) {
                case RUNNING -> running++;
                case QUEUED -> queued++;
                case COMPLETED -> completed++;
                case FAILED -> failed++;
            }
            rowsTotal += job.getRowCount();
        }
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
                request.url(), request.user(), request.sql(), null,
                request.format(), request.output(), DEFAULT_FETCH_SIZE, null, null,
                request.overwrite(), false, false, false, false, true, "", false, false,
                request.parquetCompression());
            try (RowWriter writer = new RowWriterFactory().create(options, outputColumns, transforming)) {
                if (writer instanceof AtomicRowWriter atomic) {
                    job.recordWritePath(atomic.temporaryPath().toString());
                }
                JdbcExporter.ExportResult result =
                    new JdbcExporter().export(connection, request.sql(), DEFAULT_FETCH_SIZE, null, writer,
                        job::recordProgress, columns, pipeline);
                job.markCompleted(Instant.now(), result.rowCount());
            }
        } catch (Throwable t) {
            // Catch Throwable, not Exception: an Error (OOM, StackOverflowError,
            // NoClassDefFoundError from a missing driver) must not leave the job
            // RUNNING forever (issue #32). Fatal errors are rethrown after the job
            // is marked failed, preserving the executor's default handling.
            job.markFailed(Instant.now(), t.getMessage());
            if (t instanceof Error error) {
                throw error;
            }
        } finally {
            if (pipeline != null && !pipeline.isEmpty()) {
                recordTransformMetrics(job, request, pipeline);
            }
            evictFinishedJobs();
        }
    }

    /**
     * Bounds the in-memory history: keeps the {@code maxFinishedJobs} most recent
     * finished jobs and drops the rest. RUNNING/QUEUED jobs are never touched.
     * Evicted jobs disappear from the dashboard job list and return 404 on lookup.
     */
    private void evictFinishedJobs() {
        List<ExportJob> finished = jobs.values().stream()
            .filter(ExportJob::isFinished)
            .sorted(Comparator.comparingLong((ExportJob job) -> Long.parseLong(job.getId())))
            .toList();
        for (int i = 0; i < finished.size() - maxFinishedJobs; i++) {
            jobs.remove(finished.get(i).getId());
        }
    }

    private void recordTransformMetrics(ExportJob job, ExportJobRequest request, TransformPipeline pipeline) {
        TransformMetrics.Snapshot snapshot = pipeline.metrics().snapshot();
        TransformMetricsSettings settings = TransformMetricsSettings.fromConfig();
        job.recordTransformMetrics(snapshot, settings.slowTransformThresholdMs());
        String output = request.format() == null ? "unknown" : request.format().name().toLowerCase(Locale.ROOT);
        // Metrics are published after markCompleted/markFailed, so tag the real outcome (issue #34).
        String status = job.getStatus() == ExportJob.Status.FAILED ? "error" : "success";
        TransformMetricsPublisher.publish(Metrics.globalRegistry, "daemon", "daemon", output,
            status, snapshot, Duration.ofMillis(job.getElapsedMillis()), settings);
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
