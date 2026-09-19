package com.uxplima.uxmskyblock.persistence.booster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterStoragePort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Production SQL implementation of {@link IslandBoosterStoragePort} persisting
 * island active and paused boosters across SQLite, MariaDB/MySQL, and PostgreSQL.
 */
public final class SqlIslandBoosterStorageAdapter implements IslandBoosterStoragePort {

    private final Database database;

    public SqlIslandBoosterStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void saveBooster(IslandBooster booster) {
        Objects.requireNonNull(booster, "booster must not be null");

        String checkSql = "SELECT 1 FROM island_boosters WHERE booster_id = ?";
        String updateSql = """
                UPDATE island_boosters
                SET island_id = ?, category = ?, multiplier = ?, expires_at = ?, paused_at = ?, remaining_seconds = ?
                WHERE booster_id = ?
                """;
        String insertSql = """
                INSERT INTO island_boosters (booster_id, island_id, category, multiplier, expires_at, created_at, paused_at, remaining_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, booster.id().toString());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, booster.islandId().value().toString());
                    updateStmt.setString(2, booster.category().name());
                    updateStmt.setDouble(3, booster.multiplier());
                    updateStmt.setTimestamp(4, Timestamp.from(booster.expiresAt()));
                    updateStmt.setTimestamp(5, booster.pausedAt() != null ? Timestamp.from(booster.pausedAt()) : null);
                    updateStmt.setLong(6, booster.remainingSeconds());
                    updateStmt.setString(7, booster.id().toString());
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, booster.id().toString());
                    insertStmt.setString(2, booster.islandId().value().toString());
                    insertStmt.setString(3, booster.category().name());
                    insertStmt.setDouble(4, booster.multiplier());
                    insertStmt.setTimestamp(5, Timestamp.from(booster.expiresAt()));
                    insertStmt.setTimestamp(6, Timestamp.from(booster.createdAt()));
                    insertStmt.setTimestamp(7, booster.pausedAt() != null ? Timestamp.from(booster.pausedAt()) : null);
                    insertStmt.setLong(8, booster.remainingSeconds());
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new BoosterPersistenceException("Failed to save booster " + booster.id(), e);
        }
    }

    @Override
    public void saveAll(Collection<IslandBooster> boosters) {
        Objects.requireNonNull(boosters, "boosters must not be null");
        for (IslandBooster booster : boosters) {
            saveBooster(booster);
        }
    }

    @Override
    public Optional<IslandBooster> findById(UUID boosterId) {
        Objects.requireNonNull(boosterId, "boosterId must not be null");

        String sql = """
                SELECT booster_id, island_id, category, multiplier, expires_at, created_at, paused_at, remaining_seconds
                FROM island_boosters
                WHERE booster_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, boosterId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new BoosterPersistenceException("Failed to find booster " + boosterId, e);
        }
    }

    @Override
    public List<IslandBooster> findByIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT booster_id, island_id, category, multiplier, expires_at, created_at, paused_at, remaining_seconds
                FROM island_boosters
                WHERE island_id = ?
                ORDER BY expires_at ASC
                """;

        List<IslandBooster> list = new ArrayList<>();
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new BoosterPersistenceException("Failed to query boosters for island " + islandId, e);
        }
    }

    @Override
    public List<IslandBooster> findByIslandAndCategory(IslandId islandId, BoosterCategory category) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(category, "category must not be null");

        String sql = """
                SELECT booster_id, island_id, category, multiplier, expires_at, created_at, paused_at, remaining_seconds
                FROM island_boosters
                WHERE island_id = ? AND category = ?
                ORDER BY expires_at ASC
                """;

        List<IslandBooster> list = new ArrayList<>();
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, category.name());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new BoosterPersistenceException(
                    "Failed to query boosters for island " + islandId + " category " + category, e);
        }
    }

    @Override
    public void deleteById(UUID boosterId) {
        Objects.requireNonNull(boosterId, "boosterId must not be null");

        String sql = "DELETE FROM island_boosters WHERE booster_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, boosterId.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new BoosterPersistenceException("Failed to delete booster " + boosterId, e);
        }
    }

    @Override
    public void deleteByIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = "DELETE FROM island_boosters WHERE island_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new BoosterPersistenceException("Failed to delete boosters for island " + islandId, e);
        }
    }

    @Override
    public int purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        String sql = "DELETE FROM island_boosters WHERE paused_at IS NULL AND expires_at < ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(now));
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new BoosterPersistenceException("Failed to purge expired boosters", e);
        }
    }

    private IslandBooster mapRow(ResultSet rs) throws SQLException {
        UUID boosterId = UUID.fromString(rs.getString("booster_id"));
        IslandId islandId = new IslandId(UUID.fromString(rs.getString("island_id")));
        String catStr = rs.getString("category");
        BoosterCategory category = BoosterCategory.parse(catStr).orElse(BoosterCategory.SPAWNER_RATE);
        double multiplier = rs.getDouble("multiplier");
        Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Timestamp pausedTs = rs.getTimestamp("paused_at");
        Instant pausedAt = pausedTs != null ? pausedTs.toInstant() : null;
        long remainingSeconds = rs.getLong("remaining_seconds");

        return new IslandBooster(
                boosterId, islandId, category, multiplier, expiresAt, createdAt, pausedAt, remainingSeconds);
    }

    public static final class BoosterPersistenceException extends RuntimeException {
        public BoosterPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
