package com.uxplima.uxmskyblock.persistence.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class PlayerIslandLeaderboardIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerIslandLeaderboardAdapter mariaAdapter;
    private static PlayerIslandLeaderboardAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new PlayerIslandLeaderboardAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new PlayerIslandLeaderboardAdapter(postgresDatabase);
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
    @Order(1)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: leaderboard queries and score update")
    void mariaDbLeaderboard() throws Exception {
        testLeaderboard(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: leaderboard queries and score update")
    void postgresLeaderboard() throws Exception {
        testLeaderboard(postgresDatabase, postgresAdapter);
    }

    private void testLeaderboard(Database db, PlayerIslandLeaderboardAdapter adapter) throws Exception {
        IslandId island1 = IslandId.of(UUID.randomUUID());
        IslandId island2 = IslandId.of(UUID.randomUUID());
        String player1 = UUID.randomUUID().toString();
        String player2 = UUID.randomUUID().toString();
        String profile1 = UUID.randomUUID().toString();
        String profile2 = UUID.randomUUID().toString();

        try (Connection conn = db.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?), (?)")) {
                stmt.setString(1, player1);
                stmt.setString(2, player2);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?), (?, ?)")) {
                stmt.setString(1, profile1);
                stmt.setString(2, player1);
                stmt.setString(3, profile2);
                stmt.setString(4, player2);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO islands (id, owner_profile_id, owner_account_uuid, custom_name, level_score, net_worth_minor_units, created_at)
                    VALUES (?, ?, ?, 'Island Alpha', 100, 500000, ?),
                           (?, ?, ?, 'Island Beta', 300, 100000, ?)
                    """)) {
                stmt.setString(1, island1.value().toString());
                stmt.setString(2, profile1);
                stmt.setString(3, player1);
                stmt.setTimestamp(4, Timestamp.from(Instant.now()));

                stmt.setString(5, island2.value().toString());
                stmt.setString(6, profile2);
                stmt.setString(7, player2);
                stmt.setTimestamp(8, Timestamp.from(Instant.now()));
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO island_banks (island_id, primary_balance_minor_units)
                    VALUES (?, 200000), (?, 900000)
                    """)) {
                stmt.setString(1, island1.value().toString());
                stmt.setString(2, island2.value().toString());
                stmt.executeUpdate();
            }
        }

        // Test LEVEL leaderboard: island2 (300) > island1 (100)
        List<LeaderboardEntry> levelTop = adapter.fetchTopIslands(LeaderboardCategory.LEVEL, 5);
        assertThat(levelTop).isNotEmpty();
        assertThat(levelTop.get(0).islandId()).isEqualTo(island2);
        assertThat(levelTop.get(0).score()).isEqualTo(300L);

        // Test WORTH leaderboard: island1 (500,000) > island2 (100,000)
        List<LeaderboardEntry> worthTop = adapter.fetchTopIslands(LeaderboardCategory.WORTH, 5);
        assertThat(worthTop.get(0).islandId()).isEqualTo(island1);
        assertThat(worthTop.get(0).score()).isEqualTo(500000L);

        // Test BANK leaderboard: island2 (900,000) > island1 (200,000)
        List<LeaderboardEntry> bankTop = adapter.fetchTopIslands(LeaderboardCategory.BANK, 5);
        assertThat(bankTop.get(0).islandId()).isEqualTo(island2);
        assertThat(bankTop.get(0).score()).isEqualTo(900000L);

        // Update score of island1 to 999
        adapter.updateIslandScore(island1, 999L, 10_000_000L);
        List<LeaderboardEntry> updatedLevelTop = adapter.fetchTopIslands(LeaderboardCategory.LEVEL, 5);
        assertThat(updatedLevelTop.get(0).islandId()).isEqualTo(island1);
        assertThat(updatedLevelTop.get(0).score()).isEqualTo(999L);
    }
}
