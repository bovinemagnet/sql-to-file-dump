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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator-managed registry of {@link SavedConnection}s, persisted as a JSON array on disk
 * (default {@code ~/.sluice/connections.json}, overridable via {@code sluice.connections.file}).
 * Passwords are never stored — only {@code passwordEnv} references. Reachability status from
 * the "test" action is kept in memory only, not persisted.
 */
@ApplicationScoped
public class ConnectionStore {

    /** In-memory, non-persisted reachability result from the last test of a connection. */
    public record Status(boolean ok, String state, String message, Instant at) {
    }

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "sluice.connections.file")
    Optional<String> configuredPath;

    private Path file;
    /** Set when a corrupt file could not be quarantined; save() then refuses to clobber it. */
    private volatile boolean saveBlocked;
    private final Map<String, SavedConnection> connections = new LinkedHashMap<>();
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public ConnectionStore() {
        // CDI no-arg constructor; field injection + init() finish setup.
    }

    /** Test constructor: explicit file + mapper, no CDI. */
    ConnectionStore(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
        load();
    }

    @PostConstruct
    void init() {
        file = configuredPath.filter(p -> !p.isBlank()).map(Path::of)
            .orElseGet(() -> Path.of(System.getProperty("user.home"), ".sluice", "connections.json"));
        load();
    }

    public List<SavedConnection> list() {
        synchronized (lock) {
            return new ArrayList<>(connections.values());
        }
    }

    public Optional<SavedConnection> get(String id) {
        synchronized (lock) {
            return Optional.ofNullable(connections.get(id));
        }
    }

    public SavedConnection create(String name, String driver, String url, String user, String passwordEnv) {
        validate(name, url, user);
        SavedConnection conn = new SavedConnection(
            UUID.randomUUID().toString(), name.trim(), resolveDriver(driver, url),
            url.trim(), user.trim(), blankToNull(passwordEnv), Instant.now(), null);
        synchronized (lock) {
            connections.put(conn.id(), conn);
            save();
        }
        return conn;
    }

    public SavedConnection update(String id, String name, String driver, String url, String user, String passwordEnv) {
        validate(name, url, user);
        synchronized (lock) {
            SavedConnection existing = connections.get(id);
            if (existing == null) {
                throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "Connection not found: " + id);
            }
            SavedConnection updated = new SavedConnection(
                id, name.trim(), resolveDriver(driver, url), url.trim(), user.trim(),
                blankToNull(passwordEnv), existing.createdAt(), existing.lastUsedAt());
            connections.put(id, updated);
            save();
            return updated;
        }
    }

    public boolean delete(String id) {
        synchronized (lock) {
            boolean removed = connections.remove(id) != null;
            if (removed) {
                statuses.remove(id);
                save();
            }
            return removed;
        }
    }

    public void markUsed(String id) {
        synchronized (lock) {
            SavedConnection existing = connections.get(id);
            if (existing != null) {
                connections.put(id, new SavedConnection(id, existing.name(), existing.driver(),
                    existing.url(), existing.user(), existing.passwordEnv(), existing.createdAt(), Instant.now()));
                save();
            }
        }
    }

    public void recordStatus(String id, boolean ok, String message) {
        statuses.put(id, new Status(ok, ok ? "reachable" : "failed", message, Instant.now()));
    }

    public Optional<Status> statusOf(String id) {
        return Optional.ofNullable(statuses.get(id));
    }

    /* ───────── internals ───────── */

    private void validate(String name, String url, String user) {
        if (isBlank(name)) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "Connection name is required.");
        }
        if (isBlank(url)) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "JDBC URL is required.");
        }
        if (isBlank(user)) {
            throw new ExportException(ExitCodes.INVALID_ARGUMENTS, "Database user is required.");
        }
    }

    private static String resolveDriver(String driver, String url) {
        return isBlank(driver) ? ExportJob.driverOf(url) : driver.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void load() {
        synchronized (lock) {
            connections.clear();
            if (file == null || !Files.exists(file)) {
                return;
            }
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length == 0) {
                    return;
                }
                CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class, SavedConnection.class);
                List<SavedConnection> loaded = mapper.readValue(bytes, type);
                for (SavedConnection c : loaded) {
                    connections.put(c.id(), c);
                }
            } catch (IOException e) {
                // A corrupt or unreadable file must not stop the daemon, but it must not be
                // silently overwritten by the next save() either: quarantine it so the
                // operator's data survives, and start empty.
                quarantine(e);
            }
        }
    }

    private void quarantine(IOException cause) {
        Path bad = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
        try {
            Files.move(file, bad);
            System.err.println("sluice: could not read " + file + " (" + cause.getMessage()
                + "); the file has been quarantined as " + bad
                + " and the store starts empty. Repair and restore it manually.");
        } catch (IOException moveFailure) {
            saveBlocked = true;
            System.err.println("sluice: could not read " + file + " (" + cause.getMessage()
                + ") and could not quarantine it (" + moveFailure.getMessage()
                + "); refusing to overwrite it until the file is repaired or removed.");
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        if (saveBlocked) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                "Refusing to overwrite " + file + ": it could not be read at startup and could not be "
                    + "quarantined. Repair or remove the file, then restart the daemon.");
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(connections.values()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ExportException(ExitCodes.OUTPUT_WRITE_ERROR,
                "Could not persist connections to " + file + ": " + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
