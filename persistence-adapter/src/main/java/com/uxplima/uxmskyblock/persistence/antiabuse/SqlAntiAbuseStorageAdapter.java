package com.uxplima.uxmskyblock.persistence.antiabuse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.antiabuse.AntiAbuseStoragePort;
import com.uxplima.uxmskyblock.core.domain.antiabuse.IslandQuarantineRecord;
import com.uxplima.uxmskyblock.core.domain.antiabuse.PlayerAntiAbuseRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Production persistence adapter for player anti-abuse records and island quarantines,
 * compatible with SQLite, MySQL/MariaDB, and PostgreSQL.
 */
public final class SqlAntiAbuseStorageAdapter implements AntiAbuseStoragePort {

    private final Database database;

    public SqlAntiAbuseStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public Optional<PlayerAntiAbuseRecord> findRecord(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");

        String sql = """
                SELECT last_island_reset_at, resets_today_count, reset_window_start, coop_cooldown_expires_at,
                       inventory_purge_owed
                FROM player_anti_abuse_records
                WHERE player_uuid = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerUuid.value().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                Timestamp lastResetTs = rs.getTimestamp("last_island_reset_at");
                Instant lastResetAt = lastResetTs != null ? lastResetTs.toInstant() : null;

                int resetsCount = rs.getInt("resets_today_count");

                Timestamp windowStartTs = rs.getTimestamp("reset_window_start");
                Instant resetWindowStart = windowStartTs != null ? windowStartTs.toInstant() : null;

                Timestamp coopTs = rs.getTimestamp("coop_cooldown_expires_at");
                Instant coopExpiresAt = coopTs != null ? coopTs.toInstant() : null;

                return Optional.of(new PlayerAntiAbuseRecord(
                        playerUuid,
                        lastResetAt,
                        resetsCount,
                        resetWindowStart,
                        coopExpiresAt,
                        rs.getBoolean("inventory_purge_owed")));
            }
        } catch (SQLException e) {
            throw new AntiAbusePersistenceException(
                    "Failed to query player anti-abuse record for player " + playerUuid, e);
        }
    }

    @Override
    public void saveRecord(PlayerAntiAbuseRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        String checkSql = "SELECT 1 FROM player_anti_abuse_records WHERE player_uuid = ?";
        String updateSql = """
                UPDATE player_anti_abuse_records
                SET last_island_reset_at = ?, resets_today_count = ?, reset_window_start = ?,
                    coop_cooldown_expires_at = ?, updated_at = ?, inventory_purge_owed = ?
                WHERE player_uuid = ?
                """;
        String insertSql = """
                INSERT INTO player_anti_abuse_records (
                    player_uuid, last_island_reset_at, resets_today_count, reset_window_start,
                    coop_cooldown_expires_at, updated_at, inventory_purge_owed
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        Timestamp lastResetTs = record.lastResetAt() != null ? Timestamp.from(record.lastResetAt()) : null;
        Timestamp windowStartTs = record.resetWindowStart() != null ? Timestamp.from(record.resetWindowStart()) : null;
        Timestamp coopExpiresTs =
                record.coopCooldownExpiresAt() != null ? Timestamp.from(record.coopCooldownExpiresAt()) : null;

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement check = conn.prepareStatement(checkSql)) {
                check.setString(1, record.playerUuid().value().toString());
                try (ResultSet rs = check.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement update = conn.prepareStatement(updateSql)) {
                    update.setTimestamp(1, lastResetTs);
                    update.setInt(2, record.resetsTodayCount());
                    update.setTimestamp(3, windowStartTs);
                    update.setTimestamp(4, coopExpiresTs);
                    update.setTimestamp(5, nowTs);
                    update.setBoolean(6, record.inventoryPurgeOwed());
                    update.setString(7, record.playerUuid().value().toString());
                    update.executeUpdate();
                }
            } else {
                try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                    insert.setString(1, record.playerUuid().value().toString());
                    insert.setTimestamp(2, lastResetTs);
                    insert.setInt(3, record.resetsTodayCount());
                    insert.setTimestamp(4, windowStartTs);
                    insert.setTimestamp(5, coopExpiresTs);
                    insert.setTimestamp(6, nowTs);
                    insert.setBoolean(7, record.inventoryPurgeOwed());
                    insert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new AntiAbusePersistenceException(
                    "Failed to save player anti-abuse record for " + record.playerUuid(), e);
        }
    }

    @Override
    public Optional<IslandQuarantineRecord> findQuarantine(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT quarantined_until, quarantine_reason
                FROM island_quarantines
                WHERE island_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp untilTs = rs.getTimestamp("quarantined_until");
                Instant quarantinedUntil = untilTs != null ? untilTs.toInstant() : Instant.EPOCH;
                String reason = rs.getString("quarantine_reason");
                return Optional.of(new IslandQuarantineRecord(islandId, quarantinedUntil, reason));
            }
        } catch (SQLException e) {
            throw new AntiAbusePersistenceException("Failed to query quarantine for island " + islandId, e);
        }
    }

    @Override
    public void saveQuarantine(IslandQuarantineRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        String checkSql = "SELECT 1 FROM island_quarantines WHERE island_id = ?";
        String updateSql = """
                UPDATE island_quarantines
                SET quarantined_until = ?, quarantine_reason = ?
                WHERE island_id = ?
                """;
        String insertSql = """
                INSERT INTO island_quarantines (
                    island_id, quarantined_until, quarantine_reason
                ) VALUES (?, ?, ?)
                """;

        Timestamp untilTs = Timestamp.from(record.quarantinedUntil());

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement check = conn.prepareStatement(checkSql)) {
                check.setString(1, record.islandId().value().toString());
                try (ResultSet rs = check.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement update = conn.prepareStatement(updateSql)) {
                    update.setTimestamp(1, untilTs);
                    update.setString(2, record.reason());
                    update.setString(3, record.islandId().value().toString());
                    update.executeUpdate();
                }
            } else {
                try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                    insert.setString(1, record.islandId().value().toString());
                    insert.setTimestamp(2, untilTs);
                    insert.setString(3, record.reason());
                    insert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new AntiAbusePersistenceException("Failed to save quarantine for island " + record.islandId(), e);
        }
    }

    @Override
    public void deleteQuarantine(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = "DELETE FROM island_quarantines WHERE island_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new AntiAbusePersistenceException("Failed to delete quarantine for island " + islandId, e);
        }
    }

    @Override
    public Map<IslandId, IslandQuarantineRecord> loadActiveQuarantines(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        String sql = """
                SELECT island_id, quarantined_until, quarantine_reason
                FROM island_quarantines
                WHERE quarantined_until > ?
                """;

        Map<IslandId, IslandQuarantineRecord> result = new HashMap<>();
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(now));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    IslandId islandId = new IslandId(UUID.fromString(rs.getString("island_id")));
                    Timestamp untilTs = rs.getTimestamp("quarantined_until");
                    Instant quarantinedUntil = untilTs != null ? untilTs.toInstant() : Instant.EPOCH;
                    String reason = rs.getString("quarantine_reason");
                    result.put(islandId, new IslandQuarantineRecord(islandId, quarantinedUntil, reason));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new AntiAbusePersistenceException("Failed to load active island quarantines", e);
        }
    }

    public static final class AntiAbusePersistenceException extends RuntimeException {
        public AntiAbusePersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
