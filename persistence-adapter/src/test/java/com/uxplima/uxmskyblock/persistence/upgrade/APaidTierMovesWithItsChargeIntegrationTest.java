package com.uxplima.uxmskyblock.persistence.upgrade;

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
 * {@link APaidTierMovesWithItsChargeTest} on the network engines.
 *
 * <p>PostgreSQL is the one that matters most: a statement that fails there ends the transaction it
 * is in, so a first tier that collides with another purchase's must be ignored rather than caught,
 * or the lost race would take the next purchase down with it.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class APaidTierMovesWithItsChargeIntegrationTest {

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
        if (mariaDatabase != null && !mariaDatabase.isClosed()) {
            mariaDatabase.close();
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresDatabase != null && !postgresDatabase.isClosed()) {
            postgresDatabase.close();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: a purchase takes the cost and moves the tier together")
    void mariaCharges() throws Exception {
        APaidTierMovesWithItsChargeTest.aPurchaseChargesAndMovesTogether(mariaDatabase);
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: a purchase that lost the race charges nothing")
    void mariaRace() throws Exception {
        APaidTierMovesWithItsChargeTest.aRaceChargesNothing(mariaDatabase);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a purchase takes the cost and moves the tier together")
    void postgresCharges() throws Exception {
        APaidTierMovesWithItsChargeTest.aPurchaseChargesAndMovesTogether(postgresDatabase);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a purchase that lost the race charges nothing and ends nothing")
    void postgresRace() throws Exception {
        APaidTierMovesWithItsChargeTest.aRaceChargesNothing(postgresDatabase);
    }
}
