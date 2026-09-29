package com.uxplima.uxmskyblock.core.domain.oneblock;

import java.util.Objects;

/**
 * One stretch of a OneBlock island's life: how many breaks it lasts and what the block turns into.
 *
 * @param key the phase's name in the operator's file, which the catalogue names it by
 * @param blocks how many breaks the phase lasts
 * @param blockPool what the block becomes after a break
 * @param creaturePool what may appear on the block after a break
 * @param creatureChance how likely a break is to bring a creature, from 0 to 1
 */
public record OneBlockPhase(
        String key, long blocks, WeightedPool blockPool, WeightedPool creaturePool, double creatureChance) {

    public OneBlockPhase {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(blockPool, "blockPool");
        Objects.requireNonNull(creaturePool, "creaturePool");
        if (key.isBlank()) {
            throw new IllegalArgumentException("A phase needs a name");
        }
        if (blocks < 1) {
            throw new IllegalArgumentException("Phase " + key + " must last at least one break: " + blocks);
        }
        if (blockPool.isEmpty()) {
            throw new IllegalArgumentException("Phase " + key + " has no block to turn into");
        }
        if (!(creatureChance >= 0 && creatureChance <= 1)) {
            throw new IllegalArgumentException("Phase " + key + " creature chance must be 0 to 1: " + creatureChance);
        }
    }
}
