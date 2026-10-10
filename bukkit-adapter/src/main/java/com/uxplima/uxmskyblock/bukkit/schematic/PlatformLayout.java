package com.uxplima.uxmskyblock.bukkit.schematic;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmlib.schematic.Vec3i;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;

/**
 * The starter platform the plugin lays when no schematic says otherwise: five by five, bedrock under the
 * layer below the top, a feature on one corner and a chest beside the centre.
 *
 * <p>It is one description read two ways. It is laid block by block where no file stands for it, and it is
 * written as the file an operator finds in {@code schematics/} and edits, so the island a server ships and
 * the island its file describes are the same island.
 *
 * @param top the block players stand on
 * @param below the layer under it
 * @param feature what stands on the corner: a sapling, a cactus, a lantern
 */
public record PlatformLayout(Material top, Material below, Material feature) {

    /** Half the platform's side: it reaches two blocks each way from the centre. */
    static final int REACH = 2;

    /**
     * Where the feature stands: a corner of the platform, away from where a player arrives.
     *
     * <p>It stood on the centre, which is the island's spawn and home: a player arrived inside the
     * nether's glowstone, on the desert's cactus, and inside the trunk once the classic sapling had
     * grown, and suffocated. From a corner a grown oak reaches neither the trunk's column nor the two
     * blocks a player stands in at the centre.
     */
    static final int FEATURE_OFFSET = -2;

    public PlatformLayout {
        Objects.requireNonNull(top, "top must not be null");
        Objects.requireNonNull(below, "below must not be null");
        Objects.requireNonNull(feature, "feature must not be null");
    }

    /** The platform a preset of this id lays. An id the plugin does not ship lays the classic one. */
    public static PlatformLayout ofPreset(String presetId) {
        return switch (presetId) {
            case "desert", "poseidon", "poseidon_ruin", "poseidon_ruins" ->
                // A sapling cannot grow under water, and a sea lantern lights the sea floor.
                new PlatformLayout(
                        Material.SAND,
                        Material.SANDSTONE,
                        presetId.equals("desert") ? Material.CACTUS : Material.SEA_LANTERN);
            case "nether" -> new PlatformLayout(Material.NETHERRACK, Material.BASALT, Material.CRIMSON_FUNGUS);
            case "cave", "caveblock" -> new PlatformLayout(Material.DEEPSLATE, Material.STONE, Material.LANTERN);
            default -> new PlatformLayout(Material.GRASS_BLOCK, Material.DIRT, Material.OAK_SAPLING);
        };
    }

    /** The platform an island's first visit to a dimension lays. */
    public static PlatformLayout ofDimension(IslandDimensionType dimension) {
        return switch (dimension) {
            case NETHER -> new PlatformLayout(Material.NETHER_BRICKS, Material.BASALT, Material.GLOWSTONE);
            case THE_END -> new PlatformLayout(Material.END_STONE_BRICKS, Material.END_STONE, Material.END_ROD);
            default -> new PlatformLayout(Material.GRASS_BLOCK, Material.DIRT, Material.TORCH);
        };
    }

    /** Lays the platform with its top at {@code y}, around the centre, on the thread that owns it. */
    public void lay(World world, int centerX, int y, int centerZ) {
        Objects.requireNonNull(world, "world must not be null");
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                world.getBlockAt(centerX + dx, y - 2, centerZ + dz).setType(Material.BEDROCK);
                world.getBlockAt(centerX + dx, y - 1, centerZ + dz).setType(below);
                world.getBlockAt(centerX + dx, y, centerZ + dz).setType(top);
            }
        }
        world.getBlockAt(centerX + FEATURE_OFFSET, y + 1, centerZ + FEATURE_OFFSET)
                .setType(feature);
        world.getBlockAt(centerX + 1, y + 1, centerZ).setType(Material.CHEST);
    }

    /**
     * The platform as a schematic saved around the centre of its top, the block players stand on, which is
     * where a preset's schematic is pasted.
     */
    public Schematic schematic(int dataVersion) {
        int side = 2 * REACH + 1;
        Schematic.Builder builder = Schematic.builder(side, 4, side)
                .offset(new Vec3i(-REACH, -2, -REACH))
                .dataVersion(dataVersion);
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                builder.block(x, 0, z, state(Material.BEDROCK));
                builder.block(x, 1, z, state(below));
                builder.block(x, 2, z, state(top));
            }
        }
        builder.block(REACH + FEATURE_OFFSET, 3, REACH + FEATURE_OFFSET, state(feature));
        builder.block(REACH + 1, 3, REACH, state(Material.CHEST));
        return builder.build();
    }

    private static String state(Material material) {
        return material.createBlockData().getAsString();
    }
}
