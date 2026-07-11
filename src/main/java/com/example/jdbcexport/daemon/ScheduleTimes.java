package com.example.jdbcexport.daemon;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure helpers for schedule timing and output-path rendering. Cron uses 5-field UNIX
 * syntax evaluated in the daemon's default time zone.
 */
final class ScheduleTimes {

    private static final CronParser UNIX =
        new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    private static final DateTimeFormatter ONCE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd[ HH:mm[:ss]]");

    private ScheduleTimes() {
    }

    /**
     * The next instant at or after {@code from} that this schedule should fire, if any.
     * For {@code once}, returns the configured time only while the schedule has not yet run.
     */
    static Optional<Instant> nextFire(Schedule s, Instant from) {
        ZoneId zone = ZoneId.systemDefault();
        return switch (trigger(s)) {
            case "cron" -> nextCron(s.cron(), from, zone);
            case "interval" -> nextInterval(s, from);
            case "once" -> s.lastRunAt() != null ? Optional.empty() : parseOnce(s.at(), zone);
            default -> Optional.empty();
        };
    }

    private static Optional<Instant> nextCron(String expr, Instant from, ZoneId zone) {
        if (expr == null || expr.isBlank()) {
            return Optional.empty();
        }
        try {
            ExecutionTime et = ExecutionTime.forCron(UNIX.parse(expr.trim()));
            return et.nextExecution(ZonedDateTime.ofInstant(from, zone)).map(ZonedDateTime::toInstant);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> nextInterval(Schedule s, Instant from) {
        if (s.every() == null || s.every() <= 0) {
            return Optional.empty();
        }
        ChronoUnit unit = switch (s.unit() == null ? "" : s.unit().toLowerCase(Locale.ROOT)) {
            case "minute", "minutes" -> ChronoUnit.MINUTES;
            case "hour", "hours" -> ChronoUnit.HOURS;
            case "day", "days" -> ChronoUnit.DAYS;
            default -> null;
        };
        if (unit == null) {
            return Optional.empty();
        }
        // Anchor to the grid of due times (createdAt + k * every), not to the actual
        // last-run time: a run fired late must not drift every subsequent run later
        // (issue #33b). If runs were missed, this lands on the next grid point after
        // {@code from}, so at most one catch-up run fires instead of a burst.
        Instant anchor = s.createdAt() != null ? s.createdAt() : from;
        long stepMillis = Duration.of(s.every(), unit).toMillis();
        long sinceAnchor = Duration.between(anchor, from).toMillis();
        long steps = sinceAnchor < 0 ? 0 : sinceAnchor / stepMillis + 1;
        return Optional.of(anchor.plusMillis(steps * stepMillis));
    }

    static Optional<Instant> parseOnce(String at, ZoneId zone) {
        if (at == null || at.isBlank()) {
            return Optional.empty();
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(at.trim(), ONCE_FORMAT);
            return Optional.of(ldt.atZone(zone).toInstant());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Validate that a trigger is well-formed; returns null if valid, else a reason. */
    static String validate(Schedule s) {
        switch (trigger(s)) {
            case "cron":
                try {
                    UNIX.parse(s.cron() == null ? "" : s.cron().trim());
                } catch (RuntimeException e) {
                    return "Invalid cron expression (use 5 fields: min hour day-of-month month day-of-week).";
                }
                return null;
            case "interval":
                return nextInterval(s, Instant.now()).isPresent()
                    ? null : "Interval needs a positive 'every' and a unit of minute, hour, or day.";
            case "once":
                return parseOnce(s.at(), ZoneId.systemDefault()).isPresent()
                    ? null : "One-off 'run at' must look like 2026-06-15 06:00.";
            default:
                return "Trigger type must be cron, interval, or once.";
        }
    }

    private static String trigger(Schedule s) {
        return s.triggerType() == null ? "" : s.triggerType().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Render an output path pattern for a fire at {@code when}. Supported placeholders:
     * {@code {date}} (yyyy-MM-dd), {@code {time}} (HHmmss), {@code {datetime}}
     * (yyyy-MM-dd_HHmmss), and {@code {ts}}/{@code {timestamp}} (epoch milliseconds).
     */
    static String renderOutput(String pattern, Instant when) {
        if (pattern == null) {
            return null;
        }
        ZonedDateTime z = ZonedDateTime.ofInstant(when, ZoneId.systemDefault());
        return pattern
            .replace("{date}", z.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            .replace("{time}", z.format(DateTimeFormatter.ofPattern("HHmmss")))
            .replace("{datetime}", z.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")))
            .replace("{timestamp}", String.valueOf(when.toEpochMilli()))
            .replace("{ts}", String.valueOf(when.toEpochMilli()));
    }

    /** Whole seconds until {@code instant}, floored at zero — for "next run in Xs" displays. */
    static long secondsUntil(Instant instant, Instant now) {
        long s = Duration.between(now, instant).getSeconds();
        return Math.max(0, s);
    }
}
