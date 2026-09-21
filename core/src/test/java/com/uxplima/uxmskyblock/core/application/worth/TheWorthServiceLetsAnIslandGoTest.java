package com.uxplima.uxmskyblock.core.application.worth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.level.MaterialValuationIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The material index an island's worth is computed from is released when the island goes.
 *
 * <p>It is a count per material, kept per island, and nothing ever removed one. A server that has
 * made a hundred thousand islands over a year was carrying a hundred thousand of them for islands
 * that no longer exist.
 */
class TheWorthServiceLetsAnIslandGoTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private static IslandWorthService service() {
        return new IslandWorthService(
                new MaterialValuationIndex(Map.of("minecraft:stone", 500L), Map.of()),
                Map.of("minecraft:zombie", 200L),
                25L,
                50L,
                100L,
                10_000L,
                0.85,
                org.mockito.Mockito.mock(
                        com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort.class),
                null);
    }

    @Test
    @DisplayName("A forgotten island's blocks are no longer counted")
    void aforgottenIslandsBlocksAreGone() {
        IslandWorthService worth = service();
        worth.recordBlockPlace(ISLAND, "minecraft:stone", 100);
        assertThat(worth.calculateScore(ISLAND, 0, 0L).blockScore()).isPositive();

        worth.forgetIsland(ISLAND);

        assertThat(worth.calculateScore(ISLAND, 0, 0L).blockScore())
                .describedAs("the island is gone, so what it was holding is gone with it")
                .isZero();
    }

    @Test
    @DisplayName("A forgotten island's spawners are no longer counted")
    void aforgottenIslandsSpawnersAreGone() {
        IslandWorthService worth = service();
        worth.recordSpawnerPlace(ISLAND, "minecraft:zombie");
        long withSpawner = worth.calculateScore(ISLAND, 0, 0L).spawnerScore();

        worth.forgetIsland(ISLAND);

        assertThat(worth.calculateScore(ISLAND, 0, 0L).spawnerScore())
                .isNotEqualTo(withSpawner)
                .isZero();
    }

    @Test
    @DisplayName("Forgetting one island leaves another alone")
    void forgettingOneLeavesTheOther() {
        IslandWorthService worth = service();
        IslandId other = IslandId.of(UUID.randomUUID());
        worth.recordBlockPlace(ISLAND, "minecraft:stone", 10);
        worth.recordBlockPlace(other, "minecraft:stone", 10);

        worth.forgetIsland(ISLAND);

        assertThat(worth.calculateScore(other, 0, 0L).blockScore()).isPositive();
    }
}
