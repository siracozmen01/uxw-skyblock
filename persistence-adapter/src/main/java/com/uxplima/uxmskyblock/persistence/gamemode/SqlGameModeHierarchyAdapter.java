package com.uxplima.uxmskyblock.persistence.gamemode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyStoragePort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * SQL persistence adapter for game mode instances and canonical gameplay root references.
 */
public final class SqlGameModeHierarchyAdapter implements GameModeHierarchyStoragePort {

    private final DataSource dataSource;

    public SqlGameModeHierarchyAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void saveGameModeInstance(GameModeInstance instance) {
        Objects.requireNonNull(instance, "instance must not be null");

        String updateSql = """
                UPDATE game_mode_instances
                SET game_mode_type = ?, ruleset_config = ?, updated_at = ?
                WHERE id = ?
                """;

        String insertSql = """
                INSERT INTO game_mode_instances (id, profile_id, game_mode_type, ruleset_config, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                updateStmt.setString(1, instance.gameModeType().name());
                updateStmt.setString(2, instance.rulesetConfig());
                updateStmt.setTimestamp(3, Timestamp.from(instance.updatedAt()));
                updateStmt.setString(4, instance.id().value().toString());
                int updated = updateStmt.executeUpdate();

                if (updated == 0) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, instance.id().value().toString());
                        insertStmt.setString(2, instance.profileId().value().toString());
                        insertStmt.setString(3, instance.gameModeType().name());
                        insertStmt.setString(4, instance.rulesetConfig());
                        insertStmt.setTimestamp(5, Timestamp.from(instance.createdAt()));
                        insertStmt.setTimestamp(6, Timestamp.from(instance.updatedAt()));
                        insertStmt.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed saving game mode instance: " + instance.id().value(), e);
        }
    }

    @Override
    public Optional<GameModeInstance> findInstanceById(GameModeInstanceId id) {
        Objects.requireNonNull(id, "id must not be null");

        String sql = """
                SELECT id, profile_id, game_mode_type, ruleset_config, created_at, updated_at
                FROM game_mode_instances
                WHERE id = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapInstance(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed finding game mode instance by id: " + id.value(), e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<GameModeInstance> findInstanceByProfileId(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");

        String sql = """
                SELECT id, profile_id, game_mode_type, ruleset_config, created_at, updated_at
                FROM game_mode_instances
                WHERE profile_id = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, profileId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapInstance(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed finding game mode instance by profile: " + profileId.value(), e);
        }
        return Optional.empty();
    }

    @Override
    public void savePrimaryGameplayRootRef(PrimaryGameplayRootRef rootRef) {
        Objects.requireNonNull(rootRef, "rootRef must not be null");

        String deleteSql = """
                DELETE FROM primary_gameplay_roots
                WHERE game_mode_instance_id = ?
                """;

        String insertSql = """
                INSERT INTO primary_gameplay_roots (game_mode_instance_id, root_id, root_type, bound_at)
                VALUES (?, ?, ?, ?)
                """;

        try (Connection conn = dataSource.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                    deleteStmt.setString(1, rootRef.gameModeInstanceId().value().toString());
                    deleteStmt.executeUpdate();
                }
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, rootRef.gameModeInstanceId().value().toString());
                    insertStmt.setString(2, rootRef.rootId());
                    insertStmt.setString(3, rootRef.rootType());
                    insertStmt.setTimestamp(4, Timestamp.from(rootRef.boundAt()));
                    insertStmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed saving primary gameplay root ref: " + rootRef.gameModeInstanceId().value(), e);
        }
    }

    @Override
    public Optional<PrimaryGameplayRootRef> findRootRefByInstanceId(GameModeInstanceId instanceId) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");

        String sql = """
                SELECT game_mode_instance_id, root_id, root_type, bound_at
                FROM primary_gameplay_roots
                WHERE game_mode_instance_id = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, instanceId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRootRef(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed finding primary root ref: " + instanceId.value(), e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<PrimaryGameplayRootRef> findRootRefByRootId(String rootId, String rootType) {
        Objects.requireNonNull(rootId, "rootId must not be null");
        Objects.requireNonNull(rootType, "rootType must not be null");

        String sql = """
                SELECT game_mode_instance_id, root_id, root_type, bound_at
                FROM primary_gameplay_roots
                WHERE root_id = ? AND root_type = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, rootId);
            stmt.setString(2, rootType);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRootRef(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed finding primary root ref by rootId: " + rootId, e);
        }
        return Optional.empty();
    }

    private GameModeInstance mapInstance(ResultSet rs) throws SQLException {
        GameModeInstanceId id = GameModeInstanceId.of(UUID.fromString(rs.getString("id")));
        ProfileId profileId = new ProfileId(UUID.fromString(rs.getString("profile_id")));
        GameModeType type = GameModeType.valueOf(rs.getString("game_mode_type"));
        String ruleset = rs.getString("ruleset_config");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new GameModeInstance(id, profileId, type, ruleset, createdAt, updatedAt);
    }

    private PrimaryGameplayRootRef mapRootRef(ResultSet rs) throws SQLException {
        GameModeInstanceId id = GameModeInstanceId.of(UUID.fromString(rs.getString("game_mode_instance_id")));
        String rootId = rs.getString("root_id");
        String rootType = rs.getString("root_type");
        Instant boundAt = rs.getTimestamp("bound_at").toInstant();
        return new PrimaryGameplayRootRef(id, rootId, rootType, boundAt);
    }
}
