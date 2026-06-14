package com.example.jdbcexport.daemon;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator-managed registry of {@link Schedule}s, persisted as a JSON array on disk
 * (default {@code ~/.sluice/schedule.json}, overridable via {@code sluice.schedule.file}).
 * No credentials are stored here — schedules reference a {@link SavedConnection} by id.
 */
@ApplicationScoped
public class ScheduleStore {

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "sluice.schedule.file")
    Optional<String> configuredPath;

    private Path file;
    private final Map<String, Schedule> schedules = new LinkedHashMap<>();
    private final Object lock = new Object();

    public ScheduleStore() {
        // CDI no-arg constructor.
    }

    /** Test constructor: explicit file + mapper, no CDI. */
    ScheduleStore(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
        load();
    }

    @PostConstruct
    void init() {
        file = configuredPath.filter(p -> !p.isBlank()).map(Path::of)
            .orElseGet(() -> Path.of(System.getProperty("user.home"), ".sluice", "schedule.json"));
        load();
    }

    public List<Schedule> list() {
        synchronized (lock) {
            return new ArrayList<>(schedules.values());
        }
    }

    public Optional<Schedule> get(String id) {
        synchronized (lock) {
            return Optional.ofNullable(schedules.get(id));
        }
    }

    public Schedule create(Schedule draft) {
        Schedule prepared = normalise(draft, UUID.randomUUID().toString(), Instant.now(), null, null, null);
        validate(prepared);
        synchronized (lock) {
            schedules.put(prepared.id(), prepared);
            save();
        }
        return prepared;
    }

    public Schedule update(String id, Schedule draft) {
        synchronized (lock) {
            Schedule existing = schedules.get(id);
            if (existing == null) {
                throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "Schedule not found: " + id);
            }
            Schedule prepared = normalise(draft, id, existing.createdAt(),
                existing.lastRunAt(), existing.lastStatus(), existing.lastJobId());
            validate(prepared);
            schedules.put(id, prepared);
            save();
            return prepared;
        }
    }

    public boolean delete(String id) {
        synchronized (lock) {
            boolean removed = schedules.remove(id) != null;
            if (removed) {
                save();
            }
            return removed;
        }
    }

    public Optional<Schedule> setEnabled(String id, boolean enabled) {
        synchronized (lock) {
            Schedule s = schedules.get(id);
            if (s == null) {
                return Optional.empty();
            }
            Schedule updated = withRun(s, enabled, s.lastRunAt(), s.lastStatus(), s.lastJobId());
            schedules.put(id, updated);
            save();
            return Optional.of(updated);
        }
    }

    /** Record that a schedule fired: stamps last-run time, job id and status. */
    public void markRun(String id, Instant when, String jobId, String status) {
        synchronized (lock) {
            Schedule s = schedules.get(id);
            if (s != null) {
                schedules.put(id, withRun(s, s.enabled(), when, status, jobId));
                save();
            }
        }
    }

    /** Update only the last-run status (e.g. when a previously running job finishes). */
    public void updateStatus(String id, String status) {
        synchronized (lock) {
            Schedule s = schedules.get(id);
            if (s != null && !java.util.Objects.equals(s.lastStatus(), status)) {
                schedules.put(id, withRun(s, s.enabled(), s.lastRunAt(), status, s.lastJobId()));
                save();
            }
        }
    }

    /* ───────── internals ───────── */

    private Schedule normalise(Schedule d, String id, Instant createdAt,
                               Instant lastRunAt, String lastStatus, String lastJobId) {
        return new Schedule(
            id,
            trim(d.name()),
            d.enabled(),
            trim(d.connectionId()),
            d.sql(),
            blankToDefault(d.format(), "csv").toLowerCase(java.util.Locale.ROOT),
            blankToDefault(d.compression(), "SNAPPY"),
            trim(d.outputPattern()),
            d.overwrite(),
            blankToDefault(d.triggerType(), "cron").toLowerCase(java.util.Locale.ROOT),
            trim(d.cron()),
            d.every(),
            trim(d.unit()),
            trim(d.at()),
            createdAt, lastRunAt, lastStatus, lastJobId);
    }

    private Schedule withRun(Schedule s, boolean enabled, Instant lastRunAt, String lastStatus, String lastJobId) {
        return new Schedule(s.id(), s.name(), enabled, s.connectionId(), s.sql(), s.format(),
            s.compression(), s.outputPattern(), s.overwrite(), s.triggerType(), s.cron(), s.every(),
            s.unit(), s.at(), s.createdAt(), lastRunAt, lastStatus, lastJobId);
    }

    private void validate(Schedule s) {
        require(s.name(), "Schedule name is required.");
        require(s.connectionId(), "A saved connection is required.");
        require(s.sql(), "SQL is required.");
        require(s.outputPattern(), "Output path pattern is required.");
        String triggerError = ScheduleTimes.validate(s);
        if (triggerError != null) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, triggerError);
        }
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, message);
        }
    }

    private void load() {
        synchronized (lock) {
            schedules.clear();
            if (file == null || !Files.exists(file)) {
                return;
            }
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length == 0) {
                    return;
                }
                CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class, Schedule.class);
                List<Schedule> loaded = mapper.readValue(bytes, type);
                for (Schedule s : loaded) {
                    schedules.put(s.id(), s);
                }
            } catch (IOException e) {
                System.err.println("sluice: could not read " + file + ": " + e.getMessage());
            }
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(schedules.values()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                "Could not persist schedules to " + file + ": " + e.getMessage(), e);
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String blankToDefault(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }
}
