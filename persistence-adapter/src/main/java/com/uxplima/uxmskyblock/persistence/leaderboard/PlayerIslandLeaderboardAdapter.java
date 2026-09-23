package com.uxplima.uxmskyblock.persistence.leaderboard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;

/**
 * Production SQL persistence adapter for querying competitive leaderboards.
 */
public final class PlayerIslandLeaderboardAdapter implements IslandLeaderboardPort {

    private final Database database;
    private final Dialect dialect;

    public PlayerIslandLeaderboardAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        SupportedDialects.require(dialect, "island leaderboard persistence");
    }

    @Override
    public List<LeaderboardEntry> fetchTopIslands(LeaderboardCategory category, int limit) {
        Objects.requireNonNull(category, "category");
        if (limit <= 0) {
            return List.of();
        }

        String sql =
                switch (category) {
                    case LEVEL -> """
                    SELECT id, custom_name, level_score AS score
                    FROM islands
                    WHERE lifecycle = 'ACTIVE'
                    ORDER BY level_score DESC, id ASC
                    LIMIT ?
                    """;
                    case WORTH -> """
                    SELECT id, custom_name, net_worth_minor_units AS score
                    FROM islands
                    WHERE lifecycle = 'ACTIVE'
                    ORDER BY net_worth_minor_units DESC, id ASC
                    LIMIT ?
                    """;
                    case BANK -> """
                    SELECT b.island_id AS id, i.custom_name, b.primary_balance_minor_units AS score
                    FROM island_banks b
                    JOIN islands i ON b.island_id = i.id
                    WHERE i.lifecycle = 'ACTIVE'
                    ORDER BY b.primary_balance_minor_units DESC, b.island_id ASC
                    LIMIT ?
                    """;
                };

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<LeaderboardEntry> entries = new ArrayList<>();
                int rank = 1;
                while (rs.next()) {
                    String rawId = rs.getString("id");
                    String customName = rs.getString("custom_name");
                    long score = rs.getLong("score");

                    boolean named = customName != null && !customName.isBlank();
                    String displayName =
                            named ? customName : "Island " + rawId.substring(0, Math.min(8, rawId.length()));

                    String formattedScore =
                            switch (category) {
                                case LEVEL -> "Level " + score;
                                case WORTH, BANK -> String.format(Locale.ROOT, "$%,.2f", score / 100.0);
                            };

                    entries.add(new LeaderboardEntry(
                            rank++, IslandId.fromString(rawId), displayName, score, formattedScore, named));
                }
                return Collections.unmodifiableList(entries);
            }
        } catch (SQLException e) {
            throw new IslandLeaderboardPersistenceException("Failed to fetch top islands for " + category, e);
        }
    }

    @Override
    public void updateIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {
        Objects.requireNonNull(islandId, "islandId");

        String sql = """
                UPDATE islands
                SET level_score = ?, net_worth_minor_units = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, levelScore);
            ps.setLong(2, netWorthMinorUnits);
            ps.setString(3, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IslandLeaderboardPersistenceException("Failed to update island scores for " + islandId, e);
        }
    }
}
