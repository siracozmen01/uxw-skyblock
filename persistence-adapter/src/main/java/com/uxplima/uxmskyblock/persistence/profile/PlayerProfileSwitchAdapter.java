package com.uxplima.uxmskyblock.persistence.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.StorageException;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchOperation;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchState;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.result.Unit;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Canonical SQL-backed implementation of {@link ProfileSwitchPort}.
 *
 * <p>Implements the crash-consistent write-ahead state machine for profile switches,
 * serializing on canonical {@code player_sessions} row locks and enforcing single-active-switch
 * invariants via atomic CAS reservation on {@code player_accounts.active_switch_operation_id}.
 */
public final class PlayerProfileSwitchAdapter implements ProfileSwitchPort {

    private final Database database;
    private final Dialect dialect;

    public PlayerProfileSwitchAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        validateDialect(this.dialect);
    }

    private static void validateDialect(Dialect dialect) {
        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException("Unsupported SQL dialect: " + dialect
                        + ". Skyblock persistence supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
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
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }

    private void resetAutoCommitQuietly(Connection connection, boolean autoCommit) {
        if (dialect != Dialect.SQLITE) {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException expected) {
                // best-effort connection state restoration on connection close
            }
        }
    }

    @Override
    public Result<ProfileSwitchOperation.Preparing, String> reserveSwitch(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            ServerNodeId currentNode,
            long expectedEpoch) {

        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(fromProfileId, "fromProfileId");
        Objects.requireNonNull(toProfileId, "toProfileId");
        Objects.requireNonNull(currentNode, "currentNode");

        try (Connection connection = database.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            beginTransaction(connection);
            try {
                // Step 1: Validate session authority on canonical player_sessions row
                String sessionLockSql = "SELECT authoritative_node, session_epoch, state, "
                        + "(CASE WHEN lease_expires_at >= CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS lease_valid, "
                        + "active_profile_id "
                        + "FROM player_sessions WHERE player_uuid = ?"
                        + (dialect == Dialect.SQLITE ? "" : " FOR UPDATE");

                try (PreparedStatement ps = connection.prepareStatement(sessionLockSql)) {
                    ps.setString(1, playerId.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            rollbackTransaction(connection);
                            return Result.err("SESSION_NOT_FOUND");
                        }
                        String activeNode = rs.getString("authoritative_node");
                        long epoch = rs.getLong("session_epoch");
                        String state = rs.getString("state");
                        int leaseValid = rs.getInt("lease_valid");
                        String activeProfile = rs.getString("active_profile_id");

                        if (!currentNode.value().equals(activeNode) || epoch != expectedEpoch) {
                            rollbackTransaction(connection);
                            return Result.err("AUTHORITY_MISMATCH");
                        }
                        if (!"ACTIVE".equals(state) || leaseValid != 1) {
                            rollbackTransaction(connection);
                            return Result.err("INVALID_SESSION_STATE_OR_LEASE");
                        }
                        if (!fromProfileId.toString().equals(activeProfile)) {
                            rollbackTransaction(connection);
                            return Result.err("ACTIVE_PROFILE_MISMATCH");
                        }
                    }
                }

                // Step 2: Atomic CAS reservation on player_accounts
                String casSql = "UPDATE player_accounts "
                        + "SET active_switch_operation_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE player_uuid = ? AND active_switch_operation_id IS NULL";

                try (PreparedStatement ps = connection.prepareStatement(casSql)) {
                    ps.setString(1, operationId.toString());
                    ps.setString(2, playerId.toString());
                    int rows = ps.executeUpdate();
                    if (rows != 1) {
                        rollbackTransaction(connection);
                        return Result.err("PROFILE_SWITCH_ALREADY_IN_PROGRESS");
                    }
                }

                // Step 3: Insert initial profile_switch_operations row
                String insertOpSql = "INSERT INTO profile_switch_operations "
                        + "(operation_id, player_uuid, from_profile_id, to_profile_id, state, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PREPARING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

                try (PreparedStatement ps = connection.prepareStatement(insertOpSql)) {
                    ps.setString(1, operationId.toString());
                    ps.setString(2, playerId.toString());
                    ps.setString(3, fromProfileId.toString());
                    ps.setString(4, toProfileId.toString());
                    ps.executeUpdate();
                }

                commitTransaction(connection);
                return Result.ok(new ProfileSwitchOperation.Preparing(
                        operationId, playerId, fromProfileId, toProfileId, Instant.now()));
            } catch (Exception e) {
                rollbackTransaction(connection);
                throw new StorageException("Failed to reserve profile switch operation " + operationId, e);
            } finally {
                resetAutoCommitQuietly(connection, previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new StorageException("Database error during profile switch reservation", e);
        }
    }

    @Override
    public Result<ProfileSwitchOperation.SourceSnapshotted, String> recordSourceSnapshot(
            UUID operationId, byte[] sourceSnapshot) {

        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceSnapshot, "sourceSnapshot");

        String updateSql = "UPDATE profile_switch_operations "
                + "SET state = 'SOURCE_SNAPSHOTTED', source_snapshot_blob = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE operation_id = ? AND state = 'PREPARING'";

        try (Connection connection = database.connection()) {
            try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                ps.setBytes(1, sourceSnapshot);
                ps.setString(2, operationId.toString());
                int rows = ps.executeUpdate();
                if (rows != 1) {
                    return Result.err("INVALID_STATE_TRANSITION");
                }
            }
            Optional<ProfileSwitchOperation> op = findOperation(connection, operationId);
            if (op.isPresent() && op.get() instanceof ProfileSwitchOperation.SourceSnapshotted s) {
                return Result.ok(s);
            }
            return Result.err("OPERATION_RECORD_NOT_FOUND");
        } catch (SQLException e) {
            throw new StorageException("Failed to record source snapshot for " + operationId, e);
        }
    }

    @Override
    public Result<ProfileSwitchOperation.TargetLoaded, String> recordTargetLoaded(
            UUID operationId, byte[] targetSnapshot) {

        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(targetSnapshot, "targetSnapshot");

        String updateSql = "UPDATE profile_switch_operations "
                + "SET state = 'TARGET_LOADED', target_snapshot_blob = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE operation_id = ? AND state = 'SOURCE_SNAPSHOTTED'";

        try (Connection connection = database.connection()) {
            try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                ps.setBytes(1, targetSnapshot);
                ps.setString(2, operationId.toString());
                int rows = ps.executeUpdate();
                if (rows != 1) {
                    return Result.err("INVALID_STATE_TRANSITION");
                }
            }
            Optional<ProfileSwitchOperation> op = findOperation(connection, operationId);
            if (op.isPresent() && op.get() instanceof ProfileSwitchOperation.TargetLoaded t) {
                return Result.ok(t);
            }
            return Result.err("OPERATION_RECORD_NOT_FOUND");
        } catch (SQLException e) {
            throw new StorageException("Failed to record target loaded for " + operationId, e);
        }
    }

    @Override
    public Result<ProfileSwitchOperation.TargetApplyIntent, String> recordTargetApplyIntent(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");

        String updateSql = "UPDATE profile_switch_operations "
                + "SET state = 'TARGET_APPLY_INTENT', updated_at = CURRENT_TIMESTAMP "
                + "WHERE operation_id = ? AND state = 'TARGET_LOADED'";

        try (Connection connection = database.connection()) {
            try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                ps.setString(1, operationId.toString());
                int rows = ps.executeUpdate();
                if (rows != 1) {
                    return Result.err("INVALID_STATE_TRANSITION");
                }
            }
            Optional<ProfileSwitchOperation> op = findOperation(connection, operationId);
            if (op.isPresent() && op.get() instanceof ProfileSwitchOperation.TargetApplyIntent i) {
                return Result.ok(i);
            }
            return Result.err("OPERATION_RECORD_NOT_FOUND");
        } catch (SQLException e) {
            throw new StorageException("Failed to record target apply intent for " + operationId, e);
        }
    }

    @Override
    public Result<ProfileSwitchOperation.PlayerApplied, String> recordPlayerApplied(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");

        String updateSql = "UPDATE profile_switch_operations "
                + "SET state = 'PLAYER_APPLIED', updated_at = CURRENT_TIMESTAMP "
                + "WHERE operation_id = ? AND state = 'TARGET_APPLY_INTENT'";

        try (Connection connection = database.connection()) {
            try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                ps.setString(1, operationId.toString());
                int rows = ps.executeUpdate();
                if (rows != 1) {
                    return Result.err("INVALID_STATE_TRANSITION");
                }
            }
            Optional<ProfileSwitchOperation> op = findOperation(connection, operationId);
            if (op.isPresent() && op.get() instanceof ProfileSwitchOperation.PlayerApplied p) {
                return Result.ok(p);
            }
            return Result.err("OPERATION_RECORD_NOT_FOUND");
        } catch (SQLException e) {
            throw new StorageException("Failed to record player applied for " + operationId, e);
        }
    }

    @Override
    public Result<ProfileSwitchOperation.Committed, String> commitSwitch(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId toProfileId,
            ServerNodeId currentNode,
            long expectedEpoch) {
        return commitSwitch(operationId, playerId, toProfileId, currentNode, expectedEpoch, null);
    }

    @Override
    public Result<ProfileSwitchOperation.Committed, String> commitSwitch(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId toProfileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            @Nullable StagedOutboxEvent outboxEvent) {

        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(toProfileId, "toProfileId");
        Objects.requireNonNull(currentNode, "currentNode");

        try (Connection connection = database.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            beginTransaction(connection);
            try {
                // 1. Lock and validate session authority
                String sessionLockSql = "SELECT authoritative_node, session_epoch, state, "
                        + "(CASE WHEN lease_expires_at >= CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS lease_valid "
                        + "FROM player_sessions WHERE player_uuid = ?"
                        + (dialect == Dialect.SQLITE ? "" : " FOR UPDATE");

                try (PreparedStatement ps = connection.prepareStatement(sessionLockSql)) {
                    ps.setString(1, playerId.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            rollbackTransaction(connection);
                            return Result.err("SESSION_NOT_FOUND");
                        }
                        String activeNode = rs.getString("authoritative_node");
                        long epoch = rs.getLong("session_epoch");
                        String state = rs.getString("state");
                        int leaseValid = rs.getInt("lease_valid");

                        if (!currentNode.value().equals(activeNode) || epoch != expectedEpoch) {
                            rollbackTransaction(connection);
                            return Result.err("AUTHORITY_MISMATCH");
                        }
                        if (!"ACTIVE".equals(state) || leaseValid != 1) {
                            rollbackTransaction(connection);
                            return Result.err("INVALID_SESSION_STATE_OR_LEASE");
                        }
                    }
                }

                // 2. Update player_sessions.active_profile_id
                String updateSessionSql = "UPDATE player_sessions "
                        + "SET active_profile_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE player_uuid = ? AND authoritative_node = ? AND session_epoch = ? AND state = 'ACTIVE' AND lease_expires_at >= CURRENT_TIMESTAMP";

                try (PreparedStatement ps = connection.prepareStatement(updateSessionSql)) {
                    ps.setString(1, toProfileId.toString());
                    ps.setString(2, playerId.toString());
                    ps.setString(3, currentNode.value());
                    ps.setLong(4, expectedEpoch);
                    int rows = ps.executeUpdate();
                    if (rows != 1) {
                        rollbackTransaction(connection);
                        return Result.err("FAILED_SESSION_ACTIVE_PROFILE_UPDATE");
                    }
                }

                // 3. Update player_accounts (active_profile_id and clear active_switch_operation_id)
                String updateAccountSql = "UPDATE player_accounts "
                        + "SET active_profile_id = ?, active_switch_operation_id = NULL, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE player_uuid = ? AND active_switch_operation_id = ?";

                try (PreparedStatement ps = connection.prepareStatement(updateAccountSql)) {
                    ps.setString(1, toProfileId.toString());
                    ps.setString(2, playerId.toString());
                    ps.setString(3, operationId.toString());
                    int rows = ps.executeUpdate();
                    if (rows != 1) {
                        rollbackTransaction(connection);
                        return Result.err("FAILED_ACCOUNT_ACTIVE_PROFILE_UPDATE");
                    }
                }

                // 4. Mark profile_switch_operations COMMITTED
                String updateOpSql = "UPDATE profile_switch_operations "
                        + "SET state = 'COMMITTED', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE operation_id = ? AND state IN ('TARGET_APPLY_INTENT', 'PLAYER_APPLIED')";

                try (PreparedStatement ps = connection.prepareStatement(updateOpSql)) {
                    ps.setString(1, operationId.toString());
                    int rows = ps.executeUpdate();
                    if (rows != 1) {
                        rollbackTransaction(connection);
                        return Result.err("FAILED_OPERATION_STATE_UPDATE");
                    }
                }
                if (outboxEvent != null) {
                    com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(connection, outboxEvent);
                }

                commitTransaction(connection);
                Optional<ProfileSwitchOperation> op = findOperation(connection, operationId);
                if (op.isPresent() && op.get() instanceof ProfileSwitchOperation.Committed c) {
                    return Result.ok(c);
                }
                return Result.err("COMMITTED_RECORD_NOT_FOUND");
            } catch (Exception e) {
                rollbackTransaction(connection);
                throw new StorageException("Failed to commit profile switch " + operationId, e);
            } finally {
                resetAutoCommitQuietly(connection, previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new StorageException("Database error during profile switch commit", e);
        }
    }

    @Override
    public Result<Unit, String> abortSwitch(UUID operationId, PlayerUuid playerId, String failureReason) {

        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(failureReason, "failureReason");

        try (Connection connection = database.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            beginTransaction(connection);
            try {
                // 1. Clear active_switch_operation_id from player_accounts
                String clearCasSql = "UPDATE player_accounts "
                        + "SET active_switch_operation_id = NULL, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE player_uuid = ? AND active_switch_operation_id = ?";

                try (PreparedStatement ps = connection.prepareStatement(clearCasSql)) {
                    ps.setString(1, playerId.toString());
                    ps.setString(2, operationId.toString());
                    ps.executeUpdate();
                }

                // 2. Mark profile_switch_operations FAILED
                String updateOpSql = "UPDATE profile_switch_operations "
                        + "SET state = 'FAILED', failure_reason = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE operation_id = ? AND state NOT IN ('COMMITTED', 'FAILED')";

                try (PreparedStatement ps = connection.prepareStatement(updateOpSql)) {
                    ps.setString(1, failureReason);
                    ps.setString(2, operationId.toString());
                    ps.executeUpdate();
                }

                commitTransaction(connection);
                return Result.ok(Unit.INSTANCE);
            } catch (Exception e) {
                rollbackTransaction(connection);
                throw new StorageException("Failed to abort profile switch " + operationId, e);
            } finally {
                resetAutoCommitQuietly(connection, previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new StorageException("Database error during profile switch abort", e);
        }
    }

    @Override
    public Optional<ProfileSwitchOperation> findOperation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection connection = database.connection()) {
            return findOperation(connection, operationId);
        } catch (SQLException e) {
            throw new StorageException("Failed to find profile switch operation " + operationId, e);
        }
    }

    private static Optional<ProfileSwitchOperation> findOperation(Connection connection, UUID operationId)
            throws SQLException {
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

    @Override
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
}
