package com.uxplima.uxmskyblock.bukkit.integration.placeholder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.OfflinePlayer;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkyblockPlaceholderExpansionTest {

    private IslandStoragePort mockStorage;
    private IslandBankPort mockBank;
    private IslandUpgradeStoragePort mockUpgrades;
    private IslandLeaderboardPort mockLeaderboard;
    private DirectSchedulerPort scheduler;

    private SkyblockPlaceholderExpansion expansion;
    private OfflinePlayer mockPlayer;
    private UUID playerUuid;
    private ProfileId profileId;
    private IslandId islandId;

    @BeforeEach
    void setUp() {
        mockStorage = mock(IslandStoragePort.class);
        mockBank = mock(IslandBankPort.class);
        mockUpgrades = mock(IslandUpgradeStoragePort.class);
        mockLeaderboard = mock(IslandLeaderboardPort.class);
        scheduler = new DirectSchedulerPort();

        playerUuid = UUID.randomUUID();
        profileId = new ProfileId(playerUuid);
        islandId = new IslandId(UUID.randomUUID());

        expansion = new SkyblockPlaceholderExpansion(
                mockStorage, mockBank, mockUpgrades, mockLeaderboard, scheduler, uuid -> Optional.of(profileId));

        mockPlayer = mock(OfflinePlayer.class);
        when(mockPlayer.getUniqueId()).thenReturn(playerUuid);
    }

    @Test
    @DisplayName("returns empty and false defaults when player has no island")
    void returnsDefaultsWhenPlayerHasNoIsland() {
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.empty());

        assertThat(expansion.onRequest(mockPlayer, "has_island")).isEqualTo("false");
        assertThat(expansion.onRequest(mockPlayer, "island_id")).isEqualTo("");
        assertThat(expansion.onRequest(mockPlayer, "island_role")).isEqualTo("");
        assertThat(expansion.onRequest(mockPlayer, "island_members_count")).isEqualTo("0");
        assertThat(expansion.onRequest(mockPlayer, "island_bank_balance")).isEqualTo("0.00");
        assertThat(expansion.onRequest(mockPlayer, "island_bank_crystals")).isEqualTo("0");
        assertThat(expansion.onRequest(mockPlayer, "island_upgrade_tier_size")).isEqualTo("0");
        assertThat(expansion.onRequest(mockPlayer, "island_leaderboard_rank")).isEqualTo("N/A");
    }

    @Test
    @DisplayName("resolves populated island placeholders via onRequest and registry")
    void resolvesPopulatedIslandPlaceholders() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        Island island = Island.create(islandId, bounds, new PlayerUuid(playerUuid), profileId, Instant.now());

        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.of(islandId));
        when(mockStorage.findIslandById(eq(islandId))).thenReturn(Optional.of(island));

        IslandBank bank = new IslandBank(islandId, 15000L, 25L, 100L, 1L, Instant.now());
        when(mockBank.findBankByIslandId(eq(islandId))).thenReturn(Optional.of(bank));

        when(mockUpgrades.getUpgrades(eq(islandId)))
                .thenReturn(Map.of(UpgradeId.of("size"), 2, UpgradeId.of("spawners"), 4));

        LeaderboardEntry entry = new LeaderboardEntry(1, islandId, "Island-1", 500L, "500");
        when(mockLeaderboard.fetchTopIslands(eq(LeaderboardCategory.LEVEL), eq(100)))
                .thenReturn(List.of(entry));

        // Trigger sync refresh for immediate testing
        expansion.refreshPlayerDataSync(playerUuid);

        assertThat(expansion.onRequest(mockPlayer, "has_island")).isEqualTo("true");
        assertThat(expansion.onRequest(mockPlayer, "island_id"))
                .isEqualTo(islandId.value().toString());
        assertThat(expansion.onRequest(mockPlayer, "island_role")).isEqualTo("OWNER");
        assertThat(expansion.onRequest(mockPlayer, "island_members_count")).isEqualTo("1");
        assertThat(expansion.onRequest(mockPlayer, "island_bank_balance")).isEqualTo("150.00");
        assertThat(expansion.onRequest(mockPlayer, "island_bank_balance_raw")).isEqualTo("15000");
        assertThat(expansion.onRequest(mockPlayer, "island_bank_crystals")).isEqualTo("25");
        assertThat(expansion.onRequest(mockPlayer, "island_upgrade_tier_size")).isEqualTo("2");
        assertThat(expansion.onRequest(mockPlayer, "island_upgrade_tier_spawners"))
                .isEqualTo("4");
        assertThat(expansion.onRequest(mockPlayer, "island_upgrade_tier_unknown"))
                .isEqualTo("0");
        assertThat(expansion.onRequest(mockPlayer, "island_leaderboard_rank")).isEqualTo("1");

        // Verify dispatch through PlaceholderRegistry
        assertThat(expansion.registry().resolve(mockPlayer, "has_island")).isEqualTo("true");
        assertThat(expansion.registry().resolve(mockPlayer, "island_bank_balance"))
                .isEqualTo("150.00");
    }

    @Test
    @DisplayName("resolves global leaderboard placeholders without player from cached data")
    void resolvesGlobalLeaderboardPlaceholders() {
        LeaderboardEntry entry = new LeaderboardEntry(1, islandId, "Island-1", 9999L, "9999");
        when(mockLeaderboard.fetchTopIslands(eq(LeaderboardCategory.LEVEL), eq(100)))
                .thenReturn(List.of(entry));

        expansion.refreshLeaderboardsSync();

        assertThat(expansion.onRequest(null, "leaderboard_top_level_1_score")).isEqualTo("9999");
        assertThat(expansion.onRequest(null, "leaderboard_top_level_1_id"))
                .isEqualTo(islandId.value().toString());
    }

    @Test
    @DisplayName("PAPI-001: onRequest performs ZERO persistence I/O on cache miss and throws if persistence is queried inside onRequest")
    void onRequestZeroPersistenceIoOnCacheMiss() {
        IslandLeaderboardPort throwingLeaderboard = mock(IslandLeaderboardPort.class);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            throw new AssertionError("Zero-DB violation: persistence queried inside onRequest!");
                        })
                .when(throwingLeaderboard)
                .fetchTopIslands(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());

        SkyblockPlaceholderExpansion noIoExpansion = new SkyblockPlaceholderExpansion(
                mockStorage,
                mockBank,
                mockUpgrades,
                throwingLeaderboard,
                new SchedulerPort() {
                    @Override
                    public void onGlobal(Runnable task) {}

                    @Override
                    public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {}

                    @Override
                    public void onEntity(PlayerUuid playerUuid, Runnable task) {}

                    @Override
                    public void async(Runnable task) {
                        // Background task scheduled asynchronously, never run inline inside onRequest
                    }

                    @Override
                    public void asyncAfter(Duration delay, Runnable task) {}
                },
                uuid -> Optional.of(profileId));

        // When requesting leaderboard top on empty cache, it returns default N/A without querying persistence
        assertThat(noIoExpansion.onRequest(null, "leaderboard_top_level_1_score")).isEqualTo("N/A");
        assertThat(noIoExpansion.onRequest(null, "leaderboard_top_level_1_id")).isEqualTo("N/A");
        assertThat(noIoExpansion.onRequest(mockPlayer, "island_leaderboard_rank")).isEqualTo("N/A");
    }

    @Test
    @DisplayName("cache invalidation removes player entry")
    void cacheInvalidationWorks() {
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.empty());
        expansion.refreshPlayerDataSync(playerUuid);

        assertThat(expansion.onRequest(mockPlayer, "has_island")).isEqualTo("false");

        // Now invalidate
        expansion.invalidate(playerUuid);

        // Setting up island now
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        Island island = Island.create(islandId, bounds, new PlayerUuid(playerUuid), profileId, Instant.now());
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.of(islandId));
        when(mockStorage.findIslandById(eq(islandId))).thenReturn(Optional.of(island));
        when(mockBank.findBankByIslandId(eq(islandId)))
                .thenReturn(Optional.of(new IslandBank(islandId, 500L, 0L, 0L, 1L, Instant.now())));

        expansion.refreshPlayerDataSync(playerUuid);
        assertThat(expansion.onRequest(mockPlayer, "has_island")).isEqualTo("true");
    }

    private static class DirectSchedulerPort implements SchedulerPort {
        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
        }
    }
}
