package com.uxplima.uxmskyblock.core.domain.booster;

import java.util.Locale;
import java.util.Optional;

/**
 * Governs expiration behavior when multiple boosters are active in MULTIPLIER stack mode.
 */
public enum BoosterDurationPolicy {
    /**
     * Each applied booster retains its own timestamp, decaying multipliers step-by-step as each expires.
     */
    INDEPENDENT,

    /**
     * Applying a new booster refreshes the overall duration timer to the full duration.
     */
    REFRESH;

    public static Optional<BoosterDurationPolicy> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (BoosterDurationPolicy policy : values()) {
            if (policy.name().equals(normalized)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }
}
