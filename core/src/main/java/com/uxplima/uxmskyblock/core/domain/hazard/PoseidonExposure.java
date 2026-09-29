package com.uxplima.uxmskyblock.core.domain.hazard;

import java.time.Duration;
import java.util.Objects;

/**
 * What a player on a Poseidon island is exposed to at one check.
 *
 * @param inWater whether the player is in water, which is their air
 * @param rainedOn whether rain falls on the player: a storm, and nothing between them and the sky
 * @param inSun whether the sun reaches the player: daytime in a world with a sun, and nothing overhead
 * @param stillFor how long the player has stayed in one spot in the water
 */
public record PoseidonExposure(boolean inWater, boolean rainedOn, boolean inSun, Duration stillFor) {

    public PoseidonExposure {
        Objects.requireNonNull(stillFor, "stillFor must not be null");
    }
}
