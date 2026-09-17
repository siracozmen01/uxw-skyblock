package com.uxplima.uxmskyblock.core.domain.level;

/**
 * Immutable domain record representing an evaluated island level score and economic valuation.
 *
 * @param totalScore aggregated points from blocks, spawners, quests, and bank
 * @param calculatedLevel calculated island level
 * @param economicWorthMinorUnits total estimated market worth in minor units
 */
public record IslandLevelScore(long totalScore, long calculatedLevel, long economicWorthMinorUnits) {

    public IslandLevelScore {
        if (totalScore < 0) {
            throw new IllegalArgumentException("totalScore cannot be negative: " + totalScore);
        }
        if (calculatedLevel < 0) {
            throw new IllegalArgumentException("calculatedLevel cannot be negative: " + calculatedLevel);
        }
        if (economicWorthMinorUnits < 0) {
            throw new IllegalArgumentException(
                    "economicWorthMinorUnits cannot be negative: " + economicWorthMinorUnits);
        }
    }

    public static IslandLevelScore zero() {
        return new IslandLevelScore(0L, 0L, 0L);
    }
}
