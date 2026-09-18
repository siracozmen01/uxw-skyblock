package com.uxplima.uxmskyblock.persistence.warp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.warp.IslandBan;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;

/**
 * SQL persistence adapter for {@link IslandWarpStoragePort} implementing relational persistence
 * for island warps and island visitor bans across SQLite, MySQL/MariaDB, and PostgreSQL.
 */
public final class SqlIslandWarpStorageAdapter implements IslandWarpStoragePort {

    private final Database database;

    public SqlIslandWarpStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void saveWarp(IslandWarp warp) {
        Objects.requireNonNull(warp, "warp must not be null");

        String updateSql = """
                UPDATE island_warps SET
                    warp_name = ?, world_name = ?, x = ?, y = ?, z = ?,
                    yaw = ?, pitch = ?, icon_material = ?, category = ?,
                    is_locked = ?, updated_at = ?
                WHERE warp_id = ?
                """;

        String insertSql = """
                INSERT INTO island_warps (
                    warp_id, island_id, warp_name, world_name, x, y, z,
                    yaw, pitch, icon_material, category, is_locked, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int updatedRows;
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, warp.name().value());
                    updateStmt.setString(2, warp.location().worldName());
                    updateStmt.setDouble(3, warp.location().x());
                    updateStmt.setDouble(4, warp.location().y());
                    updateStmt.setDouble(5, warp.location().z());
                    updateStmt.setFloat(6, warp.location().yaw());
                    updateStmt.setFloat(7, warp.location().pitch());
                    updateStmt.setString(8, warp.iconMaterial());
                    updateStmt.setString(9, warp.category().name());
                    updateStmt.setBoolean(10, warp.isLocked());
                    updateStmt.setTimestamp(11, Timestamp.from(warp.updatedAt()));
                    updateStmt.setString(12, warp.id().value().toString());
                    updatedRows = updateStmt.executeUpdate();
                }

                if (updatedRows == 0) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, warp.id().value().toString());
                        insertStmt.setString(2, warp.islandId().value().toString());
                        insertStmt.setString(3, warp.name().value());
                        insertStmt.setString(4, warp.location().worldName());
                        insertStmt.setDouble(5, warp.location().x());
                        insertStmt.setDouble(6, warp.location().y());
                        insertStmt.setDouble(7, warp.location().z());
                        insertStmt.setFloat(8, warp.location().yaw());
                        insertStmt.setFloat(9, warp.location().pitch());
                        insertStmt.setString(10, warp.iconMaterial());
                        insertStmt.setString(11, warp.category().name());
                        insertStmt.setBoolean(12, warp.isLocked());
                        insertStmt.setTimestamp(13, Timestamp.from(warp.createdAt()));
                        insertStmt.setTimestamp(14, Timestamp.from(warp.updatedAt()));
                        insertStmt.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save island warp: " + warp.id(), e);
        }
    }

    @Override
    public Optional<IslandWarp> findWarpById(IslandWarpId warpId) {
        Objects.requireNonNull(warpId, "warpId must not be null");

        String sql = """
                SELECT warp_id, island_id, warp_name, world_name, x, y, z,
                       yaw, pitch, icon_material, category, is_locked, created_at, updated_at
                FROM island_warps
                WHERE warp_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, warpId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapWarp(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find island warp by id: " + warpId, e);
        }
    }

    @Override
    public Optional<IslandWarp> findWarpByName(IslandId islandId, WarpName warpName) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");

