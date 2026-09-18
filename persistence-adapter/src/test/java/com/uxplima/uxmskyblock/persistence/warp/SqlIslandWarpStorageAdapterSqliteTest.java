package com.uxplima.uxmskyblock.persistence.warp;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.warp.IslandBan;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlIslandWarpStorageAdapterSqliteTest {

    private Database database;
    private SqlIslandWarpStorageAdapter adapter;

    private final IslandId islandId = IslandId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final ProfileId ownerProfileId = ProfileId.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private final PlayerUuid visitorUuid = PlayerUuid.of(UUID.fromString("44444444-4444-4444-4444-444444444444"));

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new SqlIslandWarpStorageAdapter(database);

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("INSERT INTO player_accounts (player_uuid) VALUES ('22222222-2222-2222-2222-222222222222');");
            stmt.execute(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', 'CLASSIC');");
            stmt.execute("INSERT INTO player_accounts (player_uuid) VALUES ('44444444-4444-4444-4444-444444444444');");
            stmt.execute(
                    "INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES ('11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222');");
        }
    }

    @AfterEach
    void tearDown() {
        if (!database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Saves, retrieves, updates and counts island warps")
    void saveRetrieveUpdateWarp() {
        IslandWarpId warpId = IslandWarpId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        WarpLocation loc = new WarpLocation("skyblock_world", 10.5, 64.0, 20.5, 90.0f, 0.0f);
        IslandWarp warp = new IslandWarp(
                warpId, islandId, WarpName.of("market"), loc, "CHEST", WarpCategory.SHOPS, false, now, now);

        adapter.saveWarp(warp);

        assertThat(adapter.countWarpsByIsland(islandId)).isEqualTo(1);

        Optional<IslandWarp> loadedById = adapter.findWarpById(warpId);
        assertThat(loadedById).isPresent();
        assertThat(loadedById.get().name().value()).isEqualTo("market");
        assertThat(loadedById.get().category()).isEqualTo(WarpCategory.SHOPS);
        assertThat(loadedById.get().location().worldName()).isEqualTo("skyblock_world");
        assertThat(loadedById.get().location().x()).isEqualTo(10.5);
        assertThat(loadedById.get().isLocked()).isFalse();

        Optional<IslandWarp> loadedByName = adapter.findWarpByName(islandId, WarpName.of("market"));
        assertThat(loadedByName).isPresent();
        assertThat(loadedByName.get().id()).isEqualTo(warpId);

        // Update warp
        IslandWarp updated = loadedById.get().withLocked(true).withCategory(WarpCategory.GENERAL);
        adapter.saveWarp(updated);

        Optional<IslandWarp> reloaded = adapter.findWarpById(warpId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isLocked()).isTrue();
        assertThat(reloaded.get().category()).isEqualTo(WarpCategory.GENERAL);
    }

    @Test
    @DisplayName("Retrieves public warps with category filtering and pagination")
    void publicWarpsDirectory() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        // Public shops warp
        adapter.saveWarp(new IslandWarp(
                IslandWarpId.random(),
                islandId,
                WarpName.of("shop1"),
                new WarpLocation("world", 0, 64, 0, 0, 0),
                "CHEST",
                WarpCategory.SHOPS,
                false,
                now,
                now));

        // Public farm warp
        adapter.saveWarp(new IslandWarp(
                IslandWarpId.random(),
                islandId,
                WarpName.of("farm1"),
                new WarpLocation("world", 10, 64, 10, 0, 0),
                "WHEAT",
                WarpCategory.FARMS,
                false,
                now,
                now));

        // Locked warp (should NOT appear in public queries)
        adapter.saveWarp(new IslandWarp(
                IslandWarpId.random(),
                islandId,
                WarpName.of("vault"),
                new WarpLocation("world", 20, 64, 20, 0, 0),
                "IRON_DOOR",
                WarpCategory.GENERAL,
                true,
                now,
                now));

        List<IslandWarp> publicWarps = adapter.findPublicWarps(10, 0);
        assertThat(publicWarps).hasSize(2);

        List<IslandWarp> shops = adapter.findPublicWarpsByCategory(WarpCategory.SHOPS, 10, 0);
        assertThat(shops).hasSize(1);
        assertThat(shops.get(0).name().value()).isEqualTo("shop1");

        List<IslandWarp> farms = adapter.findPublicWarpsByCategory(WarpCategory.FARMS, 10, 0);
        assertThat(farms).hasSize(1);
        assertThat(farms.get(0).name().value()).isEqualTo("farm1");

        List<IslandWarp> parkour = adapter.findPublicWarpsByCategory(WarpCategory.PARKOUR, 10, 0);
        assertThat(parkour).isEmpty();
    }

    @Test
    @DisplayName("Deletes warp by name successfully")
    void deleteWarp() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        adapter.saveWarp(new IslandWarp(
                IslandWarpId.random(),
                islandId,
                WarpName.of("to_delete"),
                new WarpLocation("world", 0, 64, 0, 0, 0),
                "SIGN",
                WarpCategory.GENERAL,
                false,
                now,
                now));

        assertThat(adapter.findWarpByName(islandId, WarpName.of("to_delete"))).isPresent();

        boolean deleted = adapter.deleteWarp(islandId, WarpName.of("to_delete"));
        assertThat(deleted).isTrue();
        assertThat(adapter.findWarpByName(islandId, WarpName.of("to_delete"))).isEmpty();

        boolean deleteMissing = adapter.deleteWarp(islandId, WarpName.of("to_delete"));
        assertThat(deleteMissing).isFalse();
    }

    @Test
    @DisplayName("Persists, queries, and removes island visitor bans")
    void banManagement() {
        assertThat(adapter.isPlayerBanned(islandId, visitorUuid)).isFalse();

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        IslandBan ban = new IslandBan(islandId, visitorUuid, ownerProfileId, "Greifing farm", now);
        adapter.banPlayer(ban);

        assertThat(adapter.isPlayerBanned(islandId, visitorUuid)).isTrue();
        List<IslandBan> bans = adapter.findBansByIsland(islandId);
        assertThat(bans).hasSize(1);
        assertThat(bans.get(0).bannedPlayerUuid()).isEqualTo(visitorUuid);
        assertThat(bans.get(0).reason()).isEqualTo("Greifing farm");

        // Unban player
        boolean unbanned = adapter.unbanPlayer(islandId, visitorUuid);
        assertThat(unbanned).isTrue();
        assertThat(adapter.isPlayerBanned(islandId, visitorUuid)).isFalse();
        assertThat(adapter.findBansByIsland(islandId)).isEmpty();
    }
}
