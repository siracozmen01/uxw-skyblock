package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Canonical SQL persistence adapter implementing {@link ProfileInventoryCheckpointPort}.
 *
 * <p>Durability Scope and Invariants:
 * <ul>
 *   <li>This adapter strictly implements routine ambient {@code CHECKPOINTED} snapshot persistence under Hybrid durability.</li>
 *   <li>It is strictly NOT an {@code InventoryMutationJournal} replacement.</li>
 *   <li>It is strictly NOT valid for {@code IMMEDIATE}, economic, or value-sensitive item mutation execution.</li>
 *   <li>Immediate, economic, and value-sensitive mutations remain strictly owned by the {@code InventoryMutationJournal}
 *       protocol and must not be downgraded or routed through this ambient checkpoint path.</li>
 * </ul>
 *
 * <p>Enforces the frozen transaction protocol:
 * <ul>
 *   <li>SQLite: writer serialization via {@code BEGIN IMMEDIATE} transactions.</li>
 *   <li>MariaDB / PostgreSQL: row lock serialization via {@code SELECT player_sessions ... FOR UPDATE}.</li>
 *   <li>Atomic authority check under the same transaction (node, epoch, ACTIVE state, DB clock lease).</li>
 *   <li>OCC mutation on {@code profile_inventories} updating version = version + 1.</li>
 *   <li>Asserts affectedRows == 1; aborts/rolls back on authority or version mismatch.</li>
 * </ul>
 */
public final class PlayerProfileInventoryAdapter implements ProfileInventoryCheckpointPort {

    private final Database database;
    private final Dialect dialect;

    private final String selectSessionAuthoritySql;
    private final String updateInventoryOccSql;
    private final String selectInventorySql;
    private final String insertInventorySql;

    public PlayerProfileInventoryAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        validateDialect(this.dialect);

