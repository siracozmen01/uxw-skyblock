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
import com.uxplima.uxmlib.storage.sql.StatementBinder;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
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
    public static final int ACTIVE_LEASE_SECONDS = 15;

    /** Bounded handoff readiness and transfer window duration in seconds. */
    public static final int HANDOFF_LEASE_SECONDS = 30;

    private final Database database;
    private final Dialect dialect;

    private final String renewSql;
    private final String drainSql;
    private final String prepareHandoffSql;
    private final String plannedAcquireSql;
    private final String failureTakeoverSql;

    public PlayerSessionAuthorityAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();

        validateDialect(this.dialect);

        String activeLeaseExpr = dbNowPlus(dialect, ACTIVE_LEASE_SECONDS);
        String handoffLeaseExpr = dbNowPlus(dialect, HANDOFF_LEASE_SECONDS);

        this.renewSql = buildRenewSql(activeLeaseExpr);
        this.drainSql = buildDrainSql();
        this.prepareHandoffSql = buildPrepareHandoffSql(handoffLeaseExpr);
        this.plannedAcquireSql = buildPlannedAcquireSql(activeLeaseExpr);
        this.failureTakeoverSql = buildFailureTakeoverSql(activeLeaseExpr);
    }

    private static void validateDialect(Dialect dialect) {
        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock player session authority supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
    }

    private static String dbNowPlus(Dialect dialect, int seconds) {
        return switch (dialect) {
            case SQLITE -> "DATETIME('now', '+" + seconds + " seconds')";
            case MYSQL -> "CURRENT_TIMESTAMP + INTERVAL " + seconds + " SECOND";
            case POSTGRES -> "CURRENT_TIMESTAMP + INTERVAL '" + seconds + " seconds'";
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        };
    }

    private static String buildRenewSql(String leaseExpr) {
        return "UPDATE player_sessions "
                + "SET lease_expires_at = " + leaseExpr + ", "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ? "
                + "AND authoritative_node = ? "
                + "AND session_epoch = ? "
                + "AND state = 'ACTIVE' "
                + "AND lease_expires_at >= CURRENT_TIMESTAMP";
    }

    private static String buildDrainSql() {
        return "UPDATE player_sessions "
                + "SET state = 'DRAINING', "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ? "
                + "AND authoritative_node = ? "
                + "AND session_epoch = ? "
                + "AND state = 'ACTIVE' "
                + "AND lease_expires_at >= CURRENT_TIMESTAMP";
    }

    private static String buildPrepareHandoffSql(String handoffLeaseExpr) {
        return "UPDATE player_sessions "
                + "SET state = 'HANDOFF_READY', "
                + "handoff_id = ?, "
                + "handoff_target_node = ?, "
                + "handoff_expires_at = " + handoffLeaseExpr + ", "
                + "lease_expires_at = " + handoffLeaseExpr + ", "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ? "
                + "AND authoritative_node = ? "
                + "AND session_epoch = ? "
                + "AND state = 'DRAINING' "
                + "AND lease_expires_at >= CURRENT_TIMESTAMP";
    }

    private static String buildPlannedAcquireSql(String activeLeaseExpr) {
        return "UPDATE player_sessions "
                + "SET authoritative_node = ?, "
                + "session_epoch = session_epoch + 1, "
                + "state = 'ACTIVE', "
                + "handoff_id = NULL, "
                + "handoff_target_node = NULL, "
                + "handoff_expires_at = NULL, "
                + "lease_expires_at = " + activeLeaseExpr + ", "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ? "
                + "AND state = 'HANDOFF_READY' "
                + "AND handoff_id = ? "
                + "AND handoff_target_node = ? "
                + "AND authoritative_node = ? "
                + "AND session_epoch = ? "
                + "AND handoff_expires_at >= CURRENT_TIMESTAMP";
    }

    private static String buildFailureTakeoverSql(String activeLeaseExpr) {
        return "UPDATE player_sessions "
                + "SET authoritative_node = ?, "
                + "session_epoch = session_epoch + 1, "
                + "state = 'RECOVERING', "
                + "handoff_id = NULL, "
                + "handoff_target_node = NULL, "
                + "handoff_expires_at = NULL, "
                + "lease_expires_at = " + activeLeaseExpr + ", "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ? "
                + "AND session_epoch = ? "
                + "AND lease_expires_at < CURRENT_TIMESTAMP";
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
                // 1. Ensure player_accounts row exists
                ProfileId activeProfile = defaultProfileId;
                String checkAccountSql = "SELECT active_profile_id FROM player_accounts WHERE player_uuid = ?";
                boolean accountExists = false;
                try (PreparedStatement ps = conn.prepareStatement(checkAccountSql)) {
                    ps.setString(1, playerUuid.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            accountExists = true;
                            String existingProf = rs.getString("active_profile_id");
                            if (existingProf != null && !existingProf.isBlank()) {
                                activeProfile = new ProfileId(UUID.fromString(existingProf));
                            }
                        }
                    }
                }

                if (!accountExists) {
                    String insertAccSql = "INSERT INTO player_accounts (player_uuid) VALUES (?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertAccSql)) {
                        ps.setString(1, playerUuid.value().toString());
                        ps.executeUpdate();
                    }
                }

                // 2. Ensure player_profiles row exists for activeProfile
                String checkProfileSql = "SELECT 1 FROM player_profiles WHERE profile_id = ?";
                boolean profileExists = false;
                try (PreparedStatement ps = conn.prepareStatement(checkProfileSql)) {
                    ps.setString(1, activeProfile.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            profileExists = true;
                        }
                    }
                }

                if (!profileExists) {
                    String insertProfSql =
                            "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')";
                    try (PreparedStatement ps = conn.prepareStatement(insertProfSql)) {
                        ps.setString(1, activeProfile.value().toString());
                        ps.setString(2, playerUuid.value().toString());
                        ps.executeUpdate();
                    }
                }

                // Ensure active_profile_id in player_accounts is set
                String updateAccProfSql =
                        "UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ? AND (active_profile_id IS NULL OR active_profile_id != ?)";
                try (PreparedStatement ps = conn.prepareStatement(updateAccProfSql)) {
                    ps.setString(1, activeProfile.value().toString());
                    ps.setString(2, playerUuid.value().toString());
                    ps.setString(3, activeProfile.value().toString());
                    ps.executeUpdate();
                }

                // 3. Ensure profile_inventories row exists for activeProfile
                String checkInvSql = "SELECT 1 FROM profile_inventories WHERE profile_id = ?";
                boolean invExists = false;
                try (PreparedStatement ps = conn.prepareStatement(checkInvSql)) {
                    ps.setString(1, activeProfile.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            invExists = true;
                        }
                    }
                }

                if (!invExists) {
                    String insertInvSql =
                            "INSERT INTO profile_inventories (profile_id, profile_inventory_version, inventory_nbt, enderchest_nbt, experience_points, health, food_level, saturation, gamemode, flight_allowed) "
                                    + "VALUES (?, 1, ?, ?, 0, 20.0, 20, 5.0, 'SURVIVAL', ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertInvSql)) {
                        ps.setString(1, activeProfile.value().toString());
                        ps.setBytes(2, new byte[0]);
                        ps.setBytes(3, new byte[0]);
                        ps.setBoolean(4, false);
                        ps.executeUpdate();
                    }
                }

                // 4. Ensure or acquire player_sessions row
                String checkSessionSql =
                        "SELECT authoritative_node, session_epoch, state, lease_expires_at FROM player_sessions WHERE player_uuid = ?";
                boolean sessionExists = false;
                String existingNode = null;
                long existingEpoch = 1L;
                Timestamp leaseExpiresAt = null;

                try (PreparedStatement ps = conn.prepareStatement(checkSessionSql)) {
                    ps.setString(1, playerUuid.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sessionExists = true;
                            existingNode = rs.getString("authoritative_node");
                            existingEpoch = rs.getLong("session_epoch");
                            leaseExpiresAt = rs.getTimestamp("lease_expires_at");
                        }
                    }
                }

                String activeLeaseExpr = dbNowPlus(dialect, ACTIVE_LEASE_SECONDS);

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
                    return SessionAuthorityOutcome.success(1L);
                }

                // Session already exists
                if (currentNode.value().equals(existingNode)) {
                    // Continuing or re-connecting on same node
                    String renewSameNodeSql =
                            "UPDATE player_sessions SET active_profile_id = ?, state = 'ACTIVE', lease_expires_at = "
                                    + activeLeaseExpr
                                    + ", updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ? AND authoritative_node = ? AND session_epoch = ?";
                    int updated;
                    try (PreparedStatement ps = conn.prepareStatement(renewSameNodeSql)) {
                        ps.setString(1, activeProfile.value().toString());
                        ps.setString(2, playerUuid.value().toString());
                        ps.setString(3, currentNode.value());
                        ps.setLong(4, existingEpoch);
                        updated = ps.executeUpdate();
                    }
                    commitTx(conn);
                    return updated == 1
                            ? SessionAuthorityOutcome.success(existingEpoch)
                            : SessionAuthorityOutcome.rejected();
                }

                // Different node: check if prior lease has expired
                Instant now = Instant.now();
                if (leaseExpiresAt != null && leaseExpiresAt.toInstant().isBefore(now)) {
                    // Prior lease expired: perform failure takeover
                    long newEpoch = existingEpoch + 1;
                    String takeoverSql =
                            "UPDATE player_sessions SET authoritative_node = ?, session_epoch = ?, state = 'ACTIVE', "
                                    + "handoff_id = NULL, handoff_target_node = NULL, handoff_expires_at = NULL, lease_expires_at = "
                                    + activeLeaseExpr
                                    + ", updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ? AND session_epoch = ? AND lease_expires_at < CURRENT_TIMESTAMP";
                    int affected;
                    try (PreparedStatement ps = conn.prepareStatement(takeoverSql)) {
                        ps.setString(1, currentNode.value());
                        ps.setLong(2, newEpoch);
                        ps.setString(3, playerUuid.value().toString());
                        ps.setLong(4, existingEpoch);
                        affected = ps.executeUpdate();
                    }
                    commitTx(conn);
                    return affected == 1
                            ? SessionAuthorityOutcome.success(newEpoch)
                            : SessionAuthorityOutcome.rejected();
                }

                // Prior lease is still active on another node! Reject to prevent split-brain.
                commitTx(conn);
                return SessionAuthorityOutcome.rejected();
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

        int affected = executeUpdate(renewSql, statement -> {
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

        int affected = executeUpdate(drainSql, statement -> {
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

        int affected = executeUpdate(prepareHandoffSql, statement -> {
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

        int affected = executeUpdate(plannedAcquireSql, statement -> {
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

        int affected = executeUpdate(failureTakeoverSql, statement -> {
            statement.setString(1, destinationNode.value());
            statement.setString(2, playerUuid.value().toString());
            statement.setLong(3, expectedEpoch);
        });

        long newEpoch = expectedEpoch + 1;
        return affected == 1 ? SessionAuthorityOutcome.success(newEpoch) : SessionAuthorityOutcome.rejected();
    }

    private int executeUpdate(String sql, StatementBinder binder) {
        if (dialect == Dialect.SQLITE) {
            return executeSqliteWriter(sql, binder);
        }
        return executeServerTransaction(sql, binder);
    }

    private int executeSqliteWriter(String sql, StatementBinder binder) {
        try (Connection conn = database.connection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("BEGIN IMMEDIATE");
            }
            try {
                int affected;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    binder.bind(ps);
                    affected = ps.executeUpdate();
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("COMMIT");
                }
                return affected;
            } catch (Exception e) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ROLLBACK");
                } catch (Exception rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new SessionPersistenceException("Failed to execute SQLite immediate write: " + sql, e);
        }
    }

    private int executeServerTransaction(String sql, StatementBinder binder) {
        try {
            Integer affected = database.transaction(tx -> tx.update(sql, binder));
            return affected != null ? affected : 0;
        } catch (Exception e) {
            throw new SessionPersistenceException("Failed to execute server transaction update: " + sql, e);
        }
    }
}
