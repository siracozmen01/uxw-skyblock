package com.uxplima.uxmskyblock.persistence.mission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;

/**
 * Production SQL implementation of {@link IslandMissionStoragePort} persisting
 * island mission and quest progression across SQLite, MariaDB/MySQL, and PostgreSQL.
 */
public final class PlayerIslandMissionAdapter implements IslandMissionStoragePort {

    private final Database database;

    public PlayerIslandMissionAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public Optional<MissionProgress> findProgress(IslandId islandId, ProfileId profileId, MissionId missionId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(missionId, "missionId must not be null");

        String sql = """
                SELECT mission_id, progress_count, completed, completed_at, updated_at
                FROM island_missions
                WHERE island_id = ? AND profile_id = ? AND mission_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, profileId.value().toString());
            stmt.setString(3, missionId.value());

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new MissionPersistenceException(
                    "Failed to query mission progress for island " + islandId + ", profile " + profileId + ", mission " + missionId,
                    e);
        }
    }

    @Override
    public Map<MissionId, MissionProgress> findAllProgress(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");

        String sql = """
                SELECT mission_id, progress_count, completed, completed_at, updated_at
                FROM island_missions
                WHERE island_id = ? AND profile_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, profileId.value().toString());

            Map<MissionId, MissionProgress> result = new HashMap<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    MissionProgress progress = mapRow(rs);
                    result.put(progress.missionId(), progress);
                }
            }
            return result;
        } catch (SQLException e) {
            throw new MissionPersistenceException(
                    "Failed to query all mission progress for island " + islandId + ", profile " + profileId, e);
        }
    }

    @Override
    public void saveProgress(IslandId islandId, ProfileId profileId, MissionProgress progress) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(progress, "progress must not be null");

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                upsertInternal(conn, islandId, profileId, progress);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new MissionPersistenceException(
                    "Failed to save mission progress for island " + islandId + ", profile " + profileId + ", mission " + progress.missionId(),
                    e);
        }
    }

    @Override
    public void saveAllProgress(IslandId islandId, ProfileId profileId, Collection<MissionProgress> progresses) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(progresses, "progresses must not be null");
        if (progresses.isEmpty()) {
            return;
        }

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                for (MissionProgress p : progresses) {
                    upsertInternal(conn, islandId, profileId, p);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new MissionPersistenceException(
                    "Failed to save batch mission progress for island " + islandId + ", profile " + profileId, e);
        }
    }

    private void upsertInternal(Connection conn, IslandId islandId, ProfileId profileId, MissionProgress progress)
            throws SQLException {
        String checkSql = """
                SELECT 1 FROM island_missions
                WHERE island_id = ? AND profile_id = ? AND mission_id = ?
                """;
        String updateSql = """
                UPDATE island_missions
                SET progress_count = ?, completed = ?, completed_at = ?, updated_at = ?
                WHERE island_id = ? AND profile_id = ? AND mission_id = ?
                """;
        String insertSql = """
                INSERT INTO island_missions (
                    island_id, profile_id, mission_id, progress_count, completed, completed_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        boolean exists;
        try (PreparedStatement check = conn.prepareStatement(checkSql)) {
            check.setString(1, islandId.value().toString());
            check.setString(2, profileId.value().toString());
            check.setString(3, progress.missionId().value());
            try (ResultSet rs = check.executeQuery()) {
                exists = rs.next();
            }
        }

        if (exists) {
            try (PreparedStatement update = conn.prepareStatement(updateSql)) {
                update.setLong(1, progress.progressCount());
                update.setBoolean(2, progress.completed());
                update.setTimestamp(3, progress.completedAt() != null ? Timestamp.from(progress.completedAt()) : null);
                update.setTimestamp(4, Timestamp.from(progress.updatedAt()));
                update.setString(5, islandId.value().toString());
                update.setString(6, profileId.value().toString());
                update.setString(7, progress.missionId().value());
                update.executeUpdate();
            }
        } else {
            try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                insert.setString(1, islandId.value().toString());
                insert.setString(2, profileId.value().toString());
                insert.setString(3, progress.missionId().value());
                insert.setLong(4, progress.progressCount());
                insert.setBoolean(5, progress.completed());
                insert.setTimestamp(6, progress.completedAt() != null ? Timestamp.from(progress.completedAt()) : null);
                insert.setTimestamp(7, Timestamp.from(progress.updatedAt()));
                insert.executeUpdate();
            }
        }
    }

    private MissionProgress mapRow(ResultSet rs) throws SQLException {
        String missionIdVal = rs.getString("mission_id");
        long progressCount = rs.getLong("progress_count");
        boolean completed = rs.getBoolean("completed");
        Timestamp compTs = rs.getTimestamp("completed_at");
        Timestamp updTs = rs.getTimestamp("updated_at");

        Instant completedAt = compTs != null ? compTs.toInstant() : null;
        Instant updatedAt = updTs != null ? updTs.toInstant() : Instant.now();

        return new MissionProgress(MissionId.of(missionIdVal), progressCount, completed, completedAt, updatedAt);
    }

    public static final class MissionPersistenceException extends RuntimeException {
        public MissionPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