        this.selectSessionAuthoritySql = buildSelectSessionAuthoritySql(this.dialect);
        this.updateInventoryOccSql = buildUpdateInventoryOccSql();
        this.selectInventorySql = buildSelectInventorySql();
        this.insertInventorySql = buildInsertInventorySql();
    }

    private static void validateDialect(Dialect dialect) {
        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock inventory persistence supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
    }

    private static String buildSelectSessionAuthoritySql(Dialect dialect) {
        String base = "SELECT authoritative_node, session_epoch, state, "
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

    private static String buildSelectInventorySql() {
        return "SELECT profile_id, profile_inventory_version, inventory_nbt, enderchest_nbt, "
                + "experience_points, health, food_level, saturation, active_potion_effects_nbt, "
                + "logout_world, logout_x, logout_y, logout_z, gamemode, flight_allowed "
                + "FROM profile_inventories "
                + "WHERE profile_id = ?";
    }

    private static String buildInsertInventorySql() {
        return "INSERT INTO profile_inventories ("
                + "profile_id, profile_inventory_version, inventory_nbt, enderchest_nbt, "
                + "experience_points, health, food_level, saturation, active_potion_effects_nbt, "
                + "logout_world, logout_x, logout_y, logout_z, gamemode, flight_allowed, updated_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
    }

    @Override
    public ProfileInventoryMutationOutcome checkpointInventory(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            byte[] inventoryNbt) {
        return checkpointInventory(
                playerUuid, profileId, currentNode, expectedEpoch, expectedVersion, inventoryNbt, null);
    }

    /**
     * Checkpoints profile inventory with an optional testing hook executed while holding the authority lock.
     */
    public ProfileInventoryMutationOutcome checkpointInventory(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            byte[] inventoryNbt,
            @Nullable Runnable beforeMutationHook) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(currentNode, "currentNode");
        Objects.requireNonNull(inventoryNbt, "inventoryNbt");

        if (dialect == Dialect.SQLITE) {
            return executeSqliteCheckpoint(
                    playerUuid,
                    profileId,
                    currentNode,
                    expectedEpoch,
                    expectedVersion,
                    inventoryNbt,
                    beforeMutationHook);
        }
        return executeServerCheckpoint(
                playerUuid, profileId, currentNode, expectedEpoch, expectedVersion, inventoryNbt, beforeMutationHook);
    }

    private ProfileInventoryMutationOutcome executeSqliteCheckpoint(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            byte[] inventoryNbt,
            @Nullable Runnable beforeMutationHook) {
        try (Connection conn = database.connection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("BEGIN IMMEDIATE");
            }
            try {
                boolean authorityValid = validateAuthority(conn, playerUuid, currentNode, expectedEpoch);
                if (!authorityValid) {
                    rollbackSqlite(conn);
                    return ProfileInventoryMutationOutcome.rejected();
                }

                if (beforeMutationHook != null) {
                    beforeMutationHook.run();
                }

                int affected = updateOcc(conn, profileId, expectedVersion, inventoryNbt);
                if (affected == 1) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("COMMIT");
                    }
                    return ProfileInventoryMutationOutcome.success(expectedVersion + 1);
                } else {
                    rollbackSqlite(conn);
                    return ProfileInventoryMutationOutcome.rejected();
                }
            } catch (Exception e) {
                rollbackSqlite(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed SQLite inventory checkpoint", e);
        }
    }

    private ProfileInventoryMutationOutcome executeServerCheckpoint(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            byte[] inventoryNbt,
            @Nullable Runnable beforeMutationHook) {
        try (Connection conn = database.connection()) {
            conn.setAutoCommit(false);
            try {
                boolean authorityValid = validateAuthority(conn, playerUuid, currentNode, expectedEpoch);
                if (!authorityValid) {
                    conn.rollback();
                    return ProfileInventoryMutationOutcome.rejected();
                }

                if (beforeMutationHook != null) {
                    beforeMutationHook.run();
                }

                int affected = updateOcc(conn, profileId, expectedVersion, inventoryNbt);
                if (affected == 1) {
                    conn.commit();
                    return ProfileInventoryMutationOutcome.success(expectedVersion + 1);
                } else {
                    conn.rollback();
                    return ProfileInventoryMutationOutcome.rejected();
                }
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed server DB inventory checkpoint", e);
        }
    }

    private boolean validateAuthority(
            Connection conn, PlayerUuid playerUuid, ServerNodeId currentNode, long expectedEpoch) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(selectSessionAuthoritySql)) {
            ps.setString(1, playerUuid.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                String authNode = rs.getString("authoritative_node");
                long epoch = rs.getLong("session_epoch");
                String state = rs.getString("state");
                int leaseValid = rs.getInt("lease_valid");

                return currentNode.value().equals(authNode)
                        && epoch == expectedEpoch
                        && "ACTIVE".equals(state)
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

    private static void rollbackSqlite(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // Already rolled back or connection closed
        }
    }

    @Override
    public Optional<ProfileInventoryRecord> loadInventory(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(selectInventorySql)) {
            ps.setString(1, profileId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ProfileId id = ProfileId.fromString(rs.getString("profile_id"));
                long version = rs.getLong("profile_inventory_version");
                byte[] inv = rs.getBytes("inventory_nbt");
                byte[] ec = rs.getBytes("enderchest_nbt");
                int xp = rs.getInt("experience_points");
                double hp = rs.getDouble("health");
                int food = rs.getInt("food_level");
                float sat = rs.getFloat("saturation");
                byte[] potion = rs.getBytes("active_potion_effects_nbt");
                String world = rs.getString("logout_world");
                double x = rs.getDouble("logout_x");
                Double logoutX = rs.wasNull() ? null : x;
                double y = rs.getDouble("logout_y");
                Double logoutY = rs.wasNull() ? null : y;
                double z = rs.getDouble("logout_z");
                Double logoutZ = rs.wasNull() ? null : z;
                String gamemode = rs.getString("gamemode");
                boolean flight = rs.getBoolean("flight_allowed");

                return Optional.of(new ProfileInventoryRecord(
                        id, version, inv, ec, xp, hp, food, sat, potion, world, logoutX, logoutY, logoutZ, gamemode,
                        flight));
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to load profile inventory for " + profileId, e);
        }
    }

    @Override
    public void initializeInventory(ProfileInventoryRecord record) {
        Objects.requireNonNull(record, "record");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(insertInventorySql)) {
            ps.setString(1, record.profileId().value().toString());
            ps.setLong(2, record.version());
            ps.setBytes(3, record.inventoryNbt());
            ps.setBytes(4, record.enderchestNbt());
            ps.setInt(5, record.experiencePoints());
            ps.setDouble(6, record.health());
            ps.setInt(7, record.foodLevel());
            ps.setFloat(8, record.saturation());

            byte[] potion = record.activePotionEffectsNbt();
            if (potion != null) {
                ps.setBytes(9, potion);
            } else {
                ps.setNull(9, Types.BINARY);
            }

            if (record.logoutWorld() != null) {
                ps.setString(10, record.logoutWorld());
            } else {
                ps.setNull(10, Types.VARCHAR);
            }

            if (record.logoutX() != null) {
                ps.setDouble(11, record.logoutX());
            } else {
                ps.setNull(11, Types.DOUBLE);
            }

            if (record.logoutY() != null) {
                ps.setDouble(12, record.logoutY());
            } else {
                ps.setNull(12, Types.DOUBLE);
            }

            if (record.logoutZ() != null) {
                ps.setDouble(13, record.logoutZ());
            } else {
                ps.setNull(13, Types.DOUBLE);
            }

            ps.setString(14, record.gamemode());
            ps.setBoolean(15, record.flightAllowed());

            ps.executeUpdate();
        } catch (SQLException e) {
            throw new InventoryPersistenceException(
                    "Failed to initialize profile inventory for " + record.profileId(), e);
        }
    }
}
