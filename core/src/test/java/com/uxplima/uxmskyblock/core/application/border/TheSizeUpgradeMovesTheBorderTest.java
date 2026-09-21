package com.uxplima.uxmskyblock.core.application.border;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island that pays for a bigger island gets a bigger island.
 *
 * <p>The size upgrade had five tiers, a cost for each and a radius on each, and nothing anywhere
 * read the radius. The bounds written when the island was created were the bounds it had for ever,
 * and the protection index, the boundary warning and every block check read those bounds. A player
 * could pay a million for the top tier and not gain one block.
 */
class TheSizeUpgradeMovesTheBorderTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private FakeIslandStorage storage;
    private FakeUpgradeStorage upgrades;
    private IslandUpgradeService upgradeService;
    private IslandSizeAllowance allowance;
    private IslandBorderService borderService;

    private static final UpgradeDefinition SIZE = new UpgradeDefinition(
            UpgradeId.SIZE,
            "Island Size",
            List.of(
                    new UpgradeTier(1, 0L, "PRIMARY", Map.of("radius", 25.0)),
                    new UpgradeTier(2, 50_000L, "PRIMARY", Map.of("radius", 37.5)),
                    new UpgradeTier(3, 150_000L, "PRIMARY", Map.of("radius", 50.0))));

    @BeforeEach
    void setUp() {
        storage = new FakeIslandStorage();
        upgrades = new FakeUpgradeStorage();
        upgradeService = new IslandUpgradeService(upgrades, Map.of(UpgradeId.SIZE, SIZE));
        allowance = new IslandSizeAllowance(upgradeService);
        borderService = new IslandBorderService(storage, allowance, new IslandMutationLock());
        storage.put(islandAt(25));
    }

    @Test
    @DisplayName("An island that has bought nothing reaches as far as the first tier says")
    void theBaseIsTheFirstTier() {
        assertThat(allowance.baseRadius()).isEqualTo(25);
        assertThat(allowance.applyAsInt(ISLAND)).isEqualTo(25);
    }

    @Test
    @DisplayName("Buying a tier moves the island's edge, and the stored bounds are what moved")
    void buyingATierMovesTheEdge() {
        upgrades.setUpgradeTier(ISLAND, UpgradeId.SIZE, 3);
        upgradeService.invalidateCache(ISLAND);

        Optional<IslandBorderService.Moved> moved = borderService.applyAllowance(ISLAND);

        assertThat(moved).isPresent();
        assertThat(moved.orElseThrow().fromRadius()).isEqualTo(25);
        assertThat(moved.orElseThrow().toRadius()).isEqualTo(50);
        assertThat(storage.findLocationByIslandId(ISLAND).orElseThrow().bounds().radius())
                .describedAs("the radius the protection index will read")
                .isEqualTo(50);
        assertThat(storage.findIslandById(ISLAND).orElseThrow().bounds().radius())
                .isEqualTo(50);
    }

    @Test
    @DisplayName("The island grows around its centre rather than away from it")
    void theCentreDoesNotMove() {
        upgrades.setUpgradeTier(ISLAND, UpgradeId.SIZE, 2);
        upgradeService.invalidateCache(ISLAND);

        borderService.applyAllowance(ISLAND);

        IslandBounds bounds =
                storage.findLocationByIslandId(ISLAND).orElseThrow().bounds();
        assertThat(bounds.centerX()).isEqualTo(512);
        assertThat(bounds.centerZ()).isEqualTo(1024);
        assertThat(bounds.minX()).isEqualTo(512 - 38);
        assertThat(bounds.maxX()).isEqualTo(512 + 38);
    }

    @Test
    @DisplayName("An edge already where the tier says is not written again")
    void anUnchangedEdgeIsNotWritten() {
        int writesBefore = storage.writes;

        assertThat(borderService.applyAllowance(ISLAND)).isEmpty();
        assertThat(storage.writes)
                .describedAs("writes of an island nothing changed")
                .isEqualTo(writesBefore);
    }

    @Test
    @DisplayName("The spawn point survives the island growing around it")
    void theSpawnSurvives() {
        upgrades.setUpgradeTier(ISLAND, UpgradeId.SIZE, 3);
        upgradeService.invalidateCache(ISLAND);

        borderService.applyAllowance(ISLAND);

        IslandLocation location = storage.findLocationByIslandId(ISLAND).orElseThrow();
        assertThat(location.spawnY()).isEqualTo(101.0);
        assertThat(location.worldName()).isEqualTo("world");
    }

    @Test
    @DisplayName("Buying the tier is what moves the edge, with nobody having to remember to ask")
    void aPurchaseMovesTheEdgeOnItsOwn() {
        // Free tiers, so the purchase never reaches a bank: what is under test is the tier moving
        // the edge, not the settlement, which its own tests already pin.
        UpgradeDefinition free = new UpgradeDefinition(
                UpgradeId.SIZE,
                "Island Size",
                List.of(
                        new UpgradeTier(1, 0L, "PRIMARY", Map.of("radius", 25.0)),
                        new UpgradeTier(2, 0L, "PRIMARY", Map.of("radius", 37.5))));
        IslandUpgradeService buying = new IslandUpgradeService(upgrades, Map.of(UpgradeId.SIZE, free));
        IslandBorderService border =
                new IslandBorderService(storage, new IslandSizeAllowance(buying), new IslandMutationLock());
        java.util.List<IslandId> moved = new java.util.ArrayList<>();
        buying.whenUpgraded((islandId, upgradeId, newTier) -> {
            if (UpgradeId.SIZE.equals(upgradeId)) {
                border.applyAllowance(islandId);
                moved.add(islandId);
            }
        });

        // The island is on the first tier, which is where an island that has bought nothing sits.
        upgrades.setUpgradeTier(ISLAND, UpgradeId.SIZE, 1);

        buying.purchaseUpgrade(
                ISLAND,
                UpgradeId.SIZE,
                UUID.randomUUID(),
                org.mockito.Mockito.mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankPort.class),
                "node-1",
                1L);

        assertThat(moved)
                .describedAs("islands whose edge was moved by the purchase")
                .containsExactly(ISLAND);
        assertThat(storage.findLocationByIslandId(ISLAND).orElseThrow().bounds().radius())
                .describedAs("radius after buying tier 2")
                .isEqualTo(38);
    }

    private static Map.Entry<Island, IslandLocation> islandAt(int radius) {
        ProfileId owner = new ProfileId(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(512, 1024, radius);
        Island island = Island.create(
                ISLAND, bounds, new PlayerUuid(owner.value()), owner, Instant.parse("2026-09-21T12:00:00Z"));
        IslandLocation location = new IslandLocation(ISLAND, "world", bounds, 512.5, 101.0, 1024.5, 0.0f, 0.0f);
        return Map.entry(island, location);
    }

    private static final class FakeIslandStorage implements IslandStoragePort {
        private final Map<IslandId, Island> islands = new HashMap<>();
        private final Map<IslandId, IslandLocation> locations = new HashMap<>();
        private final Map<ProfileId, IslandId> byProfile = new HashMap<>();
        int writes;

        void put(Map.Entry<Island, IslandLocation> pair) {
            saveIsland(pair.getKey(), pair.getValue());
            writes = 0;
        }

        @Override
        public void saveIsland(Island island, IslandLocation location) {
            islands.put(island.id(), island);
            locations.put(island.id(), location);
            byProfile.put(island.ownerProfileId(), island.id());
            writes++;
        }

        @Override
        public Optional<Island> findIslandById(IslandId islandId) {
            return Optional.ofNullable(islands.get(islandId));
        }

        @Override
        public Optional<IslandLocation> findLocationByIslandId(IslandId islandId) {
            return Optional.ofNullable(locations.get(islandId));
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            return Optional.ofNullable(byProfile.get(profileId));
        }

        @Override
        public void deleteIsland(IslandId islandId) {
            islands.remove(islandId);
            locations.remove(islandId);
        }
    }

    private static final class FakeUpgradeStorage implements IslandUpgradeStoragePort {
        private final Map<IslandId, Map<UpgradeId, Integer>> tiers = new HashMap<>();

        @Override
        public int getUpgradeTier(IslandId islandId, UpgradeId upgradeId) {
            return tiers.getOrDefault(islandId, Map.of()).getOrDefault(upgradeId, 0);
        }

        @Override
        public void setUpgradeTier(IslandId islandId, UpgradeId upgradeId, int tier) {
            tiers.computeIfAbsent(islandId, key -> new HashMap<>()).put(upgradeId, tier);
        }

        @Override
        public boolean compareAndSetUpgradeTier(IslandId islandId, UpgradeId upgradeId, int expected, int next) {
            if (getUpgradeTier(islandId, upgradeId) != expected) {
                return false;
            }
            setUpgradeTier(islandId, upgradeId, next);
            return true;
        }

        @Override
        public Map<UpgradeId, Integer> getUpgrades(IslandId islandId) {
            return Map.copyOf(tiers.getOrDefault(islandId, Map.of()));
        }
    }
}
