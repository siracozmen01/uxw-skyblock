package com.uxplima.uxmskyblock.persistence.tradewinds;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * One transaction over a vessel's rows, committed when its work says it did what it was asked and rolled
 * back otherwise. SQLite takes its write lock as the transaction begins, so two of them never both read
 * before either writes.
 */
final class VesselTransaction {

    @FunctionalInterface
    interface Work {
        boolean run(Connection conn) throws SQLException;
    }

    private final Database database;

    VesselTransaction(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    boolean run(String what, Work work) {
        boolean sqlite = database.dialect() == Dialect.SQLITE;
        try (Connection conn = database.connection()) {
            if (sqlite) {
                execute(conn, "BEGIN IMMEDIATE");
            } else {
                conn.setAutoCommit(false);
            }
            try {
                boolean done = work.run(conn);
                if (sqlite) {
                    execute(conn, done ? "COMMIT" : "ROLLBACK");
                } else if (done) {
                    conn.commit();
                } else {
                    conn.rollback();
                }
                return done;
            } catch (SQLException | RuntimeException e) {
                try {
                    if (sqlite) {
                        execute(conn, "ROLLBACK");
                    } else {
                        conn.rollback();
                    }
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not " + what, e);
        }
    }

    private static void execute(Connection conn, String statement) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(statement);
        }
    }
}
