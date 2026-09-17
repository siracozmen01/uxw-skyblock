package com.uxplima.uxmskyblock.core.application.level;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.level.IslandLevelScore;
import com.uxplima.uxmskyblock.core.domain.level.IslandMaterialIndex;

/**
 * Service calculating multi-factor island level scores and progression levels.
 *
 * <p>Composition:
 * {@code TotalScore = Score(blocks) + Score(spawners) + Score(quests) + Score(bank)}
 */
public final class IslandLevelService {

    private final long pointsPerLevel;
    private final long bankMinorUnitsPerPoint;
    private final long spawnerWeight;
    private final long questWeight;

    public IslandLevelService(long pointsPerLevel, long bankMinorUnitsPerPoint, long spawnerWeight, long questWeight) {
        if (pointsPerLevel <= 0) {
            throw new IllegalArgumentException("pointsPerLevel must be positive: " + pointsPerLevel);
        }
        this.pointsPerLevel = pointsPerLevel;
        this.bankMinorUnitsPerPoint = Math.max(1L, bankMinorUnitsPerPoint);
        this.spawnerWeight = Math.max(0L, spawnerWeight);
        this.questWeight = Math.max(0L, questWeight);
    }

    public static IslandLevelService defaultService() {
        return new IslandLevelService(100L, 10_000L, 25L, 50L);
    }

    /**
     * Calculates the composite island level score.
     *
     * @param materialIndex live material histogram of placed blocks
     * @param spawnerCount number of active spawners
     * @param questCount number of completed challenges/quests
     * @param bankPrimaryMinorUnits bank balance in minor units
     * @return evaluated IslandLevelScore
     */
    public IslandLevelScore calculateScore(
            IslandMaterialIndex materialIndex, int spawnerCount, int questCount, long bankPrimaryMinorUnits) {

        Objects.requireNonNull(materialIndex, "materialIndex");

        long blockScore = materialIndex.getCachedLevelScore();
        long spawnerScore = (long) Math.max(0, spawnerCount) * spawnerWeight;
        long questScore = (long) Math.max(0, questCount) * questWeight;
        long bankScore = Math.max(0L, bankPrimaryMinorUnits) / bankMinorUnitsPerPoint;

        long totalScore = blockScore + spawnerScore + questScore + bankScore;
        long calculatedLevel = totalScore / pointsPerLevel;
        long economicWorth = materialIndex.getCachedEconomicWorth() + Math.max(0L, bankPrimaryMinorUnits);

        return new IslandLevelScore(totalScore, calculatedLevel, economicWorth);
    }

    public long pointsPerLevel() {
        return pointsPerLevel;
    }
}
