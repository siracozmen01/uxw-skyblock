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
    private final Map<String, DirtyProgressEntry> dirtyEntries = new ConcurrentHashMap<>();

    public record DirtyProgressEntry(IslandId islandId, ProfileId profileId, MissionProgress progress) {
        public DirtyProgressEntry {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(profileId, "profileId must not be null");
            Objects.requireNonNull(progress, "progress must not be null");
        }
    }

    public IslandMissionService(IslandMissionStoragePort storagePort, @Nullable IslandMissionRewardPort rewardPort) {
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

    /**
     * How many missions this profile has finished on this island.
     *
     * <p>The island level counts finished missions, weighted by {@code levels.quest-weight}. The
     * command that shows a level passed a hardcoded zero, so that weight was a number an operator
     * could configure and never see applied: a player who finished every mission on the server got
     * the same level as one who finished none.
     */
    public int countCompleted(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        int completed = 0;
        for (MissionProgress progress : findAllProgress(islandId, profileId).values()) {
            if (progress.completed()) {
                completed++;
            }
        }
        return completed;
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
            if (next.completed()) {
                dirtyEntries.remove(dirtyKey(islandId, profileId, def.id()));
                storagePort.saveProgress(islandId, profileId, next);
            } else {
                dirtyEntries.put(
                        dirtyKey(islandId, profileId, def.id()), new DirtyProgressEntry(islandId, profileId, next));
            }
            updated.add(next);

            if (!current.completed() && next.completed() && rewardPort != null) {
                rewardPort.dispatchReward(islandId, profileId, def);
            }
        }
        return updated;
    }

    public Optional<MissionProgress> submitManualItem(
            IslandId islandId, ProfileId profileId, MissionId missionId, long amount, Instant now) {
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
        dirtyEntries.remove(dirtyKey(islandId, profileId, def.id()));
        storagePort.saveProgress(islandId, profileId, next);

        if (next.completed() && rewardPort != null) {
            rewardPort.dispatchReward(islandId, profileId, def);
        }
        return Optional.of(next);
    }

    /**
     * Flushes all buffered unpersisted non-completion progress increments to relational storage.
     *
     * @return count of dirty progress entries persisted
     */
    public int flushDirtyProgress() {
        if (dirtyEntries.isEmpty()) {
            return 0;
        }
        int count = 0;
        List<Map.Entry<String, DirtyProgressEntry>> snapshot = new ArrayList<>(dirtyEntries.entrySet());
        for (Map.Entry<String, DirtyProgressEntry> entry : snapshot) {
            if (dirtyEntries.remove(entry.getKey(), entry.getValue())) {
                DirtyProgressEntry val = entry.getValue();
                storagePort.saveProgress(val.islandId(), val.profileId(), val.progress());
                count++;
            }
        }
        return count;
    }

    /**
     * Flushes buffered unpersisted progress increments for the specified island and profile.
     *
     * @return count of dirty progress entries persisted
     */
    public int flushFor(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        String prefix = islandId.value() + ":" + profileId.value() + ":";
        int count = 0;
        List<Map.Entry<String, DirtyProgressEntry>> snapshot = new ArrayList<>(dirtyEntries.entrySet());
        for (Map.Entry<String, DirtyProgressEntry> entry : snapshot) {
            if (entry.getKey().startsWith(prefix)) {
                if (dirtyEntries.remove(entry.getKey(), entry.getValue())) {
                    DirtyProgressEntry val = entry.getValue();
                    storagePort.saveProgress(val.islandId(), val.profileId(), val.progress());
                    count++;
                }
            }
        }
        return count;
    }

    public int dirtyEntriesCount() {
        return dirtyEntries.size();
    }

    public void invalidate(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        flushFor(islandId, profileId);
        progressCache.remove(cacheKey(islandId, profileId));
    }

    private String dirtyKey(IslandId islandId, ProfileId profileId, MissionId missionId) {
        return islandId.value() + ":" + profileId.value() + ":" + missionId.value();
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
