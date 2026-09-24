package com.uxplima.uxmskyblock.persistence.backup;

import java.nio.file.Path;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A whole-database backup keeps every value as it was, on every dialect, and fails closed.
 *
 * <p>The testing standard names this test. The backup wrote each value with {@code toString()}: a
 * binary column came back as the text of a Java array reference, so a disaster restore put an empty
 * inventory, ender chest and vault page in place of every player's; a boolean went in as a number
 * PostgreSQL refuses, a backslash was read as an escape by MariaDB, and a name ending a line in a
 * semicolon split the statement. This runs on SQLite; {@link DatabaseBackupDialectConsistencyContractIntegrationTest}
 * runs the same on MariaDB and PostgreSQL.
 */
class DatabaseBackupDialectConsistencyContractTest {

    @TempDir
    Path dir;

    private Database database;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("backup.db"));
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
    }

    @AfterEach
    void tearDown() {
        if (!database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("SQLite: every row comes back as it was, binary, boolean, timestamp and awkward text included")
    void everyRowComesBack() throws Exception {
        new DatabaseBackupRoundTrip(database, DatabaseBackupDialect.SQLITE).everyRowComesBack();
    }

    @Test
    @DisplayName("SQLite: a backup is refused for another dialect, unconfirmed, or when it is not a backup")
    void itFailsClosed() {
        new DatabaseBackupRoundTrip(database, DatabaseBackupDialect.SQLITE).itFailsClosed();
    }
}
