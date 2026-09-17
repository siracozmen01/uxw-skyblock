package com.uxplima.uxmskyblock.persistence.upgrade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * Production SQL persistence adapter for Island Upgrades.
 */
public final class PlayerIslandUpgradeAdapter implements IslandUpgradeStoragePort {

    private final Database database;
    private final Dialect dialect;

    public PlayerIslandUpgradeAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        validateDialect(this.dialect);
    }

    private static void validateDialect(Dialect dialect) {
        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock island upgrade persistence supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
    }

    @Override
    public int getUpgradeTier(IslandId islandId, UpgradeId upgradeId) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");

        String sql = "SELECT tier FROM island_upgrades WHERE island_id = ? AND upgrade_key = ?";
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            ps.setString(2, upgradeId.key());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("tier");
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new IslandUpgradePersistenceException(
                    "Failed to query upgrade tier for " + islandId + ":" + upgradeId, e);
        }
    }

    @Override
    public Map<UpgradeId, Integer> getUpgrades(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");

        String sql = "SELECT upgrade_key, tier FROM island_upgrades WHERE island_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                Map<UpgradeId, Integer> result = new HashMap<>();
                while (rs.next()) {
                    String key = rs.getString("upgrade_key");
                    int tier = rs.getInt("tier");
                    result.put(UpgradeId.of(key), tier);
                }
                return Collections.unmodifiableMap(result);
            }
        } catch (SQLException e) {
            throw new IslandUpgradePersistenceException("Failed to query upgrades for " + islandId, e);
        }
    }

    @Override
    public void setUpgradeTier(IslandId islandId, UpgradeId upgradeId, int tier) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");

        String sql =
                switch (dialect) {
                    case SQLITE, POSTGRES -> """
                    INSERT INTO island_upgrades (island_id, upgrade_key, tier, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (island_id, upgrade_key) DO UPDATE SET
                        tier = excluded.tier,
                        updated_at = CURRENT_TIMESTAMP
                    """;
                    case MYSQL -> """
                    INSERT INTO island_upgrades (island_id, upgrade_key, tier, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE
                        tier = VALUES(tier),
                        updated_at = CURRENT_TIMESTAMP
                    """;
                    case H2, GENERIC -> throw new UnsupportedOperationException("Unsupported dialect: " + dialect);
                };

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            ps.setString(2, upgradeId.key());
            ps.setInt(3, tier);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IslandUpgradePersistenceException(
                    "Failed to set upgrade tier for " + islandId + ":" + upgradeId + " to " + tier, e);
        }
    }
}
