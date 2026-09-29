package com.uxplima.uxmskyblock.core.domain.hazard;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Where a player came to rest and since when. A player who moves further than the reach from that
 * spot rests somewhere new, from now.
 */
public record Stillness(double x, double y, double z, Instant since) {

    public Stillness {
        Objects.requireNonNull(since, "since must not be null");
    }

    /** A player first seen at a spot, at rest there from now. */
    public static Stillness at(double x, double y, double z, Instant now) {
        return new Stillness(x, y, z, now);
    }

    /** The rest after the player is seen at a spot: the same while within reach, a new one past it. */
    public Stillness seenAt(double atX, double atY, double atZ, Instant now, double reach) {
        double dx = atX - x;
        double dy = atY - y;
        double dz = atZ - z;
        if (dx * dx + dy * dy + dz * dz <= reach * reach) {
            return this;
        }
        return new Stillness(atX, atY, atZ, now);
    }

    /** How long the player has been at rest, never less than zero. */
    public Duration heldFor(Instant now) {
        Duration held = Duration.between(since, now);
        return held.isNegative() ? Duration.ZERO : held;
    }
}
