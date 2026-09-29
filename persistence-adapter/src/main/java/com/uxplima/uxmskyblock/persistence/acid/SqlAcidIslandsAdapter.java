package com.uxplima.uxmskyblock.persistence.acid;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.acid.AcidIslandsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The {@code acid_island_state} table. */
public final class SqlAcidIslandsAdapter implements AcidIslandsPort {

    private final DataSource dataSource;

    public SqlAcidIslandsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Map<IslandId, Integer> findAll() {
        Map<IslandId, Integer> all = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id, sea_level FROM acid_island_state");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.put(IslandId.of(UUID.fromString(rs.getString(1))), rs.getInt(2));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the AcidIsland islands", e);
        }
    }

    @Override
    public void add(IslandId islandId, int seaLevel) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO acid_island_state (island_id, sea_level) VALUES (?, ?)")) {
            ps.setString(1, islandId.value().toString());
            ps.setInt(2, seaLevel);
            ps.executeUpdate();
        } catch (SQLException e) {
            // The island is already recorded; its first level stands. Class 23, or SQLite's code 19.
            String state = e.getSQLState();
            if ((state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19)) {
                return;
            }
            throw new IllegalStateException("Could not record island " + islandId + " as an AcidIsland island", e);
        }
    }
}
