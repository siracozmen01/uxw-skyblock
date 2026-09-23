package com.uxplima.uxmskyblock.persistence.social;

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

/** {@link SocialRatingIdempotencyTest} on the engines where two ratings really do race. */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class SocialRatingIdempotencyIntegrationTest {

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
    @DisplayName("MariaDB: a changed rating replaces the old, and racing first ratings leave one")
    void mariaKeepsOneRating() throws Exception {
        for (int round = 0; round < 20; round++) {
            SocialRatingIdempotencyTest.racingFirstRatingsLeaveOne(mariaDatabase);
        }
        SocialRatingIdempotencyTest.aChangedRatingReplacesTheOld(mariaDatabase);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a changed rating replaces the old, and racing first ratings leave one")
    void postgresKeepsOneRating() throws Exception {
        for (int round = 0; round < 20; round++) {
            SocialRatingIdempotencyTest.racingFirstRatingsLeaveOne(postgresDatabase);
        }
        SocialRatingIdempotencyTest.aChangedRatingReplacesTheOld(postgresDatabase);
    }
}
