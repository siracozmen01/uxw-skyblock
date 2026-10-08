package com.uxplima.uxmskyblock.persistence.tradewinds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The {@code tradewinds_vessels} table. */
public final class SqlVesselsAdapter implements VesselsPort {

    private final DataSource dataSource;

    public SqlVesselsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Set<IslandId> findAll() {
        Set<IslandId> all = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id FROM tradewinds_vessels");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.add(IslandId.of(UUID.fromString(rs.getString(1))));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the TradeWinds vessels", e);
        }
    }

    @Override
    public boolean exists(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM tradewinds_vessels WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read island " + islandId + " as a TradeWinds vessel", e);
        }
    }

    @Override
    public void add(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("INSERT INTO tradewinds_vessels (island_id) VALUES (?)")) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            // Already recorded. Class 23, or SQLite's code 19.
            String state = e.getSQLState();
            if ((state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19)) {
                return;
            }
            throw new IllegalStateException("Could not record island " + islandId + " as a TradeWinds vessel", e);
        }
    }
}
