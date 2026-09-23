package com.uxplima.uxmskyblock.persistence.island;

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

/** {@link AuthorityRenewVsTakeoverRaceTest}'s scenarios on the engines a cluster runs, with real row locks. */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class AuthorityRenewVsTakeoverRaceIntegrationTest {

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
    @DisplayName("MariaDB: renewals racing takeovers of a live lease all land, and no takeover does")
    void mariaRenewalsWin() throws Exception {
        AuthorityRenewVsTakeoverRaceTest.renewalsBeatTakeoversOfALiveLease(
                mariaDatabase, new PlayerIslandStorageAdapter(mariaDatabase));
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: renewals racing takeovers of a live lease all land, and no takeover does")
    void postgresRenewalsWin() throws Exception {
        AuthorityRenewVsTakeoverRaceTest.renewalsBeatTakeoversOfALiveLease(
                postgresDatabase, new PlayerIslandStorageAdapter(postgresDatabase));
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: a stale epoch takes nothing, and racing takeovers leave one owner")
    void mariaOneOwner() throws Exception {
        PlayerIslandStorageAdapter adapter = new PlayerIslandStorageAdapter(mariaDatabase);
        AuthorityRenewVsTakeoverRaceTest.aTakeoverWithAStaleEpochTakesNothing(mariaDatabase, adapter);
        AuthorityRenewVsTakeoverRaceTest.racingTakeoversOfAnExpiredLeaseLeaveOneOwner(mariaDatabase, adapter);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a stale epoch takes nothing, and racing takeovers leave one owner")
    void postgresOneOwner() throws Exception {
        PlayerIslandStorageAdapter adapter = new PlayerIslandStorageAdapter(postgresDatabase);
        AuthorityRenewVsTakeoverRaceTest.aTakeoverWithAStaleEpochTakesNothing(postgresDatabase, adapter);
        AuthorityRenewVsTakeoverRaceTest.racingTakeoversOfAnExpiredLeaseLeaveOneOwner(postgresDatabase, adapter);
    }
}
