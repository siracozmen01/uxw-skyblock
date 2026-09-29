package com.uxplima.uxmskyblock.core.domain.hazard;

import java.time.Duration;
import java.util.Objects;

/**
 * How the air hurts a Poseidon player, and the water too when they stop moving in it.
 *
 * <p>Water is a Poseidon player's air. Out of it, dry air takes its damage at each check, and the sun
 * adds its own when it reaches them. Rain wets a player as the sea does, while the operator counts it.
 * In the water nothing hurts, unless the player has stayed in one spot for too long.
 *
 * @param dryDamage health taken at each check from a player out of the water
 * @param sunDamage health the sun adds at each check, on a player out of the water it reaches
 * @param stillDamage health taken at each check from a player who stays still in the water too long
 * @param stillAfter how long a player may stay still in the water before it hurts
 * @param stillReach how far a player must move, in blocks, to count as moving
 * @param rainIsWet whether rain keeps a player out of the water from drying
 * @param checkEvery how often each player on a Poseidon island is checked
 */
public record PoseidonRules(
        double dryDamage,
        double sunDamage,
        double stillDamage,
        Duration stillAfter,
        double stillReach,
        boolean rainIsWet,
        Duration checkEvery) {

    /** What hurts at one check, or nothing. */
    public enum Harm {
        NONE,
        STILL,
        DRY,
        SUN
    }

    public PoseidonRules {
        Objects.requireNonNull(stillAfter, "stillAfter must not be null");
        Objects.requireNonNull(checkEvery, "checkEvery must not be null");
        if (dryDamage < 0 || sunDamage < 0 || stillDamage < 0) {
            throw new IllegalArgumentException("damage must not be negative");
        }
        if (stillAfter.isNegative() || !(stillReach > 0)) {
            throw new IllegalArgumentException("still-after must not be negative and still-reach must be above 0");
        }
        if (checkEvery.toMillis() < 50) {
            throw new IllegalArgumentException("check-every must be at least one tick: " + checkEvery);
        }
    }

    /**
     * What the plugin ships: half a heart a second in dry air and a heart and a half in the sun, and a
     * heart a second to a swimmer who stays within a block for five seconds. Rain keeps a player wet.
     */
    public static PoseidonRules shipped() {
        return new PoseidonRules(1.0, 2.0, 2.0, Duration.ofSeconds(5), 1.0, true, Duration.ofSeconds(1));
    }

    /** What hurts the player this time. */
    public Harm harm(PoseidonExposure exposure) {
        Objects.requireNonNull(exposure, "exposure must not be null");
        if (exposure.inWater()) {
            return exposure.stillFor().compareTo(stillAfter) >= 0 && stillDamage > 0 ? Harm.STILL : Harm.NONE;
        }
        if (exposure.rainedOn() && rainIsWet) {
            return Harm.NONE;
        }
        if (exposure.inSun() && sunDamage > 0) {
            return Harm.SUN;
        }
        return dryDamage > 0 ? Harm.DRY : Harm.NONE;
    }

    /** The health one check takes. Zero when nothing hurts. */
    public double damage(PoseidonExposure exposure) {
        return switch (harm(exposure)) {
            case NONE -> 0;
            case STILL -> stillDamage;
            case DRY -> dryDamage;
            case SUN -> dryDamage + sunDamage;
        };
    }
}
