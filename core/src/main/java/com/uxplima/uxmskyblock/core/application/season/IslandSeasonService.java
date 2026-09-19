package com.uxplima.uxmskyblock.core.application.season;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonMetric;
import com.uxplima.uxmskyblock.core.domain.season.SeasonPayoutRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonPayoutState;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonSnapshotEntry;
import com.uxplima.uxmskyblock.core.domain.season.SeasonState;
import org.jspecify.annotations.Nullable;

/**
 * Orchestrates autonomous season lifecycles, immutable leaderboard freezes,
 * and durable offline reward payout queuing.
 */
public final class IslandSeasonService {

    private final IslandSeasonStoragePort storage;
    private final IslandLeaderboardPort leaderboard;
    private final @Nullable IslandStoragePort islandStorage;

    public IslandSeasonService(
            IslandSeasonStoragePort storage,
            IslandLeaderboardPort leaderboard,
            @Nullable IslandStoragePort islandStorage) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.leaderboard = Objects.requireNonNull(leaderboard, "leaderboard must not be null");
        this.islandStorage = islandStorage;
    }

    public IslandSeasonService(IslandSeasonStoragePort storage, IslandLeaderboardPort leaderboard) {
        this(storage, leaderboard, null);
    }

    public void startSeason(SeasonId id, String name, Instant startsAt, Instant endsAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(startsAt, "startsAt must not be null");
        Objects.requireNonNull(endsAt, "endsAt must not be null");

        SeasonRecord season = new SeasonRecord(id, name, startsAt, endsAt, SeasonState.ACTIVE);
        storage.saveSeason(season);
    }

    public Optional<SeasonRecord> activeSeason() {
        return storage.findActiveSeason();
    }

    public synchronized void checkAndAdvanceSeason(Instant now, Map<Integer, List<String>> tierRewardActions) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(tierRewardActions, "tierRewardActions must not be null");

        Optional<SeasonRecord> optActive = storage.findActiveSeason();
        if (optActive.isEmpty()) {
            return;
        }

        SeasonRecord active = optActive.get();
        if (active.state() != SeasonState.ACTIVE || now.isBefore(active.endsAt())) {
            return;
        }

        // 1. Cluster-safe CAS transition ACTIVE -> FROZEN.
        // Prevents split-brain duplicate rotation across multiple cluster nodes.
        if (!storage.transitionSeasonState(active.id(), SeasonState.ACTIVE, SeasonState.FROZEN)) {
            return;
        }

        // 2. Take immutable snapshots across tracked metrics
        List<SeasonSnapshotEntry> allSnapshots = new ArrayList<>();
        for (SeasonMetric metric : SeasonMetric.values()) {
            LeaderboardCategory category = LeaderboardCategory.valueOf(metric.name());
            List<LeaderboardEntry> entries = leaderboard.fetchTopIslands(category, 100);

            for (LeaderboardEntry entry : entries) {
                PlayerUuid ownerUuid = resolveOwner(entry);
                allSnapshots.add(new SeasonSnapshotEntry(
                        active.id(), metric, entry.rank(), entry.islandId(), ownerUuid, entry.score(), now));
            }
        }
        storage.saveSnapshots(allSnapshots);

        // 3. Queue reward payouts for ranked placements (primary metric LEVEL)
        for (SeasonSnapshotEntry snapshot : allSnapshots) {
            if (snapshot.metric() == SeasonMetric.LEVEL) {
                List<String> actions = tierRewardActions.get(snapshot.rank());
                if (actions != null) {
                    for (String action : actions) {
                        String payoutId = UUID.randomUUID().toString();
                        storage.queuePayout(new SeasonPayoutRecord(
                                payoutId,
                                active.id(),
                                snapshot.ownerUuid(),
                                action,
                                SeasonPayoutState.PENDING,
                                now,
                                null));
                    }
                }
            }
        }

        // 4. Complete season
        storage.transitionSeasonState(active.id(), SeasonState.FROZEN, SeasonState.COMPLETED);
    }

    public List<String> claimPendingPayouts(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        List<SeasonPayoutRecord> pending = storage.findPendingPayouts(playerUuid);
        List<String> actions = new ArrayList<>(pending.size());
        Instant now = Instant.now();

        for (SeasonPayoutRecord payout : pending) {
            actions.add(payout.rewardAction());
            storage.markPayoutDispatched(payout.payoutId(), now);
        }

        return actions;
    }

    private PlayerUuid resolveOwner(LeaderboardEntry entry) {
        if (islandStorage != null) {
            Optional<Island> optIsland = islandStorage.findIslandById(entry.islandId());
            if (optIsland.isPresent()) {
                return optIsland.get().ownerPlayerUuid();
            }
        }
        return new PlayerUuid(entry.islandId().value());
    }
}
