package com.uxplima.uxmskyblock.persistence.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Placing a block must not be a database query.
 *
 * <p>A block limit is the base quota plus the tier of the upgrade that raises it, and the limit is
 * checked on every placement. Reading the tier through to the database meant a query per block
 * placed, on the event thread.
 */
class CachingIslandUpgradeStorageTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final UpgradeId UPGRADE = UpgradeId.of("block_limit");
    private static final Instant START = Instant.parse("2026-09-21T12:00:00Z");

    private static CachingIslandUpgradeStorage caching(IslandUpgradeStoragePort delegate, Duration ttl) {
        return new CachingIslandUpgradeStorage(delegate, ttl, Clock.fixed(START, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("A thousand block placements cost one query")
    void repeatedReadsCostOneQuery() {
        IslandUpgradeStoragePort delegate = mock(IslandUpgradeStoragePort.class);
        when(delegate.getUpgradeTier(ISLAND, UPGRADE)).thenReturn(3);
        CachingIslandUpgradeStorage storage = caching(delegate, Duration.ofMinutes(1));

        for (int i = 0; i < 1000; i++) {
            assertThat(storage.getUpgradeTier(ISLAND, UPGRADE)).isEqualTo(3);
        }

        verify(delegate, times(1)).getUpgradeTier(ISLAND, UPGRADE);
    }

    @Test
    @DisplayName("A purchase on this node is visible at once")
    void aWonPurchaseIsVisibleAtOnce() {
        IslandUpgradeStoragePort delegate = mock(IslandUpgradeStoragePort.class);
        when(delegate.getUpgradeTier(ISLAND, UPGRADE)).thenReturn(3);
        when(delegate.compareAndSetUpgradeTier(ISLAND, UPGRADE, 3, 4)).thenReturn(true);
        CachingIslandUpgradeStorage storage = caching(delegate, Duration.ofMinutes(1));
        assertThat(storage.getUpgradeTier(ISLAND, UPGRADE)).isEqualTo(3);

        assertThat(storage.compareAndSetUpgradeTier(ISLAND, UPGRADE, 3, 4)).isTrue();

        assertThat(storage.getUpgradeTier(ISLAND, UPGRADE)).isEqualTo(4);
        verify(delegate, times(1)).getUpgradeTier(ISLAND, UPGRADE);
    }

    @Test
    @DisplayName("A purchase somebody else won forgets what was remembered rather than guessing")
    void aLostPurchaseForgetsTheAnswer() {
        IslandUpgradeStoragePort delegate = mock(IslandUpgradeStoragePort.class);
        when(delegate.getUpgradeTier(ISLAND, UPGRADE)).thenReturn(3);
        when(delegate.compareAndSetUpgradeTier(ISLAND, UPGRADE, 3, 4)).thenReturn(false);
        CachingIslandUpgradeStorage storage = caching(delegate, Duration.ofMinutes(1));
        storage.getUpgradeTier(ISLAND, UPGRADE);

        assertThat(storage.compareAndSetUpgradeTier(ISLAND, UPGRADE, 3, 4)).isFalse();
        storage.getUpgradeTier(ISLAND, UPGRADE);

        verify(delegate, times(2)).getUpgradeTier(ISLAND, UPGRADE);
    }

    @Test
    @DisplayName("Opening the upgrade menu answers every later limit check with the same query")
    void readingAllSeedsTheIndividualAnswers() {
        IslandUpgradeStoragePort delegate = mock(IslandUpgradeStoragePort.class);
        when(delegate.getUpgrades(ISLAND)).thenReturn(Map.of(UPGRADE, 7));
        CachingIslandUpgradeStorage storage = caching(delegate, Duration.ofMinutes(1));

        storage.getUpgrades(ISLAND);

        assertThat(storage.getUpgradeTier(ISLAND, UPGRADE)).isEqualTo(7);
        verify(delegate, times(0)).getUpgradeTier(ISLAND, UPGRADE);
    }

    @Test
    @DisplayName("An expiry of zero reads through every time, for an operator who wants no cache")
    void aZeroExpiryReadsThrough() {
        IslandUpgradeStoragePort delegate = mock(IslandUpgradeStoragePort.class);
        when(delegate.getUpgradeTier(ISLAND, UPGRADE)).thenReturn(3);
        CachingIslandUpgradeStorage storage = caching(delegate, Duration.ZERO);

        storage.getUpgradeTier(ISLAND, UPGRADE);
        storage.getUpgradeTier(ISLAND, UPGRADE);

        verify(delegate, times(2)).getUpgradeTier(ISLAND, UPGRADE);
    }
}
