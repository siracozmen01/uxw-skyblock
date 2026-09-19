package com.uxplima.uxmskyblock.persistence.backup;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private static final String BACKUP_HEADER_PREFIX = "-- SKYBLOCK_DISASTER_BACKUP_V1:";

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
                // Dialect-appropriate consistency mechanism
                setConsistencyIsolation(conn, dialect);

                DatabaseMetaData meta = conn.getMetaData();
                List<String> tableNames = new ArrayList<>();
                try (ResultSet tablesRs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                    while (tablesRs.next()) {
                        String name = tablesRs.getString("TABLE_NAME");
                        if (!name.equalsIgnoreCase("sqlite_sequence")) {
                            tableNames.add(name);
                        }
                    }
                }

                for (String table : tableNames) {
                    dumpTableData(conn, table, dump);
                }

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

                // In whole-database disaster recovery, clear existing table rows before restoring snapshot
                DatabaseMetaData meta = conn.getMetaData();
                List<String> tablesToClear = new ArrayList<>();
                try (ResultSet tablesRs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                    while (tablesRs.next()) {
                        String name = tablesRs.getString("TABLE_NAME");
                        if (!name.equalsIgnoreCase("sqlite_sequence")
                                && !name.toLowerCase(Locale.ROOT).startsWith("sqlite_")) {
                            tablesToClear.add(name);
                        }
                    }
                }
                for (String tbl : tablesToClear) {
                    stmt.executeUpdate("DELETE FROM " + tbl);
                }

                int start = headerEnd + 1;
                int len = sqlDump.length();
                while (start < len) {
                    int next = sqlDump.indexOf(";\n", start);
                    if (next == -1) {
                        next = sqlDump.indexOf(";\r\n", start);
                    }
                    String rawSql;
                    if (next == -1) {
                        rawSql = sqlDump.substring(start).trim();
                        start = len;
                    } else {
                        rawSql = sqlDump.substring(start, next).trim();
                        start = next + (sqlDump.charAt(next + 1) == '\r' ? 3 : 2);
                    }
                    if (!rawSql.isEmpty() && !rawSql.startsWith("--")) {
                        stmt.execute(rawSql);
                    }
                }

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

    private static void setConsistencyIsolation(Connection conn, DatabaseBackupDialect dialect) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            switch (dialect) {
                case SQLITE -> stmt.execute("PRAGMA read_uncommitted = 0;");
                case MARIADB -> stmt.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;");
                case POSTGRESQL -> stmt.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;");
            }
        }
    }

    private static void dumpTableData(Connection conn, String tableName, StringBuilder dump) throws SQLException {
        String query = "SELECT * FROM " + tableName;
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(query)) {
            ResultSetMetaData rsMeta = rs.getMetaData();
            int colCount = rsMeta.getColumnCount();

            while (rs.next()) {
                dump.append("INSERT INTO ").append(tableName).append(" (");
                for (int i = 1; i <= colCount; i++) {
                    dump.append(rsMeta.getColumnName(i));
                    if (i < colCount) {
                        dump.append(", ");
                    }
                }
                dump.append(") VALUES (");
                for (int i = 1; i <= colCount; i++) {
                    Object val = rs.getObject(i);
                    if (val == null) {
                        dump.append("NULL");
                    } else if (val instanceof Number) {
                        dump.append(val);
                    } else if (val instanceof Boolean b) {
                        dump.append(b ? "1" : "0");
                    } else {
                        String str = val.toString().replace("'", "''");
                        dump.append("'").append(str).append("'");
                    }
                    if (i < colCount) {
                        dump.append(", ");
                    }
                }
                dump.append(");\n");
            }
        }
    }
}
