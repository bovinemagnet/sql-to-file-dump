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
    void updateStatusOnlyChangesStatus(@TempDir Path dir) {
        ScheduleStore store = store(dir);
        Schedule s = store.create(draft("s", "cron", "0 2 * * *"));
        store.markRun(s.id(), Instant.now(), "job-1", "running");

        store.updateStatus(s.id(), "completed");

        assertThat(store.get(s.id()).orElseThrow().lastStatus()).isEqualTo("completed");
        assertThat(store.get(s.id()).orElseThrow().lastJobId()).isEqualTo("job-1");
    }
}
