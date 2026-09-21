package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.OptionalLong;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileHandoffFinalizationPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;

/**
 * Canonical SQL persistence adapter implementing {@link ProfileHandoffFinalizationPort}.
 *
 * <p>Durability Scope and Invariants:
 * <ul>
 *   <li>This adapter strictly implements the source-node final durable inventory persistence contract
 *       executed while session state is {@code DRAINING}.</li>
 *   <li>It is strictly NOT an {@code InventoryMutationJournal} replacement.</li>
 *   <li>It is strictly NOT valid for {@code IMMEDIATE}, economic, or value-sensitive item mutation execution.</li>
 *   <li>Immediate, economic, and value-sensitive mutations remain strictly owned by the {@code InventoryMutationJournal}
 *       protocol and must not be downgraded or routed through this finalization path.</li>
 * </ul>
 *
 * <p>Enforces the frozen transaction protocol:
 * <ul>
 *   <li>SQLite: writer serialization via {@code BEGIN IMMEDIATE} transactions.</li>
 *   <li>MariaDB / PostgreSQL: row lock serialization via {@code SELECT player_sessions ... FOR UPDATE}.</li>
 *   <li>Atomic authority check under the same transaction (node, epoch, DRAINING state, active profile binding, DB clock lease).</li>
 *   <li>OCC mutation on {@code profile_inventories} updating version = version + 1 (affectedRows == 1).</li>
 *   <li>Atomic update on {@code player_sessions.last_durable_inventory_version} to match the new version (affectedRows == 1).</li>
 *   <li>Asserts both affectedRows == 1; aborts/rolls back both on any authority or version mismatch.</li>
 * </ul>
 */
public final class PlayerProfileHandoffFinalizationAdapter implements ProfileHandoffFinalizationPort {

    private final Database database;
    private final Dialect dialect;

    private final String selectSessionAuthoritySql;
    private final String updateInventoryOccSql;
    private final String updateSessionLastDurableVersionSql;
    private final String selectLastDurableVersionSql;

    public PlayerProfileHandoffFinalizationAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        SupportedDialects.require(dialect, "inventory persistence");

