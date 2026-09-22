package com.uxplima.uxmskyblock.bukkit.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Random;

import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import com.uxplima.uxmskyblock.bukkit.bootstrap.UxMSkyblockPlugin;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The islands have an empty world to float in.
 *
 * <p>The plugin shipped without a generator, so every island went into ordinary generated terrain,
 * and nothing at startup said so.
 */
class IslandsHaveAnEmptyWorldTest extends MockBukkitHarness {

    @Test
    @DisplayName("The island generator makes nothing at all")
    void theGeneratorMakesNothing() {
        VoidIslandGenerator generator = new VoidIslandGenerator();

        assertThat(generator.shouldGenerateNoise()).isFalse();
        assertThat(generator.shouldGenerateSurface()).isFalse();
        assertThat(generator.shouldGenerateCaves()).isFalse();
        assertThat(generator.shouldGenerateDecorations()).isFalse();
        assertThat(generator.shouldGenerateMobs()).isFalse();
        assertThat(generator.shouldGenerateStructures()).isFalse();
        assertThat(generator
                        .getFixedSpawnLocation(mock(World.class), new Random())
                        .getY())
                .isEqualTo(100);
    }

    @Test
    @DisplayName("A world loader gets the island generator from the plugin's name, with or without the void id")
    void thePluginServesItsGenerator() {
        addWorldMadeBy("world", new VoidIslandGenerator());
        UxMSkyblockPlugin plugin = MockBukkit.load(UxMSkyblockPlugin.class);

        assertThat(plugin.getDefaultWorldGenerator("world", null)).isInstanceOf(VoidIslandGenerator.class);
        assertThat(plugin.getDefaultWorldGenerator("world", "")).isInstanceOf(VoidIslandGenerator.class);
        assertThat(plugin.getDefaultWorldGenerator("world", "void")).isInstanceOf(VoidIslandGenerator.class);
        assertThat(plugin.getDefaultWorldGenerator("world", "VOID")).isInstanceOf(VoidIslandGenerator.class);
        assertThat(plugin.getDefaultWorldGenerator("world", "flat"))
                .describedAs("an id that is not ours is the server's to answer")
                .isNull();
    }

    @Test
    @DisplayName("A missing island world is said at startup, with the setting that names another")
    void aMissingWorldIsSaid() {
        assertThat(IslandWorldCheck.warningFor("islands", null, "uxmSkyblock"))
                .hasValueSatisfying(line ->
                        assertThat(line).contains("'islands' is not loaded").contains("server-node.world-name"));
    }

    @Test
    @DisplayName("An island world of generated terrain is said at startup")
    void aTerrainWorldIsSaid() {
        World terrain = mock(World.class);
        when(terrain.getGenerator()).thenReturn(null);

        assertThat(IslandWorldCheck.warningFor("world", terrain, "uxmSkyblock"))
                .hasValueSatisfying(line -> assertThat(line)
                        .contains("generated terrain")
                        .contains("worlds:\n  world:\n    generator: uxmSkyblock"));
    }

    @Test
    @DisplayName("An island world made by the island generator is not remarked on")
    void anEmptyWorldIsQuiet() {
        World empty = mock(World.class);
        ChunkGenerator ours = new VoidIslandGenerator();
        when(empty.getGenerator()).thenReturn(ours);

        assertThat(IslandWorldCheck.warningFor("world", empty, "uxmSkyblock")).isEmpty();
    }
}
