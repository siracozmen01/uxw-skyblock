package com.uxplima.uxmskyblock.bukkit.antiabuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandAntiAbuseListenerTest extends MockBukkitHarness {

    private IslandStoragePort mockStoragePort;
    private IslandAntiAbuseService mockService;
    private AntiAbuseConfiguration configuration;
    private IslandAntiAbuseListener listener;

    private World world;
    private Island sampleIsland;
    private IslandId islandId;
    private Player ownerPlayer;
    private Player visitorPlayer;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        mockStoragePort = mock(IslandStoragePort.class);
        mockService = mock(IslandAntiAbuseService.class);
        when(mockService.clock()).thenReturn(Clock.systemUTC());

        configuration = AntiAbuseConfiguration.defaultConfiguration();
        listener = new IslandAntiAbuseListener(mockStoragePort, mockService, configuration, Messages.bundled());

        ownerPlayer = createPlayer("OwnerPlayer");
        visitorPlayer = createPlayer("VisitorPlayer");

        islandId = new IslandId(UUID.randomUUID());
        sampleIsland = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(ownerPlayer.getUniqueId()),
                new ProfileId(ownerPlayer.getUniqueId()),
                Instant.now());

        listener.cacheIsland(sampleIsland);
    }

    @Test
    @DisplayName("PlayerDropItemEvent is cancelled when island is quarantined")
    void dropItemCancelledWhenQuarantined() {
        ownerPlayer.teleport(new Location(world, 10, 64, 10));
        when(mockService.isIslandQuarantined(any(), any())).thenReturn(true);
        when(mockService.getQuarantineRemaining(any(), any())).thenReturn(Optional.of(Duration.ofMinutes(10)));

        Item droppedItem = mock(Item.class);
        when(droppedItem.getItemStack()).thenReturn(new ItemStack(Material.LAVA_BUCKET));
        PlayerDropItemEvent event = new PlayerDropItemEvent(ownerPlayer, droppedItem);

        listener.onPlayerDropItem(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("PlayerDropItemEvent succeeds when player has quarantine bypass permission")
    void dropItemAllowedWithBypass() {
        ownerPlayer.teleport(new Location(world, 10, 64, 10));
        ownerPlayer.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(),
                configuration.quarantineBypassPermission(),
                true);
        when(mockService.isIslandQuarantined(any(), any())).thenReturn(true);

        Item droppedItem = mock(Item.class);
        when(droppedItem.getItemStack()).thenReturn(new ItemStack(Material.ICE));
        PlayerDropItemEvent event = new PlayerDropItemEvent(ownerPlayer, droppedItem);

        listener.onPlayerDropItem(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("PlayerDropItemEvent succeeds when island is not quarantined")
    void dropItemAllowedWhenNotQuarantined() {
        ownerPlayer.teleport(new Location(world, 10, 64, 10));
        when(mockService.isIslandQuarantined(any(), any())).thenReturn(false);

        Item droppedItem = mock(Item.class);
        when(droppedItem.getItemStack()).thenReturn(new ItemStack(Material.DIAMOND));
        PlayerDropItemEvent event = new PlayerDropItemEvent(ownerPlayer, droppedItem);

        listener.onPlayerDropItem(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Visitor teleport into quarantined island is cancelled")
    void teleportVisitorCancelledWhenQuarantined() {
        Location target = new Location(world, 20, 64, 20);
        when(mockService.isIslandQuarantined(any(), any())).thenReturn(true);
        when(mockService.getQuarantineRemaining(any(), any())).thenReturn(Optional.of(Duration.ofMinutes(12)));

        PlayerTeleportEvent event = new PlayerTeleportEvent(
                visitorPlayer, visitorPlayer.getLocation(), target, PlayerTeleportEvent.TeleportCause.COMMAND);

        listener.onPlayerTeleport(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Owner teleport into quarantined island is allowed")
    void teleportOwnerAllowedWhenQuarantined() {
        Location target = new Location(world, 20, 64, 20);
        when(mockService.isIslandQuarantined(any(), any())).thenReturn(true);

        PlayerTeleportEvent event = new PlayerTeleportEvent(
                ownerPlayer, ownerPlayer.getLocation(), target, PlayerTeleportEvent.TeleportCause.COMMAND);

        listener.onPlayerTeleport(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Visitor with bypass permission can teleport into quarantined island")
    void teleportVisitorAllowedWithBypass() {
        Location target = new Location(world, 20, 64, 20);
        visitorPlayer.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(),
                configuration.quarantineBypassPermission(),
                true);
        when(mockService.isIslandQuarantined(any(), any())).thenReturn(true);

        PlayerTeleportEvent event = new PlayerTeleportEvent(
                visitorPlayer, visitorPlayer.getLocation(), target, PlayerTeleportEvent.TeleportCause.COMMAND);

        listener.onPlayerTeleport(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Visitor moving into quarantined island is cancelled")
    void moveVisitorCancelledWhenEnteringQuarantinedIsland() {
        Location from = new Location(world, 100, 64, 100); // Outside bounds (radius 50)
        Location to = new Location(world, 10, 64, 10); // Inside bounds

        when(mockService.isIslandQuarantined(any(), any())).thenReturn(true);
        when(mockService.getQuarantineRemaining(any(), any())).thenReturn(Optional.of(Duration.ofMinutes(14)));

        PlayerMoveEvent event = new PlayerMoveEvent(visitorPlayer, from, to);

        listener.onPlayerMove(event);

        assertThat(event.isCancelled()).isTrue();
    }
}