        String sql = """
                SELECT warp_id, island_id, warp_name, world_name, x, y, z,
                       yaw, pitch, icon_material, category, is_locked, created_at, updated_at
                FROM island_warps
                WHERE island_id = ? AND warp_name = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, warpName.value());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapWarp(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find island warp by name: " + warpName, e);
        }
    }

    @Override
    public List<IslandWarp> findWarpsByIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT warp_id, island_id, warp_name, world_name, x, y, z,
                       yaw, pitch, icon_material, category, is_locked, created_at, updated_at
                FROM island_warps
                WHERE island_id = ?
                ORDER BY warp_name ASC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<IslandWarp> warps = new ArrayList<>();
                while (rs.next()) {
                    warps.add(mapWarp(rs));
                }
                return warps;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find warps for island: " + islandId, e);
        }
    }

    @Override
    public List<IslandWarp> findPublicWarps(int limit, int offset) {
        String sql = """
                SELECT warp_id, island_id, warp_name, world_name, x, y, z,
                       yaw, pitch, icon_material, category, is_locked, created_at, updated_at
                FROM island_warps
                WHERE is_locked = FALSE
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, Math.max(1, limit));
            stmt.setInt(2, Math.max(0, offset));
            try (ResultSet rs = stmt.executeQuery()) {
                List<IslandWarp> warps = new ArrayList<>();
                while (rs.next()) {
                    warps.add(mapWarp(rs));
                }
                return warps;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find public warps", e);
        }
    }

    @Override
    public List<IslandWarp> findPublicWarpsByCategory(WarpCategory category, int limit, int offset) {
        Objects.requireNonNull(category, "category must not be null");

        String sql = """
                SELECT warp_id, island_id, warp_name, world_name, x, y, z,
                       yaw, pitch, icon_material, category, is_locked, created_at, updated_at
                FROM island_warps
                WHERE is_locked = FALSE AND category = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, category.name());
            stmt.setInt(2, Math.max(1, limit));
            stmt.setInt(3, Math.max(0, offset));
            try (ResultSet rs = stmt.executeQuery()) {
                List<IslandWarp> warps = new ArrayList<>();
                while (rs.next()) {
                    warps.add(mapWarp(rs));
                }
                return warps;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find public warps by category: " + category, e);
        }
    }

    @Override
    public int countWarpsByIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = "SELECT COUNT(*) FROM island_warps WHERE island_id = ?";

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count warps for island: " + islandId, e);
        }
    }

    @Override
    public boolean deleteWarp(IslandId islandId, WarpName warpName) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");

        String sql = "DELETE FROM island_warps WHERE island_id = ? AND warp_name = ?";

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, warpName.value());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete warp: " + warpName + " on island " + islandId, e);
        }
    }

    @Override
    public void banPlayer(IslandBan ban) {
        Objects.requireNonNull(ban, "ban must not be null");

        String updateSql = """
                UPDATE island_bans SET
                    banned_by_profile_id = ?, reason = ?, created_at = ?
                WHERE island_id = ? AND banned_player_uuid = ?
                """;

        String insertSql = """
                INSERT INTO island_bans (
                    island_id, banned_player_uuid, banned_by_profile_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int updatedRows;
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, ban.bannedByProfileId().value().toString());
                    if (ban.reason() != null) {
                        updateStmt.setString(2, ban.reason());
                    } else {
                        updateStmt.setNull(2, Types.VARCHAR);
                    }
                    updateStmt.setTimestamp(3, Timestamp.from(ban.createdAt()));
                    updateStmt.setString(4, ban.islandId().value().toString());
                    updateStmt.setString(5, ban.bannedPlayerUuid().value().toString());
                    updatedRows = updateStmt.executeUpdate();
                }

                if (updatedRows == 0) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, ban.islandId().value().toString());
                        insertStmt.setString(2, ban.bannedPlayerUuid().value().toString());
                        insertStmt.setString(3, ban.bannedByProfileId().value().toString());
                        if (ban.reason() != null) {
                            insertStmt.setString(4, ban.reason());
                        } else {
                            insertStmt.setNull(4, Types.VARCHAR);
                        }
                        insertStmt.setTimestamp(5, Timestamp.from(ban.createdAt()));
                        insertStmt.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save island ban for player: " + ban.bannedPlayerUuid(), e);
        }
    }

    @Override
    public boolean unbanPlayer(IslandId islandId, PlayerUuid playerUuid) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");

        String sql = "DELETE FROM island_bans WHERE island_id = ? AND banned_player_uuid = ?";

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, playerUuid.value().toString());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to unban player: " + playerUuid + " on island " + islandId, e);
        }
    }

    @Override
    public boolean isPlayerBanned(IslandId islandId, PlayerUuid playerUuid) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");

        String sql = "SELECT 1 FROM island_bans WHERE island_id = ? AND banned_player_uuid = ?";

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, playerUuid.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check if player is banned: " + playerUuid, e);
        }
    }

    @Override
    public List<IslandBan> findBansByIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT island_id, banned_player_uuid, banned_by_profile_id, reason, created_at
                FROM island_bans
                WHERE island_id = ?
                ORDER BY created_at DESC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<IslandBan> bans = new ArrayList<>();
                while (rs.next()) {
                    bans.add(mapBan(rs));
                }
                return bans;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find bans for island: " + islandId, e);
        }
    }

    private IslandWarp mapWarp(ResultSet rs) throws SQLException {
        IslandWarpId warpId = IslandWarpId.fromString(rs.getString("warp_id"));
        IslandId islandId = IslandId.of(UUID.fromString(rs.getString("island_id")));
        WarpName warpName = WarpName.of(rs.getString("warp_name"));
        String worldName = rs.getString("world_name");
        double x = rs.getDouble("x");
        double y = rs.getDouble("y");
        double z = rs.getDouble("z");
        float yaw = rs.getFloat("yaw");
        float pitch = rs.getFloat("pitch");
        String iconMaterial = rs.getString("icon_material");
        WarpCategory category = WarpCategory.parseCategory(rs.getString("category"));
        boolean isLocked = rs.getBoolean("is_locked");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

        WarpLocation location = new WarpLocation(worldName, x, y, z, yaw, pitch);
        return new IslandWarp(
                warpId, islandId, warpName, location, iconMaterial, category, isLocked, createdAt, updatedAt);
    }

    private IslandBan mapBan(ResultSet rs) throws SQLException {
        IslandId islandId = IslandId.of(UUID.fromString(rs.getString("island_id")));
        PlayerUuid bannedPlayerUuid = PlayerUuid.of(UUID.fromString(rs.getString("banned_player_uuid")));
        ProfileId bannedByProfileId = ProfileId.of(UUID.fromString(rs.getString("banned_by_profile_id")));
        String reason = rs.getString("reason");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();

        return new IslandBan(islandId, bannedPlayerUuid, bannedByProfileId, reason, createdAt);
    }
}
