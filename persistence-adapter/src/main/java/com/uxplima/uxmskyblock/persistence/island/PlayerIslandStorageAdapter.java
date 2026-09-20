package com.uxplima.uxmskyblock.persistence.island;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezePort;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFreezeRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Production SQL persistence adapter implementing {@link IslandStoragePort}, {@link IslandAuthorityPort}, and {@link IslandAdminFreezePort}.
 */
public final class PlayerIslandStorageAdapter implements IslandStoragePort, IslandAuthorityPort, IslandAdminFreezePort {

    private final Database database;
    private final Dialect dialect;

    public PlayerIslandStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        validateDialect(this.dialect);
    }

    private static void validateDialect(Dialect dialect) {
        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException("Unsupported SQL dialect: " + dialect
                        + ". Skyblock island persistence supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
    }

    private static String dbNowPlus(Dialect dialect, int seconds) {
        return switch (dialect) {
            case SQLITE -> "DATETIME('now', '+" + seconds + " seconds')";
            case MYSQL -> "CURRENT_TIMESTAMP + INTERVAL " + seconds + " SECOND";
            case POSTGRES -> "CURRENT_TIMESTAMP + INTERVAL '" + seconds + " seconds'";
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        };
    }

    private void beginTransaction(Connection connection) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
            }
        } else {
            connection.setAutoCommit(false);
        }
    }

    private void commitTransaction(Connection connection) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("COMMIT");
            }
        } else {
            connection.commit();
        }
    }

    private void rollbackTransaction(Connection connection) {
        try {
            if (dialect == Dialect.SQLITE) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                }
            } else {
                connection.rollback();
            }
        } catch (SQLException expected) {
            // best-effort cleanup
        }
    }

    private void resetAutoCommitQuietly(Connection connection, boolean autoCommit) {
        if (dialect != Dialect.SQLITE) {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException expected) {
                // best-effort cleanup
            }
        }
    }

    @Override
    public void saveIsland(Island island, IslandLocation location) {
        saveIsland(island, location, null);
    }

    @Override
    public void saveIsland(Island island, IslandLocation location, @Nullable StagedOutboxEvent outboxEvent) {
        Objects.requireNonNull(island, "island");
        Objects.requireNonNull(location, "location");

        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            beginTransaction(conn);
            try {
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

                commitTransaction(conn);
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw new IslandPersistenceException("Failed to persist island: " + island.id(), e);
            } finally {
                resetAutoCommitQuietly(conn, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to acquire connection to save island: " + island.id(), e);
        }
    }

    private void saveIslandCore(Connection conn, Island island) throws SQLException {
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

    private void saveIslandLocation(Connection conn, IslandLocation location) throws SQLException {
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

    private void saveIslandRoles(Connection conn, Island island) throws SQLException {
        String islandIdStr = island.id().value().toString();

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

    private void saveIslandMembers(Connection conn, Island island) throws SQLException {
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

    private void saveIslandFlags(Connection conn, Island island) throws SQLException {
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

    @Override
    public Optional<Island> findIslandById(IslandId id) {
        Objects.requireNonNull(id, "id");
        try (Connection conn = database.connection()) {
            return PlayerIslandQueryHelper.loadIsland(conn, id);
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to query island by id: " + id, e);
        }
    }

    @Override
    public Optional<IslandLocation> findLocationByIslandId(IslandId id) {
        Objects.requireNonNull(id, "id");
        String islandIdStr = id.value().toString();

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("""
                        SELECT world_name, center_x, center_z, min_x, min_z, max_x, max_z,
                               spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch
                        FROM island_locations WHERE island_id = ?
                        """)) {
            stmt.setString(1, islandIdStr);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(PlayerIslandQueryHelper.mapLocation(rs, id));
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to query location for island: " + id, e);
        }
    }

    @Override
    public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId");
        try (Connection conn = database.connection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT island_id FROM island_members WHERE profile_id = ?")) {
            stmt.setString(1, profileId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(IslandId.of(UUID.fromString(rs.getString("island_id"))));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to query island by profile id: " + profileId, e);
        }
    }

    @Override
    public void deleteIsland(IslandId id) {
        deleteIsland(id, null);
    }

    @Override
    public void deleteIsland(IslandId id, @Nullable StagedOutboxEvent outboxEvent) {
        Objects.requireNonNull(id, "id");
        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            beginTransaction(conn);
            try {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
                    stmt.setString(1, id.value().toString());
                    stmt.executeUpdate();
                }
                if (outboxEvent != null) {
                    com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(conn, outboxEvent);
                }
                commitTransaction(conn);
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw new IslandPersistenceException("Failed to delete island: " + id, e);
            } finally {
                resetAutoCommitQuietly(conn, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to acquire connection to delete island: " + id, e);
        }
    }

    @Override
    public Optional<Island> findIslandByLocation(String worldName, int x, int z) {
        Objects.requireNonNull(worldName, "worldName");
        String sql = """
                SELECT island_id FROM island_locations
                WHERE world_name = ? AND min_x <= ? AND max_x >= ? AND min_z <= ? AND max_z >= ?
                """;
        try (Connection conn = database.connection()) {
            IslandId foundId = null;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, worldName);
                stmt.setInt(2, x);
                stmt.setInt(3, x);
                stmt.setInt(4, z);
                stmt.setInt(5, z);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        foundId = IslandId.of(UUID.fromString(rs.getString("island_id")));
                    }
                }
            }
            if (foundId != null) {
                return PlayerIslandQueryHelper.loadIsland(conn, foundId);
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to find island at " + worldName + ":" + x + "," + z, e);
        }
    }

    @Override
    public List<Island> findAllByWorld(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        String sql = "SELECT island_id FROM island_locations WHERE world_name = ?";
        try (Connection conn = database.connection()) {
            List<IslandId> ids = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, worldName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        ids.add(IslandId.of(UUID.fromString(rs.getString("island_id"))));
                    }
                }
            }

            List<Island> islands = new ArrayList<>(ids.size());
            for (IslandId id : ids) {
                PlayerIslandQueryHelper.loadIsland(conn, id).ifPresent(islands::add);
            }
            return Collections.unmodifiableList(islands);
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to list islands for world " + worldName, e);
        }
    }

    @Override
    public IslandAuthorityOutcome acquireAuthority(IslandId islandId, ServerNodeId nodeId, int leaseSeconds) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(nodeId, "nodeId");

        String sql = "INSERT INTO island_authorities ("
                + "island_id, authoritative_node, authority_epoch, lease_expires_at, last_heartbeat_at, updated_at"
                + ") VALUES (?, ?, 1, " + dbNowPlus(dialect, leaseSeconds) + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            beginTransaction(conn);
            try {
                int affected;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, islandId.value().toString());
                    stmt.setString(2, nodeId.value());
                    affected = stmt.executeUpdate();
                }
                commitTransaction(conn);
                return affected == 1 ? IslandAuthorityOutcome.success(1L) : IslandAuthorityOutcome.rejected();
            } catch (SQLException e) {
                rollbackTransaction(conn);
                // Duplicate key / integrity constraint violation
                return IslandAuthorityOutcome.rejected();
            } finally {
                resetAutoCommitQuietly(conn, prevAutoCommit);
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
                + "SET lease_expires_at = " + dbNowPlus(dialect, leaseSeconds) + ", "
                + "last_heartbeat_at = CURRENT_TIMESTAMP, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE island_id = ? "
                + "AND authoritative_node = ? "
                + "AND authority_epoch = ? "
                + "AND lease_expires_at >= CURRENT_TIMESTAMP";

        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            beginTransaction(conn);
            try {
                int affected;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, islandId.value().toString());
                    stmt.setString(2, nodeId.value());
                    stmt.setLong(3, expectedEpoch);
                    affected = stmt.executeUpdate();
                }
                commitTransaction(conn);
                return affected == 1
                        ? IslandAuthorityOutcome.success(expectedEpoch)
                        : IslandAuthorityOutcome.rejected();
            } catch (SQLException e) {
                rollbackTransaction(conn);
                return IslandAuthorityOutcome.rejected();
            } finally {
                resetAutoCommitQuietly(conn, prevAutoCommit);
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
                + "lease_expires_at = " + dbNowPlus(dialect, leaseSeconds) + ", "
                + "last_heartbeat_at = CURRENT_TIMESTAMP, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE island_id = ? "
                + "AND authority_epoch = ? "
                + "AND lease_expires_at < CURRENT_TIMESTAMP";

        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            beginTransaction(conn);
            try {
                int affected;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, newNodeId.value());
                    stmt.setString(2, islandId.value().toString());
                    stmt.setLong(3, expectedEpoch);
                    affected = stmt.executeUpdate();
                }
                commitTransaction(conn);
                long newEpoch = expectedEpoch + 1;
                return affected == 1 ? IslandAuthorityOutcome.success(newEpoch) : IslandAuthorityOutcome.rejected();
            } catch (SQLException e) {
                rollbackTransaction(conn);
                return IslandAuthorityOutcome.rejected();
            } finally {
                resetAutoCommitQuietly(conn, prevAutoCommit);
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

    @Override
    public void updateAdministrativeState(IslandId islandId, AdministrativeState state, @Nullable String freezeReason) {
        updateAdministrativeState(islandId, state, freezeReason, null);
    }

    @Override
    public void updateAdministrativeState(
            IslandId islandId,
            AdministrativeState state,
            @Nullable String freezeReason,
            @Nullable StagedOutboxEvent outboxEvent) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(state, "state");
        String sql =
                "UPDATE islands SET administrative_state = ?, freeze_reason = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            beginTransaction(conn);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, state.name());
                    stmt.setString(2, freezeReason);
                    stmt.setString(3, islandId.value().toString());
                    stmt.executeUpdate();
                }
                if (outboxEvent != null) {
                    com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(conn, outboxEvent);
                }
                commitTransaction(conn);
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw new IslandPersistenceException(
                        "Failed to update administrative state for island: " + islandId, e);
            } finally {
                resetAutoCommitQuietly(conn, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to update administrative state for island: " + islandId, e);
        }
    }

    @Override
    public void updateEconomicState(IslandId islandId, EconomicState state) {
        updateEconomicState(islandId, state, null);
    }

    @Override
    public void updateEconomicState(IslandId islandId, EconomicState state, @Nullable StagedOutboxEvent outboxEvent) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(state, "state");
        String sql = "UPDATE islands SET economic_state = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            beginTransaction(conn);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, state.name());
                    stmt.setString(2, islandId.value().toString());
                    stmt.executeUpdate();
                }
                if (outboxEvent != null) {
                    com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(conn, outboxEvent);
                }
                commitTransaction(conn);
            } catch (Exception e) {
                rollbackTransaction(conn);
                throw new IslandPersistenceException("Failed to update economic state for island: " + islandId, e);
            } finally {
                resetAutoCommitQuietly(conn, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to update economic state for island: " + islandId, e);
        }
    }

    @Override
    public void updateLifecycle(IslandId islandId, IslandLifecycle lifecycle) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        String sql = "UPDATE islands SET lifecycle = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, lifecycle.name());
            stmt.setString(2, islandId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to update lifecycle for island: " + islandId, e);
        }
    }

    @Override
    public Optional<IslandFreezeRecord> findFreezeRecord(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");
        String sql = "SELECT administrative_state, freeze_reason, updated_at FROM islands WHERE id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                AdministrativeState adminState;
                try {
                    adminState = AdministrativeState.valueOf(rs.getString("administrative_state"));
                } catch (Exception e) {
                    adminState = AdministrativeState.NORMAL;
                }
                String reason = rs.getString("freeze_reason");
                Timestamp ts = rs.getTimestamp("updated_at");
                Instant updatedAt = ts != null ? ts.toInstant() : Instant.now();

                if (adminState == AdministrativeState.FROZEN) {
                    return Optional.of(IslandFreezeRecord.frozen(
                            islandId, reason != null ? reason : "Administrative quarantine", null, updatedAt));
                } else {
                    return Optional.of(IslandFreezeRecord.normal(islandId, updatedAt));
                }
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to query freeze record for island: " + islandId, e);
        }
    }
}
