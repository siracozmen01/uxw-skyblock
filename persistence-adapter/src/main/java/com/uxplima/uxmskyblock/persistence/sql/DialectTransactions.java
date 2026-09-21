package com.uxplima.uxmskyblock.persistence.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Begins, commits and rolls back a transaction the way the dialect in use needs.
 *
 * <p>SQLite takes its write lock when a transaction says {@code BEGIN IMMEDIATE}, not at the first
 * write. Taking it up front is what turns a lost race into a clean refusal. A server database takes
 * its locks per row and only needs autocommit turned off.
 *
 * <p>Four adapters each carried their own copy of these three methods, character for character.
 * The next dialect, or the next fix, would have had to find all four.
 */
public final class DialectTransactions {

    private final Dialect dialect;

    public DialectTransactions(Dialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
    }

    /** Opens a transaction, taking the write lock up front on SQLite. */
    public void begin(Connection connection) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            execute(connection, "BEGIN IMMEDIATE");
        } else {
            connection.setAutoCommit(false);
        }
    }

    public void commit(Connection connection) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            execute(connection, "COMMIT");
        } else {
            connection.commit();
        }
    }

    /**
     * Undoes the transaction, and stays quiet when it cannot.
     *
     * <p>A rollback runs on the failure path, where something has already gone wrong. Throwing here
     * would replace the original failure with this one and hide what actually happened.
     */
    public void rollbackQuietly(Connection connection) {
        try {
            if (dialect == Dialect.SQLITE) {
                execute(connection, "ROLLBACK");
            } else {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // The caller is already handling a failure. This one must not replace it.
        }
    }

    /** Puts autocommit back the way it was found, so a pooled connection is returned unchanged. */
    public void resetAutoCommitQuietly(Connection connection, boolean autoCommit) {
        try {
            if (dialect != Dialect.SQLITE) {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ignored) {
            // A connection that cannot be reset is a connection the pool will discard anyway.
        }
    }

    private static void execute(Connection connection, String statement) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(statement);
        }
    }
}
