package com.uxplima.uxmskyblock.core.application.mission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.jspecify.annotations.Nullable;

/**
 * Core application service orchestrating event-driven mission progress,
 * dynamic branching trees, manual item submissions, and reward dispatches.
 */
public final class IslandMissionService {

    private final IslandMissionStoragePort storagePort;
    private final @Nullable IslandMissionRewardPort rewardPort;

    private final Map<MissionId, MissionDefinition> missionCatalog = new ConcurrentHashMap<>();
    private final Map<String, Map<MissionId, MissionProgress>> progressCache = new ConcurrentHashMap<>();

    public IslandMissionService(
            IslandMissionStoragePort storagePort,
            @Nullable IslandMissionRewardPort rewardPort) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.rewardPort = rewardPort;
    }

    public IslandMissionService(IslandMissionStoragePort storagePort) {
        this(storagePort, null);
    }

    public void registerMission(MissionDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        missionCatalog.put(definition.id(), definition);
    }

    public void registerMissions(Collection<MissionDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        for (MissionDefinition def : definitions) {
            registerMission(def);
        }
    }

    public Optional<MissionDefinition> findMission(MissionId missionId) {
        Objects.requireNonNull(missionId, "missionId must not be null");
        return Optional.ofNullable(missionCatalog.get(missionId));
    }

    public List<MissionDefinition> allMissions() {
        return List.copyOf(missionCatalog.values());
    }

    public List<MissionDefinition> missionsByBranch(MissionBranch branch) {
        Objects.requireNonNull(branch, "branch must not be null");
        List<MissionDefinition> result = new ArrayList<>();
        for (MissionDefinition def : missionCatalog.values()) {
            if (def.branch() == branch) {
                result.add(def);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private String cacheKey(IslandId islandId, ProfileId profileId) {
        return islandId.value() + ":" + profileId.value();
    }

    public Map<MissionId, MissionProgress> findAllProgress(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        String key = cacheKey(islandId, profileId);
        return progressCache.computeIfAbsent(key, k -> {
            Map<MissionId, MissionProgress> fromStorage = storagePort.findAllProgress(islandId, profileId);
            return new ConcurrentHashMap<>(fromStorage);
        });
    }

    public Optional<MissionProgress> findProgress(IslandId islandId, ProfileId profileId, MissionId missionId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(missionId, "missionId must not be null");

        Map<MissionId, MissionProgress> playerProgress = findAllProgress(islandId, profileId);
        MissionProgress existing = playerProgress.get(missionId);
        if (existing != null) {
            return Optional.of(existing);
        }
        Optional<MissionProgress> fromStorage = storagePort.findProgress(islandId, profileId, missionId);
        fromStorage.ifPresent(p -> playerProgress.put(missionId, p));
        return fromStorage;
    }

    public List<MissionProgress> handleTrigger(
            IslandId islandId,
            ProfileId profileId,
            MissionTriggerType triggerType,
            @Nullable String target,
            long amount,
            Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(triggerType, "triggerType must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (amount <= 0) {
            return List.of();
        }

        Map<MissionId, MissionProgress> playerProgress = findAllProgress(islandId, profileId);
        List<MissionProgress> updated = new ArrayList<>();

        for (MissionDefinition def : missionCatalog.values()) {
            if (def.triggerType() != triggerType) {
                continue;
            }
            if (!matchesFilter(def.targetFilter(), target)) {
                continue;
            }

            MissionProgress current = playerProgress.computeIfAbsent(def.id(), MissionProgress::initial);
            if (current.completed()) {
                continue;
            }

            MissionProgress next = current.increment(amount, def.requiredAmount(), now);
            playerProgress.put(def.id(), next);
            storagePort.saveProgress(islandId, profileId, next);
            updated.add(next);

            if (!current.completed() && next.completed() && rewardPort != null) {
                rewardPort.dispatchReward(islandId, profileId, def);
            }
        }
        return updated;
    }

    public Optional<MissionProgress> submitManualItem(
            IslandId islandId,
            ProfileId profileId,
            MissionId missionId,
            long amount,
            Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(missionId, "missionId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (amount <= 0) {
            return Optional.empty();
        }

        MissionDefinition def = missionCatalog.get(missionId);
        if (def == null || def.triggerType() != MissionTriggerType.ITEM_SUBMIT) {
            return Optional.empty();
        }

        Map<MissionId, MissionProgress> playerProgress = findAllProgress(islandId, profileId);
        MissionProgress current = playerProgress.computeIfAbsent(def.id(), MissionProgress::initial);
        if (current.completed()) {
            return Optional.of(current);
        }

        MissionProgress next = current.increment(amount, def.requiredAmount(), now);
        playerProgress.put(def.id(), next);
        storagePort.saveProgress(islandId, profileId, next);

        if (next.completed() && rewardPort != null) {
            rewardPort.dispatchReward(islandId, profileId, def);
        }
        return Optional.of(next);
    }

    public void invalidate(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        progressCache.remove(cacheKey(islandId, profileId));
    }

    private boolean matchesFilter(String filter, @Nullable String target) {
        if (filter.isEmpty() || filter.equals("*")) {
            return true;
        }
        if (target == null) {
            return false;
        }
        return filter.equalsIgnoreCase(target.trim());
    }
}
