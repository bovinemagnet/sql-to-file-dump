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
    }

    private final ExportJobService service = new ExportJobService();

    private ExportJobRequest request(String sql, Path output) {
        return new ExportJobRequest("jdbc:duckdb:", "ignored", null, null, sql, OutputFormat.CSV, output.toString(), false);
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
