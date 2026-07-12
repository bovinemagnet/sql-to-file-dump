package com.example.jdbcexport.daemon;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleTimesTest {

    private Schedule schedule(String triggerType, String cron, Integer every, String unit, String at, Instant lastRun) {
        return new Schedule("id", "s", true, "c", "select 1 as a", "csv", "SNAPPY",
            "out/{date}.csv", true, triggerType, cron, every, unit, at,
            Instant.parse("2026-01-01T00:00:00Z"), lastRun, null, null);
    }

    @Test
    void cronNextFireIsAfterFrom() {
        Instant from = Instant.parse("2026-06-14T10:07:00Z");
        Optional<Instant> next = ScheduleTimes.nextFire(schedule("cron", "0 2 * * *", null, null, null, null), from);
        assertThat(next).isPresent();
        assertThat(next.get()).isAfter(from);
    }

    @Test
    void intervalAddsEveryUnit() {
        Instant from = Instant.parse("2026-06-14T10:00:00Z");
        Optional<Instant> next = ScheduleTimes.nextFire(schedule("interval", null, 15, "minute", null, null), from);
        assertThat(next).contains(from.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void intervalNextFireAnchorsToTheGridNotTheActualLastRun() {
        // Created 2026-01-01T00:00Z, every 15 minutes: due times are on the :00/:15/:30/:45 grid.
        // A run fired 25 seconds late must not push the next fire 25 seconds later (issue #33b).
        Instant lateLastRun = Instant.parse("2026-01-01T00:15:25Z");
        Optional<Instant> next = ScheduleTimes.nextFire(schedule("interval", null, 15, "minute", null, lateLastRun), lateLastRun);
        assertThat(next).contains(Instant.parse("2026-01-01T00:30:00Z"));
    }

    @Test
    void intervalSkipsForwardAfterMissedRunsInsteadOfBursting() {
        // Several intervals were missed (daemon down): the next fire is the next grid
        // point after the last run, so at most one catch-up run happens.
        Instant staleLastRun = Instant.parse("2026-01-01T01:07:00Z");
        Optional<Instant> next = ScheduleTimes.nextFire(schedule("interval", null, 15, "minute", null, staleLastRun), staleLastRun);
        assertThat(next).contains(Instant.parse("2026-01-01T01:15:00Z"));
    }

    @Test
    void onceFiresOnlyUntilItHasRun() {
        Schedule pending = schedule("once", null, null, null, "2026-06-15 06:00", null);
        assertThat(ScheduleTimes.nextFire(pending, Instant.EPOCH)).isPresent();

        Schedule alreadyRan = schedule("once", null, null, null, "2026-06-15 06:00", Instant.parse("2026-06-15T06:00:00Z"));
        assertThat(ScheduleTimes.nextFire(alreadyRan, Instant.EPOCH)).isEmpty();
    }

    @Test
    void parseOnceReadsLocalDateTime() {
        assertThat(ScheduleTimes.parseOnce("2026-06-15 06:00", ZoneId.of("UTC")))
            .contains(Instant.parse("2026-06-15T06:00:00Z"));
        assertThat(ScheduleTimes.parseOnce("not a date", ZoneId.of("UTC"))).isEmpty();
    }

    @Test
    void renderOutputSubstitutesPlaceholders() {
        Instant when = Instant.parse("2026-06-14T09:30:15Z");
        String out = ScheduleTimes.renderOutput("exports/{date}/course_{ts}.parquet", when);
        assertThat(out).contains("exports/").contains(String.valueOf(when.toEpochMilli())).endsWith(".parquet");
        assertThat(ScheduleTimes.renderOutput("c_{date}.csv", when)).matches("c_\\d{4}-\\d{2}-\\d{2}\\.csv");
    }

    @Test
    void validateRejectsBadTriggersAndAcceptsGoodOnes() {
        assertThat(ScheduleTimes.validate(schedule("cron", "0 2 * * *", null, null, null, null))).isNull();
        assertThat(ScheduleTimes.validate(schedule("cron", "not-a-cron", null, null, null, null))).contains("cron");
        assertThat(ScheduleTimes.validate(schedule("interval", null, 0, "hour", null, null))).contains("Interval");
        assertThat(ScheduleTimes.validate(schedule("interval", null, 2, "hour", null, null))).isNull();
        assertThat(ScheduleTimes.validate(schedule("once", null, null, null, "bad", null))).contains("run at");
        assertThat(ScheduleTimes.validate(schedule("nope", null, null, null, null, null))).contains("cron, interval, or once");
    }
}
