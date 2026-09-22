package com.uxplima.uxmskyblock.core.application.booster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.CategoryBoosterPolicy;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Opening the booster window costs one read, not fourteen.
 *
 * <p>It asked for the paused flag, then every active booster, then the active boosters of each of
 * six categories, then the effective multiplier of each of those. Every one of them was its own
 * query, and all fourteen ran on the thread that owns the player, while that thread waited.
 */
class OpeningTheBoosterWindowIsOneReadTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    /** Counts what reached the database, which is the whole point. */
    private static final class CountingStorage implements IslandBoosterStoragePort {
        private final List<IslandBooster> boosters = new ArrayList<>();
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public List<IslandBooster> findByIsland(IslandId islandId) {
            reads.incrementAndGet();
            return List.copyOf(boosters);
        }

        @Override
        public List<IslandBooster> findByIslandAndCategory(IslandId islandId, BoosterCategory category) {
            reads.incrementAndGet();
            return boosters.stream()
                    .filter(booster -> booster.category() == category)
                    .toList();
        }

        @Override
        public void saveBooster(IslandBooster booster) {
            boosters.add(booster);
        }

        @Override
        public void saveAll(Collection<IslandBooster> toSave) {
            boosters.addAll(toSave);
        }

        @Override
        public Optional<IslandBooster> findById(UUID boosterId) {
            return boosters.stream()
                    .filter(booster -> booster.id().equals(boosterId))
                    .findFirst();
        }

        @Override
        public void deleteById(UUID boosterId) {
            boosters.removeIf(booster -> booster.id().equals(boosterId));
        }

        @Override
        public void deleteByIsland(IslandId islandId) {
            boosters.clear();
        }

        @Override
        public int purgeExpired(Instant now) {
            return 0;
        }
    }

    private static IslandBoosterService serviceOver(IslandBoosterStoragePort storage) {
        return new IslandBoosterService(storage, CategoryBoosterPolicy::defaultFor, true);
    }

    @Test
    @DisplayName("Everything the window shows comes out of one read")
    void oneRead() {
        CountingStorage storage = new CountingStorage();
        storage.saveBooster(IslandBooster.create(ISLAND, BoosterCategory.MOB_EXP, 2.0, Duration.ofHours(1), NOW));
        IslandBoosterService service = serviceOver(storage);

        IslandBoosterService.BoosterOverview overview = service.overview(ISLAND, NOW);

        assertThat(storage.reads.get())
                .describedAs("reads of the booster table for one window")
                .isEqualTo(1);
        assertThat(overview.active()).hasSize(1);
        assertThat(overview.paused()).isFalse();
        for (BoosterCategory category : BoosterCategory.values()) {
            assertThat(overview.multiplierOf(category))
                    .describedAs("multiplier of %s", category)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("The overview says the same as asking each question on its own")
    void theOverviewAgreesWithTheQuestionsItReplaces() {
        CountingStorage storage = new CountingStorage();
        storage.saveBooster(IslandBooster.create(ISLAND, BoosterCategory.MOB_EXP, 2.0, Duration.ofHours(1), NOW));
        storage.saveBooster(IslandBooster.create(ISLAND, BoosterCategory.CROP_GROWTH, 1.5, Duration.ofHours(2), NOW));
        IslandBoosterService service = serviceOver(storage);

        IslandBoosterService.BoosterOverview overview = service.overview(ISLAND, NOW);

        assertThat(overview.active())
                .describedAs("every active booster")
                .hasSameSizeAs(service.getActiveBoosters(ISLAND, NOW));
        for (BoosterCategory category : BoosterCategory.values()) {
            assertThat(overview.activeIn(category))
                    .describedAs("active boosters of %s", category)
                    .hasSameSizeAs(service.getActiveBoosters(ISLAND, category, NOW));
            assertThat(overview.multiplierOf(category))
                    .describedAs("effective multiplier of %s", category)
                    .isEqualTo(service.getEffectiveMultiplier(ISLAND, category, NOW));
        }
    }

    @Test
    @DisplayName("An island whose boosters are paused says so in the overview")
    void aPausedIslandSaysSo() {
        CountingStorage storage = new CountingStorage();
        storage.saveBooster(IslandBooster.create(ISLAND, BoosterCategory.MOB_EXP, 2.0, Duration.ofHours(1), NOW));
        IslandBoosterService service = serviceOver(storage);

        service.followOccupancy(ISLAND, () -> 0, NOW);

        assertThat(service.overview(ISLAND, NOW).paused())
                .describedAs("an island nobody is on")
                .isTrue();
    }

    @Test
    @DisplayName("An island with no boosters at all is one read and nothing to show")
    void anIslandWithNothingIsStillOneRead() {
        CountingStorage storage = new CountingStorage();
        IslandBoosterService service = serviceOver(storage);

        IslandBoosterService.BoosterOverview overview = service.overview(ISLAND, NOW);

        assertThat(storage.reads.get()).isEqualTo(1);
        assertThat(overview.active()).isEmpty();
        assertThat(overview.multiplierOf(BoosterCategory.MOB_EXP)).isEqualTo(1.0);
    }
}
