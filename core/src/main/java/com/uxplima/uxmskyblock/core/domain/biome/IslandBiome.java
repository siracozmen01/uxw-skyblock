package com.uxplima.uxmskyblock.core.domain.biome;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Enumeration of supported island biomes with unlock requirements.
 */
public enum IslandBiome {
    PLAINS("plains", "Plains", "minecraft:plains", 0),
    DESERT("desert", "Desert", "minecraft:desert", 5),
    NETHER_WASTES("nether_wastes", "Nether Wastes", "minecraft:nether_wastes", 10),
    JUNGLE("jungle", "Jungle", "minecraft:jungle", 15),
    SNOWY_PLAINS("snowy_plains", "Snowy Plains", "minecraft:snowy_plains", 20),
    FLOWER_FOREST("flower_forest", "Flower Forest", "minecraft:flower_forest", 25),
    SWAMP("swamp", "Swamp", "minecraft:swamp", 30),
    THE_END("the_end", "The End", "minecraft:the_end", 50);

    private final String id;
    private final String displayName;
    private final String resourceKey;
    private final int requiredLevel;

    IslandBiome(String id, String displayName, String resourceKey, int requiredLevel) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
        this.requiredLevel = requiredLevel;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String resourceKey() {
        return resourceKey;
    }

    public int requiredLevel() {
        return requiredLevel;
    }

    public static Optional<IslandBiome> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT).trim();
        return Arrays.stream(values())
                .filter(b -> b.id.equals(normalized)
                        || b.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }
}
