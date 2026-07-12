package com.example.jdbcexport.daemon;

import com.example.jdbcexport.error.ExportException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleStoreTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private ScheduleStore store(Path dir) {
        return new ScheduleStore(dir.resolve("schedule.json"), mapper);
    }

    private Schedule draft(String name, String trigger, String cron) {
        return new Schedule(null, name, true, "conn-1", "select 1 as a", "parquet", "ZSTD",
            "exports/x_{date}.parquet", true, trigger, cron, null, null, null, null, null, null, null);
    }

    @Test
    void createValidatesAndAssignsId(@TempDir Path dir) {
        ScheduleStore store = store(dir);
        Schedule s = store.create(draft("nightly", "cron", "0 2 * * *"));

        assertThat(s.id()).isNotBlank();
        assertThat(s.createdAt()).isNotNull();
        assertThat(store.list()).extracting(Schedule::name).containsExactly("nightly");
    }

    @Test
    void rejectsMissingConnectionAndBadTrigger(@TempDir Path dir) {
        ScheduleStore store = store(dir);
        assertThatThrownBy(() -> store.create(
            new Schedule(null, "n", true, "", "select 1 as a", "csv", null, "o_{date}.csv", true, "cron", "0 2 * * *", null, null, null, null, null, null, null)))
            .isInstanceOf(ExportException.class).hasMessageContaining("connection");
        assertThatThrownBy(() -> store.create(draft("n", "cron", "nonsense")))
            .isInstanceOf(ExportException.class).hasMessageContaining("cron");
    }

    @Test
    void rejectsNonSelectSqlAtSave(@TempDir Path dir) {
        ScheduleStore store = store(dir);
        Schedule writing = new Schedule(null, "n", true, "conn-1", "DELETE FROM bookings", "csv", null,
            "o_{date}.csv", true, "cron", "0 2 * * *", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> store.create(writing))
            .isInstanceOf(ExportException.class).hasMessageContaining("SELECT");

        Schedule valid = store.create(draft("n", "cron", "0 2 * * *"));
        assertThatThrownBy(() -> store.update(valid.id(), writing))
            .isInstanceOf(ExportException.class).hasMessageContaining("SELECT");
    }

    @Test
    void rejectsUnsupportedFormat(@TempDir Path dir) {
        ScheduleStore store = store(dir);
        // The format is rendered in the dashboard and drives the writer: whitelist it.
        assertThatThrownBy(() -> store.create(new Schedule(null, "n", true, "conn-1", "select 1 as a",
            "html", null, "o_{date}.html", true, "cron", "0 2 * * *", null, null, null, null, null, null, null)))
            .isInstanceOf(ExportException.class).hasMessageContaining("format");
        assertThatThrownBy(() -> store.create(new Schedule(null, "n", true, "conn-1", "select 1 as a",
            "csv<script>", null, "o_{date}.csv", true, "cron", "0 2 * * *", null, null, null, null, null, null, null)))
            .isInstanceOf(ExportException.class).hasMessageContaining("format");
    }

    @Test
    void acceptsAllSupportedFormatsCaseInsensitively(@TempDir Path dir) {
        ScheduleStore store = store(dir);
        for (String format : new String[] {"csv", "TSV", "json", "NDJSON", "parquet"}) {
            Schedule s = store.create(new Schedule(null, "n-" + format, true, "conn-1", "select 1 as a",
                format, null, "o_{date}.out", true, "cron", "0 2 * * *", null, null, null, null, null, null, null));
            assertThat(s.format()).isEqualTo(format.toLowerCase());
        }
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) throws Exception {
        ScheduleStore store = store(dir);
        Schedule s = store.create(draft("hourly", "cron", "0 * * * *"));

        assertThat(Files.readString(dir.resolve("schedule.json"))).contains("hourly");

        ScheduleStore reloaded = store(dir);
        assertThat(reloaded.get(s.id())).isPresent();
        assertThat(reloaded.get(s.id()).orElseThrow().outputPattern()).isEqualTo("exports/x_{date}.parquet");
    }

    @Test
    void updateKeepsCreatedAtAndRunHistory(@TempDir Path dir) {
        ScheduleStore store = store(dir);
        Schedule s = store.create(draft("s", "cron", "0 2 * * *"));
        store.markRun(s.id(), Instant.now(), "job-9", "running");

        Schedule updated = store.update(s.id(), draft("s-renamed", "cron", "0 3 * * *"));

        assertThat(updated.name()).isEqualTo("s-renamed");
        assertThat(updated.createdAt()).isEqualTo(s.createdAt());
        assertThat(updated.lastJobId()).isEqualTo("job-9");
        assertThat(updated.lastStatus()).isEqualTo("running");
    }

    @Test
    void toggleAndDelete(@TempDir Path dir) {
        ScheduleStore store = store(dir);
        Schedule s = store.create(draft("s", "cron", "0 2 * * *"));

        assertThat(store.setEnabled(s.id(), false).orElseThrow().enabled()).isFalse();
        assertThat(store.delete(s.id())).isTrue();
        assertThat(store.delete(s.id())).isFalse();
    }

    @Test
    void quarantinesCorruptFileInsteadOfSilentlyReplacingIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("schedule.json");
        String corrupt = "[ {\"id\": truncated";
        Files.writeString(file, corrupt);

        ScheduleStore store = store(dir);

        // The store starts empty, but the operator's data survives in a quarantine file.
        assertThat(store.list()).isEmpty();
        assertThat(Files.exists(file)).isFalse();
        Path quarantined = quarantineFileIn(dir);
        assertThat(Files.readString(quarantined)).isEqualTo(corrupt);

        // Subsequent writes go to a fresh file and leave the quarantined data untouched.
        store.create(draft("fresh", "cron", "0 2 * * *"));
        assertThat(Files.readString(file)).contains("fresh");
        assertThat(Files.readString(quarantined)).isEqualTo(corrupt);
    }

    private static Path quarantineFileIn(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("schedule.json.bad-"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No quarantine file found in " + dir));
        }
    }

    @Test
    void updateStatusOnlyChangesStatus(@TempDir Path dir) {
        ScheduleStore store = store(dir);
        Schedule s = store.create(draft("s", "cron", "0 2 * * *"));
        store.markRun(s.id(), Instant.now(), "job-1", "running");

        store.updateStatus(s.id(), "completed");

        assertThat(store.get(s.id()).orElseThrow().lastStatus()).isEqualTo("completed");
        assertThat(store.get(s.id()).orElseThrow().lastJobId()).isEqualTo("job-1");
    }
}
