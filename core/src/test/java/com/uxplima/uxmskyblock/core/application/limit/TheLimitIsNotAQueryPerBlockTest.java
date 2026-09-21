package com.uxplima.uxmskyblock.core.application.limit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.limit.LimitQuota;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asking what an island's limit is does not go to the database every time.
 *
 * <p>{@code getEffectiveLimit} is asked on every block a player places, and it used to ask the
 * storage port for the upgrade tier each time. That is a query per placement on the thread running
 * the game for everybody in the region, and a player building runs several placements a second.
 */
class TheLimitIsNotAQueryPerBlockTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final UpgradeId HOPPERS = UpgradeId.of("hopper_limit");

    /** A storage port that counts how many times it was asked. */
    private static final class CountingUpgradeStorage implements IslandUpgradeStoragePort {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public int getUpgradeTier(IslandId islandId, UpgradeId upgradeId) {
            reads.incrementAndGet();
            return 2;
        }

        @Override
        public void setUpgradeTier(IslandId islandId, UpgradeId upgradeId, int tier) {}

        @Override
        public boolean compareAndSetUpgradeTier(IslandId islandId, UpgradeId upgradeId, int expectedTier, int newTier) {
            return false;
        }

        @Override
        public Map<UpgradeId, Integer> getUpgrades(IslandId islandId) {
            reads.incrementAndGet();
            return Map.of(HOPPERS, 2);
        }
    }

    private static Map<LimitType, LimitQuota> quotas() {
        Map<LimitType, LimitQuota> quotas = new EnumMap<>(LimitType.class);
        quotas.put(LimitType.HOPPER, new LimitQuota(10, HOPPERS, 5));
        return quotas;
    }

    @Test
    @DisplayName("A thousand placements on one island are not a thousand queries")
    void athousandPlacementsAreNotAThousandQueries() {
        CountingUpgradeStorage storage = new CountingUpgradeStorage();
        IslandUpgradeService upgrades = new IslandUpgradeService(storage, Map.of());
        IslandLimitService limits = new IslandLimitService(upgrades::getCurrentTier, quotas());

        for (int block = 0; block < 1000; block++) {
            assertThat(limits.getEffectiveLimit(ISLAND, LimitType.HOPPER)).isEqualTo(10 + 2 * 5);
        }

        assertThat(storage.reads)
                .describedAs("the tier is read once for the island, not once for every block placed")
                .hasValue(1);
    }

    @Test
    @DisplayName("The answer is the same one the uncached shape gave, so nothing was traded for speed")
    void theAnswerIsUnchanged() {
        CountingUpgradeStorage direct = new CountingUpgradeStorage();
        IslandLimitService uncached = new IslandLimitService(direct, quotas());
        IslandLimitService cached = new IslandLimitService(
                new IslandUpgradeService(new CountingUpgradeStorage(), Map.of())::getCurrentTier, quotas());

        assertThat(cached.getEffectiveLimit(ISLAND, LimitType.HOPPER))
                .isEqualTo(uncached.getEffectiveLimit(ISLAND, LimitType.HOPPER));
    }

    @Test
    @DisplayName("A type with no quota is unlimited without asking anybody")
    void anUnquotedTypeAsksNobody() {
        CountingUpgradeStorage storage = new CountingUpgradeStorage();
        IslandLimitService limits =
                new IslandLimitService(new IslandUpgradeService(storage, Map.of())::getCurrentTier, quotas());

        assertThat(limits.getEffectiveLimit(ISLAND, LimitType.SPAWNER)).isEqualTo(Integer.MAX_VALUE);
        assertThat(storage.reads).hasValue(0);
    }

    @Test
    @DisplayName("Two islands are two answers, because a cache that mixed them would be worse than none")
    void twoIslandsAreTwoAnswers() {
        CountingUpgradeStorage storage = new CountingUpgradeStorage();
        IslandLimitService limits =
                new IslandLimitService(new IslandUpgradeService(storage, Map.of())::getCurrentTier, quotas());
        IslandId other = IslandId.of(UUID.randomUUID());

        limits.getEffectiveLimit(ISLAND, LimitType.HOPPER);
        limits.getEffectiveLimit(other, LimitType.HOPPER);
        limits.getEffectiveLimit(ISLAND, LimitType.HOPPER);
        limits.getEffectiveLimit(other, LimitType.HOPPER);

        assertThat(storage.reads)
                .describedAs("one read per island, not one per island per ask")
                .hasValue(2);
    }
}
