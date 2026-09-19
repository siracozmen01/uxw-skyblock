package com.uxplima.uxmskyblock.core.domain.worth;

/**
 * Immutable domain record representing an evaluated island level score breakdown and economic valuation.
 *
 * @param blockScore points contributed by placed catalog blocks
 * @param spawnerScore points contributed by mob spawners
 * @param questScore points contributed by completed island quests
 * @param bankScore points contributed by primary bank balance
 * @param totalScore sum of all point categories
 * @param calculatedLevel calculated island level
 * @param rawEconomicWorthMinorUnits raw market net worth before damping
 * @param dampedEconomicWorthMinorUnits damped market net worth with anti-speculation factor applied
 */
public record IslandScoreBreakdown(
        long blockScore,
        long spawnerScore,
        long questScore,
        long bankScore,
        long totalScore,
        long calculatedLevel,
        long rawEconomicWorthMinorUnits,
        long dampedEconomicWorthMinorUnits) {

    public IslandScoreBreakdown {
        if (totalScore < 0) {
            throw new IllegalArgumentException("totalScore cannot be negative: " + totalScore);
        }
        if (calculatedLevel < 0) {
            throw new IllegalArgumentException("calculatedLevel cannot be negative: " + calculatedLevel);
        }
        if (rawEconomicWorthMinorUnits < 0) {
            throw new IllegalArgumentException(
                    "rawEconomicWorthMinorUnits cannot be negative: " + rawEconomicWorthMinorUnits);
        }
        if (dampedEconomicWorthMinorUnits < 0) {
            throw new IllegalArgumentException(
                    "dampedEconomicWorthMinorUnits cannot be negative: " + dampedEconomicWorthMinorUnits);
        }
    }

    public static IslandScoreBreakdown zero() {
        return new IslandScoreBreakdown(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
}
