package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportJobServiceTest {

    @BeforeAll
    static void registerDriver() throws Exception {
        // A @QuarkusTest elsewhere in the suite can trigger DriverManager's one-time
        // ServiceLoader scan under the Quarkus classloader, leaving the DuckDB driver
        // invisible to this classloader. Register it explicitly.
        DriverManager.registerDriver(new org.duckdb.DuckDBDriver());
        DriverManager.registerDriver(new ErrorThrowingDriver());
    }

    /** Simulates a broken driver whose connect throws an {@link Error}, not an exception (issue #32). */
    public static final class ErrorThrowingDriver implements java.sql.Driver {
        @Override
        public java.sql.Connection connect(String url, java.util.Properties info) {
            if (!acceptsURL(url)) {
                return null;
            }
            throw new NoClassDefFoundError("simulated missing driver class");
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:boom:");
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) {
            return new java.sql.DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 0;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("boom");
        }
    }

    private final ExportJobService service = new ExportJobService();

    private ExportJobRequest request(String sql, Path output) {
        return new ExportJobRequest("jdbc:duckdb:", "ignored", null, null, sql, OutputFormat.CSV, output.toString(), false);
    }

    private ExportJobRequest requestWithTransforms(String sql, Path output, List<String> transforms) {
        return new ExportJobRequest("jdbc:duckdb:", "ignored", null, null, sql, OutputFormat.CSV, output.toString(),
            false, "SNAPPY", transforms, null);
    }

    @Test
    void appliesTransformsAndCapturesTransformMetrics(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("transformed.csv");
        ExportJob job = service.submit(requestWithTransforms(
            "SELECT 1 AS a, 2 AS b", output, List.of("rename:a=id", "addStatic:source=sys")));

        awaitFinished(job);

        assertThat(job.getStatus()).isEqualTo(ExportJob.Status.COMPLETED);
        assertThat(job.isTransformsEnabled()).isTrue();
        assertThat(Files.readAllLines(output)).containsExactly("id,b,source", "1,2,sys");

        var snapshot = job.getTransformMetrics();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.rowsOut()).isEqualTo(1);
        assertThat(snapshot.steps()).extracting(s -> s.type()).containsExactly("rename", "addStatic");
    }

    @Test
    void transformationViewExposesPipelineForDashboard(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("view.csv");
        ExportJob job = service.submit(requestWithTransforms(
            "SELECT 'a@b.com' AS email, 5 AS n", output, List.of("mask:email")));
        awaitFinished(job);

        DashboardApiResource.TransformationView view = DashboardApiResource.TransformationView.from(job);
        assertThat(view.jobId()).isEqualTo(job.getId());
        assertThat(view.rowsOut()).isEqualTo(1);
        assertThat(view.steps()).singleElement()
            .satisfies(step -> assertThat(step.type()).isEqualTo("mask"));
        // Masked output must not leak the original value.
        assertThat(Files.readString(output)).doesNotContain("a@b.com").contains("***");
    }

    @Test
    void failedTransformJobPublishesErrorStatusMetrics(@TempDir Path tempDir) {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Metrics.addRegistry(registry);
        try {
            // The rename references a missing column, so the export fails after the
            // pipeline is built; the published metrics must not claim success (issue #34).
            ExportJob job = service.submit(requestWithTransforms(
                "SELECT 1 AS a", tempDir.resolve("fail.csv"), List.of("rename:missing=x")));
            awaitFinished(job);

            assertThat(job.getStatus()).isEqualTo(ExportJob.Status.FAILED);
            // The global composite may carry success-tagged meters from other tests,
            // so assert on the error-tagged timer: it only exists once the failed
            // outcome is threaded through to the publisher.
            var errorTimer = registry.find("sql_transformer_transform_pipeline_duration_seconds")
                .tag("status", "error").timer();
            assertThat(errorTimer).isNotNull();
            assertThat(errorTimer.count()).isEqualTo(1);
        } finally {
            io.micrometer.core.instrument.Metrics.removeRegistry(registry);
        }
    }

    @Test
    void passThroughJobIsNotTransformsEnabled(@TempDir Path tempDir) throws Exception {
        ExportJob job = service.submit(request("SELECT 1 AS a", tempDir.resolve("plain.csv")));
        awaitFinished(job);
        assertThat(job.isTransformsEnabled()).isFalse();
        assertThat(job.getTransformMetrics()).isNull();
    }

    @Test
    void runsSubmittedJobToCompletion(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.csv");
        ExportJob job = service.submit(request("SELECT 1 AS a, 2 AS b", output));

        awaitFinished(job);

        assertThat(job.getStatus()).isEqualTo(ExportJob.Status.COMPLETED);
        assertThat(job.getRowCount()).isEqualTo(1);
        assertThat(job.getDurationMillis()).isNotNull();
        assertThat(Files.readAllLines(output)).containsExactly("a,b", "1,2");
    }

    @Test
    void capturesRunMetrics(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("metrics.csv");
        ExportJob job = service.submit(request("SELECT 1 AS a, 2 AS b", output));

        awaitFinished(job);

        assertThat(job.getStatus()).isEqualTo(ExportJob.Status.COMPLETED);
        assertThat(job.getDriver()).isEqualTo("duckdb");
        assertThat(job.getFetchSize()).isEqualTo(1000);
        assertThat(job.getColumnCount()).isEqualTo(2);
        assertThat(job.getServerInfo()).isNotBlank();
        assertThat(job.getOutputBytes()).isPositive();
        assertThat(job.getCompression()).isEqualTo("SNAPPY");
    }

    @Test
    void metricsSnapshotReportsHeapAndCounts(@TempDir Path tempDir) throws Exception {
        ExportJob job = service.submit(request("SELECT 1 AS a", tempDir.resolve("m.csv")));
        awaitFinished(job);

        ExportJobService.DaemonMetrics metrics = service.metrics();

        assertThat(metrics.heapUsedBytes()).isPositive();
        assertThat(metrics.jobsTotal()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.completed()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void marksJobFailedOnBadSql(@TempDir Path tempDir) {
        ExportJob job = service.submit(request("SELECT * FROM no_such_table", tempDir.resolve("out.csv")));

        awaitFinished(job);

        assertThat(job.getStatus()).isEqualTo(ExportJob.Status.FAILED);
        assertThat(job.getError()).isNotBlank();
    }

    @Test
    void marksJobFailedWhenAnErrorEscapesTheRun(@TempDir Path tempDir) {
        ExportJob job = service.submit(new ExportJobRequest(
            "jdbc:boom:", "ignored", null, null, "SELECT 1 AS a", OutputFormat.CSV,
            tempDir.resolve("boom.csv").toString(), false));

        awaitFinished(job);

        assertThat(job.getStatus()).isEqualTo(ExportJob.Status.FAILED);
        assertThat(job.getError()).contains("simulated missing driver class");
    }

    @Test
    void rejectsNonSelectSqlOnSubmit(@TempDir Path tempDir) {
        assertThatThrownBy(() -> service.submit(request("DELETE FROM bookings", tempDir.resolve("out.csv"))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("SELECT");
    }

    @Test
    void rejectsExistingOutputWithoutOverwrite(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out.csv");
        Files.writeString(output, "existing");

        assertThatThrownBy(() -> service.submit(request("SELECT 1 AS a", output)))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void listsJobsNewestFirst(@TempDir Path tempDir) {
        ExportJob first = service.submit(request("SELECT 1 AS a", tempDir.resolve("one.csv")));
        ExportJob second = service.submit(request("SELECT 2 AS a", tempDir.resolve("two.csv")));

        assertThat(service.jobs()).extracting(ExportJob::getId)
            .containsExactly(second.getId(), first.getId());
        assertThat(service.find(first.getId())).contains(first);
    }

    @Test
    void evictsOldestFinishedJobsBeyondCap(@TempDir Path tempDir) {
        ExportJobService capped = new ExportJobService(3);
        for (int i = 1; i <= 5; i++) {
            ExportJob job = capped.submit(request("SELECT " + i + " AS a", tempDir.resolve("cap-" + i + ".csv")));
            awaitFinished(job);
        }

        assertThat(capped.jobs()).extracting(ExportJob::getId).containsExactly("5", "4", "3");
        assertThat(capped.find("1")).isEmpty();
        assertThat(capped.find("2")).isEmpty();
        assertThat(capped.find("5")).isPresent();
    }

    @Test
    void metricsReflectEvictedRegistry(@TempDir Path tempDir) {
        ExportJobService capped = new ExportJobService(2);
        for (int i = 1; i <= 4; i++) {
            awaitFinished(capped.submit(request("SELECT " + i + " AS a", tempDir.resolve("m-" + i + ".csv"))));
        }

        ExportJobService.DaemonMetrics metrics = capped.metrics();
        assertThat(metrics.jobsTotal()).isEqualTo(2);
        assertThat(metrics.completed()).isEqualTo(2);
        assertThat(metrics.running()).isZero();
        assertThat(metrics.queued()).isZero();
        assertThat(metrics.failed()).isZero();
        assertThat(metrics.rowsTotal()).isEqualTo(2);
    }

    @Test
    void describeReturnsColumns(@TempDir Path tempDir) {
        List<ResultSetColumn> columns = service.describe(request("SELECT 1 AS a, 'x' AS b", tempDir.resolve("ignored.csv")));

        assertThat(columns).extracting(ResultSetColumn::outputName).containsExactly("a", "b");
    }

    private void awaitFinished(ExportJob job) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!job.isFinished()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Job did not finish in time, status: " + job.getStatus());
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for job", e);
            }
        }
    }
}
