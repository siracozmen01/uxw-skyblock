package com.uxplima.uxmskyblock.persistence.island;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.island.ResidencyState;

/**
 * Query and result mapping helper for {@link PlayerIslandStorageAdapter}.
 * Encapsulates hydration of islands, roles, permissions, members, flags, and locations.
 */
final class PlayerIslandQueryHelper {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(PlayerIslandQueryHelper.class.getName());

    private PlayerIslandQueryHelper() {}

    static Optional<Island> loadIsland(Connection conn, IslandId id) throws SQLException {
        String islandIdStr = id.value().toString();
        PlayerUuid ownerUuid;
        ProfileId ownerProfileId;
        Instant createdAt;
        IslandLifecycle lifecycle;
        EconomicState economicState;
        AdministrativeState administrativeState;
        String freezeReason;

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT owner_profile_id, owner_account_uuid, created_at, lifecycle, economic_state, administrative_state, freeze_reason FROM islands WHERE id = ?")) {
            stmt.setString(1, islandIdStr);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ownerProfileId = ProfileId.of(UUID.fromString(rs.getString("owner_profile_id")));
                ownerUuid = PlayerUuid.of(UUID.fromString(rs.getString("owner_account_uuid")));
                createdAt = rs.getTimestamp("created_at").toInstant();
                try {
                    lifecycle = IslandLifecycle.valueOf(rs.getString("lifecycle"));
                } catch (Exception e) {
                    lifecycle = IslandLifecycle.ACTIVE;
                }
                try {
                    economicState = EconomicState.valueOf(rs.getString("economic_state"));
                } catch (Exception e) {
                    economicState = EconomicState.NORMAL;
                }
                try {
                    administrativeState = AdministrativeState.valueOf(rs.getString("administrative_state"));
                } catch (Exception e) {
                    administrativeState = AdministrativeState.NORMAL;
                }
                freezeReason = rs.getString("freeze_reason");
            }
        }

        // Load bounds from island_locations.
        //
        // An island with no row here used to be handed back with bounds around the world origin, a
        // hundred blocks across. Nothing about that island was true: it claimed ground it had never
        // been given, the first island on the server sits at the origin so the two overlapped, and a
        // player sent to their island's centre arrived at 0, 0. An island row and its location row
        // are written in one transaction, so a missing location is a broken record rather than a
        // state the server passes through. A record that broken is answered with nothing, which
        // every caller of this already handles, rather than with a place that was invented here.
        IslandBounds bounds;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT center_x, center_z, min_x, min_z, max_x, max_z FROM island_locations WHERE island_id = ?")) {
            stmt.setString(1, islandIdStr);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    LOGGER.log(
                            Level.WARNING,
                            "Island {0} has no row in island_locations, so it has no place and is "
                                    + "answered as absent. The two are written together, so this is a "
                                    + "record that needs repairing by hand.",
                            islandIdStr);
                    return Optional.empty();
                }
                int minX = rs.getInt("min_x");
                int minZ = rs.getInt("min_z");
                int maxX = rs.getInt("max_x");
                int maxZ = rs.getInt("max_z");
                int centerX = rs.getInt("center_x");
                int centerZ = rs.getInt("center_z");
                int radius = (maxX - minX) / 2;
                bounds = new IslandBounds(minX, minZ, maxX, maxZ, centerX, centerZ, radius);
            }
        }

        // Load roles & permissions
        Map<String, IslandRole> roles = loadRoles(conn, islandIdStr);

        // Load members
        Map<ProfileId, IslandMember> members = loadMembers(conn, islandIdStr, roles);

        // Load flags
        IslandFlags flags = loadFlags(conn, islandIdStr);

        return Optional.of(new Island(
                id,
                bounds,
                ownerUuid,
                ownerProfileId,
                members,
                roles,
                flags,
                createdAt,
                lifecycle,
                ResidencyState.UNLOADED,
                economicState,
                administrativeState,
                freezeReason));
    }

    static Map<String, IslandRole> loadRoles(Connection conn, String islandIdStr) throws SQLException {
        Map<String, IslandRole> roles = new HashMap<>();

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT role_id, weight, display_name, is_system FROM island_roles WHERE island_id = ?")) {
            stmt.setString(1, islandIdStr);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String roleId = rs.getString("role_id");
                    int weight = rs.getInt("weight");
                    String displayName = rs.getString("display_name");
                    boolean isSystem = rs.getBoolean("is_system");

                    Set<IslandPermission> perms = loadPermissions(conn, islandIdStr, roleId);
                    roles.put(roleId, new IslandRole(roleId, weight, displayName, perms, isSystem));
                }
            }
        }
        return roles;
    }

    static Set<IslandPermission> loadPermissions(Connection conn, String islandIdStr, String roleId)
            throws SQLException {
        Set<IslandPermission> perms = EnumSet.noneOf(IslandPermission.class);
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT permission FROM island_role_permissions WHERE island_id = ? AND role_id = ?")) {
            stmt.setString(1, islandIdStr);
            stmt.setString(2, roleId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        perms.add(IslandPermission.valueOf(rs.getString("permission")));
                    } catch (IllegalArgumentException expected) {
                        // ignore unknown future permissions
                    }
                }
            }
        }
        return perms;
    }

    static Map<ProfileId, IslandMember> loadMembers(Connection conn, String islandIdStr, Map<String, IslandRole> roles)
            throws SQLException {
        Map<ProfileId, IslandMember> members = new HashMap<>();

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT player_uuid, profile_id, role_id, joined_at FROM island_members WHERE island_id = ?")) {
            stmt.setString(1, islandIdStr);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    PlayerUuid playerUuid = PlayerUuid.of(UUID.fromString(rs.getString("player_uuid")));
                    ProfileId profileId = ProfileId.of(UUID.fromString(rs.getString("profile_id")));
                    String roleId = rs.getString("role_id");
                    Instant joinedAt = rs.getTimestamp("joined_at").toInstant();

                    IslandRole role = roles.getOrDefault(roleId, IslandRole.VISITOR);
                    members.put(profileId, new IslandMember(playerUuid, profileId, role, joinedAt));
                }
            }
        }
        return members;
    }

    /**
     * The island's flags, starting from the declared defaults and overlaid with what is stored.
     *
     * <p>It used to start empty, and {@link IslandFlags#isEnabled} answers false for a name it does
     * not hold. Every flag added to {@link IslandFlags} after an island was written therefore read
     * as off for that island, whatever its declared default said, and no migration would have shown
     * it: the rows were simply not there. VISITOR_ACCESS defaults to true and would have read as
     * false, which is an island silently closed to everybody.
     */
    static IslandFlags loadFlags(Connection conn, String islandIdStr) throws SQLException {
        Map<String, Boolean> values = new HashMap<>(IslandFlags.defaults().values());
        try (PreparedStatement stmt =
                conn.prepareStatement("SELECT flag_name, flag_value FROM island_flags WHERE island_id = ?")) {
            stmt.setString(1, islandIdStr);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    values.put(rs.getString("flag_name"), rs.getBoolean("flag_value"));
                }
            }
        }
        return new IslandFlags(values);
    }

    static IslandLocation mapLocation(ResultSet rs, IslandId id) throws SQLException {
        String worldName = rs.getString("world_name");
        int centerX = rs.getInt("center_x");
        int centerZ = rs.getInt("center_z");
        int minX = rs.getInt("min_x");
        int minZ = rs.getInt("min_z");
        int maxX = rs.getInt("max_x");
        int maxZ = rs.getInt("max_z");
        double spawnX = rs.getDouble("spawn_x");
        double spawnY = rs.getDouble("spawn_y");
        double spawnZ = rs.getDouble("spawn_z");
        float spawnYaw = rs.getFloat("spawn_yaw");
        float spawnPitch = rs.getFloat("spawn_pitch");

        int radius = (maxX - minX) / 2;
        IslandBounds bounds = new IslandBounds(minX, minZ, maxX, maxZ, centerX, centerZ, radius);
        return new IslandLocation(id, worldName, bounds, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
    }
}
