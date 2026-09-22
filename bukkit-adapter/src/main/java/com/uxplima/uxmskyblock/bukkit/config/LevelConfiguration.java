package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Declarative configuration holder for Island Level point valuations, dynamic price damping,
 * block weights, and mob spawner mappings (Section 2.26).
 */
public record LevelConfiguration(
        long pointsPerLevel,
        long bankMinorUnitsPerPoint,
        long defaultSpawnerWeight,
        long questWeight,
        double dampingFactor,
        Map<String, Long> blockWeights,
        Map<String, Long> blockPrices,
        Map<String, Long> spawnerWeights,
        java.time.Duration leaderboardFreshness) {

    public static final long DEFAULT_POINTS_PER_LEVEL = 100L;
    public static final long DEFAULT_BANK_MINOR_UNITS_PER_POINT = 10_000L;
    public static final long DEFAULT_SPAWNER_WEIGHT = 25L;
    public static final long DEFAULT_QUEST_WEIGHT = 50L;
    public static final double DEFAULT_DAMPING_FACTOR = 0.85;
    public static final java.time.Duration DEFAULT_LEADERBOARD_FRESHNESS =
            com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService.DEFAULT_FRESHNESS;

    public LevelConfiguration {
        blockWeights = Collections.unmodifiableMap(new HashMap<>(blockWeights));
        blockPrices = Collections.unmodifiableMap(new HashMap<>(blockPrices));
        spawnerWeights = Collections.unmodifiableMap(new HashMap<>(spawnerWeights));
        if (pointsPerLevel <= 0) {
            throw new IllegalArgumentException("pointsPerLevel must be positive: " + pointsPerLevel);
        }
        if (dampingFactor <= 0.0 || dampingFactor > 1.0) {
            throw new IllegalArgumentException("dampingFactor must be in range (0.0, 1.0]: " + dampingFactor);
        }
        Objects.requireNonNull(leaderboardFreshness, "leaderboardFreshness must not be null");
        if (leaderboardFreshness.isNegative()) {
            throw new IllegalArgumentException("leaderboardFreshness must not be negative: " + leaderboardFreshness);
        }
    }

    /** The eight-argument shape, for a caller that names no leaderboard window. */
    public LevelConfiguration(
            long pointsPerLevel,
            long bankMinorUnitsPerPoint,
            long defaultSpawnerWeight,
            long questWeight,
            double dampingFactor,
            Map<String, Long> blockWeights,
            Map<String, Long> blockPrices,
            Map<String, Long> spawnerWeights) {
        this(
                pointsPerLevel,
                bankMinorUnitsPerPoint,
                defaultSpawnerWeight,
                questWeight,
                dampingFactor,
                blockWeights,
                blockPrices,
                spawnerWeights,
                DEFAULT_LEADERBOARD_FRESHNESS);
    }

    public Map<String, Long> basePricesMinorUnits() {
        return blockPrices;
    }

    public static LevelConfiguration defaultConfiguration() {
        Map<String, Long> blocks = Map.ofEntries(
                Map.entry("minecraft:diamond_block", 900L),
                Map.entry("minecraft:emerald_block", 1200L),
                Map.entry("minecraft:iron_block", 90L),
                Map.entry("minecraft:gold_block", 450L),
                Map.entry("minecraft:netherite_block", 15000L),
                Map.entry("minecraft:beacon", 5000L),
                Map.entry("minecraft:crying_obsidian", 250L),
                Map.entry("minecraft:ancient_debris", 2500L),
                Map.entry("minecraft:amethyst_block", 30L),
                Map.entry("minecraft:copper_block", 20L),
                Map.entry("minecraft:lapis_block", 100L),
                Map.entry("minecraft:redstone_block", 50L));

        Map<String, Long> prices = Map.of(
                "minecraft:diamond_block", 90000L,
                "minecraft:emerald_block", 120000L,
                "minecraft:iron_block", 9000L,
                "minecraft:gold_block", 45000L,
                "minecraft:netherite_block", 1500000L,
                "minecraft:beacon", 500000L);

        Map<String, Long> spawners = Map.of(
                "minecraft:iron_golem", 5000L,
                "minecraft:blaze", 1000L,
                "minecraft:enderman", 1500L,
                "minecraft:witch", 1200L,
                "minecraft:creeper", 400L,
                "minecraft:skeleton", 250L,
                "minecraft:zombie", 200L,
                "minecraft:spider", 150L);

        return new LevelConfiguration(
                DEFAULT_POINTS_PER_LEVEL,
                DEFAULT_BANK_MINOR_UNITS_PER_POINT,
                DEFAULT_SPAWNER_WEIGHT,
                DEFAULT_QUEST_WEIGHT,
                DEFAULT_DAMPING_FACTOR,
                blocks,
                prices,
                spawners);
    }

    public static LevelConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");

        long pointsPerLevel = Math.max(1L, rootNode.node("points-per-level").getLong(DEFAULT_POINTS_PER_LEVEL));
        long bankPerPoint =
                Math.max(1L, rootNode.node("bank-minor-units-per-point").getLong(DEFAULT_BANK_MINOR_UNITS_PER_POINT));
        long defaultSpawnerWeight =
                Math.max(0L, rootNode.node("default-spawner-weight").getLong(DEFAULT_SPAWNER_WEIGHT));
        long questWeight = Math.max(0L, rootNode.node("quest-weight").getLong(DEFAULT_QUEST_WEIGHT));
        double dampingFactor = rootNode.node("damping-factor").getDouble(DEFAULT_DAMPING_FACTOR);
        if (dampingFactor <= 0.0 || dampingFactor > 1.0) {
            dampingFactor = DEFAULT_DAMPING_FACTOR;
        }

        Map<String, Long> blocks = new HashMap<>();
        ConfigurationNode blocksNode = rootNode.node("blocks");
        if (!blocksNode.virtual() && !blocksNode.empty()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    blocksNode.childrenMap().entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                long weight = entry.getValue().getLong(0L);
                if (weight > 0) {
                    blocks.put(key, weight);
                }
            }
        }

        Map<String, Long> prices = new HashMap<>();
        ConfigurationNode pricesNode = rootNode.node("prices");
        if (!pricesNode.virtual() && !pricesNode.empty()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    pricesNode.childrenMap().entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                long price = entry.getValue().getLong(0L);
                if (price > 0) {
                    prices.put(key, price);
                }
            }
        }

        Map<String, Long> spawners = new HashMap<>();
        ConfigurationNode spawnersNode = rootNode.node("spawners");
        if (!spawnersNode.virtual() && !spawnersNode.empty()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    spawnersNode.childrenMap().entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                long weight = entry.getValue().getLong(0L);
                if (weight > 0) {
                    spawners.put(key, weight);
                }
            }
        }

        return new LevelConfiguration(
                pointsPerLevel,
                bankPerPoint,
                defaultSpawnerWeight,
                questWeight,
                dampingFactor,
                blocks.isEmpty() ? defaultConfiguration().blockWeights() : blocks,
                prices.isEmpty() ? defaultConfiguration().blockPrices() : prices,
                spawners.isEmpty() ? defaultConfiguration().spawnerWeights() : spawners,
                leaderboardFreshness(rootNode));
    }

    /**
     * How long a leaderboard is answered from memory before it is built again.
     *
     * <p>Building one is a sort across every island and the two callers are a command and an HTTP
     * endpoint anybody may hammer, so it is worth holding. It used to be held for ever.
     */
    private static java.time.Duration leaderboardFreshness(ConfigurationNode rootNode) {
        String raw = rootNode.node("leaderboard-freshness").getString();
        return raw != null && !raw.isBlank()
                ? com.uxplima.uxmlib.common.Durations.parse(raw)
                : DEFAULT_LEADERBOARD_FRESHNESS;
    }
}
