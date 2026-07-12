package com.example.jdbcexport.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link ScheduleRunner} directly via {@code tick}/{@code runNow} (the wall-clock
 * executor never starts under LaunchMode.TEST). Jobs are stubbed so a "running" export
 * can be held open and finished deterministically.
 */
class ScheduleRunnerTest {

    /** Records submissions and lets tests finish jobs on demand; never runs a real export. */
    static final class StubJobService extends ExportJobService {
        final List<ExportJobRequest> submitted = new ArrayList<>();
        final Map<String, ExportJob> byId = new HashMap<>();
        private int sequence;

        @Override
        public ExportJob submit(ExportJobRequest request) {
            submitted.add(request);
            ExportJob job = new ExportJob(String.valueOf(++sequence), Instant.now(), request, 1000);
            job.markRunning(Instant.now());
            byId.put(job.getId(), job);
            return job;
        }

        @Override
        public Optional<ExportJob> find(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        void finish(String jobId) {
            byId.get(jobId).markCompleted(Instant.now(), 1);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ScheduleRunner runner;
    private StubJobService jobService;
    private ScheduleStore scheduleStore;
    private ConnectionStore connectionStore;
    private SavedConnection connection;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        scheduleStore = new ScheduleStore(dir.resolve("schedule.json"), mapper);
        connectionStore = new ConnectionStore(dir.resolve("connections.json"), mapper);
        connection = connectionStore.create("duck", "duckdb", "jdbc:duckdb:", "ignored", null);
        jobService = new StubJobService();
        runner = new ScheduleRunner();
        runner.scheduleStore = scheduleStore;
        runner.connectionStore = connectionStore;
        runner.jobService = jobService;
    }

    private Schedule intervalSchedule(int everyMinutes) {
        return scheduleStore.create(new Schedule(null, "s", true, connection.id(), "select 1 as a",
            "csv", null, "out_{ts}.csv", true, "interval", null, everyMinutes, "minute", null,
            null, null, null, null));
    }

    @Test
    void tickDoesNotFireWhilePreviousRunIsStillRunning() {
        Schedule s = intervalSchedule(1);
        Instant due = s.createdAt().plus(2, ChronoUnit.MINUTES);

        runner.tick(due);
        assertThat(jobService.submitted).hasSize(1);

        // Two intervals later the job is still running: the tick must not overlap it.
        runner.tick(due.plus(2, ChronoUnit.MINUTES));
        assertThat(jobService.submitted).hasSize(1);

        // Once the job finishes, the next due tick fires again.
        jobService.finish("1");
        runner.tick(due.plus(3, ChronoUnit.MINUTES));
        assertThat(jobService.submitted).hasSize(2);
    }

    @Test
    void runNowWhileRunningReturnsInFlightJobWithoutSubmittingAgain() {
        Schedule s = intervalSchedule(60);

        String first = runner.runNow(s.id());
        String second = runner.runNow(s.id());

        assertThat(second).isEqualTo(first);
        assertThat(jobService.submitted).hasSize(1);

        jobService.finish(first);
        String third = runner.runNow(s.id());
        assertThat(third).isNotEqualTo(first);
        assertThat(jobService.submitted).hasSize(2);
    }

    @Test
    void tickSkipsScheduleAlreadyFiredByRunNow() {
        Schedule s = intervalSchedule(1);

        runner.runNow(s.id());
        // The schedule is due, but the run-now job is still in flight.
        runner.tick(Instant.now().plus(5, ChronoUnit.MINUTES));

        assertThat(jobService.submitted).hasSize(1);
    }

    @Test
    void tickFiresASimpleDueSchedule() {
        Schedule s = intervalSchedule(1);
        Instant due = s.createdAt().plus(2, ChronoUnit.MINUTES);

        runner.tick(due);

        assertThat(jobService.submitted).hasSize(1);
        Schedule refreshed = scheduleStore.get(s.id()).orElseThrow();
        assertThat(refreshed.lastStatus()).isEqualTo("running");
        assertThat(refreshed.lastJobId()).isEqualTo("1");
    }

    @Test
    void tickDoesNotFireBeforeTheScheduleIsDue() {
        intervalSchedule(60);

        // Only 5 minutes have passed against a 60-minute interval: nothing is due yet.
        runner.tick(Instant.now().plus(5, ChronoUnit.MINUTES));

        assertThat(jobService.submitted).isEmpty();
    }

    @Test
    void tickMarksAScheduleWithAMissingConnectionAsFailedWithoutSubmittingAJob() {
        // The schedule references a connection id that ConnectionStore has never heard of
        // (e.g. it was deleted after the schedule was created).
        Schedule dangling = scheduleStore.create(new Schedule(null, "orphan", true, "no-such-connection",
            "select 1 as a", "csv", null, "out_{ts}.csv", true, "interval", null, 1, "minute", null,
            null, null, null, null));
        Instant due = dangling.createdAt().plus(2, ChronoUnit.MINUTES);

        runner.tick(due);

        assertThat(jobService.submitted).isEmpty();
        Schedule refreshed = scheduleStore.get(dangling.id()).orElseThrow();
        assertThat(refreshed.lastStatus()).isEqualTo("failed");
        assertThat(refreshed.lastJobId()).isNull();
    }

    @Test
    void onceScheduleFiresOnTheFirstDueTickAndNeverAgain() {
        Schedule once = scheduleStore.create(new Schedule(null, "one-shot", true, connection.id(),
            "select 1 as a", "csv", null, "out_{ts}.csv", true, "once", null, null, null,
            "2026-01-01 00:00", null, null, null, null));
        // ScheduleTimes parses "at" in the JVM's default zone; a full day's margin past midnight
        // keeps this deterministic across any CI runner's time zone (UTC-12 through UTC+14).
        Instant firstDue = Instant.parse("2026-01-02T00:00:00Z");

        runner.tick(firstDue);
        assertThat(jobService.submitted).hasSize(1);
        Schedule afterFirstRun = scheduleStore.get(once.id()).orElseThrow();
        assertThat(afterFirstRun.lastRunAt()).isNotNull();

        // The job finishes and time moves on well past the "once" moment: it must not fire again.
        jobService.finish("1");
        runner.tick(firstDue.plus(1, ChronoUnit.DAYS));

        assertThat(jobService.submitted).hasSize(1);
    }
}
