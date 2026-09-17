package com.uxplima.uxmskyblock.persistence.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlayerIslandUpgradeIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerIslandUpgradeAdapter mariaAdapter;
    private static PlayerIslandUpgradeAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.newMariaDbContainer();
        mariaDbContainer.start();
        mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
        new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        mariaAdapter = new PlayerIslandUpgradeAdapter(mariaDatabase);

        postgresContainer = DatabaseTestFixture.newPostgresContainer();
        postgresContainer.start();
        postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
        new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
        postgresAdapter = new PlayerIslandUpgradeAdapter(postgresDatabase);
    }

    @AfterAll
    static void tearDownAll() {
        if (mariaDatabase != null && !mariaDatabase.isClosed()) {
            mariaDatabase.close();
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresDatabase != null && !postgresDatabase.isClosed()) {
            postgresDatabase.close();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("MariaDB: upgrade progression and upsert")
    void mariaDbUpgradeProgression() throws Exception {
        testUpgradeProgression(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @DisplayName("PostgreSQL: upgrade progression and upsert")
    void postgresUpgradeProgression() throws Exception {
        testUpgradeProgression(postgresDatabase, postgresAdapter);
    }

    private void testUpgradeProgression(Database db, PlayerIslandUpgradeAdapter adapter) throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        String playerUuid = UUID.randomUUID().toString();
        String profileId = UUID.randomUUID().toString();

        try (Connection conn = db.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                stmt.setString(1, playerUuid);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                stmt.setString(1, profileId);
                stmt.setString(2, playerUuid);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO islands (id, owner_profile_id, owner_account_uuid, created_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                stmt.setString(1, islandId.value().toString());
                stmt.setString(2, profileId);
                stmt.setString(3, playerUuid);
                stmt.setTimestamp(4, Timestamp.from(Instant.now()));
                stmt.executeUpdate();
            }
        }

        // Initially tier 0
        assertThat(adapter.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(0);
        assertThat(adapter.getUpgrades(islandId)).isEmpty();

        // Set tier 1
        adapter.setUpgradeTier(islandId, UpgradeId.SIZE, 1);
        assertThat(adapter.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(1);

        // Add second upgrade
        adapter.setUpgradeTier(islandId, UpgradeId.MEMBERS, 2);
        assertThat(adapter.getUpgradeTier(islandId, UpgradeId.MEMBERS)).isEqualTo(2);

        Map<UpgradeId, Integer> all = adapter.getUpgrades(islandId);
        assertThat(all).containsEntry(UpgradeId.SIZE, 1);
        assertThat(all).containsEntry(UpgradeId.MEMBERS, 2);

        // Upsert existing tier
        adapter.setUpgradeTier(islandId, UpgradeId.SIZE, 3);
        assertThat(adapter.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(3);
    }
}
