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

import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
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
    private org.bukkit.entity.Player travelling;

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
                "skyblock_world",
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        when(islandLocationService.findIslandId(profileId)).thenReturn(Optional.of(islandId));
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(100, 200, 50);
        IslandLocation loc = new IslandLocation(islandId, "skyblock_world", bounds, 100.5, 65.0, 200.5, 0f, 0f);
        when(islandLocationService.findLocation(islandId)).thenReturn(Optional.of(loc));
        when(islandLocationService.resolveHome(profileId)).thenReturn(Optional.of(loc));

        // MockBukkit does not implement teleportAsync, and a portal now moves the player rather
        // than rewriting the event's destination. The spy answers the call so the test can read
        // where the player was actually sent.
        travelling = org.mockito.Mockito.spy(player);
        org.mockito.Mockito.doAnswer(invocation -> java.util.concurrent.CompletableFuture.completedFuture(true))
                .when(travelling)
                .teleportAsync(org.mockito.ArgumentMatchers.any(Location.class));
    }

    /** Where the player was sent, or empty when nothing sent them anywhere. */
    private Optional<Location> sentTo() {
        org.mockito.ArgumentCaptor<Location> captor = org.mockito.ArgumentCaptor.forClass(Location.class);
        org.mockito.Mockito.verify(travelling, org.mockito.Mockito.atLeast(0)).teleportAsync(captor.capture());
        return captor.getAllValues().isEmpty()
                ? Optional.empty()
                : Optional.of(captor.getAllValues().get(captor.getAllValues().size() - 1));
    }

    @Test
    @DisplayName("Portal event is ignored when cause is not nether or end portal")
    void ignoresUnrelatedPortalCauses() {
        Location from = new Location(overworld, 0, 64, 0);
        Location to = new Location(netherWorld, 0, 64, 0);
        PlayerPortalEvent event =
                new PlayerPortalEvent(travelling, from, to, PlayerTeleportEvent.TeleportCause.COMMAND);

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
                new PlayerPortalEvent(travelling, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

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
                new PlayerPortalEvent(travelling, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPlayerPortal(event);

        assertThat(event.isCancelled()).isTrue();
        verify(schematicEngine)
                .pasteDimensionPlatform(eq(netherWorld), eq(100), eq(64), eq(200), eq(IslandDimensionType.NETHER));
        assertThat(dimensionService.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isTrue();
        assertThat(sentTo())
                .describedAs("the player was put down on the new platform")
                .isPresent();
    }

    @Test
    @DisplayName("Subsequent Nether portal entry routes directly to dimension coordinates")
    void netherPortalSubsequentVisitRoutesDirectly() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(1);
        dimensionService.markDimensionGenerated(islandId, IslandDimensionType.NETHER);

        Location from = new Location(overworld, 100, 64, 200);
        Location to = new Location(netherWorld, 0, 64, 0);
        PlayerPortalEvent event =
                new PlayerPortalEvent(travelling, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPlayerPortal(event);

        // The portal is refused and the travel is done off the region thread, so the destination is
        // where the player was sent rather than where the event was pointed.
        assertThat(event.isCancelled()).isTrue();
        Location destination = sentTo().orElseThrow();
        assertThat(destination.getWorld()).isEqualTo(netherWorld);
        assertThat(destination.getBlockX()).isEqualTo(100);
        assertThat(destination.getBlockZ()).isEqualTo(200);
        verify(schematicEngine, never()).pasteDimensionPlatform(any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("Shared world dimension routes to world spawn")
    void sharedWorldRoutesToSpawn() {
        when(upgradeStoragePort.getUpgradeTier(islandId, END_UPGRADE)).thenReturn(1);

        Location from = new Location(overworld, 100, 64, 200);
        Location to = new Location(endWorld, 0, 64, 0);
        PlayerPortalEvent event =
                new PlayerPortalEvent(travelling, from, to, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPlayerPortal(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(sentTo()).contains(endWorld.getSpawnLocation());
    }

    @Test
    @DisplayName("Portal from Nether returns player to Overworld island home")
    void portalFromNetherReturnsToIslandHome() {
        Location from = new Location(netherWorld, 100, 64, 200);
        Location to = new Location(overworld, 0, 64, 0);
        PlayerPortalEvent event =
                new PlayerPortalEvent(travelling, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPlayerPortal(event);

        assertThat(event.isCancelled()).isTrue();
        Location home = sentTo().orElseThrow();
        assertThat(home.getWorld()).isEqualTo(overworld);
        assertThat(home.getX()).isEqualTo(100.5);
        assertThat(home.getY()).isEqualTo(65.0);
        assertThat(home.getZ()).isEqualTo(200.5);
    }

    @Test
    @DisplayName("executeDimensionTeleport safely teleports player to dimension")
    void executeDimensionTeleportSuccess() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(1);

        boolean teleportAttempted = false;
        try {
            listener.executeDimensionTeleport(player, IslandDimensionType.NETHER);
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException unimplemented) {
            // MockBukkit does not implement teleportAsync, and JUnit reads its exception as an
            // assumption failure, which would turn this test into a silent skip rather than a
            // failure. The teleport is a fact to assert on instead.
            assertThat(unimplemented.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().contains("teleport"));
            teleportAttempted = true;
        }

        assertThat(teleportAttempted).describedAs("the player was put down").isTrue();
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

    @Test
    @DisplayName("A player still standing in the portal does not start the same journey twice")
    void standingInThePortalStartsOneJourney() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(1);
        dimensionService.markDimensionGenerated(islandId, IslandDimensionType.NETHER);
        // The debounce is a delayed task, so a scheduler that runs a delayed task at once would
        // lift it immediately and prove nothing. This one holds it, the way a real one does.
        IslandDimensionListener onDeferring = new IslandDimensionListener(
                dimensionService,
                islandLocationService,
                schematicEngine,
                new DeferringSchedulerPort(),
                p -> Optional.of(profileId),
                "skyblock_world",
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        Location from = new Location(overworld, 100, 64, 200);
        Location to = new Location(netherWorld, 0, 64, 0);
        for (int tick = 0; tick < 3; tick++) {
            onDeferring.onPlayerPortal(
                    new PlayerPortalEvent(travelling, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));
        }

        org.mockito.Mockito.verify(travelling, org.mockito.Mockito.times(1))
                .teleportAsync(org.mockito.ArgumentMatchers.any(Location.class));
    }

    /** A scheduler that holds a delayed task rather than running it, which is what a delay is. */
    private static class DeferringSchedulerPort extends DirectSchedulerPort {
        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            // Held, not run. A real one would run it when the delay is up.
        }
    }

    @Test
    @DisplayName("The portal answer never reads a row on the thread the event runs on")
    void thePortalReadsNothingOnTheEventThread() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(1);
        dimensionService.markDimensionGenerated(islandId, IslandDimensionType.NETHER);
        RecordingSchedulerPort recording = new RecordingSchedulerPort();
        IslandDimensionListener onRecording = new IslandDimensionListener(
                dimensionService,
                islandLocationService,
                schematicEngine,
                recording,
                p -> Optional.of(profileId),
                "skyblock_world",
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        Location from = new Location(overworld, 100, 64, 200);
        Location to = new Location(netherWorld, 0, 64, 0);
        onRecording.onPlayerPortal(
                new PlayerPortalEvent(travelling, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));

        org.mockito.Mockito.verify(islandLocationService, org.mockito.Mockito.never())
                .findIslandId(org.mockito.ArgumentMatchers.any());
        assertThat(recording.asyncCalls)
                .describedAs("the travel was handed to the scheduler rather than run where the event was")
                .isEqualTo(1);
    }

    /** A scheduler that counts what was handed to it and runs nothing, so the event thread stays bare. */
    private static class RecordingSchedulerPort extends DirectSchedulerPort {
        int asyncCalls;

        @Override
        public void onGlobal(Runnable task) {}

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {}

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {}

        @Override
        public void async(Runnable task) {
            asyncCalls++;
        }
    }
}
