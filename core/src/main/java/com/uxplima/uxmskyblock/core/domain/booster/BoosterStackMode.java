package com.uxplima.uxmskyblock.core.domain.booster;

import java.util.Locale;
import java.util.Optional;

/**
 * Governs how multiple boosters applied to the same category interact.
 */
public enum BoosterStackMode {
    /**
     * Multiplier remains constant while active durations sum additively, constrained by max-duration.
     */
    DURATION,

    /**
     * Multipliers compound or additively stack, constrained by max-multiplier hard cap.
     */
    MULTIPLIER,

    /**
     * Newer, higher-tier booster replaces an inferior active booster.
     */
    REPLACE;

    public static Optional<BoosterStackMode> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (BoosterStackMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
