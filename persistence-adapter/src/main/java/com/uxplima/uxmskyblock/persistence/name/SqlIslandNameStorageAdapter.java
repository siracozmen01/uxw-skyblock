package com.uxplima.uxmskyblock.persistence.name;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.name.IslandNameStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import org.jspecify.annotations.Nullable;

/**
 * SQL persistence adapter storing custom island names in the canonical `islands.custom_name` column (Section 2.41).
 */
public final class SqlIslandNameStorageAdapter implements IslandNameStoragePort {

    private final DataSource dataSource;

    public SqlIslandNameStorageAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void updateCustomName(IslandId islandId, @Nullable IslandName name) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                UPDATE islands
                SET custom_name = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (name != null) {
                stmt.setString(1, name.value());
            } else {
                stmt.setNull(1, java.sql.Types.VARCHAR);
            }
            stmt.setString(2, islandId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update custom name for island " + islandId, e);
        }
    }

    @Override
    public Optional<IslandName> findCustomName(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = "SELECT custom_name FROM islands WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString("custom_name");
                    if (name != null && !name.isBlank()) {
                        return Optional.of(IslandName.of(name));
                    }
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find custom name for island " + islandId, e);
        }
    }

    @Override
    public Optional<IslandId> findIslandIdByName(String name) {
        Objects.requireNonNull(name, "name must not be null");

        String sql = "SELECT id FROM islands WHERE LOWER(custom_name) = ? AND lifecycle = 'ACTIVE'";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name.toLowerCase(Locale.ROOT).trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new IslandId(UUID.fromString(rs.getString("id"))));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find island by custom name " + name, e);
        }
    }
}
