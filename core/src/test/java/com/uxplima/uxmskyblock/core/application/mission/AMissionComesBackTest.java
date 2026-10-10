package com.uxplima.uxmskyblock.core.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A daily mission starts again at midnight and a weekly one on Monday, in the zone the operator names, and each pays
 * again when it is finished again. A challenge, finished once, stays finished.
 */
class AMissionComesBackTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    /** Wednesday 2026-10-07, 23:30 in Istanbul. */
    private static final Instant WEDNESDAY_NIGHT = Instant.parse("2026-10-07T20:30:00Z");

    private final IslandId island = new IslandId(UUID.randomUUID());
    private final ProfileId profile = new ProfileId(UUID.randomUUID());
    private final List<MissionId> paid = new ArrayList<>();
    private IslandMissionService service;

    private static MissionDefinition mission(String id, MissionRepeat repeat) {
        return new MissionDefinition(
                MissionId.of(id),
                MissionBranch.MINING,
                id,
                id,
                MissionTriggerType.BLOCK_BREAK,
                "STONE",
                10L,
                new MissionReward(1L, 0L, 0L, List.of()),
                repeat);
    }

    @BeforeEach
    void setUp() {
        service = new IslandMissionService(new InMemoryMissionStorage(), (isl, prof, def) -> paid.add(def.id()));
        service.resetIn(ISTANBUL);
        service.registerMissions(List.of(
                mission("daily", MissionRepeat.DAILY),
                mission("weekly", MissionRepeat.WEEKLY),
                mission("once", MissionRepeat.ONCE)));
    }

    /** Where {@code mission} stands at {@code when}, as a window shows it. */
    private com.uxplima.uxmskyblock.core.domain.mission.MissionProgress at(Instant when, MissionId mission) {
        return java.util.Objects.requireNonNull(
                service.currentProgress(island, profile, when).get(mission), mission.toString());
    }

    private void breakStone(long amount, Instant at) {
        service.handleTrigger(island, profile, MissionTriggerType.BLOCK_BREAK, "STONE", amount, at);
    }

    @Test
    @DisplayName("A day turns at midnight where the server says, and a week on the Monday of it")
    void wherePeriodsBegin() {
        assertThat(MissionRepeat.DAILY.periodStart(WEDNESDAY_NIGHT, ISTANBUL))
                .isEqualTo(Instant.parse("2026-10-06T21:00:00Z"));
        assertThat(MissionRepeat.DAILY.periodStart(WEDNESDAY_NIGHT, ZoneId.of("UTC")))
                .isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
        assertThat(MissionRepeat.WEEKLY.periodStart(WEDNESDAY_NIGHT, ISTANBUL))
                .isEqualTo(Instant.parse("2026-10-04T21:00:00Z"));
        assertThat(MissionRepeat.ONCE.periodStart(WEDNESDAY_NIGHT, ISTANBUL)).isEqualTo(Instant.EPOCH);
        assertThat(MissionRepeat.named(" Weekly ")).contains(MissionRepeat.WEEKLY);
        assertThat(MissionRepeat.named("monthly")).isEmpty();
    }

    @Test
    @DisplayName("Finished on Wednesday night, the daily mission starts again an hour later and pays again")
    void theDailyMissionComesBack() {
        breakStone(10, WEDNESDAY_NIGHT);
        assertThat(paid).containsExactlyInAnyOrder(MissionId.of("daily"), MissionId.of("weekly"), MissionId.of("once"));

        Instant thursday = WEDNESDAY_NIGHT.plusSeconds(3600);
        assertThat(at(thursday, MissionId.of("daily")).completed()).isFalse();
        assertThat(at(thursday, MissionId.of("weekly")).completed()).isTrue();
        breakStone(10, thursday);

        assertThat(paid).filteredOn(MissionId.of("daily")::equals).hasSize(2);
        assertThat(paid).filteredOn(MissionId.of("weekly")::equals).hasSize(1);
        assertThat(paid).filteredOn(MissionId.of("once")::equals).hasSize(1);
    }

    @Test
    @DisplayName("Half a daily mission done yesterday is not half done today")
    void halfDoneYesterdayIsNotHalfDoneToday() {
        breakStone(6, WEDNESDAY_NIGHT);
        Instant thursday = WEDNESDAY_NIGHT.plusSeconds(3600);

        breakStone(6, thursday);

        assertThat(at(thursday, MissionId.of("daily")).progressCount()).isEqualTo(6);
        assertThat(at(thursday, MissionId.of("weekly")).completed()).isTrue();
    }

    @Test
    @DisplayName("The weekly mission comes back on Monday, and the level counts only what is finished once")
    void theWeeklyMissionComesBackOnMonday() {
        breakStone(10, WEDNESDAY_NIGHT);
        Instant monday = Instant.parse("2026-10-11T21:30:00Z");

        assertThat(at(monday, MissionId.of("weekly")).completed()).isFalse();
        assertThat(at(monday, MissionId.of("once")).completed()).isTrue();
        assertThat(service.countCompleted(island, profile))
                .describedAs("a mission that comes back would make the level fall when it did")
                .isEqualTo(1);
    }

    /** Keeps progress in memory, as a server's storage would. */
    private static final class InMemoryMissionStorage implements IslandMissionStoragePort {
        private final java.util.Map<MissionId, com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> stored =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public java.util.Optional<com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> findProgress(
                IslandId islandId, ProfileId profileId, MissionId missionId) {
            return java.util.Optional.ofNullable(stored.get(missionId));
        }

        @Override
        public java.util.Map<MissionId, com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> findAllProgress(
                IslandId islandId, ProfileId profileId) {
            return java.util.Map.copyOf(stored);
        }

        @Override
        public void saveProgress(
                IslandId islandId,
                ProfileId profileId,
                com.uxplima.uxmskyblock.core.domain.mission.MissionProgress progress) {
            stored.put(progress.missionId(), progress);
        }

        @Override
        public void saveAllProgress(
                IslandId islandId,
                ProfileId profileId,
                java.util.Collection<com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> progresses) {
            progresses.forEach(progress -> saveProgress(islandId, profileId, progress));
        }
    }
}
