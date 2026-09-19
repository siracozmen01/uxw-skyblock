package com.uxplima.uxmskyblock.core.domain.antiabuse;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Immutable domain record tracking an island's starter protection quarantine window.
 *
 * @param islandId target island ID
 * @param quarantinedUntil timestamp when quarantine expires
 * @param reason reason for quarantine (e.g. "NEW_ISLAND_CREATION")
 */
public record IslandQuarantineRecord(IslandId islandId, Instant quarantinedUntil, String reason) {

    public IslandQuarantineRecord {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(quarantinedUntil, "quarantinedUntil must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }

    public boolean isQuarantined(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return now.isBefore(quarantinedUntil);
    }

    public Duration getRemaining(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!now.isBefore(quarantinedUntil)) {
            return Duration.ZERO;
        }
        return Duration.between(now, quarantinedUntil);
    }
}
