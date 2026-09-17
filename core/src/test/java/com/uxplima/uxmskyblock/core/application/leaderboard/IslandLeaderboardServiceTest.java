package com.uxplima.uxmskyblock.core.application.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandLeaderboardServiceTest {

    private FakeLeaderboardPort leaderboardPort;
    private IslandLeaderboardService service;

    private final IslandId islandA = IslandId.of(UUID.randomUUID());
    private final IslandId islandB = IslandId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        leaderboardPort = new FakeLeaderboardPort();
        service = new IslandLeaderboardService(leaderboardPort, 50);

        leaderboardPort.data.put(
                LeaderboardCategory.LEVEL,
                List.of(
                        new LeaderboardEntry(1, islandA, "Alpha", 1500L, "Level 15"),
                        new LeaderboardEntry(2, islandB, "Bravo", 900L, "Level 9")));
    }

    @Test
    @DisplayName("getTop fetches from port on cache miss, caches result, and respects limit")
    void getTopCachingAndLimit() {
        List<LeaderboardEntry> top1 = service.getTop(LeaderboardCategory.LEVEL, 1);
        assertThat(top1).hasSize(1);
        assertThat(top1.getFirst().islandId()).isEqualTo(islandA);
        assertThat(leaderboardPort.fetchCount).isEqualTo(1);

        // Second call should hit cache, not increase fetchCount
        List<LeaderboardEntry> topAll = service.getTop(LeaderboardCategory.LEVEL, 10);
        assertThat(topAll).hasSize(2);
        assertThat(leaderboardPort.fetchCount).isEqualTo(1);
    }

    @Test
    @DisplayName("getRank returns 1-based rank when present, or empty")
    void getRank() {
        OptionalInt rankA = service.getRank(LeaderboardCategory.LEVEL, islandA);
        OptionalInt rankB = service.getRank(LeaderboardCategory.LEVEL, islandB);
        OptionalInt rankUnknown = service.getRank(LeaderboardCategory.LEVEL, IslandId.of(UUID.randomUUID()));

        assertThat(rankA).hasValue(1);
        assertThat(rankB).hasValue(2);
        assertThat(rankUnknown).isEmpty();
    }

    @Test
    @DisplayName("refresh invalidates and updates cache for specific category")
    void refreshCategory() {
        service.getTop(LeaderboardCategory.LEVEL, 10);
        assertThat(leaderboardPort.fetchCount).isEqualTo(1);

        // Mutate backend
        leaderboardPort.data.put(
                LeaderboardCategory.LEVEL, List.of(new LeaderboardEntry(1, islandB, "Bravo", 2000L, "Level 20")));

        service.refresh(LeaderboardCategory.LEVEL);
        assertThat(leaderboardPort.fetchCount).isEqualTo(2);

        List<LeaderboardEntry> updated = service.getTop(LeaderboardCategory.LEVEL, 10);
        assertThat(updated).hasSize(1);
        assertThat(updated.getFirst().islandId()).isEqualTo(islandB);
    }

    @Test
    @DisplayName("refreshAll invalidates and updates all categories")
    void refreshAllCategories() {
        service.refreshAll();
        assertThat(leaderboardPort.fetchCount).isEqualTo(LeaderboardCategory.values().length);
    }

    private static class FakeLeaderboardPort implements IslandLeaderboardPort {
        final Map<LeaderboardCategory, List<LeaderboardEntry>> data = new HashMap<>();
        int fetchCount = 0;

        @Override
        public List<LeaderboardEntry> fetchTopIslands(LeaderboardCategory category, int limit) {
            fetchCount++;
            List<LeaderboardEntry> list = data.getOrDefault(category, List.of());
            if (limit <= 0) {
                return List.of();
            }
            return list.subList(0, Math.min(limit, list.size()));
        }

        @Override
        public void updateIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {
            // no-op for fake
        }
    }
}
