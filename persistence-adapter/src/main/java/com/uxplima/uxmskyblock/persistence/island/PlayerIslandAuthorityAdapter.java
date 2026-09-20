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
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("""
                        SELECT authoritative_node, authority_epoch, lease_expires_at, last_heartbeat_at
                        FROM island_authorities WHERE island_id = ?
                        """)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ServerNodeId node = new ServerNodeId(rs.getString("authoritative_node"));
                long epoch = rs.getLong("authority_epoch");
                Instant lease = rs.getTimestamp("lease_expires_at").toInstant();
                Instant heartbeat = rs.getTimestamp("last_heartbeat_at").toInstant();
                return Optional.of(new IslandAuthorityRecord(islandId, node, epoch, lease, heartbeat));
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to query authority for island: " + islandId, e);
        }
    }
}
