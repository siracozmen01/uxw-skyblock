package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.grid.GridLayout;
import com.uxplima.uxmskyblock.core.domain.grid.GridPalette;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/skygrid.conf}: whether islands can be SkyGrid islands, where the grid's blocks stand,
 * which blocks they are in each dimension, and what the grid's chests hold.
 *
 * @param spawnBlock the block players arrive standing on, at the grid's centre
 * @param chestRolls how many of the chest items one chest in the grid holds
 * @param chestItems what a chest in the grid can hold
 * @param palettes the grid of each dimension, by dimension id; a dimension not named uses the overworld's
 */
public record SkyGridConfiguration(
        boolean enabled,
        GridLayout layout,
        String spawnBlock,
        int chestRolls,
        List<Loot> chestItems,
        Map<String, Palette> palettes) {

    public static final String OVERWORLD = "overworld";
    public static final String NETHER = "the_nether";
    public static final String END = "the_end";

    private static final Logger LOGGER = Logger.getLogger(SkyGridConfiguration.class.getName());

    /**
     * The grid of one dimension.
     *
     * @param blocks the blocks and their weights
     * @param spawners the creatures a spawner in the grid can raise, by entity type
     */
    public record Palette(GridPalette blocks, List<String> spawners) {

        public Palette {
            Objects.requireNonNull(blocks, "blocks must not be null");
            spawners = List.copyOf(spawners);
        }
    }

    /**
     * One thing a chest can hold, and how many of it.
     *
     * @param item the item, by name
     * @param min the fewest a chest holds of it
     * @param max the most a chest holds of it
     */
    public record Loot(String item, int min, int max) {

        public Loot {
            Objects.requireNonNull(item, "item must not be null");
            if (item.isBlank() || min < 1 || max < min || max > 64) {
                throw new IllegalArgumentException("a chest item names an item and holds between 1 and 64");
            }
        }

        /**
         * An item written {@code ITEM:min:max}, such as {@code BONE_MEAL:2:6}.
         *
         * @throws IllegalArgumentException when it is not written that way
         */
        public static Loot parse(String written) {
            String[] parts = written.trim().split(":", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException(written + " is not ITEM:min:max");
            }
            try {
                return new Loot(
                        parts[0].trim().toUpperCase(Locale.ROOT),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(written + " has no whole numbers for its amounts", e);
            }
        }
    }

    public SkyGridConfiguration {
        Objects.requireNonNull(layout, "layout must not be null");
        Objects.requireNonNull(spawnBlock, "spawnBlock must not be null");
        chestItems = List.copyOf(chestItems);
        palettes = Map.copyOf(palettes);
        if (chestRolls < 0) {
            throw new IllegalArgumentException("a chest holds at least nothing");
        }
        if (!palettes.containsKey(OVERWORLD)) {
            throw new IllegalArgumentException("the overworld's grid must be named");
        }
    }

    /** The grid of a dimension, by its id. */
    public Palette palette(String dimension) {
        Palette named = palettes.get(dimension);
        return named != null ? named : Objects.requireNonNull(palettes.get(OVERWORLD));
    }

    public static SkyGridConfiguration defaultConfiguration() {
        return new SkyGridConfiguration(
                true,
                GridLayout.SHIPPED,
                "GRASS_BLOCK",
                3,
                loot(
                        "OAK_SAPLING:1:2",
                        "BONE_MEAL:2:6",
                        "WHEAT_SEEDS:1:4",
                        "SUGAR_CANE:1:3",
                        "CACTUS:1:2",
                        "MELON_SEEDS:1:2",
                        "PUMPKIN_SEEDS:1:2",
                        "ICE:1:2",
                        "WATER_BUCKET:1:1",
                        "LAVA_BUCKET:1:1",
                        "IRON_INGOT:1:3",
                        "BREAD:1:4",
                        "TORCH:4:12"),
                Map.of(
                        OVERWORLD,
                        new Palette(
                                blocks(
                                        "DIRT:40",
                                        "GRASS_BLOCK:20",
                                        "STONE:40",
                                        "COBBLESTONE:10",
                                        "SAND:10",
                                        "GRAVEL:8",
                                        "OAK_LOG:15",
                                        "SPRUCE_LOG:6",
                                        "BIRCH_LOG:6",
                                        "OAK_LEAVES:6",
                                        "CLAY:3",
                                        "PUMPKIN:2",
                                        "MELON:2",
                                        "COAL_ORE:8",
                                        "IRON_ORE:6",
                                        "COPPER_ORE:4",
                                        "GOLD_ORE:2",
                                        "REDSTONE_ORE:3",
                                        "LAPIS_ORE:2",
                                        "DIAMOND_ORE:1",
                                        "OBSIDIAN:1",
                                        "WATER:3",
                                        "LAVA:2",
                                        "CHEST:2",
                                        "SPAWNER:1"),
                                List.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER")),
                        NETHER,
                        new Palette(
                                blocks(
                                        "NETHERRACK:60",
                                        "SOUL_SAND:10",
                                        "GLOWSTONE:6",
                                        "NETHER_QUARTZ_ORE:8",
                                        "NETHER_GOLD_ORE:5",
                                        "MAGMA_BLOCK:4",
                                        "NETHER_BRICKS:6",
                                        "CRIMSON_STEM:4",
                                        "WARPED_STEM:4",
                                        "NETHER_WART_BLOCK:3",
                                        "GRAVEL:3",
                                        "LAVA:3",
                                        "CHEST:2",
                                        "SPAWNER:1"),
                                List.of("BLAZE", "MAGMA_CUBE", "WITHER_SKELETON")),
                        END,
                        new Palette(
                                blocks("END_STONE:70", "END_STONE_BRICKS:6", "PURPUR_BLOCK:8", "OBSIDIAN:6", "CHEST:2"),
                                List.of("ENDERMAN"))));
    }

    public static SkyGridConfiguration load(ConfigurationNode root) {
        SkyGridConfiguration shipped = defaultConfiguration();
        GridLayout layout;
        try {
            GridLayout s = GridLayout.SHIPPED;
            layout = new GridLayout(
                    root.node("spacing").getInt(s.spacing()),
                    root.node("radius").getInt(s.radius()),
                    root.node("below").getInt(s.below()),
                    root.node("above").getInt(s.above()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/skygrid.conf: " + e.getMessage() + ". The shipped grid is used.");
            layout = GridLayout.SHIPPED;
        }
        List<Loot> items;
        ConfigurationNode chests = root.node("chests");
        try {
            items = chests.node("items").virtual() ? shipped.chestItems() : lootOf(chests.node("items"));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/skygrid.conf chests: " + e.getMessage() + ". The shipped items are used.");
            items = shipped.chestItems();
        }
        Map<String, Palette> palettes = new HashMap<>(shipped.palettes());
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                root.node("palettes").childrenMap().entrySet()) {
            String dimension = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
            Palette fallback = shipped.palette(dimension);
            ConfigurationNode node = entry.getValue();
            try {
                palettes.put(
                        dimension,
                        new Palette(
                                node.node("blocks").virtual() ? fallback.blocks() : blocksOf(node.node("blocks")),
                                node.node("spawners").virtual()
                                        ? fallback.spawners()
                                        : strings(node.node("spawners"))));
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> "modules/skygrid.conf palettes." + dimension + ": " + e.getMessage()
                        + ". The shipped grid is used.");
            }
        }
        return new SkyGridConfiguration(
                root.node("enabled").getBoolean(true),
                layout,
                root.node("spawn-block").getString(shipped.spawnBlock()).trim().toUpperCase(Locale.ROOT),
                Math.max(0, chests.node("rolls").getInt(shipped.chestRolls())),
                items,
                palettes);
    }

    private static GridPalette blocksOf(ConfigurationNode node) {
        List<GridPalette.Entry> read = new ArrayList<>();
        for (String line : strings(node)) {
            read.add(GridPalette.Entry.parse(line));
        }
        return new GridPalette(read);
    }

    private static List<Loot> lootOf(ConfigurationNode node) {
        List<Loot> read = new ArrayList<>();
        for (String line : strings(node)) {
            read.add(Loot.parse(line));
        }
        return read;
    }

    private static List<String> strings(ConfigurationNode node) {
        List<String> read = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            String value = child.getString("").trim().toUpperCase(Locale.ROOT);
            if (!value.isEmpty()) {
                read.add(value);
            }
        }
        return read;
    }

    private static GridPalette blocks(String... written) {
        List<GridPalette.Entry> read = new ArrayList<>();
        for (String line : written) {
            read.add(GridPalette.Entry.parse(line));
        }
        return new GridPalette(read);
    }

    private static List<Loot> loot(String... written) {
        List<Loot> read = new ArrayList<>();
        for (String line : written) {
            read.add(Loot.parse(line));
        }
        return read;
    }
}
