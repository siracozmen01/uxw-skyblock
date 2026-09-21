package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;

/**
 * Runs one phase of the journal inside a transaction, and decides its fate by the outcome.
 *
 * <p>An outcome that is not a success is a refusal the journal reached deliberately, not a failure:
 * a stale epoch, an expired lease, a version that moved. Those roll back, because whatever the
 * phase wrote before it noticed must not survive. Only a success commits.
 *
 * <p>SQLite needs {@code BEGIN IMMEDIATE} to take its write lock at the start rather than at the
 * first write, where it would be too late to avoid a busy error against a concurrent writer. A
 * server database takes its locks per row and only needs autocommit turned off.
 */
final class JournalTransaction {

    @FunctionalInterface
    interface Action {
        InventoryMutationJournalOutcome execute(Connection conn) throws SQLException;
    }

    private final Database database;
    private final Dialect dialect;

    JournalTransaction(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
    }

    InventoryMutationJournalOutcome run(String opDescription, Action action) {
        return dialect == Dialect.SQLITE ? runOnSqlite(opDescription, action) : runOnServer(opDescription, action);
    }

    private InventoryMutationJournalOutcome runOnSqlite(String opDescription, Action action) {
        try (Connection conn = database.connection()) {
            execute(conn, "BEGIN IMMEDIATE");
            try {
                InventoryMutationJournalOutcome outcome = action.execute(conn);
                execute(conn, outcome.isSuccess() ? "COMMIT" : "ROLLBACK");
                return outcome;
            } catch (Exception e) {
                try {
                    execute(conn, "ROLLBACK");
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed SQLite " + opDescription, e);
        }
    }

    private InventoryMutationJournalOutcome runOnServer(String opDescription, Action action) {
        try (Connection conn = database.connection()) {
            conn.setAutoCommit(false);
            try {
                InventoryMutationJournalOutcome outcome = action.execute(conn);
                if (outcome.isSuccess()) {
                    conn.commit();
                } else {
                    conn.rollback();
                }
                return outcome;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed server DB " + opDescription, e);
        }
    }

    private static void execute(Connection conn, String statement) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(statement);
        }
    }
}
