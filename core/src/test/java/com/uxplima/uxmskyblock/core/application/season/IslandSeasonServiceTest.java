package com.uxplima.uxmskyblock.core.application.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonMetric;
import com.uxplima.uxmskyblock.core.domain.season.SeasonPayoutRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonPayoutState;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonSnapshotEntry;
import com.uxplima.uxmskyblock.core.domain.season.SeasonState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandSeasonServiceTest {

    private InMemorySeasonStorage storage;
    private InMemoryLeaderboardPort leaderboard;
    private InMemoryIslandStorage islandStorage;
    private IslandSeasonService seasonService;

    @BeforeEach
    void setUp() {
        storage = new InMemorySeasonStorage();
        leaderboard = new InMemoryLeaderboardPort();
        islandStorage = new InMemoryIslandStorage();
        seasonService = new IslandSeasonService(storage, leaderboard, islandStorage);
    }

    @Test
    @DisplayName("starts a new active season successfully")
    void startsNewActiveSeason() {
        Instant now = Instant.now();
        Instant end = now.plus(45, ChronoUnit.DAYS);
        SeasonId seasonId = SeasonId.of(1);

        seasonService.startSeason(seasonId, "Season 1", now, end);

        Optional<SeasonRecord> active = seasonService.activeSeason();
        assertThat(active).isPresent();
        assertThat(active.get().id()).isEqualTo(seasonId);
        assertThat(active.get().name()).isEqualTo("Season 1");
        assertThat(active.get().state()).isEqualTo(SeasonState.ACTIVE);
    }

    @Test
    @DisplayName("freezes, snapshots leaderboards, queues payouts, and completes season when expires")
    void expiresAndCompletesSeasonWithPayouts() {
        Instant start = Instant.now().minus(45, ChronoUnit.DAYS);
        Instant end = Instant.now().minus(1, ChronoUnit.MINUTES);
        SeasonId seasonId = SeasonId.of(1);

        storage.saveSeason(new SeasonRecord(seasonId, "Season 1", start, end, SeasonState.ACTIVE));

        IslandId isl1 = new IslandId(UUID.randomUUID());
        PlayerUuid leader1 = new PlayerUuid(UUID.randomUUID());
        Island island = Island.create(
                isl1,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                leader1,
                new ProfileId(leader1.value()),
                Instant.now());
        islandStorage.saveIsland(island, IslandLocation.fromCenterAndRadius(island.id(), "world", 0, 0, 100));

        leaderboard.setLevelEntries(List.of(new LeaderboardEntry(1, isl1, "Alex's Island", 5000L, "5,000")));

        Map<Integer, List<String>> tierRewards =
                Map.of(1, List.of("voucher give %leader% STORE_100USD", "crate give %leader% seasonal 10"));

        Instant checkTime = Instant.now();
        seasonService.checkAndAdvanceSeason(checkTime, tierRewards);

        // Season state should transition to COMPLETED
        SeasonRecord completed = storage.findSeason(seasonId).orElseThrow();
        assertThat(completed.state()).isEqualTo(SeasonState.COMPLETED);

        // Snapshots must be persisted
        List<SeasonSnapshotEntry> snapshots = storage.findSnapshots(seasonId, SeasonMetric.LEVEL, 10);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).rank()).isEqualTo(1);
        assertThat(snapshots.get(0).islandId()).isEqualTo(isl1);
        assertThat(snapshots.get(0).ownerUuid()).isEqualTo(leader1);
        assertThat(snapshots.get(0).score()).isEqualTo(5000L);

        // Pending payouts must be queued
        List<SeasonPayoutRecord> pending = storage.findPendingPayouts(leader1);
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).state()).isEqualTo(SeasonPayoutState.PENDING);
        assertThat(pending.get(0).rewardAction()).contains("%leader%");
    }

    @Test
    @DisplayName("claims pending payouts and transitions them to DISPATCHED state")
    void claimsPendingPayouts() {
        SeasonId seasonId = SeasonId.of(1);
        PlayerUuid leader = new PlayerUuid(UUID.randomUUID());
        storage.queuePayout(new SeasonPayoutRecord(
                "payout-1", seasonId, leader, "eco give Alex 1000", SeasonPayoutState.PENDING, Instant.now(), null));

        List<String> actions = seasonService.claimPendingPayouts(leader);
        assertThat(actions).containsExactly("eco give Alex 1000");

        List<SeasonPayoutRecord> pendingAfter = storage.findPendingPayouts(leader);
        assertThat(pendingAfter).isEmpty();
    }

    @Test
    @DisplayName("aborts rotation when CAS transition fails due to concurrent cluster node")
    void abortsWhenCasTransitionFailsDueToConcurrentRotation() {
        Instant now = Instant.now();
        SeasonId seasonId = SeasonId.of(99);
        Instant startsAt = now.minus(30, ChronoUnit.DAYS);
        Instant endsAt = now.minus(1, ChronoUnit.DAYS);
        SeasonRecord activeSeason = new SeasonRecord(seasonId, "Season 99", startsAt, endsAt, SeasonState.ACTIVE);
        storage.saveSeason(activeSeason);

        // Inject CAS failure by wrapping storage or setting a flag
        storage.setSimulateCasFailure(true);

        seasonService.checkAndAdvanceSeason(now, Map.of(1, List.of("eco give %leader% 1000")));

        // Since CAS failed, snapshots and payouts must NOT be processed
        List<SeasonSnapshotEntry> snapshots = storage.findSnapshots(seasonId, SeasonMetric.LEVEL, 10);
        assertThat(snapshots).isEmpty();
    }

    @Test
    @DisplayName("issues typed rewards to RewardInboxService upon season expiration")
    void issuesTypedRewardsToRewardInboxService() {
        com.uxplima.uxmskyblock.core.application.reward.RewardInboxService rewardInbox =
                org.mockito.Mockito.mock(com.uxplima.uxmskyblock.core.application.reward.RewardInboxService.class);
        IslandSeasonService serviceWithInbox =
                new IslandSeasonService(storage, leaderboard, islandStorage, rewardInbox);

        Instant start = Instant.now().minus(45, ChronoUnit.DAYS);
        Instant end = Instant.now().minus(1, ChronoUnit.MINUTES);
        SeasonId seasonId = SeasonId.of(5);

        storage.saveSeason(new SeasonRecord(seasonId, "Season 5", start, end, SeasonState.ACTIVE));

        IslandId isl = new IslandId(UUID.randomUUID());
        PlayerUuid leader = new PlayerUuid(UUID.randomUUID());
        Island island = Island.create(
                isl, IslandBounds.fromCenterAndRadius(0, 0, 100), leader, new ProfileId(leader.value()), Instant.now());
        islandStorage.saveIsland(island, IslandLocation.fromCenterAndRadius(island.id(), "world", 0, 0, 100));

        leaderboard.setLevelEntries(List.of(new LeaderboardEntry(1, isl, "Winner", 10000L, "10,000")));

        Map<Integer, List<String>> tierRewards = Map.of(1, List.of("eco give %leader% 1000000"));

        Instant checkTime = Instant.now();
        serviceWithInbox.checkAndAdvanceSeason(checkTime, tierRewards);

        // Verify RewardInboxService.issueReward was called with the winner's ProfileId and typed EXTERNAL_VAULT
        // component
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.uxplima.uxmskyblock.core.application.reward.RewardDraftComponent>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(rewardInbox)
                .issueReward(
                        org.mockito.ArgumentMatchers.eq(new ProfileId(leader.value())),
                        org.mockito.ArgumentMatchers.eq("SEASON_PAYOUT"),
                        org.mockito.ArgumentMatchers.eq("5:RANK_1"),
                        org.mockito.ArgumentMatchers.any(),
                        captor.capture());

        List<com.uxplima.uxmskyblock.core.application.reward.RewardDraftComponent> drafts = captor.getValue();
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).componentType())
                .isEqualTo(com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType.EXTERNAL_VAULT);
        assertThat(drafts.get(0).payloadData()).contains("1000000");
    }

    @Test
    @DisplayName("retry when season is already completed or frozen does not duplicate snapshots or payouts")
    void retryWhenSeasonAlreadyCompletedDoesNotDuplicate() {
        Instant start = Instant.now().minus(45, ChronoUnit.DAYS);
        Instant end = Instant.now().minus(1, ChronoUnit.MINUTES);
        SeasonId seasonId = SeasonId.of(10);

        storage.saveSeason(new SeasonRecord(seasonId, "Season 10", start, end, SeasonState.ACTIVE));

        IslandId isl = new IslandId(UUID.randomUUID());
        PlayerUuid leader = new PlayerUuid(UUID.randomUUID());
        Island island = Island.create(
                isl, IslandBounds.fromCenterAndRadius(0, 0, 100), leader, new ProfileId(leader.value()), Instant.now());
        islandStorage.saveIsland(island, IslandLocation.fromCenterAndRadius(island.id(), "world", 0, 0, 100));

        leaderboard.setLevelEntries(List.of(new LeaderboardEntry(1, isl, "Leader Island", 8000L, "8,000")));
        Map<Integer, List<String>> tierRewards = Map.of(1, List.of("eco give %leader% 500000"));

        Instant checkTime = Instant.now();
        // First execution: advances season to COMPLETED
        seasonService.checkAndAdvanceSeason(checkTime, tierRewards);
        assertThat(storage.findSnapshots(seasonId, SeasonMetric.LEVEL, 10)).hasSize(1);
        assertThat(storage.findPendingPayouts(leader)).hasSize(1);

        // Second execution (retry / periodic poll): must NO-OP and NOT duplicate
        seasonService.checkAndAdvanceSeason(checkTime.plusSeconds(30), tierRewards);
        assertThat(storage.findSnapshots(seasonId, SeasonMetric.LEVEL, 10)).hasSize(1);
        assertThat(storage.findPendingPayouts(leader)).hasSize(1);
    }

    private static class InMemorySeasonStorage implements IslandSeasonStoragePort {
        private final Map<SeasonId, SeasonRecord> seasons = new HashMap<>();
        private final List<SeasonSnapshotEntry> snapshots = new ArrayList<>();
        private final Map<String, SeasonPayoutRecord> payouts = new HashMap<>();
        private boolean simulateCasFailure = false;

        public void setSimulateCasFailure(boolean simulateCasFailure) {
            this.simulateCasFailure = simulateCasFailure;
        }

        @Override
        public boolean transitionSeasonState(SeasonId id, SeasonState expected, SeasonState target) {
            if (simulateCasFailure) {
                return false;
            }
            SeasonRecord current = seasons.get(id);
            if (current != null && current.state() == expected) {
                seasons.put(
                        id,
                        new SeasonRecord(current.id(), current.name(), current.startsAt(), current.endsAt(), target));
                return true;
            }
            return false;
        }

        @Override
        public void saveSeason(SeasonRecord season) {
            seasons.put(season.id(), season);
        }

        @Override
        public Optional<SeasonRecord> findActiveSeason() {
            return seasons.values().stream()
                    .filter(s -> s.state() == SeasonState.ACTIVE || s.state() == SeasonState.FROZEN)
                    .findFirst();
        }

        @Override
        public Optional<SeasonRecord> findSeason(SeasonId id) {
            return Optional.ofNullable(seasons.get(id));
        }

        @Override
        public List<SeasonRecord> listSeasons() {
            return List.copyOf(seasons.values());
        }

        @Override
        public void saveSnapshots(List<SeasonSnapshotEntry> entries) {
            snapshots.addAll(entries);
        }

        @Override
        public List<SeasonSnapshotEntry> findSnapshots(SeasonId id, SeasonMetric metric, int limit) {
            return snapshots.stream()
                    .filter(s -> s.seasonId().equals(id) && s.metric() == metric)
                    .sorted((a, b) -> Integer.compare(a.rank(), b.rank()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void queuePayout(SeasonPayoutRecord payout) {
            payouts.put(payout.payoutId(), payout);
        }

        @Override
        public List<SeasonPayoutRecord> findPendingPayouts(PlayerUuid recipient) {
            return payouts.values().stream()
                    .filter(p -> p.recipient().equals(recipient) && p.state() == SeasonPayoutState.PENDING)
                    .toList();
        }

        @Override
        public void markPayoutDispatched(String payoutId, Instant dispatchedAt) {
            SeasonPayoutRecord existing = payouts.get(payoutId);
            if (existing != null) {
                payouts.put(
                        payoutId,
                        new SeasonPayoutRecord(
                                existing.payoutId(),
                                existing.seasonId(),
                                existing.recipient(),
                                existing.rewardAction(),
                                SeasonPayoutState.DISPATCHED,
                                existing.createdAt(),
                                dispatchedAt));
            }
        }
    }

    private static class InMemoryLeaderboardPort implements IslandLeaderboardPort {
        private List<LeaderboardEntry> levelEntries = List.of();
        private List<LeaderboardEntry> bankEntries = List.of();
        private List<LeaderboardEntry> worthEntries = List.of();

        void setLevelEntries(List<LeaderboardEntry> entries) {
            this.levelEntries = entries;
        }

        @Override
        public List<LeaderboardEntry> fetchTopIslands(LeaderboardCategory category, int limit) {
            return switch (category) {
                case LEVEL -> levelEntries;
                case BANK -> bankEntries;
                case WORTH -> worthEntries;
            };
        }

        @Override
        public void updateIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {}
    }

    private static class InMemoryIslandStorage implements IslandStoragePort {
        private final Map<IslandId, Island> islands = new HashMap<>();

        @Override
        public void saveIsland(Island island, com.uxplima.uxmskyblock.core.domain.island.IslandLocation location) {
            islands.put(island.id(), island);
        }

        @Override
        public Optional<Island> findIslandById(IslandId id) {
            return Optional.ofNullable(islands.get(id));
        }

        @Override
        public Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation> findLocationByIslandId(IslandId id) {
            return Optional.empty();
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            return islands.values().stream()
                    .filter(i -> i.members().containsKey(profileId))
                    .map(Island::id)
                    .findFirst();
        }

        @Override
        public void deleteIsland(IslandId id) {
            islands.remove(id);
        }

        @Override
        public Optional<Island> findIslandByLocation(String worldName, int blockX, int blockZ) {
            return Optional.empty();
        }
    }
}
