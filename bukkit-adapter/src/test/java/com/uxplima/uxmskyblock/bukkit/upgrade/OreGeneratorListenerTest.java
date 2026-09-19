package com.uxplima.uxmskyblock.bukkit.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockFormEvent;

import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OreGeneratorListenerTest extends MockBukkitHarness {

    private static final UpgradeId ORE_UPGRADE_ID = UpgradeId.of("ore_generator");

    private World world;
    private Island sampleIsland;
    private IslandId islandId;
    private SpatialIslandIndex spatialIndex;
    private IslandUpgradeService mockUpgradeService;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        islandId = new IslandId(UUID.randomUUID());
        sampleIsland = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());

        spatialIndex = new SpatialIslandIndex();
        spatialIndex.indexIsland(sampleIsland, world.getName());

        mockUpgradeService = mock(IslandUpgradeService.class);
    }

    @Test
    @DisplayName("does not alter formed block when generators config is disabled")
    void doesNotAlterWhenDisabled() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(false, Map.of(1, Map.of(Material.COAL_ORE, 1.0)));
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(0, 64, 0);
        BlockState newState = block.getState();
        newState.setType(Material.COBBLESTONE);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.COBBLESTONE);
    }

    @Test
    @DisplayName("does not alter non-generator materials such as obsidian or dirt")
    void doesNotAlterOtherMaterials() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, Map.of(Material.COAL_ORE, 1.0)));
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(0, 64, 0);
        BlockState newState = block.getState();
        newState.setType(Material.OBSIDIAN);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.OBSIDIAN);
    }

    @Test
    @DisplayName("does not alter block outside island boundaries")
    void doesNotAlterOutsideIsland() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, Map.of(Material.COAL_ORE, 1.0)));
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(5000, 64, 5000);
        BlockState newState = block.getState();
        newState.setType(Material.COBBLESTONE);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.COBBLESTONE);
    }

    @Test
    @DisplayName("does not alter block when island tier is 0")
    void doesNotAlterWhenTierZero() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, Map.of(Material.COAL_ORE, 1.0)));
        when(mockUpgradeService.getCachedTier(islandId, ORE_UPGRADE_ID)).thenReturn(0);
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(0, 64, 0);
        BlockState newState = block.getState();
        newState.setType(Material.COBBLESTONE);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.COBBLESTONE);
    }

    @Test
    @DisplayName("transforms cobblestone to ore according to tier rates")
    void transformsCobblestoneToOre() {
        GeneratorsConfiguration config =
                new GeneratorsConfiguration(true, Map.of(1, Map.of(Material.DIAMOND_ORE, 1.0)));
        when(mockUpgradeService.getCachedTier(islandId, ORE_UPGRADE_ID)).thenReturn(1);
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(0, 64, 0);
        BlockState newState = block.getState();
        newState.setType(Material.COBBLESTONE);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.DIAMOND_ORE);
    }
}
