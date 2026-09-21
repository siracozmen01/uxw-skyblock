package com.uxplima.uxmskyblock.persistence.island;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates relational insert and update mutations for island records.
 */
final class PlayerIslandWriter {

    private PlayerIslandWriter() {}

    static void saveIsland(
            Connection conn, Island island, IslandLocation location, @Nullable StagedOutboxEvent outboxEvent)
            throws SQLException {
        // 1. Save or update island core row
        saveIslandCore(conn, island);

        // 2. Save or update island location
        saveIslandLocation(conn, location);

        // 3. Save or update roles and permissions
        saveIslandRoles(conn, island);

        // 4. Save or update members
        saveIslandMembers(conn, island);

        // 5. Save or update flags
        saveIslandFlags(conn, island);

        // 6. Stage outbox event atomically in same transaction
        if (outboxEvent != null) {
            com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(conn, outboxEvent);
        }
    }

    private static void saveIslandCore(Connection conn, Island island) throws SQLException {
        boolean exists;
        try (PreparedStatement checkStmt = conn.prepareStatement("SELECT 1 FROM islands WHERE id = ?")) {
            checkStmt.setString(1, island.id().value().toString());
            try (ResultSet rs = checkStmt.executeQuery()) {
                exists = rs.next();
            }
        }

        if (exists) {
            try (PreparedStatement updateStmt = conn.prepareStatement("""
                    UPDATE islands SET
                        owner_profile_id = ?,
                        owner_account_uuid = ?,
                        lifecycle = ?,
                        economic_state = ?,
                        administrative_state = ?,
                        freeze_reason = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                updateStmt.setString(1, island.ownerProfileId().value().toString());
                updateStmt.setString(2, island.ownerPlayerUuid().value().toString());
                updateStmt.setString(3, island.lifecycle().name());
                updateStmt.setString(4, island.economicState().name());
                updateStmt.setString(5, island.administrativeState().name());
                updateStmt.setString(6, island.freezeReason());
                updateStmt.setString(7, island.id().value().toString());
                updateStmt.executeUpdate();
            }
        } else {
            try (PreparedStatement insertStmt = conn.prepareStatement("""
                    INSERT INTO islands (
                        id, owner_profile_id, owner_account_uuid, custom_name, lifecycle,
                        economic_state, administrative_state, freeze_reason, level_score,
                        net_worth_minor_units, version, created_at, updated_at
                    ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 0, 0, 1, ?, CURRENT_TIMESTAMP)
                    """)) {
                insertStmt.setString(1, island.id().value().toString());
                insertStmt.setString(2, island.ownerProfileId().value().toString());
                insertStmt.setString(3, island.ownerPlayerUuid().value().toString());
                insertStmt.setString(4, island.lifecycle().name());
                insertStmt.setString(5, island.economicState().name());
                insertStmt.setString(6, island.administrativeState().name());
                insertStmt.setString(7, island.freezeReason());
                insertStmt.setTimestamp(8, Timestamp.from(island.createdAt()));
                insertStmt.executeUpdate();
            }
        }
    }

    private static void saveIslandLocation(Connection conn, IslandLocation location) throws SQLException {
        boolean exists;
        try (PreparedStatement checkStmt =
                conn.prepareStatement("SELECT 1 FROM island_locations WHERE island_id = ?")) {
            checkStmt.setString(1, location.islandId().value().toString());
            try (ResultSet rs = checkStmt.executeQuery()) {
                exists = rs.next();
            }
        }

        IslandBounds bounds = location.bounds();
        if (exists) {
            try (PreparedStatement updateStmt = conn.prepareStatement("""
                    UPDATE island_locations SET
                        world_name = ?, center_x = ?, center_z = ?,
                        min_x = ?, min_z = ?, max_x = ?, max_z = ?,
                        spawn_x = ?, spawn_y = ?, spawn_z = ?,
                        spawn_yaw = ?, spawn_pitch = ?
                    WHERE island_id = ?
                    """)) {
                updateStmt.setString(1, location.worldName());
                updateStmt.setInt(2, bounds.centerX());
                updateStmt.setInt(3, bounds.centerZ());
                updateStmt.setInt(4, bounds.minX());
                updateStmt.setInt(5, bounds.minZ());
                updateStmt.setInt(6, bounds.maxX());
                updateStmt.setInt(7, bounds.maxZ());
                updateStmt.setDouble(8, location.spawnX());
                updateStmt.setDouble(9, location.spawnY());
                updateStmt.setDouble(10, location.spawnZ());
                updateStmt.setFloat(11, location.spawnYaw());
                updateStmt.setFloat(12, location.spawnPitch());
                updateStmt.setString(13, location.islandId().value().toString());
                updateStmt.executeUpdate();
            }
        } else {
            try (PreparedStatement insertStmt = conn.prepareStatement("""
                    INSERT INTO island_locations (
                        island_id, world_name, center_x, center_z,
                        min_x, min_z, max_x, max_z,
                        spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insertStmt.setString(1, location.islandId().value().toString());
                insertStmt.setString(2, location.worldName());
                insertStmt.setInt(3, bounds.centerX());
                insertStmt.setInt(4, bounds.centerZ());
                insertStmt.setInt(5, bounds.minX());
                insertStmt.setInt(6, bounds.minZ());
                insertStmt.setInt(7, bounds.maxX());
                insertStmt.setInt(8, bounds.maxZ());
                insertStmt.setDouble(9, location.spawnX());
                insertStmt.setDouble(10, location.spawnY());
                insertStmt.setDouble(11, location.spawnZ());
                insertStmt.setFloat(12, location.spawnYaw());
                insertStmt.setFloat(13, location.spawnPitch());
                insertStmt.executeUpdate();
            }
        }
    }

    private static void saveIslandRoles(Connection conn, Island island) throws SQLException {
        String islandIdStr = island.id().value().toString();

        // A role the island no longer has is deleted, the way a member who left is. Without this the
        // row survived every save, loadRoles hydrated it back on the next read, and deleting a
        // custom role looked like it worked until the server restarted. Its permissions went with
        // it, because the permission sync below only runs for roles the island still holds.
        deleteRemovedRoles(conn, islandIdStr, island.roles().keySet());

        for (IslandRole role : island.roles().values()) {
            boolean exists;
            try (PreparedStatement checkStmt =
                    conn.prepareStatement("SELECT 1 FROM island_roles WHERE island_id = ? AND role_id = ?")) {
                checkStmt.setString(1, islandIdStr);
                checkStmt.setString(2, role.id());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE island_roles SET weight = ?, display_name = ?, is_system = ? WHERE island_id = ? AND role_id = ?")) {
                    updateStmt.setInt(1, role.weight());
                    updateStmt.setString(2, role.displayName());
                    updateStmt.setBoolean(3, role.isSystem());
                    updateStmt.setString(4, islandIdStr);
                    updateStmt.setString(5, role.id());
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO island_roles (island_id, role_id, weight, display_name, is_system) VALUES (?, ?, ?, ?, ?)")) {
                    insertStmt.setString(1, islandIdStr);
                    insertStmt.setString(2, role.id());
                    insertStmt.setInt(3, role.weight());
                    insertStmt.setString(4, role.displayName());
                    insertStmt.setBoolean(5, role.isSystem());
                    insertStmt.executeUpdate();
                }
            }

            // Sync permissions for this role
            try (PreparedStatement deletePerms =
                    conn.prepareStatement("DELETE FROM island_role_permissions WHERE island_id = ? AND role_id = ?")) {
                deletePerms.setString(1, islandIdStr);
                deletePerms.setString(2, role.id());
                deletePerms.executeUpdate();
            }

            if (!role.permissions().isEmpty()) {
                try (PreparedStatement insertPerm = conn.prepareStatement(
                        "INSERT INTO island_role_permissions (island_id, role_id, permission) VALUES (?, ?, ?)")) {
                    for (IslandPermission perm : role.permissions()) {
                        insertPerm.setString(1, islandIdStr);
                        insertPerm.setString(2, role.id());
                        insertPerm.setString(3, perm.name());
                        insertPerm.executeUpdate();
                    }
                }
            }
        }
    }

    private static void deleteRemovedRoles(Connection conn, String islandIdStr, Set<String> keptRoleIds)
            throws SQLException {
        Set<String> storedRoleIds = new HashSet<>();
        try (PreparedStatement query = conn.prepareStatement("SELECT role_id FROM island_roles WHERE island_id = ?")) {
            query.setString(1, islandIdStr);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    storedRoleIds.add(rs.getString("role_id"));
                }
            }
        }

