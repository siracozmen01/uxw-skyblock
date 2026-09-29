package com.uxplima.uxmskyblock.persistence.stranger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The {@code stranger_claims} table. */
public final class SqlStrangerRealmsAdapter implements StrangerRealmsPort {

    private final DataSource dataSource;

    public SqlStrangerRealmsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Set<IslandId> findAll() {
        Set<IslandId> all = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id FROM stranger_claims");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.add(IslandId.of(UUID.fromString(rs.getString(1))));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the StrangerRealms islands", e);
        }
    }

    @Override
    public boolean exists(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM stranger_claims WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read island " + islandId + " as a StrangerRealms island", e);
        }
    }

    @Override
    public int farthestReach() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT MAX(ABS(l.min_x)), MAX(ABS(l.max_x)),"
                        + " MAX(ABS(l.min_z)), MAX(ABS(l.max_z))"
                        + " FROM island_locations l JOIN stranger_claims s ON s.island_id = l.island_id");
                ResultSet rs = ps.executeQuery()) {
            int reach = 0;
            if (rs.next()) {
                for (int column = 1; column <= 4; column++) {
                    reach = Math.max(reach, rs.getInt(column));
                }
            }
            return reach;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read how far the StrangerRealms islands reach", e);
        }
    }

    @Override
    public void add(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("INSERT INTO stranger_claims (island_id) VALUES (?)")) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            // Already recorded. Class 23, or SQLite's code 19.
            String state = e.getSQLState();
            if ((state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19)) {
                return;
            }
            throw new IllegalStateException("Could not record island " + islandId + " as a StrangerRealms island", e);
        }
    }
}
