package com.uxplima.uxmskyblock.core.domain.booster;

import java.time.Duration;
import java.util.Objects;

/**
 * Result of attempting to apply a booster to an island.
 */
public sealed interface BoosterApplyResult {

    /**
     * Booster successfully created as the sole active booster for this category.
     */
    record Success(IslandBooster booster, double effectiveMultiplier, Duration remainingDuration)
            implements BoosterApplyResult {
        public Success {
            Objects.requireNonNull(booster, "booster must not be null");
            Objects.requireNonNull(remainingDuration, "remainingDuration must not be null");
        }
    }

    /**
     * Booster applied in DURATION mode, extending existing booster's duration.
     */
    record DurationExtended(IslandBooster booster, double effectiveMultiplier, Duration totalDuration, boolean capped)
            implements BoosterApplyResult {
        public DurationExtended {
            Objects.requireNonNull(booster, "booster must not be null");
            Objects.requireNonNull(totalDuration, "totalDuration must not be null");
        }
    }

    /**
     * Booster applied in MULTIPLIER mode, stacking multipliers.
     */
    record MultiplierStacked(
            IslandBooster booster, double effectiveMultiplier, Duration remainingDuration, boolean capped)
            implements BoosterApplyResult {
        public MultiplierStacked {
            Objects.requireNonNull(booster, "booster must not be null");
            Objects.requireNonNull(remainingDuration, "remainingDuration must not be null");
        }
    }

    /**
     * Booster applied in REPLACE mode, replacing an existing lower-tier booster.
     */
    record Replaced(IslandBooster oldBooster, IslandBooster newBooster) implements BoosterApplyResult {
        public Replaced {
            Objects.requireNonNull(oldBooster, "oldBooster must not be null");
            Objects.requireNonNull(newBooster, "newBooster must not be null");
        }
    }

    /**
     * Booster rejected in REPLACE mode because active booster has equal or higher multiplier.
     */
    record RejectedLowerTier(double currentMultiplier, double attemptedMultiplier) implements BoosterApplyResult {}

    /**
     * Booster rejected because category is disabled in configuration.
     */
    record CategoryDisabled(BoosterCategory category) implements BoosterApplyResult {
        public CategoryDisabled {
            Objects.requireNonNull(category, "category must not be null");
        }
    }
}
