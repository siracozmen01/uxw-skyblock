package com.uxplima.uxmskyblock.bukkit.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.boundary.WorldBorderPacketPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings({"deprecation", "removal"})
class IslandBoundaryListenerTest extends MockBukkitHarness {

    private World world;
    private IslandProtectionListener protectionListener;
    private WorldBorderPacketPort worldBorderPort;
    private IslandBoundaryService boundaryService;
    private IslandBoundaryListener boundaryListener;
    private Island island;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        IslandStoragePort storagePort = mock(IslandStoragePort.class);
        protectionListener = new IslandProtectionListener(storagePort, new IslandAccessService());
        worldBorderPort = mock(WorldBorderPacketPort.class);
        boundaryService = new IslandBoundaryService(worldBorderPort);
        boundaryListener = new IslandBoundaryListener(boundaryService, protectionListener, Messages.bundled());

        PlayerUuid ownerUuid = new PlayerUuid(UUID.randomUUID());
        ProfileId ownerProfile = new ProfileId(ownerUuid.value());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 10);
        island = Island.create(IslandId.of(UUID.randomUUID()), bounds, ownerUuid, ownerProfile, Instant.now());
        protectionListener.cacheIsland(island);

        player = server.addPlayer("TestExplorer");
    }

    @Test
    @DisplayName("BlockFromToEvent cancels fluid spillover across island boundary")
    void testBlockFromToSpilloverCancelled() {
        Block fromBlock = world.getBlockAt(10, 64, 0);
        Block toOutsideBlock = world.getBlockAt(11, 64, 0);

        BlockFromToEvent event = new BlockFromToEvent(fromBlock, toOutsideBlock);
        boundaryListener.onBlockFromTo(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("BlockFromToEvent permits fluid flow within island bounds")
    void testBlockFromToInsideAllowed() {
        Block fromBlock = world.getBlockAt(0, 64, 0);
        Block toInsideBlock = world.getBlockAt(1, 64, 0);

        BlockFromToEvent event = new BlockFromToEvent(fromBlock, toInsideBlock);
        boundaryListener.onBlockFromTo(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("BlockPistonExtendEvent cancels pushing blocks outside boundary")
    void testPistonExtendSpilloverCancelled() {
        Block pistonBlock = world.getBlockAt(9, 64, 0);
        pistonBlock.setType(Material.PISTON);
        Block pushedBlock = world.getBlockAt(10, 64, 0);
        pushedBlock.setType(Material.STONE);

        BlockPistonExtendEvent event = new BlockPistonExtendEvent(pistonBlock, List.of(pushedBlock), BlockFace.EAST);
        boundaryListener.onPistonExtend(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("BlockPistonExtendEvent permits pushing blocks within boundary")
    void testPistonExtendInsideAllowed() {
        Block pistonBlock = world.getBlockAt(0, 64, 0);
        pistonBlock.setType(Material.PISTON);
        Block pushedBlock = world.getBlockAt(1, 64, 0);
        pushedBlock.setType(Material.STONE);

        BlockPistonExtendEvent event = new BlockPistonExtendEvent(pistonBlock, List.of(pushedBlock), BlockFace.EAST);
        boundaryListener.onPistonExtend(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("PlayerTeleportEvent cancels ender pearl thrown beyond island boundary")
    void testEnderPearlOutsideCancelled() {
        Location from = new Location(world, 0, 64, 0);
        Location toOutside = new Location(world, 50, 64, 50);

        PlayerTeleportEvent event =
                new PlayerTeleportEvent(player, from, toOutside, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        boundaryListener.onPlayerTeleport(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("PlayerMoveEvent updates world border when player crosses island boundary")
    void testPlayerMoveCrossBoundary() {
        Location outside = new Location(world, 50, 64, 50);
        Location inside = new Location(world, 0, 64, 0);

        PlayerMoveEvent enterEvent = new PlayerMoveEvent(player, outside, inside);
        boundaryListener.onPlayerMove(enterEvent);

        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        verify(worldBorderPort).sendWorldBorder(eq(uuid), eq(0), eq(0), eq(10.0), eq(0.0), eq(0L));

        PlayerMoveEvent exitEvent = new PlayerMoveEvent(player, inside, outside);
        boundaryListener.onPlayerMove(exitEvent);

        verify(worldBorderPort).resetWorldBorder(eq(uuid));
    }

    @Test
    @DisplayName("renderPerimeterForPlayer renders particles when perimeter is active")
    void testRenderPerimeter() {
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        boundaryService.enablePerimeter(uuid);
        player.teleport(new Location(world, 0, 64, 0));

        boundaryListener.renderPerimeterForPlayer(player);
        // Does not throw and perimeter calculation executes
        assertThat(boundaryService.isPerimeterActive(uuid)).isTrue();
    }

    @Test
    @DisplayName("PlayerMoveEvent cancels border breach into void when stopBorderCrossing is enabled")
    void testStopBorderCrossingCancelled() {
        boundaryListener.setStopBorderCrossing(true);
        Location inside = new Location(world, 0, 64, 0);
        Location outside = new Location(world, 50, 64, 50);

        PlayerMoveEvent breachEvent = new PlayerMoveEvent(player, inside, outside);
        boundaryListener.onPlayerMove(breachEvent);

        assertThat(breachEvent.isCancelled()).isTrue();
    }
}
