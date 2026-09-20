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
import com.uxplima.uxmskyblock.core.application.reward.RewardDraftComponent;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
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
    private final @Nullable RewardInboxService rewardInboxService;

    public IslandSeasonService(
            IslandSeasonStoragePort storage,
            IslandLeaderboardPort leaderboard,
            @Nullable IslandStoragePort islandStorage,
            @Nullable RewardInboxService rewardInboxService) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.leaderboard = Objects.requireNonNull(leaderboard, "leaderboard must not be null");
        this.islandStorage = islandStorage;
        this.rewardInboxService = rewardInboxService;
    }

    public IslandSeasonService(
            IslandSeasonStoragePort storage,
            IslandLeaderboardPort leaderboard,
            @Nullable IslandStoragePort islandStorage) {
        this(storage, leaderboard, islandStorage, null);
    }

    public IslandSeasonService(IslandSeasonStoragePort storage, IslandLeaderboardPort leaderboard) {
        this(storage, leaderboard, null, null);
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
        checkAndAdvanceSeason(now, tierRewardActions, Map.of());
    }

    public synchronized void checkAndAdvanceSeason(
            Instant now,
            Map<Integer, List<String>> tierRewardActions,
            Map<Integer, List<RewardDraftComponent>> typedTierRewards) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(tierRewardActions, "tierRewardActions must not be null");
        Objects.requireNonNull(typedTierRewards, "typedTierRewards must not be null");

        Optional<SeasonRecord> optActive = storage.findActiveSeason();
        if (optActive.isEmpty()) {
            return;
        }

        SeasonRecord active = optActive.get();
        if (active.state() != SeasonState.ACTIVE && active.state() != SeasonState.FROZEN) {
            return;
        }

        if (active.state() == SeasonState.ACTIVE) {
            if (now.isBefore(active.endsAt())) {
                return;
            }
            // 1. Cluster-safe CAS transition ACTIVE -> FROZEN.
            // Prevents split-brain duplicate rotation across multiple cluster nodes.
            if (!storage.transitionSeasonState(active.id(), SeasonState.ACTIVE, SeasonState.FROZEN)) {
                return;
            }
        }

        // 2. Take immutable snapshots across tracked metrics (or reuse existing if crash recovering)
        List<SeasonSnapshotEntry> existingSnapshots = storage.findSnapshots(active.id(), SeasonMetric.LEVEL, 100);
        List<SeasonSnapshotEntry> allSnapshots;
        if (existingSnapshots.isEmpty()) {
            allSnapshots = new ArrayList<>();
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
        } else {
            allSnapshots = new ArrayList<>();
            for (SeasonMetric metric : SeasonMetric.values()) {
                allSnapshots.addAll(storage.findSnapshots(active.id(), metric, 100));
            }
        }

        // 3. Queue reward payouts for ranked placements (primary metric LEVEL)
        for (SeasonSnapshotEntry snapshot : allSnapshots) {
            if (snapshot.metric() == SeasonMetric.LEVEL) {
                ProfileId recipientProfileId = resolveOwnerProfile(snapshot);

                List<RewardDraftComponent> draftComponents = new ArrayList<>();
                List<RewardDraftComponent> configuredDrafts = typedTierRewards.get(snapshot.rank());
                if (configuredDrafts != null && !configuredDrafts.isEmpty()) {
                    draftComponents.addAll(configuredDrafts);
                }

                List<String> actions = tierRewardActions.get(snapshot.rank());
                if (actions != null && !actions.isEmpty()) {
                    for (int i = 0; i < actions.size(); i++) {
                        String action = actions.get(i);
                        String payoutId = "season-" + active.id().number() + "-rank-" + snapshot.rank() + "-"
                                + recipientProfileId.value() + (actions.size() > 1 ? "-" + i : "");
                        storage.queuePayout(new SeasonPayoutRecord(
                                payoutId,
                                active.id(),
                                snapshot.ownerUuid(),
                                action,
                                SeasonPayoutState.PENDING,
                                now,
                                null));

                        if (draftComponents.isEmpty()) {
                            var draft = parseRewardAction(action);
                            if (draft != null) {
                                draftComponents.add(draft);
                            }
                        }
                    }
                }

                if (rewardInboxService != null && !draftComponents.isEmpty()) {
                    try {
                        UUID grantUuid =
                                UUID.nameUUIDFromBytes(("season:" + active.id().number() + ":rank:" + snapshot.rank()
                                                + ":recipient:" + recipientProfileId.value())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId grantId =
                                com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId.of(grantUuid);
                        rewardInboxService.issueReward(
                                grantId,
                                recipientProfileId,
                                "SEASON_PAYOUT",
                                active.id().number() + ":RANK_" + snapshot.rank(),
                                now.plus(java.time.Duration.ofDays(30)),
                                draftComponents);
                    } catch (Exception expected) {
                        // Payout is already recorded in durable season storage; reward inbox delivery is
                        // best-effort on rotation
                    }
                }
            }
        }

        // 4. Complete season
        storage.transitionSeasonState(active.id(), SeasonState.FROZEN, SeasonState.COMPLETED);
    }

    public List<SeasonPayoutRecord> getPendingPayouts(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        return storage.findPendingPayouts(playerUuid);
    }

    public void markPayoutDispatched(String payoutId, Instant dispatchedAt) {
        Objects.requireNonNull(payoutId, "payoutId must not be null");
        Objects.requireNonNull(dispatchedAt, "dispatchedAt must not be null");
        storage.markPayoutDispatched(payoutId, dispatchedAt);
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

    public int dispatchPendingPayouts(PlayerUuid playerUuid, java.util.function.Consumer<String> actionExecutor) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(actionExecutor, "actionExecutor must not be null");
        List<SeasonPayoutRecord> pending = storage.findPendingPayouts(playerUuid);
        int dispatchedCount = 0;
        Instant now = Instant.now();

        for (SeasonPayoutRecord payout : pending) {
            actionExecutor.accept(payout.rewardAction());
            storage.markPayoutDispatched(payout.payoutId(), now);
            dispatchedCount++;
        }

        return dispatchedCount;
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

    private ProfileId resolveOwnerProfile(SeasonSnapshotEntry snapshot) {
        if (islandStorage != null) {
            Optional<Island> optIsland = islandStorage.findIslandById(snapshot.islandId());
            if (optIsland.isPresent()) {
                return optIsland.get().ownerProfileId();
            }
        }
        return new ProfileId(snapshot.ownerUuid().value());
    }

    private static @Nullable RewardDraftComponent parseRewardAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        String trimmed = action.trim();
        if (trimmed.startsWith("eco give ")) {
            int lastSpace = trimmed.lastIndexOf(' ');
            if (lastSpace > 8) {
                String amountStr = trimmed.substring(lastSpace + 1).trim();
                try {
                    long amount = Long.parseLong(amountStr);
                    return new RewardDraftComponent(
                            RewardComponentType.EXTERNAL_VAULT, "currency.vault", 1, "{\"amount\":" + amount + "}");
                } catch (NumberFormatException expected) {
                    return null;
                }
            }
        }
        return null;
    }
}
