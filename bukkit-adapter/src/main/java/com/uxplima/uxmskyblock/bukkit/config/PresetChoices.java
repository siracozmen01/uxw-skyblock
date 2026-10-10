package com.uxplima.uxmskyblock.bukkit.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.bukkit.permissions.Permissible;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * How a player picks the island they start with, as {@code modules/presets.conf} says.
 *
 * <p>A player with no island who typed {@code /is} was told to type {@code /is create}, and {@code /is create}
 * made the default island at once: the other kinds a server offered were names a player had to know. The operator
 * says here whether a player picks from a window, gets the default island at once, or reads the command list, and
 * which permission node, if any, each kind asks for.
 *
 * @param looks how each kind is drawn in the window and who may start it, by preset id
 * @param whenNoIsland what {@code /is} does for a player with no island
 * @param createAsks whether {@code /is create} with no kind written opens the window rather than making the default
 */
public record PresetChoices(Map<String, Look> looks, WhenNoIsland whenNoIsland, boolean createAsks) {

    /** The window for both, every kind open to everyone, each wearing the item its id suggests. */
    public static final PresetChoices DEFAULT = new PresetChoices(Map.of(), WhenNoIsland.MENU, true);

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(PresetChoices.class.getName());

    /** What {@code /is} does for a player with no island. */
    public enum WhenNoIsland {
        /** Opens the window of island kinds. */
        MENU,
        /** Makes the default island at once. */
        CREATE,
        /** Lists the commands. */
        HELP
    }

    /**
     * How a kind of island is drawn in the window, and who may start it.
     *
     * @param icon the item its tile wears
     * @param permission the node a player needs to start it, blank for everyone
     * @param order where its tile stands among the others, the lowest first
     */
    public record Look(String icon, String permission, int order) {
        public Look {
            Objects.requireNonNull(icon, "icon must not be null");
            permission = Objects.requireNonNull(permission, "permission must not be null")
                    .strip();
        }

        /** A kind drawn with {@code icon}, open to whoever holds {@code permission}, among the last. */
        public Look(String icon, String permission) {
            this(icon, permission, DEFAULT_ORDER);
        }
    }

    /** Where a kind whose file names no order stands: after every kind that names one. */
    public static final int DEFAULT_ORDER = 1000;

    public PresetChoices {
        looks = Map.copyOf(Objects.requireNonNull(looks, "looks must not be null"));
        Objects.requireNonNull(whenNoIsland, "whenNoIsland must not be null");
    }

    /** How the kind {@code presetId} is drawn: as the file says, or with the item its id suggests. */
    public Look lookOf(String presetId) {
        Look look = looks.get(presetId);
        return look != null ? look : new Look(iconOf(presetId), "");
    }

    /**
     * {@code presets} in the order the file gives them. The file's own order cannot be read back, as the format
     * keeps no order among the entries, so each names its place, and two of one place stand by their ids.
     */
    public java.util.List<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> ordered(
            java.util.List<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> presets) {
        java.util.List<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> sorted =
                new java.util.ArrayList<>(presets);
        sorted.sort(
                java.util.Comparator.comparingInt((com.uxplima.uxmskyblock.core.domain.preset.StarterPreset preset) ->
                                lookOf(preset.id()).order())
                        .thenComparing(com.uxplima.uxmskyblock.core.domain.preset.StarterPreset::id));
        return java.util.List.copyOf(sorted);
    }

    /** Whether {@code who} may start the kind {@code presetId}. */
    public boolean allows(Permissible who, String presetId) {
        String node = lookOf(presetId).permission();
        return node.isEmpty() || who.hasPermission(node);
    }

    /** Reads the {@code presets} block. A word the file gets wrong falls back to the window, and the console says so. */
    public static PresetChoices load(ConfigurationNode presets) {
        Objects.requireNonNull(presets, "presets must not be null");
        Map<String, Look> looks = new LinkedHashMap<>();
        for (var entry : presets.node("entries").childrenMap().entrySet()) {
            String id = String.valueOf(entry.getKey()).trim();
            ConfigurationNode preset = entry.getValue();
            looks.put(
                    id,
                    new Look(
                            preset.node("icon").getString(iconOf(id)).trim(),
                            preset.node("permission").getString(""),
                            preset.node("order").getInt(DEFAULT_ORDER)));
        }
        String written = presets.node("when-no-island").getString("menu");
        WhenNoIsland whenNoIsland;
        try {
            whenNoIsland = WhenNoIsland.valueOf(written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            LOGGER.warning(() -> "presets.when-no-island is '" + written
                    + "', which is none of menu, create and help. A player with no island gets the window.");
            whenNoIsland = WhenNoIsland.MENU;
        }
        String create = presets.node("create-without-type").getString("menu").trim();
        if (!create.equalsIgnoreCase("menu") && !create.equalsIgnoreCase("default")) {
            LOGGER.warning(() -> "presets.create-without-type is '" + create
                    + "', which is neither menu nor default. /is create opens the window.");
        }
        return new PresetChoices(looks, whenNoIsland, !create.equalsIgnoreCase("default"));
    }

    /** The item a kind's id suggests, for a kind whose file names none. */
    static String iconOf(String presetId) {
        return switch (presetId) {
            case "desert" -> "SAND";
            case "nether" -> "NETHERRACK";
            case "cave" -> "DEEPSLATE";
            case "oneblock" -> "GRASS_BLOCK";
            case "chunkblock" -> "MOSS_BLOCK";
            case "acid_island" -> "SLIME_BLOCK";
            case "caveblock" -> "POINTED_DRIPSTONE";
            case "boxed" -> "COMPOSTER";
            case "poseidon" -> "PRISMARINE";
            case "poseidon_ruin" -> "CRACKED_STONE_BRICKS";
            case "poseidon_ruins" -> "MOSSY_STONE_BRICKS";
            case "stranger_realms" -> "CRYING_OBSIDIAN";
            case "parkour" -> "LIGHT_WEIGHTED_PRESSURE_PLATE";
            case "brix" -> "BRICKS";
            case "tradewinds" -> "OAK_BOAT";
            case "skygrid" -> "GLASS";
            default -> "OAK_SAPLING";
        };
    }
}
