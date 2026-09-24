package com.uxplima.uxmskyblock.persistence.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionLease;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;

/**
 * Canonical SQL persistence adapter implementing {@link PlayerSessionAuthorityPort}.
 *
 * <p>Enforces cluster-wide single-writer guarantees via DB clock predicates, session epoch fencing,
 * affected-row assertions, and database-level synchronization:
 * <ul>
 *   <li>SQLite: writer serialization via {@code BEGIN IMMEDIATE} transactions.</li>
 *   <li>MariaDB / PostgreSQL: server transaction row-level locking via atomic conditional updates.</li>
 * </ul>
 */
public final class PlayerSessionAuthorityAdapter implements PlayerSessionAuthorityPort {

    /** Standard active heartbeat lease window duration in seconds. */
    public static final int ACTIVE_LEASE_SECONDS = (int) SessionLease.ACTIVE.toSeconds();

    /** Bounded handoff readiness and transfer window duration in seconds. */
    public static final int HANDOFF_LEASE_SECONDS = (int) SessionLease.HANDOFF.toSeconds();

    private final Database database;
    private final Dialect dialect;

    private final SessionAuthoritySql sql;
    private final SessionWriter writer;

    public PlayerSessionAuthorityAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();

        this.sql = SessionAuthoritySql.forDialect(this.dialect);
        this.writer = new SessionWriter(database);
    }

    @Override
    public Optional<PlayerSessionRecord> findSession(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        String sql = "SELECT player_uuid, active_profile_id, authoritative_node, session_epoch, state, "
                + "lease_expires_at, last_durable_inventory_version, handoff_id, handoff_target_node, handoff_expires_at "
                + "FROM player_sessions WHERE player_uuid = ?";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                PlayerUuid pid = new PlayerUuid(UUID.fromString(rs.getString("player_uuid")));
                ProfileId activeProf = new ProfileId(UUID.fromString(rs.getString("active_profile_id")));
                ServerNodeId node = new ServerNodeId(rs.getString("authoritative_node"));
                long epoch = rs.getLong("session_epoch");
                SessionState state = SessionState.valueOf(rs.getString("state"));
                Timestamp leaseTs = rs.getTimestamp("lease_expires_at");
                Instant leaseExpiresAt = leaseTs != null ? leaseTs.toInstant() : Instant.EPOCH;
                long lastDurableVer = rs.getLong("last_durable_inventory_version");
                String handoffId = rs.getString("handoff_id");
                String targetNodeStr = rs.getString("handoff_target_node");
                ServerNodeId targetNode = targetNodeStr != null ? new ServerNodeId(targetNodeStr) : null;
                Timestamp handoffTs = rs.getTimestamp("handoff_expires_at");
                Instant handoffExpiresAt = handoffTs != null ? handoffTs.toInstant() : null;

                return Optional.of(new PlayerSessionRecord(
                        pid,
                        activeProf,
                        node,
                        epoch,
                        state,
                        leaseExpiresAt,
                        lastDurableVer,
                        handoffId,
                        targetNode,
                        handoffExpiresAt));
            }
        } catch (SQLException e) {
            throw new SessionPersistenceException("Failed to query player session for " + playerUuid, e);
        }
    }

    @Override
    public SessionAuthorityOutcome ensureSession(
            PlayerUuid playerUuid, ProfileId defaultProfileId, ServerNodeId currentNode) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(defaultProfileId, "defaultProfileId");
        Objects.requireNonNull(currentNode, "currentNode");

        try (Connection conn = database.connection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            if (dialect == Dialect.SQLITE) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("BEGIN IMMEDIATE");
                }
            } else {
                conn.setAutoCommit(false);
            }
            try {
                // 1 to 3. The account, the profile it plays and that profile's inventory row exist.
                ProfileId activeProfile = PlayerRows.ensure(conn, playerUuid, defaultProfileId);

                // 4. Ensure or acquire player_sessions row with FOR UPDATE on server dialects
                String checkSessionSql =
                        "SELECT authoritative_node, session_epoch, state, lease_expires_at FROM player_sessions WHERE player_uuid = ?"
                                + (dialect != Dialect.SQLITE ? " FOR UPDATE" : "");
                boolean sessionExists = false;
                String existingNode = null;
                long existingEpoch = 1L;
                String existingState = null;

                try (PreparedStatement ps = conn.prepareStatement(checkSessionSql)) {
                    ps.setString(1, playerUuid.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sessionExists = true;
                            existingNode = rs.getString("authoritative_node");
                            existingEpoch = rs.getLong("session_epoch");
                            existingState = rs.getString("state");
                        }
                    }
                }

                String activeLeaseExpr = sql.activeLeaseExpr();

                if (!sessionExists) {
                    String insertSessSql =
                            "INSERT INTO player_sessions (player_uuid, active_profile_id, authoritative_node, session_epoch, state, lease_expires_at, last_durable_inventory_version) "
                                    + "VALUES (?, ?, ?, 1, 'ACTIVE', " + activeLeaseExpr + ", 1)";
                    try (PreparedStatement ps = conn.prepareStatement(insertSessSql)) {
                        ps.setString(1, playerUuid.value().toString());
                        ps.setString(2, activeProfile.value().toString());
                        ps.setString(3, currentNode.value());
                        ps.executeUpdate();
                    }
                    commitTx(conn);
                    return SessionAuthorityOutcome.success(1L, false);
                }

                // Session row already exists: evaluate state machine
                // A. Clean login from OFFLINE state
                if ("OFFLINE".equalsIgnoreCase(existingState)) {
                    long newEpoch = existingEpoch + 1;
                    String offlineClaimSql =
                            "UPDATE player_sessions SET active_profile_id = ?, authoritative_node = ?, session_epoch = ?, state = 'ACTIVE', "
                                    + "handoff_id = NULL, handoff_target_node = NULL, handoff_expires_at = NULL, lease_expires_at = "
                                    + activeLeaseExpr
                                    + ", updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ? AND session_epoch = ? AND state = 'OFFLINE'";
                    int affected;
                    try (PreparedStatement ps = conn.prepareStatement(offlineClaimSql)) {
                        ps.setString(1, activeProfile.value().toString());
                        ps.setString(2, currentNode.value());
                        ps.setLong(3, newEpoch);
                        ps.setString(4, playerUuid.value().toString());
                        ps.setLong(5, existingEpoch);
                        affected = ps.executeUpdate();
                    }
                    commitTx(conn);
                    return affected == 1
                            ? SessionAuthorityOutcome.success(newEpoch, false)
                            : SessionAuthorityOutcome.rejected();
                }

                // B. Same node continuing or reconnecting
                if (currentNode.value().equals(existingNode)) {
                    if ("ACTIVE".equalsIgnoreCase(existingState)) {
                        // Reconnecting on same node with active state: attempt renewal with lease_expires_at >=
                        // CURRENT_TIMESTAMP
                        String renewSameNodeSql =
                                "UPDATE player_sessions SET active_profile_id = ?, state = 'ACTIVE', lease_expires_at = "
                                        + activeLeaseExpr
                                        + ", updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ? AND authoritative_node = ? AND session_epoch = ? AND state = 'ACTIVE' AND lease_expires_at >= CURRENT_TIMESTAMP";
                        int updated;
                        try (PreparedStatement ps = conn.prepareStatement(renewSameNodeSql)) {
                            ps.setString(1, activeProfile.value().toString());
                            ps.setString(2, playerUuid.value().toString());
                            ps.setString(3, currentNode.value());
                            ps.setLong(4, existingEpoch);
                            updated = ps.executeUpdate();
                        }
                        if (updated == 1) {
                            commitTx(conn);
                            return SessionAuthorityOutcome.success(existingEpoch, false);
                        }
                        // If updated == 0, lease expired on same node (e.g. crash/restart)!
                        // Fall through to failure takeover into RECOVERING below
                    } else if ("RECOVERING".equalsIgnoreCase(existingState)) {
                        // Already recovering on same node: renew lease while keeping state = 'RECOVERING'
                        String renewRecoveringSql =
                                "UPDATE player_sessions SET active_profile_id = ?, lease_expires_at = "
                                        + activeLeaseExpr
                                        + ", updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ? AND authoritative_node = ? AND session_epoch = ? AND state = 'RECOVERING' AND lease_expires_at >= CURRENT_TIMESTAMP";
                        int updated;
                        try (PreparedStatement ps = conn.prepareStatement(renewRecoveringSql)) {
                            ps.setString(1, activeProfile.value().toString());
                            ps.setString(2, playerUuid.value().toString());
                            ps.setString(3, currentNode.value());
                            ps.setLong(4, existingEpoch);
                            updated = ps.executeUpdate();
                        }
                        if (updated == 1) {
                            commitTx(conn);
                            return SessionAuthorityOutcome.success(existingEpoch, true);
                        }
                        // If expired, fall through to failure takeover
                    } else if ("DRAINING".equalsIgnoreCase(existingState)
                            || "HANDOFF_READY".equalsIgnoreCase(existingState)) {
                        // Session is undergoing handoff. If lease not expired, reject reconnect to avoid split-brain
                        // with target node
                        // If expired, fall through to failure takeover
                    }
                }

                // C. Failure takeover via DB clock (different node OR expired same node)
                // Evaluated strictly via database clock in SQL: lease_expires_at < CURRENT_TIMESTAMP
                // Transition state MUST be 'RECOVERING'!
                long newEpoch = existingEpoch + 1;
                String takeoverSql =
                        "UPDATE player_sessions SET active_profile_id = ?, authoritative_node = ?, session_epoch = ?, state = 'RECOVERING', "
                                + "handoff_id = NULL, handoff_target_node = NULL, handoff_expires_at = NULL, lease_expires_at = "
                                + activeLeaseExpr
                                + ", updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ? AND session_epoch = ? AND lease_expires_at < CURRENT_TIMESTAMP";
                int affected;
                try (PreparedStatement ps = conn.prepareStatement(takeoverSql)) {
                    ps.setString(1, activeProfile.value().toString());
                    ps.setString(2, currentNode.value());
                    ps.setLong(3, newEpoch);
                    ps.setString(4, playerUuid.value().toString());
                    ps.setLong(5, existingEpoch);
                    affected = ps.executeUpdate();
                }
                commitTx(conn);
                return affected == 1
                        ? SessionAuthorityOutcome.success(newEpoch, true)
                        : SessionAuthorityOutcome.rejected();
            } catch (Exception e) {
                rollbackTx(conn);
                throw new SessionPersistenceException("Failed to ensure player session: " + playerUuid, e);
            } finally {
                if (dialect != Dialect.SQLITE) {
                    conn.setAutoCommit(originalAutoCommit);
                }
            }
        } catch (SQLException e) {
            throw new SessionPersistenceException("Database error ensuring player session: " + playerUuid, e);
        }
    }

    private void commitTx(Connection conn) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("COMMIT");
            }
        } else {
            conn.commit();
        }
    }

    @SuppressWarnings("EmptyCatch")
    private void rollbackTx(Connection conn) {
        try {
            if (dialect == Dialect.SQLITE) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ROLLBACK");
                }
            } else {
                conn.rollback();
            }
        } catch (Exception ignored) {
            // Best-effort rollback
        }
    }

    @Override
    public SessionAuthorityOutcome renew(PlayerUuid playerUuid, ServerNodeId currentNode, long currentEpoch) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(currentNode, "currentNode");

        int affected = writer.executeUpdate(sql.renew(), statement -> {
            statement.setString(1, playerUuid.value().toString());
            statement.setString(2, currentNode.value());
            statement.setLong(3, currentEpoch);
        });

        return affected == 1 ? SessionAuthorityOutcome.success(currentEpoch) : SessionAuthorityOutcome.rejected();
    }

    @Override
    public SessionAuthorityOutcome drain(PlayerUuid playerUuid, ServerNodeId currentNode, long currentEpoch) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(currentNode, "currentNode");

        int affected = writer.executeUpdate(sql.drain(), statement -> {
            statement.setString(1, playerUuid.value().toString());
            statement.setString(2, currentNode.value());
            statement.setLong(3, currentEpoch);
        });

        return affected == 1 ? SessionAuthorityOutcome.success(currentEpoch) : SessionAuthorityOutcome.rejected();
    }

    @Override
    public SessionAuthorityOutcome prepareHandoff(
            PlayerUuid playerUuid,
            ServerNodeId currentNode,
            long currentEpoch,
            String handoffId,
            ServerNodeId targetNode) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(currentNode, "currentNode");
        Objects.requireNonNull(handoffId, "handoffId");
        Objects.requireNonNull(targetNode, "targetNode");

        int affected = writer.executeUpdate(sql.prepareHandoff(), statement -> {
            statement.setString(1, handoffId);
            statement.setString(2, targetNode.value());
            statement.setString(3, playerUuid.value().toString());
            statement.setString(4, currentNode.value());
            statement.setLong(5, currentEpoch);
        });

        return affected == 1 ? SessionAuthorityOutcome.success(currentEpoch) : SessionAuthorityOutcome.rejected();
    }

    @Override
    public SessionAuthorityOutcome plannedAcquire(
            PlayerUuid playerUuid,
            ServerNodeId expectedSourceNode,
            long expectedSourceEpoch,
            String expectedHandoffId,
            ServerNodeId destinationNode) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(expectedSourceNode, "expectedSourceNode");
        Objects.requireNonNull(expectedHandoffId, "expectedHandoffId");
        Objects.requireNonNull(destinationNode, "destinationNode");

        int affected = writer.executeUpdate(sql.plannedAcquire(), statement -> {
            statement.setString(1, destinationNode.value());
            statement.setString(2, playerUuid.value().toString());
            statement.setString(3, expectedHandoffId);
            statement.setString(4, destinationNode.value());
            statement.setString(5, expectedSourceNode.value());
            statement.setLong(6, expectedSourceEpoch);
        });

        long newEpoch = expectedSourceEpoch + 1;
        return affected == 1 ? SessionAuthorityOutcome.success(newEpoch) : SessionAuthorityOutcome.rejected();
    }

    @Override
    public SessionAuthorityOutcome failureTakeover(
            PlayerUuid playerUuid, long expectedEpoch, ServerNodeId destinationNode) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(destinationNode, "destinationNode");

        int affected = writer.executeUpdate(sql.failureTakeover(), statement -> {
            statement.setString(1, destinationNode.value());
            statement.setString(2, playerUuid.value().toString());
            statement.setLong(3, expectedEpoch);
        });

        long newEpoch = expectedEpoch + 1;
        return affected == 1 ? SessionAuthorityOutcome.success(newEpoch, true) : SessionAuthorityOutcome.rejected();
    }

    @Override
    public SessionAuthorityOutcome markRecoveredActive(
            PlayerUuid playerUuid, ServerNodeId currentNode, long currentEpoch) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(currentNode, "currentNode");

        int affected = writer.executeUpdate(sql.markRecoveredActive(), statement -> {
            statement.setString(1, playerUuid.value().toString());
            statement.setString(2, currentNode.value());
            statement.setLong(3, currentEpoch);
        });

        return affected == 1
                ? SessionAuthorityOutcome.success(currentEpoch, false)
                : SessionAuthorityOutcome.rejected();
    }

    @Override
    public SessionAuthorityOutcome releaseToOffline(
            PlayerUuid playerUuid, ServerNodeId currentNode, long currentEpoch) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(currentNode, "currentNode");

        int affected = writer.executeUpdate(sql.releaseToOffline(), statement -> {
            statement.setString(1, playerUuid.value().toString());
            statement.setString(2, currentNode.value());
            statement.setLong(3, currentEpoch);
        });

        return affected == 1
                ? SessionAuthorityOutcome.success(currentEpoch, false)
                : SessionAuthorityOutcome.rejected();
    }
}
