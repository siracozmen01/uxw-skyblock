package com.uxplima.uxmskyblock.persistence.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.StorageException;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchOperation;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchState;

/**
 * Reading a profile switch, without changing one.
 *
 * <p>A switch is a state machine with seven transitions, each of them a guarded write. Looking up
 * where a switch has got to is not one of them, and the two sat in the same class: the reader had
 * to know which of the thirteen methods could move a player's inventory and which could not.
 */
final class SqlProfileSwitchQueries {

    private final Database database;

    SqlProfileSwitchQueries(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public Optional<ProfileSwitchOperation> findOperation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection connection = database.connection()) {
            return findOperation(connection, operationId);
        } catch (SQLException e) {
            throw new StorageException("Failed to find profile switch operation " + operationId, e);
        }
    }

    static Optional<ProfileSwitchOperation> findOperation(Connection connection, UUID operationId) throws SQLException {
        String sql = "SELECT operation_id, player_uuid, from_profile_id, to_profile_id, state, "
                + "source_snapshot_blob, target_snapshot_blob, failure_reason, created_at, updated_at "
                + "FROM profile_switch_operations WHERE operation_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToOperation(rs));
                }
                return Optional.empty();
            }
        }
    }

    public Optional<ProfileSwitchOperation> findActiveOperation(PlayerUuid playerId) {
        Objects.requireNonNull(playerId, "playerId");

        String sql = "SELECT operation_id, player_uuid, from_profile_id, to_profile_id, state, "
                + "source_snapshot_blob, target_snapshot_blob, failure_reason, created_at, updated_at "
                + "FROM profile_switch_operations "
                + "WHERE player_uuid = ? AND state NOT IN ('COMMITTED', 'FAILED') "
                + "ORDER BY created_at DESC LIMIT 1";

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToOperation(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to find active profile switch operation for " + playerId, e);
        }
    }

    private static ProfileSwitchOperation mapResultSetToOperation(ResultSet rs) throws SQLException {
        UUID opId = UUID.fromString(rs.getString("operation_id"));
        PlayerUuid playerUuid = PlayerUuid.fromString(rs.getString("player_uuid"));
        ProfileId fromProf = ProfileId.fromString(rs.getString("from_profile_id"));
        ProfileId toProf = ProfileId.fromString(rs.getString("to_profile_id"));
        ProfileSwitchState state = ProfileSwitchState.valueOf(rs.getString("state"));
        byte[] srcBlob = rs.getBytes("source_snapshot_blob");
        byte[] tgtBlob = rs.getBytes("target_snapshot_blob");
        String failReason = rs.getString("failure_reason");
        Instant created = rs.getTimestamp("created_at").toInstant();
        Instant updated = rs.getTimestamp("updated_at").toInstant();

        return switch (state) {
            case PREPARING -> new ProfileSwitchOperation.Preparing(opId, playerUuid, fromProf, toProf, created);
            case SOURCE_SNAPSHOTTED ->
                new ProfileSwitchOperation.SourceSnapshotted(
                        opId, playerUuid, fromProf, toProf, created, srcBlob != null ? srcBlob : new byte[0]);
            case TARGET_LOADED ->
                new ProfileSwitchOperation.TargetLoaded(
                        opId,
                        playerUuid,
                        fromProf,
                        toProf,
                        created,
                        srcBlob != null ? srcBlob : new byte[0],
                        tgtBlob != null ? tgtBlob : new byte[0]);
            case TARGET_APPLY_INTENT ->
                new ProfileSwitchOperation.TargetApplyIntent(
                        opId, playerUuid, fromProf, toProf, created, tgtBlob != null ? tgtBlob : new byte[0]);
            case PLAYER_APPLIED ->
                new ProfileSwitchOperation.PlayerApplied(opId, playerUuid, fromProf, toProf, created);
            case COMMITTED ->
                new ProfileSwitchOperation.Committed(opId, playerUuid, fromProf, toProf, created, updated);
            case FAILED ->
                new ProfileSwitchOperation.Failed(
                        opId,
                        playerUuid,
                        fromProf,
                        toProf,
                        created,
                        failReason != null ? failReason : "Unknown error",
                        true);
            case RECOVERY_REQUIRED ->
                new ProfileSwitchOperation.RecoveryRequired(
                        opId,
                        playerUuid,
                        fromProf,
                        toProf,
                        created,
                        failReason != null ? failReason : "Recovery required");
        };
    }

    public Optional<PlayerUuid> resolvePlayerUuid(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId");
        String sql = "SELECT player_uuid FROM player_profiles WHERE profile_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, profileId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PlayerUuid(UUID.fromString(rs.getString("player_uuid"))));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to resolve player_uuid for profile_id " + profileId, e);
        }
    }
}
