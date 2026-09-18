package com.uxplima.uxmskyblock.core.domain.season;

/**
 * Strongly-typed monotonic identifier for an automated season.
 */
public record SeasonId(int number) {

    public SeasonId {
        if (number <= 0) {
            throw new IllegalArgumentException("Season number must be positive: " + number);
        }
    }

    public static SeasonId of(int number) {
        return new SeasonId(number);
    }
}
