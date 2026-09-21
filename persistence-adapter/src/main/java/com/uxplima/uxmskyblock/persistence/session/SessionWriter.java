package com.uxplima.uxmskyblock.persistence.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmlib.storage.sql.StatementBinder;

/**
 * Runs one conditional update and reports how many rows it touched.
 *
 * <p>The row count is the answer, not a detail: the statement carries its own guard, so one row
 * means this node holds the session and zero means another node took it.
 *
 * <p>SQLite needs {@code BEGIN IMMEDIATE} to take its write lock up front, where a concurrent
 * writer is refused cleanly instead of losing a race at the first write. A server database needs
 * only a transaction.
 */
final class SessionWriter {

    private final Database database;
    private final Dialect dialect;

    SessionWriter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
    }

    int executeUpdate(String sql, StatementBinder binder) {
        if (dialect == Dialect.SQLITE) {
            return executeSqliteWriter(sql, binder);
        }
        return executeServerTransaction(sql, binder);
    }

    private int executeSqliteWriter(String sql, StatementBinder binder) {
        try (Connection conn = database.connection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("BEGIN IMMEDIATE");
            }
            try {
                int affected;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    binder.bind(ps);
                    affected = ps.executeUpdate();
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("COMMIT");
                }
                return affected;
            } catch (Exception e) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ROLLBACK");
                } catch (Exception rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new SessionPersistenceException("Failed to execute SQLite immediate write: " + sql, e);
        }
    }

    private int executeServerTransaction(String sql, StatementBinder binder) {
        try {
            Integer affected = database.transaction(tx -> tx.update(sql, binder));
            return affected != null ? affected : 0;
        } catch (Exception e) {
            throw new SessionPersistenceException("Failed to execute server transaction update: " + sql, e);
        }
    }
}
