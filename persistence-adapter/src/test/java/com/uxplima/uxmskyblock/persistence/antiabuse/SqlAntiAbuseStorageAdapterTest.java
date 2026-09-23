package com.uxplima.uxmskyblock.persistence.antiabuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.antiabuse.IslandQuarantineRecord;
import com.uxplima.uxmskyblock.core.domain.antiabuse.PlayerAntiAbuseRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlAntiAbuseStorageAdapterTest {

    private Database database;
    private SqlAntiAbuseStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new SqlAntiAbuseStorageAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    private void seedAccountAndProfile(UUID playerUuid, UUID profileId) throws SQLException {
        try (Connection conn = database.connection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_accounts (player_uuid, active_profile_id) VALUES (?, ?)")) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, profileId.toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                stmt.setString(1, profileId.toString());
                stmt.setString(2, playerUuid.toString());
                stmt.executeUpdate();
            }
        }
    }

    private void seedIsland(UUID islandId, UUID ownerAccountUuid, UUID ownerProfileId) throws SQLException {
        seedAccountAndProfile(ownerAccountUuid, ownerProfileId);
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO islands (
                    id, owner_profile_id, owner_account_uuid, custom_name,
                    lifecycle, economic_state, administrative_state, version
                ) VALUES (?, ?, ?, 'Test Island', 'ACTIVE', 'NORMAL', 'NORMAL', 1)
                """)) {
            stmt.setString(1, islandId.toString());
            stmt.setString(2, ownerProfileId.toString());
            stmt.setString(3, ownerAccountUuid.toString());
            stmt.executeUpdate();
        }
    }

    @Test
    @DisplayName("Player anti-abuse record insert, find, and update roundtrip")
    void playerRecordCrudRoundtrip() {
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());

        // Initially empty
        Optional<PlayerAntiAbuseRecord> initial = adapter.findRecord(playerUuid);
        assertThat(initial).isEmpty();

        // Save new record
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        PlayerAntiAbuseRecord record =
                new PlayerAntiAbuseRecord(playerUuid, now, 1, now, now.plus(Duration.ofHours(24)));
        adapter.saveRecord(record);

        Optional<PlayerAntiAbuseRecord> loaded = adapter.findRecord(playerUuid);
        assertThat(loaded).isPresent();
        PlayerAntiAbuseRecord l = loaded.get();
        assertThat(l.playerUuid()).isEqualTo(playerUuid);
        assertThat(l.lastResetAt()).isEqualTo(now);
        assertThat(l.resetsTodayCount()).isEqualTo(1);
        assertThat(l.resetWindowStart()).isEqualTo(now);
        assertThat(l.coopCooldownExpiresAt()).isEqualTo(now.plus(Duration.ofHours(24)));

        // Update record (advance reset count)
        PlayerAntiAbuseRecord updated =
                new PlayerAntiAbuseRecord(playerUuid, now.plus(Duration.ofHours(6)), 2, now, null);
        adapter.saveRecord(updated);

        Optional<PlayerAntiAbuseRecord> reloaded = adapter.findRecord(playerUuid);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().resetsTodayCount()).isEqualTo(2);
        assertThat(reloaded.get().lastResetAt()).isEqualTo(now.plus(Duration.ofHours(6)));
        assertThat(reloaded.get().coopCooldownExpiresAt()).isNull();
    }

    @Test
    @DisplayName("Island quarantine CRUD and active filtering")
    void islandQuarantineCrud() throws SQLException {
        UUID islandUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        UUID profileUuid = UUID.randomUUID();
        seedIsland(islandUuid, playerUuid, profileUuid);

        IslandId islandId = new IslandId(islandUuid);

        // Initially empty
        assertThat(adapter.findQuarantine(islandId)).isEmpty();

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant until = now.plus(Duration.ofMinutes(15));
        IslandQuarantineRecord record = new IslandQuarantineRecord(islandId, until, "NEW_ISLAND_CREATION");

        adapter.saveQuarantine(record);

        Optional<IslandQuarantineRecord> loaded = adapter.findQuarantine(islandId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().islandId()).isEqualTo(islandId);
        assertThat(loaded.get().quarantinedUntil()).isEqualTo(until);
        assertThat(loaded.get().reason()).isEqualTo("NEW_ISLAND_CREATION");

        // Test loadActiveQuarantines
        Map<IslandId, IslandQuarantineRecord> active = adapter.loadActiveQuarantines(now);
        assertThat(active).containsKey(islandId);

        // Querying with timestamp past expiry returns empty map
        Map<IslandId, IslandQuarantineRecord> expired = adapter.loadActiveQuarantines(until.plusSeconds(1));
        assertThat(expired).doesNotContainKey(islandId);

        // Delete quarantine
        adapter.deleteQuarantine(islandId);
        assertThat(adapter.findQuarantine(islandId)).isEmpty();
    }

    @Test
    @DisplayName("An inventory purge a reset owes is kept, and cleared once paid")
    void anOwedPurgeIsKept() {
        PlayerUuid owing = PlayerUuid.of(java.util.UUID.randomUUID());
        adapter.saveRecord(PlayerAntiAbuseRecord.initial(owing).withInventoryPurgeOwed(true));

        assertThat(adapter.findRecord(owing).orElseThrow().inventoryPurgeOwed()).isTrue();

        adapter.saveRecord(adapter.findRecord(owing).orElseThrow().withInventoryPurgeOwed(false));

        assertThat(adapter.findRecord(owing).orElseThrow().inventoryPurgeOwed()).isFalse();
    }
}
