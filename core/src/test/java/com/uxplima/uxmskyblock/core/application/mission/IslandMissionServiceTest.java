package com.uxplima.uxmskyblock.core.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandMissionServiceTest {

    private InMemoryMissionStorage storage;
    private AtomicBoolean rewardDispatched;
    private IslandMissionService service;

    private final IslandId islandId = new IslandId(UUID.randomUUID());
    private final ProfileId profileId = new ProfileId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        storage = new InMemoryMissionStorage();
        rewardDispatched = new AtomicBoolean(false);
        service = new IslandMissionService(storage, (isl, prof, def) -> rewardDispatched.set(true));
    }

    @Test
    @DisplayName("registers missions and queries by branch")
    void registersAndQueriesByBranch() {
        MissionDefinition m1 = new MissionDefinition(
                MissionId.of("wheat_1"),
                MissionBranch.FARMING,
                "Wheat Harvester I",
                "Harvest 50 wheat",
                MissionTriggerType.CROP_HARVEST,
                "WHEAT",
                50L,
                new MissionReward(10L, 500L, 100L, List.of()));

        MissionDefinition m2 = new MissionDefinition(
                MissionId.of("zombie_1"),
                MissionBranch.SLAYER,
                "Zombie Slayer I",
                "Kill 25 zombies",
                MissionTriggerType.MOB_KILL,
                "ZOMBIE",
                25L,
                new MissionReward(15L, 1000L, 200L, List.of()));

        service.registerMissions(List.of(m1, m2));

        assertThat(service.allMissions()).hasSize(2);
        assertThat(service.missionsByBranch(MissionBranch.FARMING)).containsExactly(m1);
        assertThat(service.missionsByBranch(MissionBranch.SLAYER)).containsExactly(m2);
        assertThat(service.missionsByBranch(MissionBranch.MINING)).isEmpty();
    }

    @Test
    @DisplayName("increments progress on matching trigger and dispatches reward upon completion")
    void incrementsProgressAndCompletes() {
        MissionDefinition m1 = new MissionDefinition(
                MissionId.of("wheat_1"),
                MissionBranch.FARMING,
                "Wheat Harvester I",
                "Harvest 10 wheat",
                MissionTriggerType.CROP_HARVEST,
                "WHEAT",
                10L,
                new MissionReward(10L, 500L, 100L, List.of()));
        service.registerMission(m1);

        Instant now = Instant.now();

        // 1. Non-matching target has no effect
        List<MissionProgress> res1 =
                service.handleTrigger(islandId, profileId, MissionTriggerType.CROP_HARVEST, "CARROT", 5L, now);
        assertThat(res1).isEmpty();
        assertThat(rewardDispatched.get()).isFalse();

        // 2. Partial progress
        List<MissionProgress> res2 =
                service.handleTrigger(islandId, profileId, MissionTriggerType.CROP_HARVEST, "WHEAT", 4L, now);
        assertThat(res2).hasSize(1);
        assertThat(res2.get(0).progressCount()).isEqualTo(4L);
        assertThat(res2.get(0).completed()).isFalse();
        assertThat(rewardDispatched.get()).isFalse();

        // 3. Completion
        List<MissionProgress> res3 = service.handleTrigger(
                islandId, profileId, MissionTriggerType.CROP_HARVEST, "WHEAT", 6L, now.plusSeconds(5));
        assertThat(res3).hasSize(1);
        assertThat(res3.get(0).progressCount()).isEqualTo(10L);
        assertThat(res3.get(0).completed()).isTrue();
        assertThat(res3.get(0).completedAt()).isNotNull();
        assertThat(rewardDispatched.get()).isTrue();

        // 4. Further triggers are ignored once completed
        rewardDispatched.set(false);
        List<MissionProgress> res4 = service.handleTrigger(
                islandId, profileId, MissionTriggerType.CROP_HARVEST, "WHEAT", 10L, now.plusSeconds(10));
        assertThat(res4).isEmpty();
        assertThat(rewardDispatched.get()).isFalse();
    }

    @Test
    @DisplayName("manual item submission increments progress and triggers reward")
    void manualItemSubmissionWorks() {
        MissionDefinition m1 = new MissionDefinition(
                MissionId.of("submit_cobble"),
                MissionBranch.BUILDER,
                "Cobblestone Delivery",
                "Submit 64 cobblestone",
                MissionTriggerType.ITEM_SUBMIT,
                "COBBLESTONE",
                64L,
                new MissionReward(20L, 2000L, 500L, List.of()));
        service.registerMission(m1);

        Instant now = Instant.now();
        Optional<MissionProgress> p1 =
                service.submitManualItem(islandId, profileId, MissionId.of("submit_cobble"), 32L, now);
        assertThat(p1).isPresent();
        assertThat(p1.get().progressCount()).isEqualTo(32L);
        assertThat(p1.get().completed()).isFalse();
        assertThat(rewardDispatched.get()).isFalse();

        Optional<MissionProgress> p2 =
                service.submitManualItem(islandId, profileId, MissionId.of("submit_cobble"), 32L, now.plusSeconds(1));
        assertThat(p2).isPresent();
        assertThat(p2.get().progressCount()).isEqualTo(64L);
        assertThat(p2.get().completed()).isTrue();
        assertThat(rewardDispatched.get()).isTrue();
    }

    @Test
    @DisplayName("routine non-completion triggers are buffered in memory and flushed on demand")
    void routineIncrementsAreBufferedAndFlushed() {
        MissionDefinition def = new MissionDefinition(
                MissionId.of("mine_stone"),
                MissionBranch.MINING,
                "Miner",
                "Mine 100 stone",
                MissionTriggerType.BLOCK_BREAK,
                "STONE",
                100L,
                new MissionReward(10L, 500L, 100L, List.of()));
        service.registerMission(def);

        Instant now = Instant.now();
        // Trigger partial progress (10 out of 100)
        List<MissionProgress> updated =
                service.handleTrigger(islandId, profileId, MissionTriggerType.BLOCK_BREAK, "STONE", 10L, now);

        assertThat(updated).hasSize(1);
        assertThat(service.dirtyEntriesCount()).isEqualTo(1);
        // Storage should NOT have received it yet
        assertThat(storage.findProgress(islandId, profileId, MissionId.of("mine_stone")))
                .isEmpty();

        // Flushed
        int flushed = service.flushDirtyProgress();
        assertThat(flushed).isEqualTo(1);
        assertThat(service.dirtyEntriesCount()).isEqualTo(0);
        // Now storage has it
        assertThat(storage.findProgress(islandId, profileId, MissionId.of("mine_stone")))
                .isPresent();
    }

    @Test
    @DisplayName("invalidate automatically flushes dirty progress before clearing cache")
    void invalidateFlushesDirtyEntries() {
        MissionDefinition def = new MissionDefinition(
                MissionId.of("slay_spider"),
                MissionBranch.SLAYER,
                "Spider Slayer",
                "Kill 50 spiders",
                MissionTriggerType.MOB_KILL,
                "SPIDER",
                50L,
                new MissionReward(10L, 500L, 100L, List.of()));
        service.registerMission(def);

        service.handleTrigger(islandId, profileId, MissionTriggerType.MOB_KILL, "SPIDER", 5L, Instant.now());
        assertThat(service.dirtyEntriesCount()).isEqualTo(1);

        service.invalidate(islandId, profileId);
        assertThat(service.dirtyEntriesCount()).isEqualTo(0);
        assertThat(storage.findProgress(islandId, profileId, MissionId.of("slay_spider")))
                .isPresent();
    }

    private static class InMemoryMissionStorage implements IslandMissionStoragePort {
        private final Map<String, Map<MissionId, MissionProgress>> data = new HashMap<>();

        private String key(IslandId islandId, ProfileId profileId) {
            return islandId.value() + ":" + profileId.value();
        }

        @Override
        public Optional<MissionProgress> findProgress(IslandId islandId, ProfileId profileId, MissionId missionId) {
            Map<MissionId, MissionProgress> map = data.get(key(islandId, profileId));
            return map == null ? Optional.empty() : Optional.ofNullable(map.get(missionId));
        }

        @Override
        public Map<MissionId, MissionProgress> findAllProgress(IslandId islandId, ProfileId profileId) {
            Map<MissionId, MissionProgress> map = data.get(key(islandId, profileId));
            return map == null ? Map.of() : new HashMap<>(map);
        }

        @Override
        public void saveProgress(IslandId islandId, ProfileId profileId, MissionProgress progress) {
            data.computeIfAbsent(key(islandId, profileId), k -> new HashMap<>()).put(progress.missionId(), progress);
        }

        @Override
        public void saveAllProgress(IslandId islandId, ProfileId profileId, Collection<MissionProgress> progresses) {
            for (MissionProgress p : progresses) {
                saveProgress(islandId, profileId, p);
            }
        }
    }
}
