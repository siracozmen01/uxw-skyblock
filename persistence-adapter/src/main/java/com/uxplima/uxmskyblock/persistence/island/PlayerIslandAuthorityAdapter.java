package com.uxplima.uxmskyblock.persistence.island;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Production SQL persistence adapter implementing {@link IslandAuthorityPort}.
 */
public final class PlayerIslandAuthorityAdapter implements IslandAuthorityPort {

    private final Database database;
    private final Dialect dialect;

    public PlayerIslandAuthorityAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        IslandSqlSupport.validateDialect(this.dialect);
    }

    @Override
    public IslandAuthorityOutcome acquireAuthority(IslandId islandId, ServerNodeId nodeId, int leaseSeconds) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(nodeId, "nodeId");

        String sql = "INSERT INTO island_authorities ("
                + "island_id, authoritative_node, authority_epoch, lease_expires_at, last_heartbeat_at, updated_at"
                + ") VALUES (?, ?, 1, " + IslandSqlSupport.dbNowPlus(dialect, leaseSeconds)
                + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            IslandSqlSupport.beginTransaction(conn, dialect);
            try {
                int affected;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, islandId.value().toString());
                    stmt.setString(2, nodeId.value());
                    affected = stmt.executeUpdate();
                }
                IslandSqlSupport.commitTransaction(conn, dialect);
                return affected == 1 ? IslandAuthorityOutcome.success(1L) : IslandAuthorityOutcome.rejected();
            } catch (SQLException e) {
                IslandSqlSupport.rollbackTransaction(conn, dialect);
                // Duplicate key / integrity constraint violation
                return IslandAuthorityOutcome.rejected();
            } finally {
                IslandSqlSupport.resetAutoCommitQuietly(conn, dialect, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to acquire authority on island: " + islandId, e);
        }
    }

    @Override
    public IslandAuthorityOutcome renewAuthority(
            IslandId islandId, ServerNodeId nodeId, long expectedEpoch, int leaseSeconds) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(nodeId, "nodeId");

        String sql = "UPDATE island_authorities "
                + "SET lease_expires_at = " + IslandSqlSupport.dbNowPlus(dialect, leaseSeconds) + ", "
                + "last_heartbeat_at = CURRENT_TIMESTAMP, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE island_id = ? "
                + "AND authoritative_node = ? "
                + "AND authority_epoch = ? "
                + "AND lease_expires_at >= CURRENT_TIMESTAMP";

        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            IslandSqlSupport.beginTransaction(conn, dialect);
            try {
                int affected;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, islandId.value().toString());
                    stmt.setString(2, nodeId.value());
                    stmt.setLong(3, expectedEpoch);
                    affected = stmt.executeUpdate();
                }
                IslandSqlSupport.commitTransaction(conn, dialect);
                return affected == 1
                        ? IslandAuthorityOutcome.success(expectedEpoch)
                        : IslandAuthorityOutcome.rejected();
            } catch (SQLException e) {
                IslandSqlSupport.rollbackTransaction(conn, dialect);
                return IslandAuthorityOutcome.rejected();
            } finally {
                IslandSqlSupport.resetAutoCommitQuietly(conn, dialect, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to renew authority on island: " + islandId, e);
        }
    }

    @Override
    public IslandAuthoritySweep sweepAuthority(ServerNodeId nodeId, String worldName, int leaseSeconds) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(worldName, "worldName");
        if (leaseSeconds < 1) {
            throw new IllegalArgumentException("leaseSeconds must be >= 1: " + leaseSeconds);
        }

        String renewSql = "UPDATE island_authorities "
                + "SET lease_expires_at = " + IslandSqlSupport.dbNowPlus(dialect, leaseSeconds) + ", "
                + "last_heartbeat_at = CURRENT_TIMESTAMP, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE authoritative_node = ? "
                + "AND lease_expires_at >= CURRENT_TIMESTAMP "
                + "AND island_id IN (SELECT island_id FROM island_locations WHERE world_name = ?)";

        // Any node may take a lease that has run out, and the epoch moves so the old holder is
        // fenced off. The world is the bound: this node picks up islands it actually serves and
        // leaves another shard's islands to the node that serves them.
        String takeoverSql = "UPDATE island_authorities "
                + "SET authoritative_node = ?, "
                + "authority_epoch = authority_epoch + 1, "
                + "lease_expires_at = " + IslandSqlSupport.dbNowPlus(dialect, leaseSeconds) + ", "
                + "last_heartbeat_at = CURRENT_TIMESTAMP, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE lease_expires_at < CURRENT_TIMESTAMP "
                + "AND island_id IN (SELECT island_id FROM island_locations WHERE world_name = ?)";

        String acquireSql = "INSERT INTO island_authorities ("
                + "island_id, authoritative_node, authority_epoch, lease_expires_at, last_heartbeat_at, updated_at) "
                + "SELECT l.island_id, ?, 1, " + IslandSqlSupport.dbNowPlus(dialect, leaseSeconds)
                + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                + "FROM island_locations l "
                + "WHERE l.world_name = ? "
                + "AND NOT EXISTS (SELECT 1 FROM island_authorities a WHERE a.island_id = l.island_id)";

        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            IslandSqlSupport.beginTransaction(conn, dialect);
            try {
                int renewed;
                try (PreparedStatement stmt = conn.prepareStatement(renewSql)) {
                    stmt.setString(1, nodeId.value());
                    stmt.setString(2, worldName);
                    renewed = stmt.executeUpdate();
                }
                int takenOver;
                try (PreparedStatement stmt = conn.prepareStatement(takeoverSql)) {
                    stmt.setString(1, nodeId.value());
                    stmt.setString(2, worldName);
                    takenOver = stmt.executeUpdate();
                }
                int acquired;
                try (PreparedStatement stmt = conn.prepareStatement(acquireSql)) {
                    stmt.setString(1, nodeId.value());
                    stmt.setString(2, worldName);
                    acquired = stmt.executeUpdate();
                }
                IslandSqlSupport.commitTransaction(conn, dialect);
                return new IslandAuthoritySweep(renewed, takenOver, acquired);
            } catch (SQLException e) {
                IslandSqlSupport.rollbackTransaction(conn, dialect);
                throw new IslandPersistenceException("Failed to sweep island authority in world " + worldName, e);
            } finally {
                IslandSqlSupport.resetAutoCommitQuietly(conn, dialect, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to sweep island authority in world " + worldName, e);
        }
    }

    @Override
    public IslandAuthorityOutcome takeoverAuthority(
            IslandId islandId, ServerNodeId newNodeId, long expectedEpoch, int leaseSeconds) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(newNodeId, "newNodeId");

        String sql = "UPDATE island_authorities "
                + "SET authoritative_node = ?, "
                + "authority_epoch = authority_epoch + 1, "
                + "lease_expires_at = " + IslandSqlSupport.dbNowPlus(dialect, leaseSeconds) + ", "
                + "last_heartbeat_at = CURRENT_TIMESTAMP, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE island_id = ? "
                + "AND authority_epoch = ? "
                + "AND lease_expires_at < CURRENT_TIMESTAMP";

        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            IslandSqlSupport.beginTransaction(conn, dialect);
            try {
                int affected;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, newNodeId.value());
                    stmt.setString(2, islandId.value().toString());
                    stmt.setLong(3, expectedEpoch);
                    affected = stmt.executeUpdate();
                }
                IslandSqlSupport.commitTransaction(conn, dialect);
                long newEpoch = expectedEpoch + 1;
                return affected == 1 ? IslandAuthorityOutcome.success(newEpoch) : IslandAuthorityOutcome.rejected();
            } catch (SQLException e) {
                IslandSqlSupport.rollbackTransaction(conn, dialect);
                return IslandAuthorityOutcome.rejected();
            } finally {
                IslandSqlSupport.resetAutoCommitQuietly(conn, dialect, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to takeover authority on island: " + islandId, e);
        }
    }

    @Override
    public Optional<IslandAuthorityRecord> findAuthority(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");
        // The database's own now comes back with the row. Every lease is written by a SQL
        // expression off the database's clock, in the database's zone, and the caller compares what
        // comes back against Instant.now() on this machine. Those are two clocks: on a JVM two
        // hours ahead of the database every live lease read as two hours in the past, so the bank
        // refused every movement on an island from the moment it was made. Reading the lease as a
        // distance from the database's own now cancels the difference out, whatever either zone is.
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("""
                        SELECT authoritative_node, authority_epoch, lease_expires_at, last_heartbeat_at,
                               CURRENT_TIMESTAMP AS database_now
                        FROM island_authorities WHERE island_id = ?
                        """)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ServerNodeId node = new ServerNodeId(rs.getString("authoritative_node"));
                long epoch = rs.getLong("authority_epoch");
                Instant here = Instant.now();
                Instant databaseNow = rs.getTimestamp("database_now").toInstant();
                Instant lease = asLocalInstant(rs.getTimestamp("lease_expires_at"), databaseNow, here);
                Instant heartbeat = asLocalInstant(rs.getTimestamp("last_heartbeat_at"), databaseNow, here);
                return Optional.of(new IslandAuthorityRecord(islandId, node, epoch, lease, heartbeat));
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to query authority for island: " + islandId, e);
        }
    }

    /**
     * Reads a moment the database wrote as a moment on this machine.
     *
     * <p>Both timestamps come back through the same conversion, so whatever the driver and the two
     * zones do to them cancels. What survives is the distance between them, which is the only thing
     * a lease means.
     */
    private static Instant asLocalInstant(java.sql.Timestamp written, Instant databaseNow, Instant here) {
        return here.plus(java.time.Duration.between(databaseNow, written.toInstant()));
    }
}
