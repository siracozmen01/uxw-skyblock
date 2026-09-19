package com.uxplima.uxmskyblock.persistence.booster;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class SqlIslandBoosterStorageAdapterTest {

    private Database database;
    private SqlIslandBoosterStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new SqlIslandBoosterStorageAdapter(database);
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
    @DisplayName("Booster insert, find, update, and delete roundtrip")
    void boosterCrudRoundtrip() throws SQLException {
        UUID islandUuid = UUID.randomUUID();
        seedIsland(islandUuid, UUID.randomUUID(), UUID.randomUUID());
        IslandId islandId = new IslandId(islandUuid);

        Instant now = Instant.parse("2026-09-19T12:00:00Z");
        IslandBooster booster =
                IslandBooster.create(islandId, BoosterCategory.SPAWNER_RATE, 2.0, Duration.ofHours(2), now);

        // Initially empty
        assertThat(adapter.findById(booster.id())).isEmpty();

        // Save
        adapter.saveBooster(booster);

        Optional<IslandBooster> loaded = adapter.findById(booster.id());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo(booster.id());
        assertThat(loaded.get().islandId()).isEqualTo(islandId);
        assertThat(loaded.get().category()).isEqualTo(BoosterCategory.SPAWNER_RATE);
        assertThat(loaded.get().multiplier()).isEqualTo(2.0);
        assertThat(loaded.get().isPaused()).isFalse();

        // Update (pause it)
        IslandBooster paused = booster.withPause(now.plus(Duration.ofMinutes(30)));
        adapter.saveBooster(paused);

        Optional<IslandBooster> reloaded = adapter.findById(booster.id());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isPaused()).isTrue();
        assertThat(reloaded.get().remainingSeconds()).isEqualTo(90 * 60);

        // Delete
        adapter.deleteById(booster.id());
        assertThat(adapter.findById(booster.id())).isEmpty();
    }

    @Test
    @DisplayName("Query by island and category filters appropriately")
    void queryByIslandAndCategory() throws SQLException {
        UUID islandUuid = UUID.randomUUID();
        seedIsland(islandUuid, UUID.randomUUID(), UUID.randomUUID());
        IslandId islandId = new IslandId(islandUuid);

        Instant now = Instant.parse("2026-09-19T12:00:00Z");
        IslandBooster b1 = IslandBooster.create(islandId, BoosterCategory.CROP_GROWTH, 1.5, Duration.ofHours(1), now);
        IslandBooster b2 = IslandBooster.create(islandId, BoosterCategory.MOB_EXP, 2.0, Duration.ofHours(1), now);

        adapter.saveBooster(b1);
        adapter.saveBooster(b2);

        List<IslandBooster> all = adapter.findByIsland(islandId);
        assertThat(all).hasSize(2);

        List<IslandBooster> crops = adapter.findByIslandAndCategory(islandId, BoosterCategory.CROP_GROWTH);
        assertThat(crops).hasSize(1);
        assertThat(crops.getFirst().id()).isEqualTo(b1.id());

        List<IslandBooster> mobs = adapter.findByIslandAndCategory(islandId, BoosterCategory.MOB_EXP);
        assertThat(mobs).hasSize(1);
        assertThat(mobs.getFirst().id()).isEqualTo(b2.id());
    }

    @Test
    @DisplayName("purgeExpired purges unpaused expired boosters but keeps paused ones")
    void purgeExpired() throws SQLException {
        UUID islandUuid = UUID.randomUUID();
        seedIsland(islandUuid, UUID.randomUUID(), UUID.randomUUID());
        IslandId islandId = new IslandId(islandUuid);

        Instant now = Instant.parse("2026-09-19T12:00:00Z");
        // Expired unpaused booster (expires at now - 10m)
        IslandBooster b1 = new IslandBooster(
                UUID.randomUUID(),
                islandId,
                BoosterCategory.SPAWNER_RATE,
                1.5,
                now.minus(Duration.ofMinutes(10)),
                now.minus(Duration.ofHours(1)),
                null,
                0);

        // Paused booster whose original expires_at was now - 10m, but was paused at now - 20m with 10m remaining
        IslandBooster b2 = new IslandBooster(
                UUID.randomUUID(),
                islandId,
                BoosterCategory.CROP_GROWTH,
                2.0,
                now.minus(Duration.ofMinutes(10)),
                now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofMinutes(20)),
                600);

        adapter.saveBooster(b1);
        adapter.saveBooster(b2);

        int purged = adapter.purgeExpired(now);
        assertThat(purged).isEqualTo(1);

        assertThat(adapter.findById(b1.id())).isEmpty();
        assertThat(adapter.findById(b2.id())).isPresent();
    }

    @Test
    @DisplayName("deleteByIsland removes all boosters for that island")
    void deleteByIsland() throws SQLException {
        UUID islandUuid = UUID.randomUUID();
        seedIsland(islandUuid, UUID.randomUUID(), UUID.randomUUID());
        IslandId islandId = new IslandId(islandUuid);

        Instant now = Instant.parse("2026-09-19T12:00:00Z");
        adapter.saveBooster(IslandBooster.create(islandId, BoosterCategory.MOB_EXP, 1.5, Duration.ofHours(1), now));
        adapter.saveBooster(
                IslandBooster.create(islandId, BoosterCategory.SPAWNER_RATE, 2.0, Duration.ofHours(1), now));

        assertThat(adapter.findByIsland(islandId)).hasSize(2);
        adapter.deleteByIsland(islandId);
        assertThat(adapter.findByIsland(islandId)).isEmpty();
    }
}
