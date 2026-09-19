package com.uxplima.uxmskyblock.bukkit.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandLimitReconcilerTest extends MockBukkitHarness {

    private SchedulerPort mockScheduler;
    private IslandLimitService mockLimitService;
    private IslandLimitReconciler reconciler;

    private World world;
    private Island island;
    private IslandId islandId;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        mockScheduler = mock(SchedulerPort.class);
        mockLimitService = mock(IslandLimitService.class);

        // Synchronously execute tasks passed to onRegion
        doAnswer(invocation -> {
                    Runnable task = invocation.getArgument(3);
                    task.run();
                    return null;
                })
                .when(mockScheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));

        reconciler = new IslandLimitReconciler(mockScheduler, mockLimitService);

        islandId = new IslandId(UUID.randomUUID());
        // Island bounds within chunk (0,0): x in [3, 13], z in [3, 13]
        island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(8, 8, 5),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }

    @Test
    @DisplayName("reconcileIsland counts tile and entity limits and updates limitService")
    void reconcileIslandAuditsAccurately() {
        // Ensure chunk (0,0) is loaded in mock world
        world.getChunkAt(0, 0).load();

        // Spawn a Villager inside bounds (8, 65, 8)
        Location insideLoc = new Location(world, 8, 65, 8);
        world.spawnEntity(insideLoc, org.bukkit.entity.EntityType.VILLAGER);

        // Spawn another Villager outside bounds (500, 65, 500)
        Location outsideLoc = new Location(world, 500, 65, 500);
        world.spawnEntity(outsideLoc, org.bukkit.entity.EntityType.VILLAGER);

        CompletableFuture<Map<LimitType, Integer>> future = reconciler.reconcileIsland(island, "skyblock_world");

        Map<LimitType, Integer> results = future.join();
        assertThat(results).isNotNull();
        // Only the villager inside bounds is counted
        assertThat(results.get(LimitType.VILLAGER)).isEqualTo(1);

        verify(mockLimitService).setCount(islandId, LimitType.VILLAGER, 1);
    }

    @Test
    @DisplayName("reconcileIsland with no tiles or entities returns zero counts")
    void reconcileIslandEmptyWorldReturnsZeroCounts() {
        world.getChunkAt(0, 0).load();

        CompletableFuture<Map<LimitType, Integer>> future = reconciler.reconcileIsland(island, "skyblock_world");

        Map<LimitType, Integer> results = future.join();
        assertThat(results).isNotNull();
        assertThat(results.get(LimitType.HOPPER)).isEqualTo(0);
        assertThat(results.get(LimitType.VILLAGER)).isEqualTo(0);

        verify(mockLimitService).setCount(islandId, LimitType.HOPPER, 0);
        verify(mockLimitService).setCount(islandId, LimitType.VILLAGER, 0);
    }
}