        for (String storedRoleId : storedRoleIds) {
            if (keptRoleIds.contains(storedRoleId)) {
                continue;
            }
            try (PreparedStatement deletePerms =
                    conn.prepareStatement("DELETE FROM island_role_permissions WHERE island_id = ? AND role_id = ?")) {
                deletePerms.setString(1, islandIdStr);
                deletePerms.setString(2, storedRoleId);
                deletePerms.executeUpdate();
            }
            try (PreparedStatement deleteRole =
                    conn.prepareStatement("DELETE FROM island_roles WHERE island_id = ? AND role_id = ?")) {
                deleteRole.setString(1, islandIdStr);
                deleteRole.setString(2, storedRoleId);
                deleteRole.executeUpdate();
            }
        }
    }

    private static void saveIslandMembers(Connection conn, Island island) throws SQLException {
        String islandIdStr = island.id().value().toString();

        // 1. Delete removed members
        Set<String> activeProfileIds = new HashSet<>();
        for (ProfileId pid : island.members().keySet()) {
            activeProfileIds.add(pid.value().toString());
        }

        Set<String> existingProfileIds = new HashSet<>();
        try (PreparedStatement queryMembers =
                conn.prepareStatement("SELECT profile_id FROM island_members WHERE island_id = ?")) {
            queryMembers.setString(1, islandIdStr);
            try (ResultSet rs = queryMembers.executeQuery()) {
                while (rs.next()) {
                    existingProfileIds.add(rs.getString("profile_id"));
                }
            }
        }

        for (String existingPid : existingProfileIds) {
            if (!activeProfileIds.contains(existingPid)) {
                try (PreparedStatement deleteMember =
                        conn.prepareStatement("DELETE FROM island_members WHERE island_id = ? AND profile_id = ?")) {
                    deleteMember.setString(1, islandIdStr);
                    deleteMember.setString(2, existingPid);
                    deleteMember.executeUpdate();
                }
            }
        }

        // 2. Upsert current members
        for (IslandMember member : island.members().values()) {
            String profileIdStr = member.profileId().value().toString();
            boolean exists = existingProfileIds.contains(profileIdStr);

            if (exists) {
                try (PreparedStatement updateMember = conn.prepareStatement(
                        "UPDATE island_members SET player_uuid = ?, role_id = ? WHERE island_id = ? AND profile_id = ?")) {
                    updateMember.setString(1, member.playerUuid().value().toString());
                    updateMember.setString(2, member.role().id());
                    updateMember.setString(3, islandIdStr);
                    updateMember.setString(4, profileIdStr);
                    updateMember.executeUpdate();
                }
            } else {
                try (PreparedStatement insertMember = conn.prepareStatement(
                        "INSERT INTO island_members (island_id, player_uuid, profile_id, role_id, joined_at) VALUES (?, ?, ?, ?, ?)")) {
                    insertMember.setString(1, islandIdStr);
                    insertMember.setString(2, member.playerUuid().value().toString());
                    insertMember.setString(3, profileIdStr);
                    insertMember.setString(4, member.role().id());
                    insertMember.setTimestamp(5, Timestamp.from(member.joinedAt()));
                    insertMember.executeUpdate();
                }
            }
        }
    }

    private static void saveIslandFlags(Connection conn, Island island) throws SQLException {
        String islandIdStr = island.id().value().toString();

        for (Map.Entry<String, Boolean> entry : island.flags().values().entrySet()) {
            boolean exists;
            try (PreparedStatement checkStmt =
                    conn.prepareStatement("SELECT 1 FROM island_flags WHERE island_id = ? AND flag_name = ?")) {
                checkStmt.setString(1, islandIdStr);
                checkStmt.setString(2, entry.getKey());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE island_flags SET flag_value = ? WHERE island_id = ? AND flag_name = ?")) {
                    updateStmt.setBoolean(1, entry.getValue());
                    updateStmt.setString(2, islandIdStr);
                    updateStmt.setString(3, entry.getKey());
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO island_flags (island_id, flag_name, flag_value) VALUES (?, ?, ?)")) {
                    insertStmt.setString(1, islandIdStr);
                    insertStmt.setString(2, entry.getKey());
                    insertStmt.setBoolean(3, entry.getValue());
                    insertStmt.executeUpdate();
                }
            }
        }
    }
}
