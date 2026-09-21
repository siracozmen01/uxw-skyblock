package com.uxplima.uxmskyblock.persistence.session;

import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Every statement the session authority runs, written once for the dialect in use.
 *
 * <p>Each one is a conditional update, and its {@code WHERE} clause is the whole guarantee: the
 * node that holds the session, the epoch it holds it at, the state it must be in, and a lease the
 * database clock has not yet passed. A row count of one means this node won. Anything else means
 * another node did, and the caller is fenced.
 *
 * <p>The lease expiry is computed by the database, never by the caller. A server whose clock has
 * drifted must not be able to extend its own hold on a player.
 */
final class SessionAuthoritySql {

    private final String activeLeaseExpr;
    private final String renew;
    private final String drain;
    private final String prepareHandoff;
    private final String plannedAcquire;
    private final String failureTakeover;
    private final String markRecoveredActive;
    private final String releaseToOffline;

    private SessionAuthoritySql(Dialect dialect) {
        String activeLeaseExpr = dbNowPlus(dialect, PlayerSessionAuthorityAdapter.ACTIVE_LEASE_SECONDS);
        String handoffLeaseExpr = dbNowPlus(dialect, PlayerSessionAuthorityAdapter.HANDOFF_LEASE_SECONDS);

        this.activeLeaseExpr = activeLeaseExpr;
        this.renew = buildRenewSql(activeLeaseExpr);
        this.drain = buildDrainSql();
        this.prepareHandoff = buildPrepareHandoffSql(handoffLeaseExpr);
        this.plannedAcquire = buildPlannedAcquireSql(activeLeaseExpr);
        this.failureTakeover = buildFailureTakeoverSql(activeLeaseExpr);
        this.markRecoveredActive = buildMarkRecoveredActiveSql(activeLeaseExpr);
        this.releaseToOffline = buildReleaseToOfflineSql();
    }

    /** Builds the statement set for {@code dialect}, refusing a dialect this adapter cannot serve. */
    static SessionAuthoritySql forDialect(Dialect dialect) {
        Objects.requireNonNull(dialect, "dialect");

        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock player session authority supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
        return new SessionAuthoritySql(dialect);
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

    private static String buildMarkRecoveredActiveSql(String activeLeaseExpr) {
        return "UPDATE player_sessions "
                + "SET state = 'ACTIVE', "
                + "lease_expires_at = " + activeLeaseExpr + ", "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ? "
                + "AND authoritative_node = ? "
                + "AND session_epoch = ? "
                + "AND state = 'RECOVERING' "
                + "AND lease_expires_at >= CURRENT_TIMESTAMP";
    }

    private static String buildReleaseToOfflineSql() {
        return "UPDATE player_sessions "
                + "SET state = 'OFFLINE', "
                + "handoff_id = NULL, "
                + "handoff_target_node = NULL, "
                + "handoff_expires_at = NULL, "
                + "lease_expires_at = CURRENT_TIMESTAMP, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ? "
                + "AND authoritative_node = ? "
                + "AND session_epoch = ? "
                + "AND state = 'DRAINING'";
    }

    /**
     * The SQL expression for a fresh active lease, for the one caller that writes its own statement.
     *
     * <p>{@code ensureSession} inserts a session that does not exist yet, so it has no conditional
     * update to guard and builds its own insert. It still takes its expiry from the database clock.
     */
    String activeLeaseExpr() {
        return activeLeaseExpr;
    }

    String renew() {
        return renew;
    }

    String drain() {
        return drain;
    }

    String prepareHandoff() {
        return prepareHandoff;
    }

    String plannedAcquire() {
        return plannedAcquire;
    }

    String failureTakeover() {
        return failureTakeover;
    }

    String markRecoveredActive() {
        return markRecoveredActive;
    }

    String releaseToOffline() {
        return releaseToOffline;
    }
}
