package com.uxplima.uxmskyblock.core.domain.booster;

import java.util.Locale;
import java.util.Optional;

/**
 * Categorical multiplier targets for island boosters.
 */
public enum BoosterCategory {
    SPAWNER_RATE("spawner_rate", "Spawner Rate"),
    CROP_GROWTH("crop_growth", "Crop Growth"),
    ORE_GENERATOR("ore_generator", "Ore Generator"),
    MOB_EXP("mob_exp", "Mob EXP"),
    ISLAND_WORTH("island_worth", "Island Worth"),
    MISSION_REWARDS("mission_rewards", "Mission Rewards");

    private final String key;
    private final String displayName;

    BoosterCategory(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<BoosterCategory> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (BoosterCategory category : values()) {
            if (category.key.equals(normalized)
                    || category.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
