package com.uxplima.uxmskyblock.bukkit.schematic;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;

/**
 * Schematic and structure generation engine for initializing starter islands.
 */
public final class StarterSchematicEngine {

    public void pastePreset(World world, int centerX, int y, int centerZ, StarterPreset preset) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(preset, "preset");

        Material primaryBlock =
                switch (preset.id()) {
                    case "desert" -> Material.SAND;
                    case "nether" -> Material.NETHERRACK;
                    case "cave" -> Material.DEEPSLATE;
                    default -> Material.GRASS_BLOCK;
                };

        Material subBlock =
                switch (preset.id()) {
                    case "desert" -> Material.SANDSTONE;
                    case "nether" -> Material.BASALT;
                    case "cave" -> Material.STONE;
                    default -> Material.DIRT;
                };

        // Create platform around center
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(centerX + dx, y - 2, centerZ + dz).setType(Material.BEDROCK);
                world.getBlockAt(centerX + dx, y - 1, centerZ + dz).setType(subBlock);
                world.getBlockAt(centerX + dx, y, centerZ + dz).setType(primaryBlock);
            }
        }

        // Center feature and starter chest
        Block featureBlock = world.getBlockAt(centerX, y + 1, centerZ);
        switch (preset.id()) {
            case "desert" -> {
                featureBlock.setType(Material.CACTUS);
                world.getBlockAt(centerX + 1, y + 1, centerZ).setType(Material.CHEST);
            }
            case "nether" -> {
                featureBlock.setType(Material.CRIMSON_FUNGUS);
                world.getBlockAt(centerX + 1, y + 1, centerZ).setType(Material.CHEST);
            }
            case "cave" -> {
                featureBlock.setType(Material.LANTERN);
                world.getBlockAt(centerX + 1, y + 1, centerZ).setType(Material.CHEST);
            }
            default -> {
                featureBlock.setType(Material.OAK_SAPLING);
                world.getBlockAt(centerX + 1, y + 1, centerZ).setType(Material.CHEST);
            }
        }
    }
}
