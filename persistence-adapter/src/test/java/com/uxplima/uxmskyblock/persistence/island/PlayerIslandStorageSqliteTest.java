package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFreezeRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIslandStorageSqliteTest {

    private Database database;
    private PlayerIslandStorageAdapter adapter;

    private final IslandId islandId = IslandId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final PlayerUuid ownerUuid = PlayerUuid.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private final ProfileId ownerProfileId = ProfileId.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private final ServerNodeId nodeAlpha = new ServerNodeId("node-alpha");
    private final ServerNodeId nodeBeta = new ServerNodeId("node-beta");
    private final Instant now = Instant.parse("2026-09-17T12:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }

        adapter = new PlayerIslandStorageAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("saveIsland persists island and location, and findIslandById reloads identical state")
    void saveAndFindIsland() {
        Island island = Island.create(
                islandId, IslandBounds.fromCenterAndRadius(1000, 2000, 100), ownerUuid, ownerProfileId, now);
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 1000, 2000, 100);

        adapter.saveIsland(island, location);

        Optional<Island> loaded = adapter.findIslandById(islandId);
        assertThat(loaded).isPresent();
        Island found = loaded.get();
        assertThat(found.id()).isEqualTo(islandId);
        assertThat(found.ownerPlayerUuid()).isEqualTo(ownerUuid);
        assertThat(found.ownerProfileId()).isEqualTo(ownerProfileId);
        assertThat(found.bounds().centerX()).isEqualTo(1000);
        assertThat(found.bounds().centerZ()).isEqualTo(2000);
        assertThat(found.bounds().radius()).isEqualTo(100);
        assertThat(found.isMember(ownerProfileId)).isTrue();
        assertThat(found.roleOf(ownerProfileId)).isEqualTo(IslandRole.OWNER);

        Optional<IslandLocation> loadedLoc = adapter.findLocationByIslandId(islandId);
        assertThat(loadedLoc).isPresent();
        assertThat(loadedLoc.get().worldName()).isEqualTo("skyblock_world");
        assertThat(loadedLoc.get().spawnX()).isEqualTo(1000.5);
        assertThat(loadedLoc.get().spawnZ()).isEqualTo(2000.5);
    }

    @Test
    @DisplayName("findIslandIdByProfileId resolves island for owner and members, empty for non-members")
    void findIslandIdByProfile() {
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), ownerUuid, ownerProfileId, now);
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 0, 0, 50);
        adapter.saveIsland(island, location);

        assertThat(adapter.findIslandIdByProfileId(ownerProfileId)).contains(islandId);

        ProfileId nonMember = ProfileId.of(UUID.fromString("99999999-9999-9999-9999-999999999999"));
        assertThat(adapter.findIslandIdByProfileId(nonMember)).isEmpty();
    }

    @Test
    @DisplayName("member additions, updates and removals persist across reloads")
    void memberLifecyclePersistence() {
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), ownerUuid, ownerProfileId, now);
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 0, 0, 50);
        adapter.saveIsland(island, location);

        PlayerUuid memberUuid = PlayerUuid.of(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        ProfileId memberProfile = ProfileId.of(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        IslandMember member = new IslandMember(memberUuid, memberProfile, IslandRole.MEMBER, now);

        Island withMember = island.addMember(member);
        adapter.saveIsland(withMember, location);

        Island loaded = adapter.findIslandById(islandId).orElseThrow();
        assertThat(loaded.isMember(memberProfile)).isTrue();
        assertThat(loaded.roleOf(memberProfile)).isEqualTo(IslandRole.MEMBER);

        // Remove member
        Island removed = loaded.removeMember(memberProfile);
        adapter.saveIsland(removed, location);

        Island loadedAfterRemove = adapter.findIslandById(islandId).orElseThrow();
        assertThat(loadedAfterRemove.isMember(memberProfile)).isFalse();
        assertThat(adapter.findIslandIdByProfileId(memberProfile)).isEmpty();
    }

    @Test
    @DisplayName("island flags and bounds updates persist cleanly")
    void flagsAndBoundsUpdates() {
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), ownerUuid, ownerProfileId, now);
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 0, 0, 50);
        adapter.saveIsland(island, location);

        Island updated = island.withFlags(island.flags().withFlag(IslandFlags.PVP, true))
                .withBounds(island.bounds().expand(25));
        IslandLocation updatedLocation =
                new IslandLocation(islandId, "skyblock_world", updated.bounds(), 0.5, 100.0, 0.5, 0.0f, 0.0f);
        adapter.saveIsland(updated, updatedLocation);

        Island loaded = adapter.findIslandById(islandId).orElseThrow();
        assertThat(loaded.flags().isEnabled(IslandFlags.PVP)).isTrue();
        assertThat(loaded.bounds().radius()).isEqualTo(75);
    }

    @Test
    @DisplayName("deleteIsland cascades and removes all associated data")
    void deleteIslandCascades() {
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), ownerUuid, ownerProfileId, now);
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 0, 0, 50);
        adapter.saveIsland(island, location);

        adapter.deleteIsland(islandId);

        assertThat(adapter.findIslandById(islandId)).isEmpty();
        assertThat(adapter.findLocationByIslandId(islandId)).isEmpty();
        assertThat(adapter.findIslandIdByProfileId(ownerProfileId)).isEmpty();
    }

    @Test
    @DisplayName("authority lifecycle: acquire -> renew -> takeover")
    void authorityLifecycle() throws Exception {
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), ownerUuid, ownerProfileId, now);
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 0, 0, 50);
        adapter.saveIsland(island, location);

        // 1. Initial acquire
        IslandAuthorityOutcome outcome = adapter.acquireAuthority(islandId, nodeAlpha, 15);
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(((IslandAuthorityOutcome.Success) outcome).epoch()).isEqualTo(1L);

        // 2. Duplicate acquire rejected
        assertThat(adapter.acquireAuthority(islandId, nodeAlpha, 15).isRejected())
                .isTrue();

        // 3. Renew authority by owner succeeds
        IslandAuthorityOutcome renewOutcome = adapter.renewAuthority(islandId, nodeAlpha, 1L, 15);
        assertThat(renewOutcome.isSuccess()).isTrue();
        assertThat(((IslandAuthorityOutcome.Success) renewOutcome).epoch()).isEqualTo(1L);

        // 4. Renew by wrong node or wrong epoch rejected
        assertThat(adapter.renewAuthority(islandId, nodeBeta, 1L, 15).isRejected())
                .isTrue();
        assertThat(adapter.renewAuthority(islandId, nodeAlpha, 2L, 15).isRejected())
                .isTrue();

        // 5. Takeover before lease expiry rejected
        assertThat(adapter.takeoverAuthority(islandId, nodeBeta, 1L, 15).isRejected())
                .isTrue();

        // 6. Force expire lease in DB
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "UPDATE island_authorities SET lease_expires_at = DATETIME('now', '-5 seconds') WHERE island_id = '"
                            + islandId + "'");
        }

        // 7. Takeover after lease expiry succeeds and increments epoch
        IslandAuthorityOutcome takeoverOutcome = adapter.takeoverAuthority(islandId, nodeBeta, 1L, 15);
        assertThat(takeoverOutcome.isSuccess()).isTrue();
        assertThat(((IslandAuthorityOutcome.Success) takeoverOutcome).epoch()).isEqualTo(2L);

        Optional<IslandAuthorityRecord> authority = adapter.findAuthority(islandId);
        assertThat(authority).isPresent();
        assertThat(authority.get().authoritativeNode()).isEqualTo(nodeBeta);
        assertThat(authority.get().authorityEpoch()).isEqualTo(2L);
    }

    @Test
    @DisplayName("findIslandByLocation returns island when point is inside bounds and empty otherwise")
    void spatialLookupByLocation() {
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(100, 200, 50), ownerUuid, ownerProfileId, now);
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 100, 200, 50);
        adapter.saveIsland(island, location);

        // Point inside bounds
        assertThat(adapter.findIslandByLocation("skyblock_world", 120, 210)).isPresent();
        assertThat(adapter.findIslandByLocation("skyblock_world", 120, 210)
                        .get()
                        .id())
                .isEqualTo(islandId);

        // Point outside bounds
        assertThat(adapter.findIslandByLocation("skyblock_world", 500, 500)).isEmpty();

        // Point in different world
        assertThat(adapter.findIslandByLocation("other_world", 120, 210)).isEmpty();
    }

    @Test
    @DisplayName("findAllByWorld returns all islands saved in that world")
    void findAllByWorld() {
        Island island1 =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), ownerUuid, ownerProfileId, now);
        IslandLocation loc1 = IslandLocation.fromCenterAndRadius(islandId, "world_a", 0, 0, 50);
        adapter.saveIsland(island1, loc1);

        IslandId id2 = IslandId.of(UUID.randomUUID());
        PlayerUuid owner2 = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile2 = ProfileId.of(UUID.randomUUID());
        Island island2 = Island.create(id2, IslandBounds.fromCenterAndRadius(200, 200, 50), owner2, profile2, now);
        IslandLocation loc2 = IslandLocation.fromCenterAndRadius(id2, "world_a", 200, 200, 50);
        adapter.saveIsland(island2, loc2);

        List<Island> worldAIslands = adapter.findAllByWorld("world_a");
        assertThat(worldAIslands).hasSize(2);
        assertThat(worldAIslands.stream().map(Island::id)).containsExactlyInAnyOrder(islandId, id2);

        List<Island> worldBIslands = adapter.findAllByWorld("world_b");
        assertThat(worldBIslands).isEmpty();
    }

    @Test
    @DisplayName("Administrative freeze updates and reads back freeze record and island state accurately")
    void freezeAndUnfreezePersistence() {
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), ownerUuid, ownerProfileId, now);
        IslandLocation loc = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 0, 0, 50);
        adapter.saveIsland(island, loc);

        // Initially normal
        Optional<IslandFreezeRecord> initialFreeze = adapter.findFreezeRecord(islandId);
        assertThat(initialFreeze).isPresent();
        assertThat(initialFreeze.get().isFrozen()).isFalse();

        // Freeze island
        adapter.updateAdministrativeState(islandId, AdministrativeState.FROZEN, "Staff quarantine for investigation");

        Optional<IslandFreezeRecord> frozenRecord = adapter.findFreezeRecord(islandId);
        assertThat(frozenRecord).isPresent();
        assertThat(frozenRecord.get().isFrozen()).isTrue();
        assertThat(frozenRecord.get().freezeReason()).isEqualTo("Staff quarantine for investigation");

        Optional<Island> frozenIsland = adapter.findIslandById(islandId);
        assertThat(frozenIsland).isPresent();
        assertThat(frozenIsland.get().isFrozen()).isTrue();
        assertThat(frozenIsland.get().administrativeState()).isEqualTo(AdministrativeState.FROZEN);
        assertThat(frozenIsland.get().freezeReason()).isEqualTo("Staff quarantine for investigation");

        // Unfreeze island
        adapter.updateAdministrativeState(islandId, AdministrativeState.NORMAL, null);

        Optional<IslandFreezeRecord> unfrozenRecord = adapter.findFreezeRecord(islandId);
        assertThat(unfrozenRecord).isPresent();
        assertThat(unfrozenRecord.get().isFrozen()).isFalse();

        Optional<Island> unfrozenIsland = adapter.findIslandById(islandId);
        assertThat(unfrozenIsland).isPresent();
        assertThat(unfrozenIsland.get().isFrozen()).isFalse();
        assertThat(unfrozenIsland.get().administrativeState()).isEqualTo(AdministrativeState.NORMAL);
        assertThat(unfrozenIsland.get().freezeReason()).isNull();
    }

    @Test
    @DisplayName("Economic state and lifecycle transitions persist and reload accurately")
    void economicAndLifecycleStatePersistence() {
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), ownerUuid, ownerProfileId, now);
        IslandLocation loc = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 0, 0, 50);
        adapter.saveIsland(island, loc);

        adapter.updateEconomicState(islandId, EconomicState.BANKRUPTCY_GRACE);
        adapter.updateLifecycle(islandId, IslandLifecycle.DELETING);

        Optional<Island> reloaded = adapter.findIslandById(islandId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().economicState()).isEqualTo(EconomicState.BANKRUPTCY_GRACE);
        assertThat(reloaded.get().lifecycle()).isEqualTo(IslandLifecycle.DELETING);
    }
}
