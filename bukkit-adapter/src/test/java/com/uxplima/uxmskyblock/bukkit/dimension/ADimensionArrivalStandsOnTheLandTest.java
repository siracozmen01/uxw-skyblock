package com.uxplima.uxmskyblock.bukkit.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player travelling to an island's dimension arrives standing: on the platform the preset laid, or on
 * land a mode brought there at another height, such as the Upside Down.
 */
class ADimensionArrivalStandsOnTheLandTest extends MockBukkitHarness {

    private static final int X = 8;
    private static final int Z = 8;

    @Test
    @DisplayName("On a platform at the named height the player stands there, and on higher land they stand on it")
    void theLandDecides() {
        World world = server.addSimpleWorld("upside_down");
        fill(world, 60, 64, Material.STONE);
        assertThat(IslandDimensionListener.arrivalHeight(world, X, 65, Z, preset(GameModeType.SKYBLOCK)))
                .isEqualTo(65);

        fill(world, 65, 80, Material.STONE);
        assertThat(IslandDimensionListener.arrivalHeight(world, X, 65, Z, preset(GameModeType.STRANGER_REALMS)))
                .isEqualTo(81);
    }

    @Test
    @DisplayName("Where nothing near is safe, room is made at the named height, with water for a mode played in it")
    void roomIsMade() {
        World world = server.addSimpleWorld("drowned");
        fill(world, world.getMinHeight(), world.getMaxHeight() - 1, Material.NETHERRACK);

        assertThat(IslandDimensionListener.arrivalHeight(world, X, 70, Z, preset(GameModeType.POSEIDON)))
                .isEqualTo(70);
        assertThat(world.getBlockAt(X, 70, Z).getType()).isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(X, 71, Z).getType()).isEqualTo(Material.WATER);
    }

    private static void fill(World world, int from, int to, Material material) {
        for (int y = from; y <= to; y++) {
            world.getBlockAt(X, y, Z).setType(material);
        }
    }

    private static StarterPreset preset(GameModeType mode) {
        return new StarterPreset(
                "test", "Test", "", "schematics/test.schem", IslandBiome.PLAINS, mode, List.of(StarterPreset.PLATFORM));
    }
}
