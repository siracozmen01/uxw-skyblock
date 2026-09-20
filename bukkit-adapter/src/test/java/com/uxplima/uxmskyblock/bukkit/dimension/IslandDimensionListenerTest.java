package com.uxplima.uxmskyblock.bukkit.dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMode;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandDimensionListenerTest extends MockBukkitHarness {

    private IslandDimensionService dimensionService;
    private IslandUpgradeStoragePort upgradeStoragePort;
    private IslandLocationService islandLocationService;
    private StarterSchematicEngine schematicEngine;
    private IslandDimensionListener listener;

    private World overworld;
    private World netherWorld;
    private World endWorld;
    private Player player;
    private ProfileId profileId;
    private IslandId islandId;

    private static final UpgradeId NETHER_UPGRADE = new UpgradeId("island_nether");
    private static final UpgradeId END_UPGRADE = new UpgradeId("island_end");

    @BeforeEach
    void setUp() {
        overworld = server.addSimpleWorld("skyblock_world");
        netherWorld = server.addSimpleWorld("skyblock_nether");
        endWorld = server.addSimpleWorld("skyblock_the_end");

        player = createPlayer("DimensionTraveller");
        profileId = new ProfileId(player.getUniqueId());
        islandId = new IslandId(UUID.randomUUID());

        upgradeStoragePort = mock(IslandUpgradeStoragePort.class);
        islandLocationService = mock(IslandLocationService.class);
        schematicEngine = mock(StarterSchematicEngine.class);

        Map<IslandDimensionType, DimensionMapping> mappings = Map.of(
                IslandDimensionType.OVERWORLD,
                new DimensionMapping(
                        IslandDimensionType.OVERWORLD, DimensionMode.PRIVATE_ISLAND, "skyblock_world", null, null),
                IslandDimensionType.NETHER,
                new DimensionMapping(
                        IslandDimensionType.NETHER,
                        DimensionMode.PRIVATE_ISLAND,
                        "skyblock_nether",
                        NETHER_UPGRADE,
                        "island_nether"),
                IslandDimensionType.THE_END,
                new DimensionMapping(
                        IslandDimensionType.THE_END,
                        DimensionMode.SHARED_WORLD,
                        "skyblock_the_end",
                        END_UPGRADE,
                        null));

        dimensionService = new IslandDimensionService(upgradeStoragePort, mappings);

        DirectSchedulerPort directScheduler = new DirectSchedulerPort();
        listener = new IslandDimensionListener(
                dimensionService,
                islandLocationService,
                schematicEngine,
                directScheduler,
                p -> Optional.of(profileId),
                "skyblock_world");

        when(islandLocationService.findIslandId(profileId)).thenReturn(Optional.of(islandId));
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(100, 200, 50);
        IslandLocation loc = new IslandLocation(islandId, "skyblock_world", bounds, 100.5, 65.0, 200.5, 0f, 0f);
        when(islandLocationService.findLocation(islandId)).thenReturn(Optional.of(loc));
        when(islandLocationService.resolveHome(profileId)).thenReturn(Optional.of(loc));
    }

    @Test
    @DisplayName("Portal event is ignored when cause is not nether or end portal")
    void ignoresUnrelatedPortalCauses() {
        Location from = new Location(overworld, 0, 64, 0);
        Location to = new Location(netherWorld, 0, 64, 0);
        PlayerPortalEvent event = new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.COMMAND);

        listener.onPlayerPortal(event);
        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Portal entry to Nether cancels when required upgrade is locked")
    void netherPortalLockedCancelsEvent() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(0);

        Location from = new Location(overworld, 100, 64, 200);
        Location to = new Location(netherWorld, 0, 64, 0);
        PlayerPortalEvent event =
                new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPlayerPortal(event);
        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("First-time Nether entry generates platform and cancels vanilla portal")
    void netherPortalFirstVisitPastesSchematic() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(1);

        Location from = new Location(overworld, 100, 64, 200);
        Location to = new Location(netherWorld, 0, 64, 0);
        PlayerPortalEvent event =
                new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPlayerPortal(event);

        assertThat(event.isCancelled()).isTrue();
        verify(schematicEngine)
                .pasteDimensionPlatform(eq(netherWorld), eq(100), eq(64), eq(200), eq(IslandDimensionType.NETHER));
        assertThat(dimensionService.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isTrue();
    }

    @Test
    @DisplayName("Subsequent Nether portal entry routes directly to dimension coordinates")
    void netherPortalSubsequentVisitRoutesDirectly() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(1);
        dimensionService.markDimensionGenerated(islandId, IslandDimensionType.NETHER);

        Location from = new Location(overworld, 100, 64, 200);
        Location to = new Location(netherWorld, 0, 64, 0);
        PlayerPortalEvent event =
                new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPlayerPortal(event);

        assertThat(event.isCancelled()).isFalse();
        assertThat(event.getTo()).isNotNull();
        assertThat(event.getTo().getWorld()).isEqualTo(netherWorld);
        assertThat(event.getTo().getBlockX()).isEqualTo(100);
        assertThat(event.getTo().getBlockZ()).isEqualTo(200);
        verify(schematicEngine, never()).pasteDimensionPlatform(any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("Shared world dimension routes to world spawn")
    void sharedWorldRoutesToSpawn() {
        when(upgradeStoragePort.getUpgradeTier(islandId, END_UPGRADE)).thenReturn(1);

        Location from = new Location(overworld, 100, 64, 200);
        Location to = new Location(endWorld, 0, 64, 0);
        PlayerPortalEvent event = new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPlayerPortal(event);

        assertThat(event.isCancelled()).isFalse();
        assertThat(event.getTo()).isEqualTo(endWorld.getSpawnLocation());
    }

    @Test
    @DisplayName("Portal from Nether returns player to Overworld island home")
    void portalFromNetherReturnsToIslandHome() {
        Location from = new Location(netherWorld, 100, 64, 200);
        Location to = new Location(overworld, 0, 64, 0);
        PlayerPortalEvent event =
                new PlayerPortalEvent(player, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPlayerPortal(event);

        assertThat(event.isCancelled()).isFalse();
        assertThat(event.getTo()).isNotNull();
        assertThat(event.getTo().getWorld()).isEqualTo(overworld);
        assertThat(event.getTo().getX()).isEqualTo(100.5);
        assertThat(event.getTo().getY()).isEqualTo(65.0);
        assertThat(event.getTo().getZ()).isEqualTo(200.5);
    }

    @Test
    @DisplayName("executeDimensionTeleport safely teleports player to dimension")
    void executeDimensionTeleportSuccess() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(1);

        listener.executeDimensionTeleport(player, IslandDimensionType.NETHER);

        verify(schematicEngine)
                .pasteDimensionPlatform(eq(netherWorld), eq(100), eq(64), eq(200), eq(IslandDimensionType.NETHER));
        assertThat(dimensionService.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isTrue();
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

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }
    }
}
