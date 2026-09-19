package com.uxplima.uxmskyblock.core.domain.booster;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration policy for a specific booster category.
 *
 * @param category the category this policy applies to
 * @param enabled whether boosters are allowed for this category
 * @param stackMode stacking strategy (DURATION, MULTIPLIER, REPLACE)
 * @param durationPolicy expiration decay policy for MULTIPLIER mode (INDEPENDENT, REFRESH)
 * @param calculation calculation logic (ADDITIVE, COMPOUND)
 * @param maxMultiplier maximum cap on effective multiplier
 * @param maxDuration maximum cap on active duration
 * @param defaultMultiplier default multiplier when not explicitly specified
 * @param defaultDuration default duration when not explicitly specified
 */
public record CategoryBoosterPolicy(
        BoosterCategory category,
        boolean enabled,
        BoosterStackMode stackMode,
        BoosterDurationPolicy durationPolicy,
        BoosterCalculation calculation,
        double maxMultiplier,
        Duration maxDuration,
        double defaultMultiplier,
        Duration defaultDuration) {

    public CategoryBoosterPolicy {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(stackMode, "stackMode must not be null");
        Objects.requireNonNull(durationPolicy, "durationPolicy must not be null");
        Objects.requireNonNull(calculation, "calculation must not be null");
        Objects.requireNonNull(maxDuration, "maxDuration must not be null");
        Objects.requireNonNull(defaultDuration, "defaultDuration must not be null");
        if (maxMultiplier <= 0) {
            throw new IllegalArgumentException("maxMultiplier must be positive");
        }
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
    }

    public static CategoryBoosterPolicy defaultFor(BoosterCategory category) {
        return new CategoryBoosterPolicy(
                category,
                true,
                BoosterStackMode.DURATION,
                BoosterDurationPolicy.INDEPENDENT,
                BoosterCalculation.ADDITIVE,
                5.0,
                Duration.ofDays(7),
                1.5,
                Duration.ofHours(1));
    }
}
