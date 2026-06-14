package com.example.jdbcexport.daemon;

import com.example.jdbcexport.error.ExportException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionStoreTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private ConnectionStore store(Path dir) {
        return new ConnectionStore(dir.resolve("connections.json"), mapper);
    }

    @Test
    void createsAndListsConnections(@TempDir Path dir) {
        ConnectionStore store = store(dir);

        SavedConnection conn = store.create("analytics-pg", "postgres",
            "jdbc:postgresql://h:5432/db", "app_ro", "DB_PASSWORD");

        assertThat(conn.id()).isNotBlank();
        assertThat(conn.driver()).isEqualTo("postgres");
        assertThat(conn.passwordEnv()).isEqualTo("DB_PASSWORD");
        assertThat(store.list()).extracting(SavedConnection::name).containsExactly("analytics-pg");
    }

    @Test
    void persistsAcrossInstancesWithoutStoringPassword(@TempDir Path dir) throws Exception {
        ConnectionStore store = store(dir);
        SavedConnection conn = store.create("warehouse", "", "jdbc:postgresql://wh:5432/w", "etl", "WH_PW");

        // The on-disk file holds the env-var name but never an actual password value.
        String json = Files.readString(dir.resolve("connections.json"));
        assertThat(json).contains("WH_PW").doesNotContain("password\"");

        // A fresh store reading the same file sees the connection.
        ConnectionStore reloaded = store(dir);
        assertThat(reloaded.get(conn.id())).isPresent();
        assertThat(reloaded.get(conn.id()).get().user()).isEqualTo("etl");
    }

    @Test
    void derivesDriverFromUrlWhenBlank(@TempDir Path dir) {
        SavedConnection conn = store(dir).create("duck", "  ", "jdbc:duckdb:/data/x.duckdb", "u", null);
        assertThat(conn.driver()).isEqualTo("duckdb");
        assertThat(conn.passwordEnv()).isNull();
    }

    @Test
    void updatesAndDeletes(@TempDir Path dir) {
        ConnectionStore store = store(dir);
        SavedConnection conn = store.create("c1", "postgres", "jdbc:postgresql://h/db", "u1", null);

        SavedConnection updated = store.update(conn.id(), "c1-renamed", "postgres", "jdbc:postgresql://h/db", "u2", "PW");
        assertThat(updated.name()).isEqualTo("c1-renamed");
        assertThat(updated.user()).isEqualTo("u2");
        assertThat(updated.createdAt()).isEqualTo(conn.createdAt());

        assertThat(store.delete(conn.id())).isTrue();
        assertThat(store.get(conn.id())).isEmpty();
        assertThat(store.delete(conn.id())).isFalse();
    }

    @Test
    void markUsedSetsLastUsedTimestamp(@TempDir Path dir) {
        ConnectionStore store = store(dir);
        SavedConnection conn = store.create("c", "postgres", "jdbc:postgresql://h/db", "u", null);
        assertThat(conn.lastUsedAt()).isNull();

        store.markUsed(conn.id());

        assertThat(store.get(conn.id()).orElseThrow().lastUsedAt()).isNotNull();
    }

    @Test
    void rejectsMissingRequiredFields(@TempDir Path dir) {
        ConnectionStore store = store(dir);
        assertThatThrownBy(() -> store.create("", "postgres", "jdbc:postgresql://h/db", "u", null))
            .isInstanceOf(ExportException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> store.create("n", "postgres", "", "u", null))
            .isInstanceOf(ExportException.class).hasMessageContaining("URL");
        assertThatThrownBy(() -> store.create("n", "postgres", "jdbc:postgresql://h/db", "", null))
            .isInstanceOf(ExportException.class).hasMessageContaining("user");
    }
}
