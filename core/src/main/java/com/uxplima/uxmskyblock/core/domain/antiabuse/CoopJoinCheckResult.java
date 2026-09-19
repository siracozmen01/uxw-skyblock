package com.uxplima.uxmskyblock.core.domain.antiabuse;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of checking whether a player is eligible to join another island co-op team.
 */
public sealed interface CoopJoinCheckResult {

    /**
     * Player is allowed to join an island co-op.
     */
    record Allowed() implements CoopJoinCheckResult {}

    /**
     * Co-op recruitment is rejected because of the nomadic hopper quarantine lock.
     *
     * @param availableAt timestamp when co-op hopping lock expires
     * @param remaining duration until expiration
     */
    record CooldownActive(Instant availableAt, Duration remaining) implements CoopJoinCheckResult {
        public CooldownActive {
            Objects.requireNonNull(availableAt, "availableAt must not be null");
            Objects.requireNonNull(remaining, "remaining must not be null");
        }
    }

    /**
     * Co-op join restriction is bypassed via permission or administrative override.
     */
    record Bypassed() implements CoopJoinCheckResult {}
}
