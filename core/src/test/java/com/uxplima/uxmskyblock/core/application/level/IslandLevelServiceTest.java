package com.uxplima.uxmskyblock.core.application.level;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.level.IslandLevelScore;
import com.uxplima.uxmskyblock.core.domain.level.IslandMaterialIndex;
import com.uxplima.uxmskyblock.core.domain.level.MaterialValuationIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandLevelServiceTest {

    @Test
    @DisplayName("calculateScore aggregates blocks, spawners, quests, and bank into level score")
    void calculateScore() {
        MaterialValuationIndex valuation = new MaterialValuationIndex();
        valuation.setWeight("minecraft:diamond_block", 10L);
        valuation.setPrice("minecraft:diamond_block", 100L);

        IslandMaterialIndex index = new IslandMaterialIndex(IslandId.of(UUID.randomUUID()), valuation);
        index.increment("minecraft:diamond_block", 50); // 500 block score, 5000 worth

        // defaultService: pointsPerLevel=100, bankMinorUnitsPerPoint=10,000, spawnerWeight=25, questWeight=50
        IslandLevelService service = IslandLevelService.defaultService();

        // 500 (blocks) + 4 * 25 (100 spawners) + 6 * 50 (300 quests) + 200,000 / 10,000 (20 bank) = 920 total score
        // Level: 920 / 100 = 9
        // Net worth: 5000 + 200,000 = 205,000
        IslandLevelScore score = service.calculateScore(index, 4, 6, 200_000L);

        assertThat(score.totalScore()).isEqualTo(920L);
        assertThat(score.calculatedLevel()).isEqualTo(9L);
        assertThat(score.economicWorthMinorUnits()).isEqualTo(205_000L);
    }
}
