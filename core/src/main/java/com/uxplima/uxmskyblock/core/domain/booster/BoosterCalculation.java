package com.uxplima.uxmskyblock.core.domain.booster;

import java.util.Locale;
import java.util.Optional;

/**
 * Governs mathematical compounding when combining multiple active booster multipliers.
 */
public enum BoosterCalculation {
    /**
     * Multipliers stack additively above base 1.0 (e.g., +0.5x and +0.5x yield 2.0x).
     */
    ADDITIVE,

    /**
     * Multipliers compound multiplicatively (e.g., 1.5x * 1.5x = 2.25x).
     */
    COMPOUND;

    public static Optional<BoosterCalculation> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (BoosterCalculation calc : values()) {
            if (calc.name().equals(normalized)) {
                return Optional.of(calc);
            }
        }
        return Optional.empty();
    }
}
