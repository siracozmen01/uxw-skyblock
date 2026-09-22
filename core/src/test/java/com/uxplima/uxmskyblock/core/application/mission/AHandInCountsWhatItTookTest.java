package com.uxplima.uxmskyblock.core.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a hand in is told it gave is what the mission took, even when two arrive together.
 *
 * <p>What the mission still wanted was read before the progress was moved. Two hand ins that read it
 * at once both took what was left, the second one's items went past the target, and the window was
 * told they counted, so it handed nothing back for them.
 */
class AHandInCountsWhatItTookTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final MissionId MISSION = MissionId.of("hand_in_diamonds");
    private static final long REQUIRED = 10;

    @Test
    @DisplayName("Hand ins racing each other are credited no more, together, than the mission wanted")
    void racingHandInsAreCreditedExactly() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 200; round++) {
                IslandMissionService service = new IslandMissionService(new InMemoryMissionStorage());
                service.registerMission(new MissionDefinition(
                        MISSION,
                        MissionBranch.MINING,
                        "Hand in diamonds",
                        "Hand some in",
                        MissionTriggerType.ITEM_SUBMIT,
                        "DIAMOND",
                        REQUIRED,
                        MissionReward.empty()));
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Long>> credited = new java.util.ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    credited.add(pool.submit(() -> {
                        start.await();
                        return service.submitManualItems(ISLAND, PROFILE, MISSION, 3, Instant.now())
                                .map(IslandMissionService.MissionSubmission::credited)
                                .orElse(0L);
                    }));
                }
                start.countDown();
                long total = 0;
                for (Future<Long> each : credited) {
                    total += each.get(5, TimeUnit.SECONDS);
                }

                assertThat(total).describedAs("round " + round).isEqualTo(REQUIRED);
                assertThat(service.findProgress(ISLAND, PROFILE, MISSION)
                                .orElseThrow()
                                .progressCount())
                        .isEqualTo(REQUIRED);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class InMemoryMissionStorage implements IslandMissionStoragePort {
        private final Map<MissionId, MissionProgress> stored = new ConcurrentHashMap<>();

        @Override
        public Optional<MissionProgress> findProgress(IslandId islandId, ProfileId profileId, MissionId missionId) {
            return Optional.ofNullable(stored.get(missionId));
        }

        @Override
        public Map<MissionId, MissionProgress> findAllProgress(IslandId islandId, ProfileId profileId) {
            return Map.copyOf(stored);
        }

        @Override
        public void saveProgress(IslandId islandId, ProfileId profileId, MissionProgress progress) {
            stored.put(progress.missionId(), progress);
        }

        @Override
        public void saveAllProgress(IslandId islandId, ProfileId profileId, Collection<MissionProgress> progresses) {
            for (MissionProgress progress : progresses) {
                saveProgress(islandId, profileId, progress);
            }
        }
    }
}
