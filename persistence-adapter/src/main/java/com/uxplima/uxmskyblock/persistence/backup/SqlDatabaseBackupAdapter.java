package com.uxplima.uxmskyblock.persistence.backup;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.backup.DatabaseBackupPort;
import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;

/**
 * SQL persistence adapter providing dialect-aware catastrophic whole-database
 * disaster recovery backups (Section 2.29).
 */
public final class SqlDatabaseBackupAdapter implements DatabaseBackupPort {

    /**
     * The second format, which keeps values as data. The first wrote SQL text that could not carry a
     * binary column; a backup in it is refused rather than restored into empty inventories.
     */
    private static final String BACKUP_HEADER_PREFIX = "-- SKYBLOCK_DISASTER_BACKUP_V2:";

    private final Database database;

    public SqlDatabaseBackupAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public byte[] captureDatabaseBackup(DatabaseBackupDialect dialect) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        checkDialectMatch(dialect);

        StringBuilder dump = new StringBuilder();
        dump.append(BACKUP_HEADER_PREFIX).append(dialect.name()).append("\n");

        try (Connection conn = database.connection()) {
            conn.setAutoCommit(false);
            try {
                // One consistent read of every table.
                conn.setTransactionIsolation(
                        database.dialect() == Dialect.SQLITE
                                ? Connection.TRANSACTION_SERIALIZABLE
                                : Connection.TRANSACTION_REPEATABLE_READ);
                DatabaseDump.write(conn, database.dialect(), dump);

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new BackupCatalogPersistenceException("Failed to capture whole-database backup", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new BackupCatalogPersistenceException("Database error during backup capture", e);
        }

        return dump.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void restoreDatabaseBackup(
            byte[] backupArtifact, DatabaseBackupDialect dialect, boolean disasterRecoveryConfirmed) {
        if (!disasterRecoveryConfirmed) {
            throw new IllegalArgumentException("Disaster recovery requires explicit administrator confirmation.");
        }
        Objects.requireNonNull(backupArtifact, "backupArtifact must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");
        checkDialectMatch(dialect);

        String sqlDump = new String(backupArtifact, StandardCharsets.UTF_8);
        if (!sqlDump.startsWith(BACKUP_HEADER_PREFIX)) {
            throw new IllegalArgumentException("Invalid backup artifact header format");
        }

        int headerEnd = sqlDump.indexOf('\n');
        String header = sqlDump.substring(0, headerEnd).trim();
        String embeddedDialect = header.substring(BACKUP_HEADER_PREFIX.length()).trim();
        if (!embeddedDialect.equalsIgnoreCase(dialect.name())) {
            throw new IllegalArgumentException(
                    "Backup artifact dialect mismatch: expected " + dialect.name() + " but found " + embeddedDialect);
        }

        try (Connection conn = database.connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                if (database.dialect() == Dialect.SQLITE) {
                    stmt.execute("PRAGMA foreign_keys = OFF;");
                } else if (database.dialect() == Dialect.MYSQL) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 0;");
                }

                // Every row goes, children first, and the backup's rows come back, parents first.
                DatabaseDump.restore(conn, database.dialect(), sqlDump.substring(headerEnd + 1));

                if (database.dialect() == Dialect.SQLITE) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                } else if (database.dialect() == Dialect.MYSQL) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 1;");
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new BackupCatalogPersistenceException("Failed to restore disaster database backup", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new BackupCatalogPersistenceException("Database error during backup restoration", e);
        }
    }

    /**
     * Which dialect the live database speaks.
     *
     * <p>A dialect this backup has no dump rules for is refused rather than guessed at. An export
     * written for the wrong dialect is worse than no export, because it is only wrong on the day it
     * is restored.
     */
    @Override
    public DatabaseBackupDialect liveDialect() {
        return switch (database.dialect()) {
            case SQLITE -> DatabaseBackupDialect.SQLITE;
            case MYSQL -> DatabaseBackupDialect.MARIADB;
            case POSTGRES -> DatabaseBackupDialect.POSTGRESQL;
            case H2, GENERIC ->
                throw new IllegalStateException(
                        "No disaster backup is written for the " + database.dialect() + " dialect");
        };
    }

    private void checkDialectMatch(DatabaseBackupDialect requestedDialect) {
        Dialect dbDialect = database.dialect();
        boolean matches =
                switch (requestedDialect) {
                    case SQLITE -> dbDialect == Dialect.SQLITE;
                    case MARIADB -> dbDialect == Dialect.MYSQL;
                    case POSTGRESQL -> dbDialect == Dialect.POSTGRES;
                };
        if (!matches) {
            throw new IllegalArgumentException(
                    "Requested backup dialect " + requestedDialect + " does not match database dialect " + dbDialect);
        }
    }
}
