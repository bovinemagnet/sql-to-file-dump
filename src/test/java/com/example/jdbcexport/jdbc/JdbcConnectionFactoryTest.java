package com.example.jdbcexport.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConnectionFactoryTest {

    @Test
    void disablesAutoCommitForCursorStreaming() throws Exception {
        try (Connection connection = JdbcConnectionFactory.connect("jdbc:duckdb:", "ignored", null)) {
            assertThat(connection.getAutoCommit()).isFalse();
        }
    }

    @Test
    void connectionIsUsableForQueries() throws Exception {
        try (Connection connection = JdbcConnectionFactory.connect("jdbc:duckdb:", "ignored", null)) {
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("SELECT 1")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }
        }
    }
}
