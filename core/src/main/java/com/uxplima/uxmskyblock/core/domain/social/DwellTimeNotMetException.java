package com.uxplima.uxmskyblock.core.domain.social;

import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when a player rates an island before they have visited it for the configured dwell time.
 *
 * <p>It carries how much longer the player has to stay, so the refusal a player reads can say so.
 */
public class DwellTimeNotMetException extends IllegalStateException {

    private final Duration remaining;

    public DwellTimeNotMetException(Duration required, Duration remaining) {
        super("Visitor has not met the minimum dwell time requirement of " + required);
        this.remaining = Objects.requireNonNull(remaining, "remaining must not be null");
    }

    /** How much longer the player has to stay before they may rate. */
    public Duration remaining() {
        return remaining;
    }
}
