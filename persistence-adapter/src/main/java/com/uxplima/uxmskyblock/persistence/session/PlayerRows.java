package com.uxplima.uxmskyblock.persistence.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * The rows a player needs before they can hold a session: the account, the profile it plays, and
 * that profile's inventory, each made the first time it is missing.
 */
final class PlayerRows {

    private PlayerRows() {}

    /**
     * Makes whatever of the three is missing, inside the caller's transaction, and answers the profile
     * the account plays: the one it already names, or {@code defaultProfileId} for a new account.
     */
    static ProfileId ensure(Connection conn, PlayerUuid playerUuid, ProfileId defaultProfileId) throws SQLException {
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
        return activeProfile;
    }
}
