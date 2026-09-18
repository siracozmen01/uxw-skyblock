package com.uxplima.uxmskyblock.persistence.season;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonMetric;
import com.uxplima.uxmskyblock.core.domain.season.SeasonPayoutRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonPayoutState;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonSnapshotEntry;
import com.uxplima.uxmskyblock.core.domain.season.SeasonState;

/**
 * Production SQL implementation of {@link IslandSeasonStoragePort} managing seasons,
 * immutable placement snapshots, and durable reward payout queues.
 */
public final class PlayerIslandSeasonAdapter implements IslandSeasonStoragePort {

    private final Database database;
    private final Dialect dialect;

    public PlayerIslandSeasonAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.dialect = database.dialect();
    }

    @Override
    public void saveSeason(SeasonRecord season) {
        Objects.requireNonNull(season, "season must not be null");

        String checkSql = "SELECT 1 FROM island_seasons WHERE season_id = ?";
        String updateSql = """
                UPDATE island_seasons
                SET name = ?, starts_at = ?, ends_at = ?, state = ?, updated_at = CURRENT_TIMESTAMP
                WHERE season_id = ?
                """;
        String insertSql = """
                INSERT INTO island_seasons (
                    season_id, name, starts_at, ends_at, state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setInt(1, season.id().number());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, season.name());
                    updateStmt.setTimestamp(2, Timestamp.from(season.startsAt()));
                    updateStmt.setTimestamp(3, Timestamp.from(season.endsAt()));
                    updateStmt.setString(4, season.state().name());
                    updateStmt.setInt(5, season.id().number());
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setInt(1, season.id().number());
                    insertStmt.setString(2, season.name());
                    insertStmt.setTimestamp(3, Timestamp.from(season.startsAt()));
                    insertStmt.setTimestamp(4, Timestamp.from(season.endsAt()));
                    insertStmt.setString(5, season.state().name());
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new SeasonPersistenceException(
                    "Failed to persist season: " + season.id().number(), e);
        }
    }

    @Override
    public Optional<SeasonRecord> findActiveSeason() {
        String sql = """
                SELECT season_id, name, starts_at, ends_at, state
                FROM island_seasons
                WHERE state = 'ACTIVE'
                ORDER BY season_id DESC
                LIMIT 1
                """;
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapSeason(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new SeasonPersistenceException("Failed to query active season", e);
        }
    }

    @Override
    public Optional<SeasonRecord> findSeason(SeasonId id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = """
                SELECT season_id, name, starts_at, ends_at, state
                FROM island_seasons
                WHERE season_id = ?
                """;
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id.number());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapSeason(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new SeasonPersistenceException("Failed to query season: " + id.number(), e);
        }
    }

    @Override
    public List<SeasonRecord> listSeasons() {
        String sql = """
                SELECT season_id, name, starts_at, ends_at, state
                FROM island_seasons
                ORDER BY season_id ASC
                """;
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            List<SeasonRecord> seasons = new ArrayList<>();
            while (rs.next()) {
                seasons.add(mapSeason(rs));
            }
            return seasons;
        } catch (SQLException e) {
            throw new SeasonPersistenceException("Failed to list seasons", e);
        }
    }

    @Override
    public void saveSnapshots(List<SeasonSnapshotEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO season_snapshots (
                    season_id, metric, rank, island_id, owner_player_uuid, score, snapshot_timestamp
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            boolean prevAutoCommit = conn.getAutoCommit();
            beginTransaction(conn);
            try {
                for (SeasonSnapshotEntry entry : entries) {
                    ps.setInt(1, entry.seasonId().number());
                    ps.setString(2, entry.metric().name());
                    ps.setInt(3, entry.rank());
                    ps.setString(4, entry.islandId().value().toString());
                    ps.setString(5, entry.ownerUuid().value().toString());
                    ps.setLong(6, entry.score());
                    ps.setTimestamp(7, Timestamp.from(entry.snapshotTimestamp()));
                    ps.addBatch();
                }
                ps.executeBatch();
                commitTransaction(conn);
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw new SeasonPersistenceException("Failed to execute snapshot batch", e);
            } finally {
                resetAutoCommitQuietly(conn, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new SeasonPersistenceException("Failed to acquire connection for snapshots batch", e);
        }
    }

    @Override
    public List<SeasonSnapshotEntry> findSnapshots(SeasonId id, SeasonMetric metric, int limit) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(metric, "metric must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }

        String sql = """
                SELECT season_id, metric, rank, island_id, owner_player_uuid, score, snapshot_timestamp
                FROM season_snapshots
                WHERE season_id = ? AND metric = ?
                ORDER BY rank ASC
                LIMIT ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id.number());
            ps.setString(2, metric.name());
            ps.setInt(3, limit);

            try (ResultSet rs = ps.executeQuery()) {
                List<SeasonSnapshotEntry> snapshots = new ArrayList<>();
                while (rs.next()) {
                    snapshots.add(new SeasonSnapshotEntry(
                            SeasonId.of(rs.getInt("season_id")),
                            SeasonMetric.valueOf(rs.getString("metric")),
                            rs.getInt("rank"),
                            IslandId.of(UUID.fromString(rs.getString("island_id"))),
                            new PlayerUuid(UUID.fromString(rs.getString("owner_player_uuid"))),
                            rs.getLong("score"),
                            rs.getTimestamp("snapshot_timestamp").toInstant()));
                }
                return snapshots;
            }
        } catch (SQLException e) {
            throw new SeasonPersistenceException(
                    "Failed to query snapshots for season " + id.number() + " metric " + metric, e);
        }
    }

    @Override
    public void queuePayout(SeasonPayoutRecord payout) {
        Objects.requireNonNull(payout, "payout must not be null");

        String sql = """
                INSERT INTO season_payouts (
                    payout_id, season_id, recipient_uuid, reward_action, state, created_at, dispatched_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, payout.payoutId());
            ps.setInt(2, payout.seasonId().number());
            ps.setString(3, payout.recipient().value().toString());
            ps.setString(4, payout.rewardAction());
            ps.setString(5, payout.state().name());
            ps.setTimestamp(6, Timestamp.from(payout.createdAt()));
            if (payout.dispatchedAt() != null) {
                ps.setTimestamp(7, Timestamp.from(payout.dispatchedAt()));
            } else {
                ps.setNull(7, Types.TIMESTAMP);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SeasonPersistenceException("Failed to queue season payout: " + payout.payoutId(), e);
        }
    }

    @Override
    public List<SeasonPayoutRecord> findPendingPayouts(PlayerUuid recipient) {
        Objects.requireNonNull(recipient, "recipient must not be null");

        String sql = """
                SELECT payout_id, season_id, recipient_uuid, reward_action, state, created_at, dispatched_at
                FROM season_payouts
                WHERE recipient_uuid = ? AND state = 'PENDING'
                ORDER BY created_at ASC
                """;

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recipient.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<SeasonPayoutRecord> payouts = new ArrayList<>();
                while (rs.next()) {
                    Timestamp dispatchedTs = rs.getTimestamp("dispatched_at");
                    Instant dispatchedAt = dispatchedTs != null ? dispatchedTs.toInstant() : null;
                    payouts.add(new SeasonPayoutRecord(
                            rs.getString("payout_id"),
                            SeasonId.of(rs.getInt("season_id")),
                            new PlayerUuid(UUID.fromString(rs.getString("recipient_uuid"))),
                            rs.getString("reward_action"),
                            SeasonPayoutState.valueOf(rs.getString("state")),
                            rs.getTimestamp("created_at").toInstant(),
                            dispatchedAt));
                }
                return payouts;
            }
        } catch (SQLException e) {
            throw new SeasonPersistenceException(
                    "Failed to query pending payouts for recipient: " + recipient.value(), e);
        }
    }

    @Override
    public void markPayoutDispatched(String payoutId, Instant dispatchedAt) {
        Objects.requireNonNull(payoutId, "payoutId must not be null");
        Objects.requireNonNull(dispatchedAt, "dispatchedAt must not be null");

        String sql = """
                UPDATE season_payouts
                SET state = 'DISPATCHED', dispatched_at = ?
                WHERE payout_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(dispatchedAt));
            ps.setString(2, payoutId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SeasonPersistenceException("Failed to mark payout dispatched: " + payoutId, e);
        }
    }

    private SeasonRecord mapSeason(ResultSet rs) throws SQLException {
        return new SeasonRecord(
                SeasonId.of(rs.getInt("season_id")),
                rs.getString("name"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),
                SeasonState.valueOf(rs.getString("state")));
    }

    private void beginTransaction(Connection connection) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
            }
        } else {
            connection.setAutoCommit(false);
        }
    }

    private void commitTransaction(Connection connection) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("COMMIT");
            }
        } else {
            connection.commit();
        }
    }

    private void rollbackTransaction(Connection connection) {
        try {
            if (dialect == Dialect.SQLITE) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                }
            } else {
                connection.rollback();
            }
        } catch (SQLException expected) {
            // Best-effort cleanup
        }
    }

    private void resetAutoCommitQuietly(Connection connection, boolean autoCommit) {
        if (dialect != Dialect.SQLITE) {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException expected) {
                // Best-effort cleanup
            }
        }
    }
}
