package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JdbcConnectionFactory {

    private static final Logger LOG = Logger.getLogger(JdbcConnectionFactory.class.getName());

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
            applyReadOnly(connection);
            try {
                // PostgreSQL only streams with fetch size when autocommit is off.
                connection.setAutoCommit(false);
            } catch (SQLException ignored) {
                // Some JDBC drivers do not support disabling autocommit.
            }
            return connection;
        } catch (SQLException e) {
            throw new ExportException(ExitCodes.DATABASE_ERROR,
                "Failed to connect to database: " + e.getMessage(), e);
        }
    }

    /**
     * Requests a read-only connection and warns when the driver refuses or silently ignores it.
     * {@link SqlSafetyValidator} remains the primary guard, so a driver without read-only support
     * does not fail the export -- but the lost backstop must not be silent.
     */
    private static void applyReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
            if (!connection.isReadOnly()) {
                LOG.warning("JDBC driver ignored the read-only request; the connection is still writable. "
                    + "Read-only enforcement relies on SQL validation alone.");
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "JDBC driver rejected the read-only request; the connection may be writable. "
                + "Read-only enforcement relies on SQL validation alone.", e);
        }
    }
}
