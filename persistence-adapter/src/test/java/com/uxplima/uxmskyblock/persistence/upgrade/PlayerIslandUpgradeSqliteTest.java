package com.uxplima.uxmskyblock.persistence.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIslandUpgradeSqliteTest {

    private Database database;
    private PlayerIslandUpgradeAdapter adapter;
    private final IslandId islandId = IslandId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("INSERT INTO player_accounts (player_uuid) VALUES ('p-upg-1')");
            stmt.execute("INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-upg-1', 'p-upg-1')");
            stmt.execute("INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES ('" + islandId.value()
                    + "', 'prof-upg-1', 'p-upg-1')");
        }

        adapter = new PlayerIslandUpgradeAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Unconfigured upgrade returns tier 0 and empty map")
    void defaultZeroTier() {
        assertThat(adapter.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(0);
        assertThat(adapter.getUpgrades(islandId)).isEmpty();
    }

    @Test
    @DisplayName("setUpgradeTier creates and updates tiers idempotently")
    void setAndAdvanceTier() {
        adapter.setUpgradeTier(islandId, UpgradeId.SIZE, 1);
        assertThat(adapter.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(1);

        adapter.setUpgradeTier(islandId, UpgradeId.MEMBERS, 2);
        assertThat(adapter.getUpgradeTier(islandId, UpgradeId.MEMBERS)).isEqualTo(2);

        Map<UpgradeId, Integer> all = adapter.getUpgrades(islandId);
        assertThat(all).containsEntry(UpgradeId.SIZE, 1);
        assertThat(all).containsEntry(UpgradeId.MEMBERS, 2);

        // Advance size tier
        adapter.setUpgradeTier(islandId, UpgradeId.SIZE, 3);
        assertThat(adapter.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(3);
    }

    @Test
    @DisplayName("Deleting island cascades and removes all upgrade records")
    void foreignKeyCascadeDelete() throws SQLException {
        adapter.setUpgradeTier(islandId, UpgradeId.SIZE, 1);
        assertThat(adapter.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(1);

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("DELETE FROM islands WHERE id = '" + islandId.value() + "'");
        }

        assertThat(adapter.getUpgrades(islandId)).isEmpty();
    }
}
