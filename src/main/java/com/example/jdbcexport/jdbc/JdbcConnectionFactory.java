package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class JdbcConnectionFactory {

    private JdbcConnectionFactory() {
    }

    public static Connection connect(String url, String user, String password) {
        Properties props = new Properties();
        if (user != null && !user.isBlank()) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        try {
            Connection connection = DriverManager.getConnection(url, props);
            try {
                connection.setReadOnly(true);
            } catch (SQLException ignored) {
                // Some JDBC drivers ignore or reject read-only mode.
            }
            return connection;
        } catch (SQLException e) {
            throw new ExportException(ExitCodes.DATABASE_ERROR,
                "Failed to connect to database: " + e.getMessage(), e);
        }
    }
}
