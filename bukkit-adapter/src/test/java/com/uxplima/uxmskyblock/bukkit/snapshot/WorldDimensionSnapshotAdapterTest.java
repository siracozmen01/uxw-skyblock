package com.uxplima.uxmskyblock.bukkit.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
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
    @DisplayName("Captures and restores world dimension snapshot payload round-trip")
    void testCaptureAndRestoreRoundTrip() {
        PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), "island-test-123", "ISLAND", Instant.now());

        byte[] payload = adapter.captureWorldDimension(rootRef, DimensionId.OVERWORLD);
        assertThat(payload).isNotEmpty();

        // Restore should succeed without error
        adapter.restoreWorldDimension(rootRef, DimensionId.OVERWORLD, payload);
    }

    @Test
    @DisplayName("Captures and restores real non-air blocks in Bukkit world round-trip")
    void testRealWorldBlockCaptureAndRestoreRoundTrip() {
        ServerMock server = MockBukkit.mock();
        try {
            World world = server.addSimpleWorld("world");
            Block block1 = world.getBlockAt(2, 64, 3);
            block1.setType(Material.DIAMOND_BLOCK, false);
            Block block2 = world.getBlockAt(0, 70, 0);
            block2.setType(Material.GOLD_BLOCK, false);

            PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                    GameModeInstanceId.of(UUID.randomUUID()), "island-real-blocks", "ISLAND", Instant.now());

            byte[] payload = adapter.captureWorldDimension(rootRef, DimensionId.OVERWORLD);
            assertThat(payload).isNotEmpty();

            // Clear the blocks
            block1.setType(Material.AIR, false);
            block2.setType(Material.AIR, false);
            assertThat(world.getBlockAt(2, 64, 3).getType()).isEqualTo(Material.AIR);
            assertThat(world.getBlockAt(0, 70, 0).getType()).isEqualTo(Material.AIR);

            // Restore from payload
            adapter.restoreWorldDimension(rootRef, DimensionId.OVERWORLD, payload);

            // Verify blocks restored
            assertThat(world.getBlockAt(2, 64, 3).getType()).isEqualTo(Material.DIAMOND_BLOCK);
            assertThat(world.getBlockAt(0, 70, 0).getType()).isEqualTo(Material.GOLD_BLOCK);
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
