package com.uxplima.uxmskyblock.core.application.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultStoragePort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpStoragePort;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UpgradeServiceWiringIntegrationTest {

    private InMemoryUpgradeStorage upgradeStorage;
    private IslandUpgradeService upgradeService;
    private IslandWarpStoragePort warpStorage;
    private IslandVaultStoragePort vaultStorage;
    private IslandWarpService warpService;
    private IslandVaultService vaultService;

    private IslandId islandId;
    private Island island;
    private PlayerUuid ownerUuid;
    private ProfileId ownerProfileId;

    @BeforeEach
    void setUp() {
        islandId = IslandId.of(UUID.randomUUID());
        ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ownerProfileId = ProfileId.of(UUID.randomUUID());

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        island = Island.create(islandId, bounds, ownerUuid, ownerProfileId, Instant.now());

        upgradeStorage = new InMemoryUpgradeStorage();

        UpgradeDefinition warpDef = new UpgradeDefinition(
                UpgradeId.WARPS,
                "Island Warps",
                List.of(
                        new UpgradeTier(1, 1000L, "PRIMARY", Map.of("limit", 5.0)),
                        new UpgradeTier(2, 2000L, "PRIMARY", Map.of("limit", 10.0))));

        UpgradeDefinition vaultDef = new UpgradeDefinition(
                UpgradeId.VAULT_PAGES,
                "Vault Pages",
                List.of(
                        new UpgradeTier(1, 1000L, "PRIMARY", Map.of("pages", 3.0)),
                        new UpgradeTier(2, 2000L, "PRIMARY", Map.of("pages", 6.0))));

        upgradeService = new IslandUpgradeService(
                upgradeStorage,
                Map.of(
                        UpgradeId.WARPS, warpDef,
                        UpgradeId.VAULT_PAGES, vaultDef));

        warpStorage = mock(IslandWarpStoragePort.class);
        warpService = new IslandWarpService(
                warpStorage,
                new SafeTeleportEngine(5),
                upgradeService,
                2, // base warp limit = 2
                null);

        vaultStorage = mock(IslandVaultStoragePort.class);
        vaultService = new IslandVaultService(
                vaultStorage,
                upgradeService,
                1, // base pages = 1
                10,
                Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("warp service enforces upgrade tier limits dynamically")
    void warpServiceEnforcesUpgradeLimits() {
        // Base tier: limit is 2
        assertThat(warpService.getMaxAllowedWarps(islandId)).isEqualTo(2);

        // When island already has 2 warps, creating another fails at tier 0
        when(warpStorage.countWarpsByIsland(islandId)).thenReturn(2);
        assertThatThrownBy(() -> warpService.createWarp(
                        island,
                        ownerProfileId,
                        new WarpName("warp3"),
                        new WarpLocation("world", 20, 64, 20, 0, 0),
                        WarpCategory.GENERAL,
                        "STONE"))
                .isInstanceOf(WarpLimitExceededException.class);

        // Advance tier to 1: limit becomes 5
        upgradeStorage.setUpgradeTier(islandId, UpgradeId.WARPS, 1);
        upgradeService.invalidateCache(islandId);
        assertThat(warpService.getMaxAllowedWarps(islandId)).isEqualTo(5);

        // Now with count = 2, creating warp succeeds because 2 < 5
        when(warpStorage.findWarpByName(eq(islandId), any())).thenReturn(Optional.empty());
        warpService.createWarp(
                island,
                ownerProfileId,
                new WarpName("warp3"),
                new WarpLocation("world", 20, 64, 20, 0, 0),
                WarpCategory.GENERAL,
                "STONE");
    }

    @Test
    @DisplayName("vault service enforces upgrade tier page limits dynamically")
    void vaultServiceEnforcesUpgradePageLimits() {
        // Base tier: 1 page allowed
        assertThat(vaultService.getMaxAllowedPages(islandId)).isEqualTo(1);

        VaultPage mockPage = mock(VaultPage.class);
        VaultEditSession mockSession = mock(VaultEditSession.class);
        when(vaultStorage.findPage(eq(islandId), anyInt())).thenReturn(Optional.of(mockPage));
        when(vaultStorage.acquireEditSession(eq(islandId), anyInt(), eq(ownerUuid.value()), any()))
                .thenReturn(Optional.of(mockSession));

        // Page 1 is accessible
        vaultService.openVaultPage(
                island, ownerProfileId, IslandRole.OWNER, 1, ownerUuid.value(), Duration.ofMinutes(1));

        // Page 2 fails
        assertThatThrownBy(() -> vaultService.openVaultPage(
                        island, ownerProfileId, IslandRole.OWNER, 2, ownerUuid.value(), Duration.ofMinutes(1)))
                .isInstanceOf(VaultPageLimitExceededException.class);

        // Advance tier to 2: 6 pages allowed
        upgradeStorage.setUpgradeTier(islandId, UpgradeId.VAULT_PAGES, 2);
        upgradeService.invalidateCache(islandId);
        assertThat(vaultService.getMaxAllowedPages(islandId)).isEqualTo(6);

        // Page 2 and Page 6 now accessible
        vaultService.openVaultPage(
                island, ownerProfileId, IslandRole.OWNER, 2, ownerUuid.value(), Duration.ofMinutes(1));
        vaultService.openVaultPage(
                island, ownerProfileId, IslandRole.OWNER, 6, ownerUuid.value(), Duration.ofMinutes(1));

        // Page 7 still exceeds
        assertThatThrownBy(() -> vaultService.openVaultPage(
                        island, ownerProfileId, IslandRole.OWNER, 7, ownerUuid.value(), Duration.ofMinutes(1)))
                .isInstanceOf(VaultPageLimitExceededException.class);
    }

    private static final class InMemoryUpgradeStorage implements IslandUpgradeStoragePort {
        private final Map<IslandId, Map<UpgradeId, Integer>> storage = new HashMap<>();

        @Override
        public int getUpgradeTier(IslandId islandId, UpgradeId upgradeId) {
            return storage.getOrDefault(islandId, Map.of()).getOrDefault(upgradeId, 0);
        }

        @Override
        public boolean compareAndSetUpgradeTier(IslandId id, UpgradeId upgradeId, int expectedTier, int newTier) {

            if (getUpgradeTier(id, upgradeId) != expectedTier) {

                return false;
            }

            setUpgradeTier(id, upgradeId, newTier);

            return true;
        }

        @Override
        public void setUpgradeTier(IslandId islandId, UpgradeId upgradeId, int tier) {
            storage.computeIfAbsent(islandId, k -> new HashMap<>()).put(upgradeId, tier);
        }

        @Override
        public Map<UpgradeId, Integer> getUpgrades(IslandId islandId) {
            return storage.getOrDefault(islandId, Map.of());
        }
    }
}
