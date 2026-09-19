package com.uxplima.uxmskyblock.persistence.dimension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionStoragePort;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Production SQL implementation of {@link IslandDimensionStoragePort} durably tracking
 * starter platform generation states across SQLite, MariaDB, and PostgreSQL.
 */
public final class PlayerIslandDimensionAdapter implements IslandDimensionStoragePort {

    private final Database database;
    private final Dialect dialect;

    public PlayerIslandDimensionAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.dialect = database.dialect();
    }

    @Override
    public void markDimensionGenerated(IslandId islandId, IslandDimensionType dimension) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(dimension, "dimension must not be null");

        String sql =
                switch (dialect) {
                    case SQLITE, POSTGRES -> """
                    INSERT INTO island_dimensions (island_id, dimension_type, generated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (island_id, dimension_type) DO NOTHING
                    """;
                    case MYSQL -> """
                    INSERT IGNORE INTO island_dimensions (island_id, dimension_type, generated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """;
                    case H2, GENERIC -> throw new UnsupportedOperationException("Unsupported SQL dialect: " + dialect);
                };

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            ps.setString(2, dimension.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IslandDimensionPersistenceException(
                    "Failed to mark dimension " + dimension + " generated for island " + islandId, e);
        }
    }

    @Override
    public boolean hasGeneratedDimension(IslandId islandId, IslandDimensionType dimension) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(dimension, "dimension must not be null");

        String sql = "SELECT 1 FROM island_dimensions WHERE island_id = ? AND dimension_type = ?";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            ps.setString(2, dimension.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IslandDimensionPersistenceException(
                    "Failed to query generated dimension state for island " + islandId, e);
        }
    }

    @Override
    public Set<IslandDimensionType> getGeneratedDimensions(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = "SELECT dimension_type FROM island_dimensions WHERE island_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                Set<IslandDimensionType> result = EnumSet.noneOf(IslandDimensionType.class);
                while (rs.next()) {
                    try {
                        result.add(IslandDimensionType.valueOf(rs.getString("dimension_type")));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore unknown dimension type values from future schemas
                    }
                }
                return Collections.unmodifiableSet(result);
            }
        } catch (SQLException e) {
            throw new IslandDimensionPersistenceException(
                    "Failed to query generated dimensions for island " + islandId, e);
        }
    }

    @Override
    public void deleteIslandDimensions(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = "DELETE FROM island_dimensions WHERE island_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IslandDimensionPersistenceException("Failed to delete dimensions for island " + islandId, e);
        }
    }

    public static class IslandDimensionPersistenceException extends RuntimeException {
        public IslandDimensionPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
