package com.uxplima.uxmskyblock.core.domain.upgrade;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable domain record representing a specific tier of an upgrade.
 *
 * @param tier tier number (1-based)
 * @param costMinorUnits financial cost in currency minor units
 * @param currencyId currency identifier (e.g. PRIMARY, CRYSTALS, EXP)
 * @param properties custom configuration properties (e.g. radius, multiplier, limit)
 */
public record UpgradeTier(int tier, long costMinorUnits, String currencyId, Map<String, Double> properties) {

    public UpgradeTier {
        Objects.requireNonNull(currencyId, "currencyId");
        if (tier <= 0) {
            throw new IllegalArgumentException("tier must be positive: " + tier);
        }
        if (costMinorUnits < 0) {
            throw new IllegalArgumentException("costMinorUnits cannot be negative: " + costMinorUnits);
        }
        properties = (properties == null) ? Map.of() : Map.copyOf(properties);
    }
}
