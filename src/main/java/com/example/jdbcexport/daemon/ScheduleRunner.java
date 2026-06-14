package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires due {@link Schedule}s by reusing the export pipeline via {@link ExportJobService}.
 * A single background thread ticks roughly every 30 seconds. It runs only inside the
 * daemon (when the HTTP listener is enabled) and never during one-shot CLI exports or tests.
 */
@ApplicationScoped
public class ScheduleRunner {

    private static final long INITIAL_DELAY_SECONDS = 10;
    private static final long TICK_SECONDS = 30;

    @Inject
    ScheduleStore scheduleStore;

    @Inject
    ConnectionStore connectionStore;

    @Inject
    ExportJobService jobService;

    @ConfigProperty(name = "quarkus.http.host-enabled", defaultValue = "true")
    boolean httpEnabled;

    private ScheduledExecutorService executor;

    void onStart(@Observes StartupEvent event) {
        // Only run inside the daemon: a one-shot CLI export disables the HTTP listener,
        // and tests drive scheduling explicitly rather than via the wall clock.
        if (!httpEnabled || LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sluice-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::safeTick, INITIAL_DELAY_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    private void safeTick() {
        try {
            tick(Instant.now());
        } catch (RuntimeException e) {
            System.err.println("sluice: scheduler tick failed: " + e.getMessage());
        }
    }

    /** Visible for testing: evaluate every schedule once against {@code now}. */
    void tick(Instant now) {
        for (Schedule s : scheduleStore.list()) {
            refreshLastStatus(s);
            if (!s.enabled()) {
                continue;
            }
            Instant from = s.lastRunAt() != null ? s.lastRunAt() : s.createdAt();
            Optional<Instant> next = ScheduleTimes.nextFire(s, from);
            if (next.isPresent() && !next.get().isAfter(now)) {
                try {
                    fire(s, now);
                } catch (RuntimeException e) {
                    scheduleStore.markRun(s.id(), now, null, "failed");
                }
            }
        }
    }

    /** Run a schedule immediately (the "Run now" action). Returns the created job id. */
    public String runNow(String id) {
        Schedule s = scheduleStore.get(id)
            .orElseThrow(() -> new ExportException(ExitCodes.INVALID_ARGUMENTS, "Schedule not found: " + id));
        return fire(s, Instant.now());
    }

    private String fire(Schedule s, Instant now) {
        SavedConnection conn = connectionStore.get(s.connectionId()).orElse(null);
        if (conn == null) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS,
                "Schedule '" + s.name() + "' references a missing connection.");
        }
        String output = ScheduleTimes.renderOutput(s.outputPattern(), now);
        ExportJobRequest request = new ExportJobRequest(
            conn.url(), conn.user(), null, conn.passwordEnv(), s.sql(),
            parseFormat(s.format()), output, s.overwrite(), s.compression());
        ExportJob job = jobService.submit(request);
        scheduleStore.markRun(s.id(), now, job.getId(), "running");
        connectionStore.markUsed(conn.id());
        return job.getId();
    }

    private void refreshLastStatus(Schedule s) {
        if (s.lastJobId() == null || !"running".equals(s.lastStatus())) {
            return;
        }
        jobService.find(s.lastJobId())
            .filter(ExportJob::isFinished)
            .ifPresent(job -> scheduleStore.updateStatus(s.id(), job.getStatus().name().toLowerCase(java.util.Locale.ROOT)));
    }

    private OutputFormat parseFormat(String value) {
        try {
            return OutputFormat.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ExportException(ExitCodes.UNSUPPORTED_FORMAT, "Unsupported format: " + value);
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
