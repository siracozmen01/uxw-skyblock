package com.uxplima.uxmskyblock.persistence.sql;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * Whether the engine refused a row because a unique index already held its key.
 *
 * <p>Each engine says so differently: MariaDB throws its own subclass, PostgreSQL sets a SQL state
 * in class 23, and SQLite gives error code 19 and no state at all. A write that loses a race to an
 * identical write reads it here and takes the path that race calls for.
 */
public final class UniqueViolations {

    private UniqueViolations() {
        throw new UnsupportedOperationException("UniqueViolations is a check, not a thing to hold.");
    }

    public static boolean isUniqueViolation(SQLException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
                // SQLite reports a constraint as error code 19 and no SQL state.
                if (sql.getErrorCode() == 19) {
                    return true;
                }
            }
        }
        return false;
    }
}
