package com.uxplima.uxmskyblock.core.domain.antiabuse;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of checking whether a player is permitted to reset or delete their island.
 */
public sealed interface ResetCheckResult {

    /**
     * Reset is allowed.
     *
     * @param remainingResetsToday number of resets remaining in the current window (-1 if unlimited)
     */
    record Allowed(int remainingResetsToday) implements ResetCheckResult {}

    /**
     * Reset is rejected because cooldown between resets has not elapsed.
     *
     * @param availableAt timestamp when the next reset becomes available
     * @param remaining duration until available
     */
    record CooldownActive(Instant availableAt, Duration remaining) implements ResetCheckResult {
        public CooldownActive {
            Objects.requireNonNull(availableAt, "availableAt must not be null");
            Objects.requireNonNull(remaining, "remaining must not be null");
        }
    }

    /**
     * Reset is rejected because daily reset quota has been exhausted.
     *
     * @param maxDailyResets maximum allowed resets per window
     * @param nextWindowStart timestamp when the daily window resets
     * @param remaining duration until window resets
     */
    record DailyLimitExceeded(int maxDailyResets, Instant nextWindowStart, Duration remaining)
            implements ResetCheckResult {
        public DailyLimitExceeded {
            Objects.requireNonNull(nextWindowStart, "nextWindowStart must not be null");
            Objects.requireNonNull(remaining, "remaining must not be null");
        }
    }

    /**
     * Reset is allowed due to administrative or permission bypass.
     */
    record Bypassed() implements ResetCheckResult {}
}
