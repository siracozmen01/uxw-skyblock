package com.uxplima.uxmskyblock.persistence.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The board a node holds is a projection of the islands' scores, never their source.
 *
 * <p>The testing standard names this test for a Redis board; this node holds its board in memory for a
 * window the operator sets, and the rule is the same. A board that has gone stale, or has been thrown
 * away, says nothing about an island's score: the score lives on the island's row, and a board built
 * again from the rows is the board the rows describe. Reading and rebuilding a board never writes a
 * score.
 */
class LeaderboardProjectionIsNonCanonicalTest {

    private final IslandId low = IslandId.of(UUID.randomUUID());
    private final IslandId high = IslandId.of(UUID.randomUUID());
    private Database database;
    private PlayerIslandLeaderboardAdapter rows;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO player_accounts (player_uuid) VALUES ('p-a'), ('p-b')");
            stmt.execute(
                    "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-a', 'p-a'), ('prof-b', 'p-b')");
            stmt.execute(
                    "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, level_score, net_worth_minor_units)"
                            + " VALUES ('" + low.value() + "', 'prof-a', 'p-a', 10, 1000), ('" + high.value()
                            + "', 'prof-b', 'p-b', 20, 2000)");
        }
        rows = new PlayerIslandLeaderboardAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A stale board changes no score, and a board thrown away is built again from the rows")
    void aStaleOrClearedBoardDoesNotMoveTheRows() {
        IslandLeaderboardService node = service(rows);
        assertThat(firstOf(node)).isEqualTo(high);

        // The rows move, and this node's board has not been built again yet.
        rows.updateIslandScore(low, 50, 1000);
        assertThat(firstOf(node)).describedAs("the board in hand, stale").isEqualTo(high);
        assertThat(scores())
                .describedAs("the rows, which the stale board did not touch")
                .containsEntry(low, 50L);

        // Another node, or this one after a restart, holds no board and builds it from the rows.
        assertThat(firstOf(service(rows))).isEqualTo(low);
        node.refresh(LeaderboardCategory.LEVEL);
        assertThat(firstOf(node)).isEqualTo(low);
    }

    @Test
    @DisplayName("Reading, ranking and rebuilding the board writes no score")
    void theBoardNeverWritesTheRows() {
        AtomicInteger writes = new AtomicInteger();
        IslandLeaderboardService node = service(new IslandLeaderboardPort() {
            @Override
            public List<LeaderboardEntry> fetchTopIslands(LeaderboardCategory category, int limit) {
                return rows.fetchTopIslands(category, limit);
            }

            @Override
            public void updateIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {
                writes.incrementAndGet();
                rows.updateIslandScore(islandId, levelScore, netWorthMinorUnits);
            }
        });
        Map<IslandId, Long> before = scores();

        for (int i = 0; i < 3; i++) {
            node.getTop(LeaderboardCategory.LEVEL, 10);
            node.getTop(LeaderboardCategory.WORTH, 10);
            node.getRank(LeaderboardCategory.BANK, low);
            node.refreshAll();
        }

        assertThat(writes).hasValue(0);
        assertThat(scores()).isEqualTo(before);
    }

    private static IslandLeaderboardService service(IslandLeaderboardPort port) {
        return new IslandLeaderboardService(port, 10, Duration.ofHours(1), Clock.systemUTC());
    }

    private static IslandId firstOf(IslandLeaderboardService board) {
        return board.getTop(LeaderboardCategory.LEVEL, 1).get(0).islandId();
    }

    private Map<IslandId, Long> scores() {
        Map<IslandId, Long> scores = new LinkedHashMap<>();
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT id, level_score FROM islands ORDER BY id")) {
            while (rs.next()) {
                scores.put(IslandId.fromString(rs.getString(1)), rs.getLong(2));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return scores;
    }
}