        this.selectSessionAuthoritySql = buildSelectSessionAuthoritySql(this.dialect);
        this.updateInventoryOccSql = buildUpdateInventoryOccSql();
        this.updateSessionLastDurableVersionSql = buildUpdateSessionLastDurableVersionSql();
        this.selectLastDurableVersionSql = buildSelectLastDurableVersionSql();
    }

    private static String buildSelectSessionAuthoritySql(Dialect dialect) {
        String base = "SELECT active_profile_id, authoritative_node, session_epoch, state, "
                + "(CASE WHEN lease_expires_at >= CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS lease_valid "
                + "FROM player_sessions "
                + "WHERE player_uuid = ?";
        return dialect == Dialect.SQLITE ? base : base + " FOR UPDATE";
    }

    private static String buildUpdateInventoryOccSql() {
        return "UPDATE profile_inventories "
                + "SET profile_inventory_version = profile_inventory_version + 1, "
                + "inventory_nbt = ?, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE profile_id = ? "
                + "AND profile_inventory_version = ?";
    }

    private static String buildUpdateSessionLastDurableVersionSql() {
        return "UPDATE player_sessions "
                + "SET last_durable_inventory_version = ?, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ?";
    }

    private static String buildSelectLastDurableVersionSql() {
        return "SELECT last_durable_inventory_version FROM player_sessions WHERE player_uuid = ?";
    }

    @Override
    public ProfileInventoryMutationOutcome finalizeHandoffFlush(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            byte[] inventoryNbt) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(currentNode, "currentNode");
        Objects.requireNonNull(inventoryNbt, "inventoryNbt");

        if (dialect == Dialect.SQLITE) {
            return executeSqliteFinalization(
                    playerUuid, profileId, currentNode, expectedEpoch, expectedVersion, inventoryNbt);
        }
        return executeServerFinalization(
                playerUuid, profileId, currentNode, expectedEpoch, expectedVersion, inventoryNbt);
    }

    private ProfileInventoryMutationOutcome executeSqliteFinalization(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            byte[] inventoryNbt) {
        try (Connection conn = database.connection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("BEGIN IMMEDIATE");
            }
            try {
                boolean authorityValid = validateAuthority(conn, playerUuid, profileId, currentNode, expectedEpoch);
                if (!authorityValid) {
                    rollbackSqlite(conn);
                    return ProfileInventoryMutationOutcome.rejected();
                }

                int affectedInventory = updateOcc(conn, profileId, expectedVersion, inventoryNbt);
                if (affectedInventory != 1) {
                    rollbackSqlite(conn);
                    return ProfileInventoryMutationOutcome.rejected();
                }

                long newVersion = expectedVersion + 1;
                int affectedSession = updateSessionLastDurableVersion(conn, playerUuid, newVersion);
                if (affectedSession != 1) {
                    rollbackSqlite(conn);
                    return ProfileInventoryMutationOutcome.rejected();
                }

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("COMMIT");
                }
                return ProfileInventoryMutationOutcome.success(newVersion);
            } catch (Exception e) {
                rollbackSqlite(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed SQLite handoff finalization flush", e);
        }
    }

    private ProfileInventoryMutationOutcome executeServerFinalization(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            byte[] inventoryNbt) {
        try (Connection conn = database.connection()) {
            conn.setAutoCommit(false);
            try {
                boolean authorityValid = validateAuthority(conn, playerUuid, profileId, currentNode, expectedEpoch);
                if (!authorityValid) {
                    conn.rollback();
                    return ProfileInventoryMutationOutcome.rejected();
                }

                int affectedInventory = updateOcc(conn, profileId, expectedVersion, inventoryNbt);
                if (affectedInventory != 1) {
                    conn.rollback();
                    return ProfileInventoryMutationOutcome.rejected();
                }

                long newVersion = expectedVersion + 1;
                int affectedSession = updateSessionLastDurableVersion(conn, playerUuid, newVersion);
                if (affectedSession != 1) {
                    conn.rollback();
                    return ProfileInventoryMutationOutcome.rejected();
                }

                conn.commit();
                return ProfileInventoryMutationOutcome.success(newVersion);
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed server DB handoff finalization flush", e);
        }
    }

    private boolean validateAuthority(
            Connection conn, PlayerUuid playerUuid, ProfileId profileId, ServerNodeId currentNode, long expectedEpoch)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(selectSessionAuthoritySql)) {
            ps.setString(1, playerUuid.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                String activeProfileId = rs.getString("active_profile_id");
                String authNode = rs.getString("authoritative_node");
                long epoch = rs.getLong("session_epoch");
                String state = rs.getString("state");
                int leaseValid = rs.getInt("lease_valid");

                return profileId.value().toString().equals(activeProfileId)
                        && currentNode.value().equals(authNode)
                        && epoch == expectedEpoch
                        && "DRAINING".equals(state)
                        && leaseValid == 1;
            }
        }
    }

    private int updateOcc(Connection conn, ProfileId profileId, long expectedVersion, byte[] inventoryNbt)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(updateInventoryOccSql)) {
            ps.setBytes(1, inventoryNbt);
            ps.setString(2, profileId.value().toString());
            ps.setLong(3, expectedVersion);
            return ps.executeUpdate();
        }
    }

    private int updateSessionLastDurableVersion(Connection conn, PlayerUuid playerUuid, long newVersion)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(updateSessionLastDurableVersionSql)) {
            ps.setLong(1, newVersion);
            ps.setString(2, playerUuid.value().toString());
            return ps.executeUpdate();
        }
    }

    private static void rollbackSqlite(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // Already rolled back or connection closed
        }
    }

    @Override
    public OptionalLong loadLastDurableInventoryVersion(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(selectLastDurableVersionSql)) {
            ps.setString(1, playerUuid.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return OptionalLong.empty();
                }
                return OptionalLong.of(rs.getLong("last_durable_inventory_version"));
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException(
                    "Failed to load last durable inventory version for " + playerUuid, e);
        }
    }
}
