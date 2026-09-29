package com.uxplima.uxmskyblock.core.domain.hazard;

/**
 * What a player is exposed to at one check.
 *
 * @param inWater whether the player is in the acid sea
 * @param rainedOn whether rain falls on the player: a storm, and nothing between them and the sky
 * @param helmet whether the player wears something on their head
 * @param waterProtected whether an effect the operator names keeps the player safe in the sea
 */
public record AcidExposure(boolean inWater, boolean rainedOn, boolean helmet, boolean waterProtected) {

    public static final AcidExposure NONE = new AcidExposure(false, false, false, false);
}
