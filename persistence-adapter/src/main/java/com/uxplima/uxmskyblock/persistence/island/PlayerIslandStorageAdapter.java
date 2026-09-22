package com.uxplima.uxmskyblock.persistence.island;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Production SQL persistence adapter implementing {@link IslandStoragePort}, {@link IslandAuthorityPort}, and {@link IslandAdminFreezePort}.
 */
public final class PlayerIslandStorageAdapter implements IslandStoragePort, IslandAuthorityPort, IslandAdminFreezePort {

    private final Database database;
    private final Dialect dialect;
    private final PlayerIslandAuthorityAdapter authorityAdapter;
    private final PlayerIslandFreezeAdapter freezeAdapter;

    public PlayerIslandStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        IslandSqlSupport.validateDialect(this.dialect);
        this.authorityAdapter = new PlayerIslandAuthorityAdapter(database);
        this.freezeAdapter = new PlayerIslandFreezeAdapter(database);
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
            IslandSqlSupport.beginTransaction(conn, dialect);
            try {
                PlayerIslandWriter.saveIsland(conn, island, location, outboxEvent);
                IslandSqlSupport.commitTransaction(conn, dialect);
            } catch (Exception e) {
                IslandSqlSupport.rollbackTransaction(conn, dialect);
                throw new IslandPersistenceException("Failed to persist island: " + island.id(), e);
            } finally {
                IslandSqlSupport.resetAutoCommitQuietly(conn, dialect, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to acquire connection to save island: " + island.id(), e);
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
            IslandSqlSupport.beginTransaction(conn, dialect);
            try {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
                    stmt.setString(1, id.value().toString());
                    stmt.executeUpdate();
                }
                if (outboxEvent != null) {
                    com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(conn, outboxEvent);
                }
                IslandSqlSupport.commitTransaction(conn, dialect);
            } catch (Exception e) {
                IslandSqlSupport.rollbackTransaction(conn, dialect);
                throw new IslandPersistenceException("Failed to delete island: " + id, e);
            } finally {
                IslandSqlSupport.resetAutoCommitQuietly(conn, dialect, prevAutoCommit);
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
        // Six queries, whatever the world holds. This used to read a list of island ids and then
        // load each of them on its own, and each of those is five queries plus one per role: a
        // world with ten thousand islands was sixty thousand round trips, on every restart and
        // again on every upkeep sweep and every inactivity scan.
        try (Connection conn = database.connection()) {
            return PlayerIslandQueryHelper.loadIslandsByWorld(conn, worldName);
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to list islands for world " + worldName, e);
        }
    }

    @Override
    public IslandAuthorityOutcome acquireAuthority(IslandId islandId, ServerNodeId nodeId, int leaseSeconds) {
        return authorityAdapter.acquireAuthority(islandId, nodeId, leaseSeconds);
    }

    @Override
    public IslandAuthorityOutcome renewAuthority(
            IslandId islandId, ServerNodeId nodeId, long expectedEpoch, int leaseSeconds) {
        return authorityAdapter.renewAuthority(islandId, nodeId, expectedEpoch, leaseSeconds);
    }

    @Override
    public IslandAuthorityOutcome takeoverAuthority(
            IslandId islandId, ServerNodeId newNodeId, long expectedEpoch, int leaseSeconds) {
        return authorityAdapter.takeoverAuthority(islandId, newNodeId, expectedEpoch, leaseSeconds);
    }

    @Override
    public Optional<IslandAuthorityRecord> findAuthority(IslandId islandId) {
        return authorityAdapter.findAuthority(islandId);
    }

    @Override
    public com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep sweepAuthority(
            ServerNodeId nodeId, String worldName, int leaseSeconds) {
        return authorityAdapter.sweepAuthority(nodeId, worldName, leaseSeconds);
    }

    @Override
    public void updateAdministrativeState(IslandId islandId, AdministrativeState state, @Nullable String freezeReason) {
        freezeAdapter.updateAdministrativeState(islandId, state, freezeReason);
    }

    @Override
    public void updateAdministrativeState(
            IslandId islandId,
            AdministrativeState state,
            @Nullable String freezeReason,
            @Nullable StagedOutboxEvent outboxEvent) {
        freezeAdapter.updateAdministrativeState(islandId, state, freezeReason, outboxEvent);
    }

    @Override
    public void updateEconomicState(IslandId islandId, EconomicState state) {
        freezeAdapter.updateEconomicState(islandId, state);
    }

    @Override
    public void updateEconomicState(IslandId islandId, EconomicState state, @Nullable StagedOutboxEvent outboxEvent) {
        freezeAdapter.updateEconomicState(islandId, state, outboxEvent);
    }

    @Override
    public void updateLifecycle(IslandId islandId, IslandLifecycle lifecycle) {
        freezeAdapter.updateLifecycle(islandId, lifecycle);
    }

    @Override
    public Optional<IslandFreezeRecord> findFreezeRecord(IslandId islandId) {
        return freezeAdapter.findFreezeRecord(islandId);
    }
}
