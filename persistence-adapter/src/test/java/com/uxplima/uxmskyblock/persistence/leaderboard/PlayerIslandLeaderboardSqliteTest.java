package com.uxplima.uxmskyblock.persistence.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIslandLeaderboardSqliteTest {

    private Database database;
    private PlayerIslandLeaderboardAdapter adapter;

    private final IslandId islandA = IslandId.of(UUID.randomUUID());
    private final IslandId islandB = IslandId.of(UUID.randomUUID());
    private final IslandId islandC = IslandId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("INSERT INTO player_accounts (player_uuid) VALUES ('p-lb-1'), ('p-lb-2'), ('p-lb-3')");
            stmt.execute(
                    "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-lb-1', 'p-lb-1'), ('prof-lb-2', 'p-lb-2'), ('prof-lb-3', 'p-lb-3')");

            // Island A: Level 100, Worth 500,000, Bank 200,000
            stmt.execute(
                    "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, custom_name, level_score, net_worth_minor_units) VALUES ('"
                            + islandA.value() + "', 'prof-lb-1', 'p-lb-1', 'Alpha', 100, 500000)");
            stmt.execute("INSERT INTO island_banks (island_id, primary_balance_minor_units) VALUES ('" + islandA.value()
                    + "', 200000)");

            // Island B: Level 250, Worth 100,000, Bank 800,000
            stmt.execute(
                    "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, custom_name, level_score, net_worth_minor_units) VALUES ('"
                            + islandB.value() + "', 'prof-lb-2', 'p-lb-2', 'Beta', 250, 100000)");
            stmt.execute("INSERT INTO island_banks (island_id, primary_balance_minor_units) VALUES ('" + islandB.value()
                    + "', 800000)");

            // Island C: Inactive island (Level 999, should be excluded)
            stmt.execute(
                    "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, custom_name, lifecycle, level_score, net_worth_minor_units) VALUES ('"
                            + islandC.value() + "', 'prof-lb-3', 'p-lb-3', 'Inactive', 'DELETED', 999, 9999999)");
        }

        adapter = new PlayerIslandLeaderboardAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("fetchTopIslands LEVEL orders by level_score DESC and excludes inactive")
    void fetchTopLevel() {
        List<LeaderboardEntry> top = adapter.fetchTopIslands(LeaderboardCategory.LEVEL, 10);
        assertThat(top).hasSize(2);

        assertThat(top.get(0).rank()).isEqualTo(1);
        assertThat(top.get(0).islandId()).isEqualTo(islandB);
        assertThat(top.get(0).score()).isEqualTo(250L);
        assertThat(top.get(0).formattedScore()).isEqualTo("Level 250");

        assertThat(top.get(1).rank()).isEqualTo(2);
        assertThat(top.get(1).islandId()).isEqualTo(islandA);
        assertThat(top.get(1).score()).isEqualTo(100L);
    }

    @Test
    @DisplayName("fetchTopIslands WORTH orders by net_worth_minor_units DESC")
    void fetchTopWorth() {
        List<LeaderboardEntry> top = adapter.fetchTopIslands(LeaderboardCategory.WORTH, 10);
        assertThat(top).hasSize(2);

        assertThat(top.get(0).rank()).isEqualTo(1);
        assertThat(top.get(0).islandId()).isEqualTo(islandA);
        assertThat(top.get(0).score()).isEqualTo(500000L);

        assertThat(top.get(1).rank()).isEqualTo(2);
        assertThat(top.get(1).islandId()).isEqualTo(islandB);
        assertThat(top.get(1).score()).isEqualTo(100000L);
    }

    @Test
    @DisplayName("fetchTopIslands BANK orders by bank primary balance DESC")
    void fetchTopBank() {
        List<LeaderboardEntry> top = adapter.fetchTopIslands(LeaderboardCategory.BANK, 10);
        assertThat(top).hasSize(2);

        assertThat(top.get(0).rank()).isEqualTo(1);
        assertThat(top.get(0).islandId()).isEqualTo(islandB);
        assertThat(top.get(0).score()).isEqualTo(800000L);

        assertThat(top.get(1).rank()).isEqualTo(2);
        assertThat(top.get(1).islandId()).isEqualTo(islandA);
        assertThat(top.get(1).score()).isEqualTo(200000L);
    }

    @Test
    @DisplayName("updateIslandScore updates score and affects subsequent leaderboard ranking")
    void updateIslandScoreAffectsRanking() {
        adapter.updateIslandScore(islandA, 500L, 2000000L);

        List<LeaderboardEntry> topLevel = adapter.fetchTopIslands(LeaderboardCategory.LEVEL, 10);
        assertThat(topLevel.get(0).islandId()).isEqualTo(islandA);
        assertThat(topLevel.get(0).score()).isEqualTo(500L);

        List<LeaderboardEntry> topWorth = adapter.fetchTopIslands(LeaderboardCategory.WORTH, 10);
        assertThat(topWorth.get(0).islandId()).isEqualTo(islandA);
        assertThat(topWorth.get(0).score()).isEqualTo(2000000L);
    }
}
