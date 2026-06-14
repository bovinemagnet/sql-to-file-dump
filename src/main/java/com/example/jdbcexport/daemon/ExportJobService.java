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
import com.example.jdbcexport.writer.RowWriter;
import com.example.jdbcexport.writer.RowWriterFactory;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
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
        ExportJob job = new ExportJob(String.valueOf(sequence.incrementAndGet()), Instant.now(), request);
        jobs.put(job.getId(), job);
        executor.submit(() -> run(job, request, resolvedPassword));
        return job;
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
        try (Connection connection = JdbcConnectionFactory.connect(request.url(), request.user(), resolvedPassword)) {
            List<ResultSetColumn> columns = ResultSetSchemaReader.readColumns(connection, request.sql(), DEFAULT_FETCH_SIZE);
            ExportOptions options = new ExportOptions(
                request.url(), request.user(), resolvedPassword, request.sql(), null,
                request.format(), request.output(), DEFAULT_FETCH_SIZE, null, null,
                request.overwrite(), false, false, false, false, true, "", "SNAPPY");
            try (RowWriter writer = new RowWriterFactory().create(options, columns)) {
                JdbcExporter.ExportResult result =
                    new JdbcExporter().export(connection, request.sql(), DEFAULT_FETCH_SIZE, null, writer);
                job.markCompleted(Instant.now(), result.rowCount());
            }
        } catch (Exception e) {
            job.markFailed(Instant.now(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
