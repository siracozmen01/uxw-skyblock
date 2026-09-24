package com.uxplima.uxmskyblock.persistence.vault;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
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

/**
 * {@link AVaultCloseWritesThePlayerWithThePageTest} on MariaDB and PostgreSQL, where the session row is
 * locked for update and the columns are typed as the server types them.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class AVaultCloseWritesThePlayerWithThePageIntegrationTest {

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
    @DisplayName("MariaDB: a vault close writes the page and the player together, or neither")
    void mariaDb() throws Exception {
        new VaultCloseScene(mariaDatabase).bothAreWritten();
        new VaultCloseScene(mariaDatabase).aStaleSessionWritesNeither();
        new VaultCloseScene(mariaDatabase).aMovedVersionWritesNeither();
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a vault close writes the page and the player together, or neither")
    void postgres() throws Exception {
        new VaultCloseScene(postgresDatabase).bothAreWritten();
        new VaultCloseScene(postgresDatabase).aStaleSessionWritesNeither();
        new VaultCloseScene(postgresDatabase).aMovedVersionWritesNeither();
    }
}
