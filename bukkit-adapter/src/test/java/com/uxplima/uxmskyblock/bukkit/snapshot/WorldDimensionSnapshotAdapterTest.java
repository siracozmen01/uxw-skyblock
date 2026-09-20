package com.uxplima.uxmskyblock.bukkit.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class WorldDimensionSnapshotAdapterTest {

    private Plugin plugin;
    private WorldDimensionSnapshotAdapter adapter;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        adapter = new WorldDimensionSnapshotAdapter(plugin);
    }

    @Test
    @DisplayName("Captures and restores world dimension snapshot payload round-trip when world is headless")
    void testCaptureAndRestoreRoundTrip() {
        PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), "island-test-123", "ISLAND", Instant.now());

        byte[] payload = adapter.captureWorldDimension(rootRef, DimensionId.OVERWORLD);
        assertThat(payload).isNotEmpty();

        // Restore should succeed without error
        adapter.restoreWorldDimension(rootRef, DimensionId.OVERWORLD, payload);
    }

    @Test
    @DisplayName("Fails closed with IllegalStateException if bounds cannot be resolved from storage")
    void testFailClosedWhenBoundsCannotBeResolved() {
        ServerMock server = MockBukkit.mock();
        try {
            server.addSimpleWorld("world");
            PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                    GameModeInstanceId.of(UUID.randomUUID()), UUID.randomUUID().toString(), "ISLAND", Instant.now());

            // adapter without IslandStoragePort must fail closed rather than falling back to (0,0,16)
            assertThatThrownBy(() -> adapter.captureWorldDimension(rootRef, DimensionId.OVERWORLD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to resolve island bounds from storage");
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    @DisplayName("Captures and restores real non-air blocks in Bukkit world round-trip with storage bounds")
    void testRealWorldBlockCaptureAndRestoreRoundTrip() {
        ServerMock server = MockBukkit.mock();
        try {
            World world = server.addSimpleWorld("world");
            Block block1 = world.getBlockAt(2, 64, 3);
            block1.setType(Material.DIAMOND_BLOCK, false);
            Block block2 = world.getBlockAt(0, 70, 0);
            block2.setType(Material.GOLD_BLOCK, false);

            UUID islandUuid = UUID.randomUUID();
            IslandStoragePort storagePort = mock(IslandStoragePort.class);
            when(storagePort.findLocationByIslandId(any()))
                    .thenReturn(Optional.of(
                            IslandLocation.fromCenterAndRadius(IslandId.of(islandUuid), "world", 0, 0, 16)));
            WorldDimensionSnapshotAdapter boundedAdapter = new WorldDimensionSnapshotAdapter(plugin, storagePort);

            PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                    GameModeInstanceId.of(UUID.randomUUID()), islandUuid.toString(), "ISLAND", Instant.now());

            byte[] payload = boundedAdapter.captureWorldDimension(rootRef, DimensionId.OVERWORLD);
            assertThat(payload).isNotEmpty();

            // Clear the blocks
            block1.setType(Material.AIR, false);
            block2.setType(Material.AIR, false);
            assertThat(world.getBlockAt(2, 64, 3).getType()).isEqualTo(Material.AIR);
            assertThat(world.getBlockAt(0, 70, 0).getType()).isEqualTo(Material.AIR);

            // Restore from payload
            boundedAdapter.restoreWorldDimension(rootRef, DimensionId.OVERWORLD, payload);

            // Verify blocks restored
            assertThat(world.getBlockAt(2, 64, 3).getType()).isEqualTo(Material.DIAMOND_BLOCK);
            assertThat(world.getBlockAt(0, 70, 0).getType()).isEqualTo(Material.GOLD_BLOCK);
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    @DisplayName("Deterministic replacement clears un-snapshotted blocks within bounds to air")
    void testDeterministicReplacementClearsExtraBlocksToAir() {
        ServerMock server = MockBukkit.mock();
        try {
            World world = server.addSimpleWorld("world");
            Block block1 = world.getBlockAt(2, 64, 3);
            block1.setType(Material.DIAMOND_BLOCK, false);

            UUID islandUuid = UUID.randomUUID();
            IslandStoragePort storagePort = mock(IslandStoragePort.class);
            when(storagePort.findLocationByIslandId(any()))
                    .thenReturn(Optional.of(
                            IslandLocation.fromCenterAndRadius(IslandId.of(islandUuid), "world", 0, 0, 16)));
            WorldDimensionSnapshotAdapter boundedAdapter = new WorldDimensionSnapshotAdapter(plugin, storagePort);

            PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                    GameModeInstanceId.of(UUID.randomUUID()), islandUuid.toString(), "ISLAND", Instant.now());

            byte[] payload = boundedAdapter.captureWorldDimension(rootRef, DimensionId.OVERWORLD);

            // Simulate player building a new block after snapshot was taken
            Block extraBlock = world.getBlockAt(5, 64, 5);
            extraBlock.setType(Material.COBBLESTONE, false);
            assertThat(world.getBlockAt(5, 64, 5).getType()).isEqualTo(Material.COBBLESTONE);

            // Restore should deterministically clear extraBlock to AIR
            boundedAdapter.restoreWorldDimension(rootRef, DimensionId.OVERWORLD, payload);

            assertThat(world.getBlockAt(2, 64, 3).getType()).isEqualTo(Material.DIAMOND_BLOCK);
            assertThat(world.getBlockAt(5, 64, 5).getType()).isEqualTo(Material.AIR);
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    @DisplayName("ECONOMIC_ITEM_STATE: container inventories are cleared during restore")
    void testEconomicItemStateClearedDuringRestore() {
        ServerMock server = MockBukkit.mock();
        try {
            World world = server.addSimpleWorld("world");
            Block chestBlock = world.getBlockAt(2, 64, 2);
            chestBlock.setType(Material.CHEST, false);
            Chest chest = (Chest) chestBlock.getState();
            chest.getInventory().addItem(new ItemStack(Material.DIAMOND, 64));
            chest.update(true, false);

            UUID islandUuid = UUID.randomUUID();
            IslandStoragePort storagePort = mock(IslandStoragePort.class);
            when(storagePort.findLocationByIslandId(any()))
                    .thenReturn(Optional.of(
                            IslandLocation.fromCenterAndRadius(IslandId.of(islandUuid), "world", 0, 0, 16)));
            WorldDimensionSnapshotAdapter boundedAdapter = new WorldDimensionSnapshotAdapter(plugin, storagePort);

            PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                    GameModeInstanceId.of(UUID.randomUUID()), islandUuid.toString(), "ISLAND", Instant.now());

            byte[] payload = boundedAdapter.captureWorldDimension(rootRef, DimensionId.OVERWORLD);

            // Add more diamonds before restore
            chest.getInventory().addItem(new ItemStack(Material.EMERALD, 32));
            chest.update(true, false);

            // Restore
            boundedAdapter.restoreWorldDimension(rootRef, DimensionId.OVERWORLD, payload);

            // Chest should exist as geometry, but inventory must be cleared (ECONOMIC_ITEM_STATE boundary)
            assertThat(world.getBlockAt(2, 64, 2).getType()).isEqualTo(Material.CHEST);
            Chest restoredChest = (Chest) world.getBlockAt(2, 64, 2).getState();
            assertThat(restoredChest.getInventory().isEmpty()).isTrue();
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    @DisplayName("Restoring snapshot with mismatched rootId throws IllegalArgumentException")
    void testRestoreRootIdMismatch() {
        PrimaryGameplayRootRef rootRef1 = new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), "island-test-123", "ISLAND", Instant.now());
        PrimaryGameplayRootRef rootRef2 = new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), "island-test-456", "ISLAND", Instant.now());

        byte[] payload = adapter.captureWorldDimension(rootRef1, DimensionId.OVERWORLD);

        assertThatThrownBy(() -> adapter.restoreWorldDimension(rootRef2, DimensionId.OVERWORLD, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dimension snapshot rootId mismatch");
    }

    @Test
    @DisplayName("Restoring snapshot with mismatched dimensionId throws IllegalArgumentException")
    void testRestoreDimensionMismatch() {
        PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), "island-test-123", "ISLAND", Instant.now());

        byte[] payload = adapter.captureWorldDimension(rootRef, DimensionId.OVERWORLD);

        assertThatThrownBy(() -> adapter.restoreWorldDimension(rootRef, DimensionId.THE_NETHER, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dimension snapshot dimensionId mismatch");
    }
}
