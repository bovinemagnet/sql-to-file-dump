package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.OutputFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExportJobTest {

    @Test
    void outputBytesTracksTheWritePathWhileRunningAndTheTargetOnceFinished(@TempDir Path tempDir) throws Exception {
        // Issue #24: exports stream to a temporary file that is renamed onto the target on
        // success, so live output bytes must be read from the in-progress write path.
        Path target = tempDir.resolve("out.csv");
        Path temp = tempDir.resolve(".out.csv.tmp");
        ExportJobRequest request = new ExportJobRequest(
            "jdbc:duckdb:", "user", null, null, "SELECT 1", OutputFormat.CSV, target.toString(), false);
        ExportJob job = new ExportJob("1", Instant.now(), request, 1000);

        job.markRunning(Instant.now());
        assertThat(job.getOutputBytes()).isZero();

        job.recordWritePath(temp.toString());
        Files.writeString(temp, "12345");
        assertThat(job.getOutputBytes()).isEqualTo(5L);

        Files.writeString(target, "1234567890");
        Files.delete(temp);
        job.markCompleted(Instant.now(), 1);
        assertThat(job.getOutputBytes()).isEqualTo(10L);
    }
}
