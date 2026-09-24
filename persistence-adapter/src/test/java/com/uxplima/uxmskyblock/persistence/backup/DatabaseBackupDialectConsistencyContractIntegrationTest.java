package com.uxplima.uxmskyblock.persistence.backup;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** {@link DatabaseBackupDialectConsistencyContractTest} on MariaDB and PostgreSQL. */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class DatabaseBackupDialectConsistencyContractIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;
    private static Database mariaDatabase;
    private static Database postgresDatabase;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        }
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
        }
    }

    @AfterAll
    static void tearDownAll() {
        for (Database database : new Database[] {mariaDatabase, postgresDatabase}) {
            if (database != null && !database.isClosed()) {
                database.close();
            }
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: every row comes back as it was, and a backup fails closed")
    void mariaDb() throws Exception {
        new DatabaseBackupRoundTrip(mariaDatabase, DatabaseBackupDialect.MARIADB).everyRowComesBack();
        new DatabaseBackupRoundTrip(mariaDatabase, DatabaseBackupDialect.MARIADB).itFailsClosed();
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: every row comes back as it was, and a backup fails closed")
    void postgres() throws Exception {
        new DatabaseBackupRoundTrip(postgresDatabase, DatabaseBackupDialect.POSTGRESQL).everyRowComesBack();
        new DatabaseBackupRoundTrip(postgresDatabase, DatabaseBackupDialect.POSTGRESQL).itFailsClosed();
    }
}
