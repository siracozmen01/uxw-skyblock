package com.uxplima.uxmskyblock.persistence.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmlib.storage.sql.StatementBinder;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;

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
