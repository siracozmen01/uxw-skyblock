package com.uxplima.uxmskyblock.bukkit.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A restore never drops again what was lying on the ground when the snapshot was taken.
 *
 * <p>The testing standard's scenario: 20 diamonds lie on the island when it is snapshotted, a player
 * picks them up, and the island is restored. The diamonds are in the player's inventory and nowhere
 * else. An item on the ground is economic state, so a snapshot neither keeps it nor puts it back,
 * and the restore clears whatever lies on the island so nothing survives from before it.
 */
class RestoreDroppedItemEconomicDupePreventionTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("The diamonds picked up after the snapshot are in the inventory and not back on the ground")
    void theDiamondsExistOnce() {
        World world = server.addSimpleWorld("world");
        world.getBlockAt(0, 63, 0).setType(Material.STONE, false);
        UUID islandUuid = UUID.randomUUID();
        IslandStoragePort islands = mock(IslandStoragePort.class);
        when(islands.findLocationByIslandId(any()))
                .thenReturn(
                        Optional.of(IslandLocation.fromCenterAndRadius(IslandId.of(islandUuid), "world", 0, 0, 16)));
        WorldDimensionSnapshotAdapter worlds = new WorldDimensionSnapshotAdapter(mock(Plugin.class), islands);
        PrimaryGameplayRootRef root = new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), islandUuid.toString(), "ISLAND", Instant.now());
        PlayerMock player = server.addPlayer();

        // T1: 20 diamonds lie on the island, and it is snapshotted.
        Item dropped = world.dropItem(new Location(world, 1.5, 64, 1.5), new ItemStack(Material.DIAMOND, 20));
        byte[] snapshot = worlds.captureWorldDimension(root, DimensionId.OVERWORLD);

        // T2: the player picks them up.
        player.getInventory().addItem(dropped.getItemStack());
        dropped.remove();

        // T3: the island is restored.
        worlds.restoreWorldDimension(root, DimensionId.OVERWORLD, snapshot);

        assertThat(world.getEntitiesByClass(Item.class))
                .describedAs("nothing from the snapshot lies on the ground again")
                .isEmpty();
        assertThat(player.getInventory().all(Material.DIAMOND).values().stream()
                        .mapToInt(ItemStack::getAmount)
                        .sum())
                .describedAs("the player keeps what they picked up")
                .isEqualTo(20);
        assertThat(world.getBlockAt(0, 63, 0).getType())
                .describedAs("the island itself comes back")
                .isEqualTo(Material.STONE);
    }

    @Test
    @DisplayName("An item lying on the island at the restore is cleared, so none survives from before it")
    void whatLiesOnTheIslandIsCleared() {
        World world = server.addSimpleWorld("world");
        UUID islandUuid = UUID.randomUUID();
        IslandStoragePort islands = mock(IslandStoragePort.class);
        when(islands.findLocationByIslandId(any()))
                .thenReturn(
                        Optional.of(IslandLocation.fromCenterAndRadius(IslandId.of(islandUuid), "world", 0, 0, 16)));
        WorldDimensionSnapshotAdapter worlds = new WorldDimensionSnapshotAdapter(mock(Plugin.class), islands);
        PrimaryGameplayRootRef root = new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), islandUuid.toString(), "ISLAND", Instant.now());
        byte[] snapshot = worlds.captureWorldDimension(root, DimensionId.OVERWORLD);

        world.dropItem(new Location(world, 3.5, 64, 3.5), new ItemStack(Material.DIAMOND, 20));
        Item outside = world.dropItem(new Location(world, 200.5, 64, 200.5), new ItemStack(Material.EMERALD, 5));

        worlds.restoreWorldDimension(root, DimensionId.OVERWORLD, snapshot);

        assertThat(world.getEntitiesByClass(Item.class))
                .describedAs("only the item beyond the island's edge is left")
                .containsExactly(outside);
    }
}
