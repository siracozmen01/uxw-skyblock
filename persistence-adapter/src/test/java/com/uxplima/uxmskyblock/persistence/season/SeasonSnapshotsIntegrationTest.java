package com.uxplima.uxmskyblock.persistence.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonMetric;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonSnapshotEntry;
import com.uxplima.uxmskyblock.core.domain.season.SeasonState;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMySql;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A season's placings are written and read back in order on every server database.
 *
 * <p>The placing column is called rank, which MySQL 8 reserves for its window function: the migration
 * that made the table was refused, so a MySQL server never started, and every statement naming the
 * column would have been refused after it. Only SQLite had ever run these statements.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class SeasonSnapshotsIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static MySQLContainer<?> mySqlContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    @BeforeAll
    static void startEngines() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        mySqlContainer = DatabaseTestFixture.startMySqlIfEnabled();
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
    }

    @AfterAll
    static void stopEngines() {
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (mySqlContainer != null) {
            mySqlContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: placings are written and read back by rank")
    void mariaDb() {
        assertPlacingsRoundTrip(DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfMySql
    @DisplayName("MySQL: placings are written and read back by rank")
    void mySql() {
        assertPlacingsRoundTrip(DatabaseTestFixture.connectToContainer(mySqlContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: placings are written and read back by rank")
    void postgres() {
        assertPlacingsRoundTrip(DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES));
    }

    private static void assertPlacingsRoundTrip(Database database) {
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            PlayerIslandSeasonAdapter seasons = new PlayerIslandSeasonAdapter(database);
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            SeasonId season = SeasonId.of(1);
            seasons.saveSeason(new SeasonRecord(season, "S1", now, now.plusSeconds(3600), SeasonState.ACTIVE));
            IslandId second = IslandId.of(UUID.randomUUID());
            IslandId first = IslandId.of(UUID.randomUUID());

            seasons.saveSnapshots(List.of(
                    new SeasonSnapshotEntry(
                            season, SeasonMetric.LEVEL, 2, second, PlayerUuid.of(UUID.randomUUID()), 40L, now),
                    new SeasonSnapshotEntry(
                            season, SeasonMetric.LEVEL, 1, first, PlayerUuid.of(UUID.randomUUID()), 90L, now)));

            assertThat(seasons.findSnapshots(season, SeasonMetric.LEVEL, 10))
                    .extracting(SeasonSnapshotEntry::rank, SeasonSnapshotEntry::islandId)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(1, first),
                            org.assertj.core.groups.Tuple.tuple(2, second));
        } finally {
            if (!database.isClosed()) {
                database.close();
            }
        }
    }
}
