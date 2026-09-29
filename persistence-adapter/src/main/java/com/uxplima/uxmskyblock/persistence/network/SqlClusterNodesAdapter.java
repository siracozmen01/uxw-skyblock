package com.uxplima.uxmskyblock.persistence.network;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.network.ClusterNodesPort;
import com.uxplima.uxmskyblock.core.domain.network.NodeHealth;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The {@code cluster_nodes} table: one row per node, written on its heartbeat.
 *
 * <p>Time is the database's on both sides, so nodes whose clocks disagree still agree on who is alive.
 */
public final class SqlClusterNodesAdapter implements ClusterNodesPort {

    private final Database database;

    public SqlClusterNodesAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void publish(NodeHealth health, String worldName) {
        Objects.requireNonNull(health, "health must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement update = conn.prepareStatement("UPDATE cluster_nodes SET world_name = ?, "
                        + "hosted = ?, capacity = ?, average_mspt = ?, last_seen = CURRENT_TIMESTAMP "
                        + "WHERE node_id = ?")) {
                    bind(update, health, worldName);
                    updated = update.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement insert = conn.prepareStatement("INSERT INTO cluster_nodes "
                            + "(world_name, hosted, capacity, average_mspt, node_id) VALUES (?, ?, ?, ?, ?)")) {
                        bind(insert, health, worldName);
                        insert.executeUpdate();
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
            throw new IllegalStateException("Could not publish the health of node " + health.nodeId(), e);
        }
    }

    @Override
    public List<NodeHealth> serving(String worldName, Duration window) {
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(window, "window must not be null");
        String sql = "SELECT node_id, hosted, capacity, average_mspt FROM cluster_nodes "
                + "WHERE world_name = ? AND last_seen >= " + since(database.dialect(), window.toSeconds())
                + " ORDER BY node_id";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, worldName);
            List<NodeHealth> alive = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    alive.add(new NodeHealth(
                            ServerNodeId.of(rs.getString(1)), true, rs.getInt(2), rs.getInt(3), rs.getDouble(4)));
                }
            }
            return List.copyOf(alive);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read which nodes serve " + worldName, e);
        }
    }

    private static void bind(PreparedStatement stmt, NodeHealth health, String worldName) throws SQLException {
        stmt.setString(1, worldName);
        stmt.setInt(2, health.currentHostedCount());
        stmt.setInt(3, health.maxCapacity());
        stmt.setDouble(4, health.averageMspt());
        stmt.setString(5, health.nodeId().value());
    }

    private static String since(Dialect dialect, long seconds) {
        return switch (dialect) {
            case SQLITE -> "DATETIME('now', '-" + seconds + " seconds')";
            case MYSQL -> "CURRENT_TIMESTAMP - INTERVAL " + seconds + " SECOND";
            case POSTGRES -> "CURRENT_TIMESTAMP - INTERVAL '" + seconds + " seconds'";
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        };
    }
}
