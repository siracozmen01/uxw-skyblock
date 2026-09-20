package com.uxplima.uxmskyblock.persistence.island;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Shared SQL utilities and transaction lifecycle helpers for island persistence adapters.
 */
final class IslandSqlSupport {

    private IslandSqlSupport() {}

    static void validateDialect(Dialect dialect) {
        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException("Unsupported SQL dialect: " + dialect
                        + ". Skyblock island persistence supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
    }

    static String dbNowPlus(Dialect dialect, int seconds) {
        return switch (dialect) {
            case SQLITE -> "DATETIME('now', '+" + seconds + " seconds')";
            case MYSQL -> "CURRENT_TIMESTAMP + INTERVAL " + seconds + " SECOND";
            case POSTGRES -> "CURRENT_TIMESTAMP + INTERVAL '" + seconds + " seconds'";
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        };
    }

    static void beginTransaction(Connection connection, Dialect dialect) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
            }
        } else {
            connection.setAutoCommit(false);
        }
    }

    static void commitTransaction(Connection connection, Dialect dialect) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("COMMIT");
            }
        } else {
            connection.commit();
        }
    }

    static void rollbackTransaction(Connection connection, Dialect dialect) {
        try {
            if (dialect == Dialect.SQLITE) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                }
            } else {
                connection.rollback();
            }
        } catch (SQLException expected) {
            // best-effort cleanup
        }
    }

    static void resetAutoCommitQuietly(Connection connection, Dialect dialect, boolean autoCommit) {
        if (dialect != Dialect.SQLITE) {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException expected) {
                // best-effort cleanup
            }
        }
    }
}
