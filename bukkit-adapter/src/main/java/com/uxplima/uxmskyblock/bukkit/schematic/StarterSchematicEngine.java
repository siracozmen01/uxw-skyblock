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

    private final com.uxplima.uxmskyblock.core.application.performance.@org.jspecify.annotations.Nullable AdaptiveBackpressureController
            backpressureController;

    public StarterSchematicEngine(
            com.uxplima.uxmskyblock.core.application.performance.@org.jspecify.annotations.Nullable AdaptiveBackpressureController
                    backpressureController) {
        this.backpressureController = backpressureController;
    }

    public StarterSchematicEngine() {
        this(null);
    }

    public com.uxplima.uxmskyblock.core.application.performance.@org.jspecify.annotations.Nullable AdaptiveBackpressureController backpressureController() {
        return backpressureController;
    }

    /**
     * Where the island's feature stands: a corner of the platform, away from where a player arrives.
     *
     * <p>It stood on the centre, which is the island's spawn and home: a player arrived inside the
     * nether's glowstone, on the desert's cactus, and inside the trunk once the classic sapling had
     * grown, and suffocated. From a corner a grown oak reaches neither the trunk's column nor the two
     * blocks a player stands in at the centre.
     */
    static final int FEATURE_OFFSET = -2;

    /**
     * The height of the platform's top for an island whose players arrive at {@code spawnY}: the block
     * they stand on. The platform was laid at the spawn height itself, so a player arrived with their
     * feet in the grass and was pushed up into whatever stood above it.
     */
    public static int platformBelow(double spawnY) {
        return (int) Math.floor(spawnY) - 1;
    }

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
        Block featureBlock = world.getBlockAt(centerX + FEATURE_OFFSET, y + 1, centerZ + FEATURE_OFFSET);
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

    public void pasteDimensionPlatform(
            World world,
            int centerX,
            int y,
            int centerZ,
            com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType dimensionType) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(dimensionType, "dimensionType");

        Material primaryBlock =
                switch (dimensionType) {
                    case NETHER -> Material.NETHER_BRICKS;
                    case THE_END -> Material.END_STONE_BRICKS;
                    default -> Material.GRASS_BLOCK;
                };

        Material subBlock =
                switch (dimensionType) {
                    case NETHER -> Material.BASALT;
                    case THE_END -> Material.END_STONE;
                    default -> Material.DIRT;
                };

        // Create 5x5 platform around center
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(centerX + dx, y - 2, centerZ + dz).setType(Material.BEDROCK);
                world.getBlockAt(centerX + dx, y - 1, centerZ + dz).setType(subBlock);
                world.getBlockAt(centerX + dx, y, centerZ + dz).setType(primaryBlock);
            }
        }

        // Add dimension specific center marker / chest
        Block featureBlock = world.getBlockAt(centerX + FEATURE_OFFSET, y + 1, centerZ + FEATURE_OFFSET);
        switch (dimensionType) {
            case NETHER -> {
                featureBlock.setType(Material.GLOWSTONE);
                world.getBlockAt(centerX + 1, y + 1, centerZ).setType(Material.CHEST);
            }
            case THE_END -> {
                featureBlock.setType(Material.END_ROD);
                world.getBlockAt(centerX + 1, y + 1, centerZ).setType(Material.CHEST);
            }
            default -> {
                featureBlock.setType(Material.TORCH);
                world.getBlockAt(centerX + 1, y + 1, centerZ).setType(Material.CHEST);
            }
        }
    }
}
