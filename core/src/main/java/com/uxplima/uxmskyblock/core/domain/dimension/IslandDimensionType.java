package com.uxplima.uxmskyblock.core.domain.dimension;

import java.util.Locale;
import java.util.Objects;

/**
 * Supported Minecraft dimensional environments for skyblock islands (Sections 2.27 & 2.37).
 */
public enum IslandDimensionType {
    OVERWORLD,
    NETHER,
    THE_END;

    public boolean isNether() {
        return this == NETHER;
    }

    public boolean isEnd() {
        return this == THE_END;
    }

    public boolean isOverworld() {
        return this == OVERWORLD;
    }

    public static IslandDimensionType fromKey(String key) {
        Objects.requireNonNull(key, "key");
        String normalized = key.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("NETHER")) {
            return NETHER;
        }
        if (normalized.contains("END")) {
            return THE_END;
        }
        return OVERWORLD;
    }
}
