package com.uxplima.uxmskyblock.core.application.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.limit.IslandLimitCheckResult;
import com.uxplima.uxmskyblock.core.domain.limit.LimitCategory;
import com.uxplima.uxmskyblock.core.domain.limit.LimitQuota;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandLimitServiceTest {

    private IslandUpgradeStoragePort upgradeStoragePort;
    private IslandLimitService limitService;
    private IslandId islandId;

    private static final UpgradeId HOPPER_UPGRADE = new UpgradeId("HOPPER_LIMIT");

    @BeforeEach
    void setUp() {
        upgradeStoragePort = mock(IslandUpgradeStoragePort.class);
        islandId = new IslandId(UUID.randomUUID());

        Map<LimitType, LimitQuota> quotas = Map.of(
                LimitType.HOPPER, new LimitQuota(50, HOPPER_UPGRADE, 25),
                LimitType.SPAWNER, new LimitQuota(10, null, 0),
                LimitType.VILLAGER, new LimitQuota(15, null, 0));

        limitService = new IslandLimitService(upgradeStoragePort, quotas);
    }

    @Test
    @DisplayName("Initial limit without upgrades matches base limit")
    void initialLimitMatchesBase() {
        when(upgradeStoragePort.getUpgradeTier(islandId, HOPPER_UPGRADE)).thenReturn(0);

        int limit = limitService.getEffectiveLimit(islandId, LimitType.HOPPER);
        assertThat(limit).isEqualTo(50);
        assertThat(limitService.getCount(islandId, LimitType.HOPPER)).isEqualTo(0);
    }

    @Test
    @DisplayName("Limit scales dynamically with upgrade tier")
    void limitScalesWithUpgradeTier() {
        when(upgradeStoragePort.getUpgradeTier(islandId, HOPPER_UPGRADE)).thenReturn(2);

        int limit = limitService.getEffectiveLimit(islandId, LimitType.HOPPER);
        assertThat(limit).isEqualTo(100); // 50 + 2 * 25
    }

    @Test
    @DisplayName("Increment succeeds while below limit")
    void incrementSucceedsBelowLimit() {
        when(upgradeStoragePort.getUpgradeTier(islandId, HOPPER_UPGRADE)).thenReturn(0);

        boolean incremented = limitService.tryIncrement(islandId, LimitType.HOPPER, false);
        assertThat(incremented).isTrue();
        assertThat(limitService.getCount(islandId, LimitType.HOPPER)).isEqualTo(1);

        IslandLimitCheckResult result = limitService.checkPlacement(islandId, LimitType.HOPPER, false);
        assertThat(result).isInstanceOf(IslandLimitCheckResult.Allowed.class);
        IslandLimitCheckResult.Allowed allowed = (IslandLimitCheckResult.Allowed) result;
        assertThat(allowed.current()).isEqualTo(1);
        assertThat(allowed.max()).isEqualTo(50);
    }

    @Test
    @DisplayName("Increment fails and check returns LimitReached when cap hit")
    void incrementFailsWhenCapReached() {
        when(upgradeStoragePort.getUpgradeTier(islandId, HOPPER_UPGRADE)).thenReturn(0);

        limitService.setCount(islandId, LimitType.HOPPER, 50);

        boolean incremented = limitService.tryIncrement(islandId, LimitType.HOPPER, false);
        assertThat(incremented).isFalse();
        assertThat(limitService.getCount(islandId, LimitType.HOPPER)).isEqualTo(50);

        IslandLimitCheckResult result = limitService.checkPlacement(islandId, LimitType.HOPPER, false);
        assertThat(result).isInstanceOf(IslandLimitCheckResult.LimitReached.class);
        IslandLimitCheckResult.LimitReached reached = (IslandLimitCheckResult.LimitReached) result;
        assertThat(reached.current()).isEqualTo(50);
        assertThat(reached.max()).isEqualTo(50);
    }

    @Test
    @DisplayName("Bypass flag allows incrementing beyond limit")
    void bypassAllowsExceedingLimit() {
        when(upgradeStoragePort.getUpgradeTier(islandId, HOPPER_UPGRADE)).thenReturn(0);

        limitService.setCount(islandId, LimitType.HOPPER, 50);

        boolean incremented = limitService.tryIncrement(islandId, LimitType.HOPPER, true);
        assertThat(incremented).isTrue();
        assertThat(limitService.getCount(islandId, LimitType.HOPPER)).isEqualTo(51);

        IslandLimitCheckResult result = limitService.checkPlacement(islandId, LimitType.HOPPER, true);
        assertThat(result).isInstanceOf(IslandLimitCheckResult.Bypassed.class);
    }

    @Test
    @DisplayName("Decrement reduces count but does not drop below zero")
    void decrementReducesCountFlooredAtZero() {
        limitService.setCount(islandId, LimitType.SPAWNER, 2);

        limitService.decrement(islandId, LimitType.SPAWNER);
        assertThat(limitService.getCount(islandId, LimitType.SPAWNER)).isEqualTo(1);

        limitService.decrement(islandId, LimitType.SPAWNER);
        assertThat(limitService.getCount(islandId, LimitType.SPAWNER)).isEqualTo(0);

        limitService.decrement(islandId, LimitType.SPAWNER);
        assertThat(limitService.getCount(islandId, LimitType.SPAWNER)).isEqualTo(0);
    }

    @Test
    @DisplayName("Clear island resets all tracked counts to zero")
    void clearIslandResetsCounts() {
        limitService.setCount(islandId, LimitType.HOPPER, 10);
        limitService.setCount(islandId, LimitType.VILLAGER, 5);

        limitService.clearIsland(islandId);

        assertThat(limitService.getCount(islandId, LimitType.HOPPER)).isEqualTo(0);
        assertThat(limitService.getCount(islandId, LimitType.VILLAGER)).isEqualTo(0);
    }

    @Test
    @DisplayName("LimitType categories are correctly classified")
    void limitTypeCategories() {
        assertThat(LimitType.HOPPER.category()).isEqualTo(LimitCategory.TILE_ENTITY);
        assertThat(LimitType.VILLAGER.category()).isEqualTo(LimitCategory.ENTITY);
        assertThat(LimitType.SPAWNER.category()).isEqualTo(LimitCategory.TILE_ENTITY);
        assertThat(LimitType.ARMOR_STAND.category()).isEqualTo(LimitCategory.ENTITY);
    }
}
