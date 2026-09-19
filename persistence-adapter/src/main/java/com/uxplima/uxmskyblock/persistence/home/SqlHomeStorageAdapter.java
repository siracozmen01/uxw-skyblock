package com.uxplima.uxmskyblock.persistence.home;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.home.HomeStoragePort;
import com.uxplima.uxmskyblock.core.domain.home.Home;
import com.uxplima.uxmskyblock.core.domain.home.HomeId;
import com.uxplima.uxmskyblock.core.domain.home.HomeScope;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * SQL persistence adapter for multi-home storage (Section 2.42).
 */
public final class SqlHomeStorageAdapter implements HomeStoragePort {

    private final DataSource dataSource;

    public SqlHomeStorageAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void saveHome(Home home) {
        Objects.requireNonNull(home, "home must not be null");

        String updateSql = """
                UPDATE island_homes
                SET island_id = ?, home_scope = ?, world_name = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, updated_at = ?
                WHERE owner_profile_id = ? AND home_name = ?
                """;

        String insertSql = """
                INSERT INTO island_homes (
                    home_id, owner_profile_id, island_id, home_name, home_scope,
                    world_name, x, y, z, yaw, pitch, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                updateStmt.setString(1, home.islandId().value().toString());
                updateStmt.setString(2, home.scope().name());
                updateStmt.setString(3, home.worldName());
                updateStmt.setDouble(4, home.x());
                updateStmt.setDouble(5, home.y());
                updateStmt.setDouble(6, home.z());
                updateStmt.setFloat(7, home.yaw());
                updateStmt.setFloat(8, home.pitch());
                updateStmt.setTimestamp(9, Timestamp.from(home.updatedAt()));
                updateStmt.setString(10, home.ownerProfileId().value().toString());
                updateStmt.setString(11, home.name().toLowerCase(Locale.ROOT));

                int updated = updateStmt.executeUpdate();
                if (updated == 0) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, home.id().value().toString());
                        insertStmt.setString(2, home.ownerProfileId().value().toString());
                        insertStmt.setString(3, home.islandId().value().toString());
                        insertStmt.setString(4, home.name().toLowerCase(Locale.ROOT));
                        insertStmt.setString(5, home.scope().name());
                        insertStmt.setString(6, home.worldName());
                        insertStmt.setDouble(7, home.x());
                        insertStmt.setDouble(8, home.y());
                        insertStmt.setDouble(9, home.z());
                        insertStmt.setFloat(10, home.yaw());
                        insertStmt.setFloat(11, home.pitch());
                        insertStmt.setTimestamp(12, Timestamp.from(home.createdAt()));
                        insertStmt.setTimestamp(13, Timestamp.from(home.updatedAt()));
                        insertStmt.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to save home " + home.name() + " for profile " + home.ownerProfileId(), e);
        }
    }

    @Override
    public Optional<Home> findHome(ProfileId profileId, String name) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(name, "name must not be null");

        String sql = """
                SELECT home_id, owner_profile_id, island_id, home_name, home_scope,
                       world_name, x, y, z, yaw, pitch, created_at, updated_at
                FROM island_homes
                WHERE owner_profile_id = ? AND home_name = ?
                """;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, profileId.value().toString());
            stmt.setString(2, name.toLowerCase(Locale.ROOT));

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find home " + name + " for profile " + profileId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<Home> findHomesByProfileId(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");

        String sql = """
                SELECT home_id, owner_profile_id, island_id, home_name, home_scope,
                       world_name, x, y, z, yaw, pitch, created_at, updated_at
                FROM island_homes
                WHERE owner_profile_id = ?
                ORDER BY home_name ASC
                """;

        List<Home> homes = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, profileId.value().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    homes.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find homes for profile " + profileId, e);
        }
        return homes;
    }

    @Override
    public List<Home> findHomesByIslandId(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT home_id, owner_profile_id, island_id, home_name, home_scope,
                       world_name, x, y, z, yaw, pitch, created_at, updated_at
                FROM island_homes
                WHERE island_id = ?
                ORDER BY home_name ASC
                """;

        List<Home> homes = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    homes.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find homes for island " + islandId, e);
        }
        return homes;
    }

    @Override
    public boolean deleteHome(ProfileId profileId, String name) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(name, "name must not be null");

        String sql = "DELETE FROM island_homes WHERE owner_profile_id = ? AND home_name = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, profileId.value().toString());
            stmt.setString(2, name.toLowerCase(Locale.ROOT));
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete home " + name + " for profile " + profileId, e);
        }
    }

    @Override
    public int countHomes(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");

        String sql = "SELECT COUNT(*) FROM island_homes WHERE owner_profile_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, profileId.value().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count homes for profile " + profileId, e);
        }
    }

    private static Home mapRow(ResultSet rs) throws SQLException {
        UUID id = UUID.fromString(rs.getString("home_id"));
        UUID ownerProfileId = UUID.fromString(rs.getString("owner_profile_id"));
        UUID islandId = UUID.fromString(rs.getString("island_id"));
        String homeName = rs.getString("home_name");
        HomeScope scope = HomeScope.valueOf(rs.getString("home_scope"));
        String worldName = rs.getString("world_name");
        double x = rs.getDouble("x");
        double y = rs.getDouble("y");
        double z = rs.getDouble("z");
        float yaw = rs.getFloat("yaw");
        float pitch = rs.getFloat("pitch");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

        return new Home(
                new HomeId(id),
                ProfileId.of(ownerProfileId),
                IslandId.of(islandId),
                homeName,
                scope,
                worldName,
                x,
                y,
                z,
                yaw,
                pitch,
                createdAt,
                updatedAt);
    }
}
