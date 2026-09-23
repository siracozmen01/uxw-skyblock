package com.uxplima.uxmskyblock.persistence.upgrade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.application.upgrade.PaidTierMove;
import com.uxplima.uxmskyblock.core.application.upgrade.TierPurchase;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;

/**
 * Production SQL persistence adapter for Island Upgrades.
 */
public final class PlayerIslandUpgradeAdapter implements IslandUpgradeStoragePort {

    private final Database database;
    private final Dialect dialect;

    /** The bank a paid tier move charges, on the same database and in the same transaction. */
    private final PlayerIslandBankAdapter bank;

    public PlayerIslandUpgradeAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        this.bank = new PlayerIslandBankAdapter(database);
        SupportedDialects.require(dialect, "island upgrade persistence");
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
    public boolean compareAndSetUpgradeTier(IslandId islandId, UpgradeId upgradeId, int expectedTier, int newTier) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");
        try (Connection connection = database.connection()) {
            return move(connection, islandId, upgradeId, expectedTier, newTier);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to move upgrade " + upgradeId.key() + " for island " + islandId.value(), e);
        }
    }

    @Override
    public Optional<PaidTierMove> chargeAndMoveTier(TierPurchase purchase) {
        Objects.requireNonNull(purchase, "purchase");
        BankTransactionOutcome outcome = bank.executeTransactionWith(
                purchase.islandId(),
                purchase.actorUuid(),
                purchase.currencyId(),
                2,
                -purchase.costMinorUnits(),
                purchase.reason(),
                purchase.currentNode(),
                purchase.expectedEpoch(),
                purchase.expectedBankVersion(),
                purchase.operationId(),
                purchase.idempotencyKey(),
                "ISLAND_BANK",
                null,
                connection -> move(
                        connection, purchase.islandId(), purchase.upgradeId(), purchase.fromTier(), purchase.toTier()));
        if (outcome == null) {
            return Optional.of(new PaidTierMove.Raced());
        }
        if (outcome instanceof BankTransactionOutcome.Success success) {
            return Optional.of(new PaidTierMove.Moved(success));
        }
        return Optional.of(new PaidTierMove.Refused(outcome));
    }

    /**
     * Moves an upgrade from {@code expectedTier} to {@code newTier} on {@code connection}, and only
     * from there.
     *
     * <p>Two statements rather than a conditional upsert, because the three dialects spell that three
     * different ways and the update carries the condition portably in all of them. The first tier is
     * inserted with the conflict ignored rather than caught: on PostgreSQL a failed statement ends the
     * transaction it is in, and this one may be carrying a bank charge.
     */
    private boolean move(Connection connection, IslandId islandId, UpgradeId upgradeId, int expectedTier, int newTier)
            throws SQLException {
        String update = """
                UPDATE island_upgrades SET tier = ?, updated_at = CURRENT_TIMESTAMP
                WHERE island_id = ? AND upgrade_key = ? AND tier = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(update)) {
            ps.setInt(1, newTier);
            ps.setString(2, islandId.value().toString());
            ps.setString(3, upgradeId.key());
            ps.setInt(4, expectedTier);
            if (ps.executeUpdate() > 0) {
                return true;
            }
        }
        if (expectedTier != 0) {
            // There was a row and it did not hold what the caller expected.
            return false;
        }
        String insert =
                switch (dialect) {
                    case SQLITE, POSTGRES -> """
                    INSERT INTO island_upgrades (island_id, upgrade_key, tier, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (island_id, upgrade_key) DO NOTHING
                    """;
                    case MYSQL -> """
                    INSERT IGNORE INTO island_upgrades (island_id, upgrade_key, tier, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """;
                    case H2, GENERIC -> throw new UnsupportedOperationException("Unsupported dialect: " + dialect);
                };
        try (PreparedStatement ps = connection.prepareStatement(insert)) {
            ps.setString(1, islandId.value().toString());
            ps.setString(2, upgradeId.key());
            ps.setInt(3, newTier);
            // Nothing inserted means another purchase put the first tier in between the two statements.
            return ps.executeUpdate() > 0;
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
