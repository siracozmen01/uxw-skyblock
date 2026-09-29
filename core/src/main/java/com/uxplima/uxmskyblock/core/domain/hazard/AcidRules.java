package com.uxplima.uxmskyblock.core.domain.hazard;

import java.time.Duration;
import java.util.Objects;

/**
 * How much an acid sea and acid rain hurt, and how often a player is checked.
 *
 * @param waterDamage health taken at each check from a player in the sea
 * @param rainDamage health taken at each check from a player the rain falls on
 * @param helmetBlocksRain whether anything worn on the head keeps the rain off
 * @param checkEvery how often each player on an acid island is checked
 */
public record AcidRules(double waterDamage, double rainDamage, boolean helmetBlocksRain, Duration checkEvery) {

    public AcidRules {
        Objects.requireNonNull(checkEvery, "checkEvery must not be null");
        if (waterDamage < 0 || rainDamage < 0) {
            throw new IllegalArgumentException("acid damage must not be negative");
        }
        if (checkEvery.toMillis() < 50) {
            throw new IllegalArgumentException("check-every must be at least one tick: " + checkEvery);
        }
    }

    /** What the plugin ships: a heart a second in the sea, half one in the rain, a helmet keeps it off. */
    public static AcidRules shipped() {
        return new AcidRules(2.0, 1.0, true, Duration.ofSeconds(1));
    }

    /** The health one check takes. Zero when nothing reaches the player. */
    public double damage(AcidExposure exposure) {
        Objects.requireNonNull(exposure, "exposure must not be null");
        double taken = 0;
        if (exposure.inWater() && !exposure.waterProtected()) {
            taken += waterDamage;
        }
        if (exposure.rainedOn() && !(exposure.helmet() && helmetBlocksRain)) {
            taken += rainDamage;
        }
        return taken;
    }

    /** Whether the sea itself is what hurts this time, which is when its effects are given too. */
    public boolean seaHurts(AcidExposure exposure) {
        return exposure.inWater() && !exposure.waterProtected();
    }
}
