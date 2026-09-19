package com.uxplima.uxmskyblock.core.application.booster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.booster.BoosterApplyResult;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCalculation;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterDurationPolicy;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterStackMode;
import com.uxplima.uxmskyblock.core.domain.booster.CategoryBoosterPolicy;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class IslandBoosterServiceTest {

    private InMemoryIslandBoosterStoragePort storage;
    private Map<BoosterCategory, CategoryBoosterPolicy> policies;
    private IslandBoosterService service;
    private Instant now;
    private IslandId islandId;

    @BeforeEach
    void setUp() {
        storage = new InMemoryIslandBoosterStoragePort();
        policies = new ConcurrentHashMap<>();
        now = Instant.parse("2026-09-19T12:00:00Z");
        islandId = new IslandId(UUID.randomUUID());

        // Setup default policies for each category
        for (BoosterCategory category : BoosterCategory.values()) {
            policies.put(category, CategoryBoosterPolicy.defaultFor(category));
        }

        service = new IslandBoosterService(storage, policies::get, true);
    }

    @Test
    @DisplayName("applying single booster succeeds and yields expected multiplier and remaining duration")
    void testApplySingleBoosterSuccess() {
        BoosterApplyResult result =
                service.applyBooster(islandId, BoosterCategory.SPAWNER_RATE, 2.0, Duration.ofHours(1), now);

        assertThat(result).isInstanceOf(BoosterApplyResult.Success.class);
        BoosterApplyResult.Success success = (BoosterApplyResult.Success) result;
        assertThat(success.effectiveMultiplier()).isEqualTo(2.0);
        assertThat(success.remainingDuration()).isEqualTo(Duration.ofHours(1));

        double effective = service.getEffectiveMultiplier(islandId, BoosterCategory.SPAWNER_RATE, now);
        assertThat(effective).isEqualTo(2.0);

        List<IslandBooster> active = service.getActiveBoosters(islandId, now);
        assertThat(active).hasSize(1);
    }

    @Test
    @DisplayName("DURATION stack mode adds active duration additively and caps at maxDuration")
    void testDurationStackingAdditiveDurationAndCapping() {
        policies.put(
                BoosterCategory.CROP_GROWTH,
                new CategoryBoosterPolicy(
                        BoosterCategory.CROP_GROWTH,
                        true,
                        BoosterStackMode.DURATION,
                        BoosterDurationPolicy.INDEPENDENT,
                        BoosterCalculation.ADDITIVE,
                        5.0,
                        Duration.ofHours(3),
                        1.5,
                        Duration.ofHours(1)));

        // Apply 1 hour
        service.applyBooster(islandId, BoosterCategory.CROP_GROWTH, 2.0, Duration.ofHours(1), now);

        // Apply another 1 hour -> total 2 hours
        BoosterApplyResult r2 =
                service.applyBooster(islandId, BoosterCategory.CROP_GROWTH, 2.0, Duration.ofHours(1), now);
        assertThat(r2).isInstanceOf(BoosterApplyResult.DurationExtended.class);
        BoosterApplyResult.DurationExtended extended1 = (BoosterApplyResult.DurationExtended) r2;
        assertThat(extended1.totalDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(extended1.capped()).isFalse();

        // Apply 2 more hours -> capped at maxDuration (3 hours)
        BoosterApplyResult r3 =
                service.applyBooster(islandId, BoosterCategory.CROP_GROWTH, 2.0, Duration.ofHours(2), now);
        assertThat(r3).isInstanceOf(BoosterApplyResult.DurationExtended.class);
        BoosterApplyResult.DurationExtended extended2 = (BoosterApplyResult.DurationExtended) r3;
        assertThat(extended2.totalDuration()).isEqualTo(Duration.ofHours(3));
        assertThat(extended2.capped()).isTrue();
    }

    @Test
    @DisplayName("MULTIPLIER stack mode with ADDITIVE calculation stacks bonus multipliers (1.5x -> 2.0x -> 2.5x)")
    void testMultiplierStackingAdditive() {
        policies.put(
                BoosterCategory.MOB_EXP,
                new CategoryBoosterPolicy(
                        BoosterCategory.MOB_EXP,
                        true,
                        BoosterStackMode.MULTIPLIER,
                        BoosterDurationPolicy.INDEPENDENT,
                        BoosterCalculation.ADDITIVE,
                        2.4, // Max multiplier cap
                        Duration.ofHours(5),
                        1.5,
                        Duration.ofHours(1)));

        // First 1.5x (+0.5 bonus)
        service.applyBooster(islandId, BoosterCategory.MOB_EXP, 1.5, Duration.ofHours(1), now);
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.MOB_EXP, now))
                .isEqualTo(1.5);

        // Second 1.5x (+0.5 bonus) -> 1.0 + 0.5 + 0.5 = 2.0x
        BoosterApplyResult r2 = service.applyBooster(islandId, BoosterCategory.MOB_EXP, 1.5, Duration.ofHours(1), now);
        assertThat(r2).isInstanceOf(BoosterApplyResult.MultiplierStacked.class);
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.MOB_EXP, now))
                .isEqualTo(2.0);

        // Third 1.5x (+0.5 bonus) -> 1.0 + 1.5 = 2.5x, but capped at 2.4x
        BoosterApplyResult r3 = service.applyBooster(islandId, BoosterCategory.MOB_EXP, 1.5, Duration.ofHours(1), now);
        assertThat(r3).isInstanceOf(BoosterApplyResult.MultiplierStacked.class);
        BoosterApplyResult.MultiplierStacked stacked = (BoosterApplyResult.MultiplierStacked) r3;
        assertThat(stacked.effectiveMultiplier()).isEqualTo(2.4);
        assertThat(stacked.capped()).isTrue();
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.MOB_EXP, now))
                .isEqualTo(2.4);
    }

    @Test
    @DisplayName("MULTIPLIER stack mode with COMPOUND calculation multiplies multipliers")
    void testMultiplierStackingCompound() {
        policies.put(
                BoosterCategory.ORE_GENERATOR,
                new CategoryBoosterPolicy(
                        BoosterCategory.ORE_GENERATOR,
                        true,
                        BoosterStackMode.MULTIPLIER,
                        BoosterDurationPolicy.INDEPENDENT,
                        BoosterCalculation.COMPOUND,
                        10.0,
                        Duration.ofHours(5),
                        1.5,
                        Duration.ofHours(1)));

        service.applyBooster(islandId, BoosterCategory.ORE_GENERATOR, 1.5, Duration.ofHours(1), now);
        service.applyBooster(islandId, BoosterCategory.ORE_GENERATOR, 2.0, Duration.ofHours(1), now);

        // 1.5 * 2.0 = 3.0
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.ORE_GENERATOR, now))
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("INDEPENDENT duration policy decays multipliers step-by-step as each booster expires")
    void testIndependentDurationDecay() {
        policies.put(
                BoosterCategory.MISSION_REWARDS,
                new CategoryBoosterPolicy(
                        BoosterCategory.MISSION_REWARDS,
                        true,
                        BoosterStackMode.MULTIPLIER,
                        BoosterDurationPolicy.INDEPENDENT,
                        BoosterCalculation.ADDITIVE,
                        10.0,
                        Duration.ofHours(5),
                        1.5,
                        Duration.ofHours(1)));

        // Booster A: +0.5 bonus for 30 minutes
        service.applyBooster(islandId, BoosterCategory.MISSION_REWARDS, 1.5, Duration.ofMinutes(30), now);
        // Booster B: +1.0 bonus for 60 minutes
        service.applyBooster(islandId, BoosterCategory.MISSION_REWARDS, 2.0, Duration.ofMinutes(60), now);

        // At t=0: 1.0 + 0.5 + 1.0 = 2.5x
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.MISSION_REWARDS, now))
                .isEqualTo(2.5);

        // At t=40m: Booster A expired, only Booster B active (2.0x)
        Instant t40 = now.plus(Duration.ofMinutes(40));
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.MISSION_REWARDS, t40))
                .isEqualTo(2.0);

        // At t=70m: Both expired, returns 1.0
        Instant t70 = now.plus(Duration.ofMinutes(70));
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.MISSION_REWARDS, t70))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("REFRESH duration policy refreshes timers of existing boosters")
    void testRefreshDurationPolicy() {
        policies.put(
                BoosterCategory.ISLAND_WORTH,
                new CategoryBoosterPolicy(
                        BoosterCategory.ISLAND_WORTH,
                        true,
                        BoosterStackMode.MULTIPLIER,
                        BoosterDurationPolicy.REFRESH,
                        BoosterCalculation.ADDITIVE,
                        10.0,
                        Duration.ofHours(5),
                        1.5,
                        Duration.ofHours(1)));

        service.applyBooster(islandId, BoosterCategory.ISLAND_WORTH, 1.5, Duration.ofMinutes(30), now);

        // After 20 minutes, apply another booster with 60 minutes duration
        Instant t20 = now.plus(Duration.ofMinutes(20));
        service.applyBooster(islandId, BoosterCategory.ISLAND_WORTH, 1.5, Duration.ofMinutes(60), t20);

        // At t=40m (which was after initial 30m expiry), both boosters are still active because duration refreshed to
        // t20 + 60m = t80m!
        Instant t40 = now.plus(Duration.ofMinutes(40));
        List<IslandBooster> active = service.getActiveBoosters(islandId, BoosterCategory.ISLAND_WORTH, t40);
        assertThat(active).hasSize(2);
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.ISLAND_WORTH, t40))
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("REPLACE stack mode replaces lower-tier booster and rejects inferior booster")
    void testReplaceStackMode() {
        policies.put(
                BoosterCategory.SPAWNER_RATE,
                new CategoryBoosterPolicy(
                        BoosterCategory.SPAWNER_RATE,
                        true,
                        BoosterStackMode.REPLACE,
                        BoosterDurationPolicy.INDEPENDENT,
                        BoosterCalculation.ADDITIVE,
                        5.0,
                        Duration.ofHours(5),
                        1.5,
                        Duration.ofHours(1)));

        service.applyBooster(islandId, BoosterCategory.SPAWNER_RATE, 2.0, Duration.ofHours(1), now);

        // Lower or equal tier is rejected
        BoosterApplyResult rLower =
                service.applyBooster(islandId, BoosterCategory.SPAWNER_RATE, 1.5, Duration.ofHours(1), now);
        assertThat(rLower).isInstanceOf(BoosterApplyResult.RejectedLowerTier.class);
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.SPAWNER_RATE, now))
                .isEqualTo(2.0);

        // Higher tier replaces active
        BoosterApplyResult rHigher =
                service.applyBooster(islandId, BoosterCategory.SPAWNER_RATE, 3.0, Duration.ofHours(2), now);
        assertThat(rHigher).isInstanceOf(BoosterApplyResult.Replaced.class);
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.SPAWNER_RATE, now))
                .isEqualTo(3.0);
        assertThat(service.getActiveBoosters(islandId, BoosterCategory.SPAWNER_RATE, now))
                .hasSize(1);
    }

    @Test
    @DisplayName("Pause-on-idle freezes timers when 0 members online and resumes seamlessly")
    void testPauseOnIdleFreezeAndResume() {
        service.applyBooster(islandId, BoosterCategory.SPAWNER_RATE, 2.0, Duration.ofMinutes(60), now);

        // Advance 10 minutes, then pause
        Instant t10 = now.plus(Duration.ofMinutes(10));
        service.pauseBoosters(islandId, t10);

        assertThat(service.isIslandPaused(islandId)).isTrue();
        List<IslandBooster> boosters = storage.findByIsland(islandId);
        assertThat(boosters.getFirst().isPaused()).isTrue();
        assertThat(boosters.getFirst().remainingSeconds()).isEqualTo(50 * 60);

        // Advance 100 hours while paused
        Instant t100Hours = now.plus(Duration.ofHours(100));
        assertThat(service.getActiveBoosters(islandId, t100Hours)).hasSize(1);
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.SPAWNER_RATE, t100Hours))
                .isEqualTo(2.0);

        // Member rejoins, unpausing
        service.resumeBoosters(islandId, t100Hours);
        assertThat(service.isIslandPaused(islandId)).isFalse();

        // Should now have 50 minutes remaining from t100Hours
        List<IslandBooster> resumed = storage.findByIsland(islandId);
        assertThat(resumed.getFirst().isPaused()).isFalse();
        assertThat(resumed.getFirst().effectiveRemainingDuration(t100Hours)).isEqualTo(Duration.ofMinutes(50));
    }

    @Test
    @DisplayName("Disabled category rejects application")
    void testDisabledCategory() {
        policies.put(
                BoosterCategory.MOB_EXP,
                new CategoryBoosterPolicy(
                        BoosterCategory.MOB_EXP,
                        false,
                        BoosterStackMode.DURATION,
                        BoosterDurationPolicy.INDEPENDENT,
                        BoosterCalculation.ADDITIVE,
                        5.0,
                        Duration.ofHours(1),
                        1.5,
                        Duration.ofHours(1)));

        BoosterApplyResult result =
                service.applyBooster(islandId, BoosterCategory.MOB_EXP, 2.0, Duration.ofHours(1), now);
        assertThat(result).isInstanceOf(BoosterApplyResult.CategoryDisabled.class);
        assertThat(service.getEffectiveMultiplier(islandId, BoosterCategory.MOB_EXP, now))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("purgeExpired purges unpaused expired boosters but preserves paused boosters")
    void testPurgeExpired() {
        // Booster 1: unpaused, expires at now + 10m
        service.applyBooster(islandId, BoosterCategory.SPAWNER_RATE, 2.0, Duration.ofMinutes(10), now);
        // Booster 2: paused, expires at now + 10m but paused at now + 5m
        IslandId island2 = new IslandId(UUID.randomUUID());
        service.applyBooster(island2, BoosterCategory.CROP_GROWTH, 2.0, Duration.ofMinutes(10), now);
        service.pauseBoosters(island2, now.plus(Duration.ofMinutes(5)));

        // Advance 20 minutes: Booster 1 has expired, Booster 2 is paused with remaining time
        Instant t20 = now.plus(Duration.ofMinutes(20));
        int purged = service.purgeExpired(t20);

        assertThat(purged).isEqualTo(1);
        assertThat(storage.findByIsland(islandId)).isEmpty();
        assertThat(storage.findByIsland(island2)).hasSize(1);
    }

    private static final class InMemoryIslandBoosterStoragePort implements IslandBoosterStoragePort {
        private final Map<UUID, IslandBooster> store = new ConcurrentHashMap<>();

        @Override
        public void saveBooster(IslandBooster booster) {
            Objects.requireNonNull(booster, "booster must not be null");
            store.put(booster.id(), booster);
        }

        @Override
        public void saveAll(Collection<IslandBooster> boosters) {
            for (IslandBooster b : boosters) {
                saveBooster(b);
            }
        }

        @Override
        public Optional<IslandBooster> findById(UUID boosterId) {
            return Optional.ofNullable(store.get(boosterId));
        }

        @Override
        public List<IslandBooster> findByIsland(IslandId islandId) {
            return store.values().stream()
                    .filter(b -> b.islandId().equals(islandId))
                    .toList();
        }

        @Override
        public List<IslandBooster> findByIslandAndCategory(IslandId islandId, BoosterCategory category) {
            return store.values().stream()
                    .filter(b -> b.islandId().equals(islandId) && b.category() == category)
                    .toList();
        }

        @Override
        public void deleteById(UUID boosterId) {
            store.remove(boosterId);
        }

        @Override
        public void deleteByIsland(IslandId islandId) {
            store.values().removeIf(b -> b.islandId().equals(islandId));
        }

        @Override
        public int purgeExpired(Instant now) {
            int before = store.size();
            store.values().removeIf(b -> !b.isPaused() && b.expiresAt().isBefore(now));
            return before - store.size();
        }
    }
}
