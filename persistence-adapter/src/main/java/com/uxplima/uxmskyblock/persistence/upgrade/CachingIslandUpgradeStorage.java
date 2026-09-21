package com.uxplima.uxmskyblock.persistence.upgrade;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * Remembers an island's upgrade tiers, so a block placement is not a database query.
 *
 * <p>A block limit is the base quota plus the tier of the upgrade that raises it, and the limit is
 * checked on every placement of a limited block. Reading the tier from the database each time meant
 * a query per block placed, on the event thread, which the standards call the worst defect in this
 * estate.
 *
 * <p>A tier is bought once and read constantly, so it is the right thing to remember. Every write
 * goes through this class, so a purchase on this node is visible on the next read with no delay at
 * all. The expiry only bounds how long it takes to notice a purchase made on another node.
 */
public final class CachingIslandUpgradeStorage implements IslandUpgradeStoragePort {

    private record Key(IslandId islandId, UpgradeId upgradeId) {}

    private record Entry(int tier, Instant readAt) {}

    private final IslandUpgradeStoragePort delegate;
    private final Duration ttl;
    private final Clock clock;
    private final Map<Key, Entry> tiers = new ConcurrentHashMap<>();

    public CachingIslandUpgradeStorage(IslandUpgradeStoragePort delegate, Duration ttl, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("The upgrade tier cache expiry must not be negative");
        }
    }

    @Override
    public int getUpgradeTier(IslandId islandId, UpgradeId upgradeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(upgradeId, "upgradeId must not be null");
        Key key = new Key(islandId, upgradeId);
        Instant now = clock.instant();
        Entry cached = tiers.get(key);
        if (cached != null && isFresh(cached, now)) {
            return cached.tier();
        }
        int tier = delegate.getUpgradeTier(islandId, upgradeId);
        tiers.put(key, new Entry(tier, now));
        return tier;
    }

    /**
     * Reads every upgrade, and seeds the per upgrade answers from the same query.
     *
     * <p>This is what a menu opens with. One query answers every later limit check for that island.
     */
    @Override
    public Map<UpgradeId, Integer> getUpgrades(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Map<UpgradeId, Integer> all = delegate.getUpgrades(islandId);
        Instant now = clock.instant();
        all.forEach((upgradeId, tier) -> tiers.put(new Key(islandId, upgradeId), new Entry(tier, now)));
        return all;
    }

    @Override
    public void setUpgradeTier(IslandId islandId, UpgradeId upgradeId, int tier) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(upgradeId, "upgradeId must not be null");
        delegate.setUpgradeTier(islandId, upgradeId, tier);
        tiers.put(new Key(islandId, upgradeId), new Entry(tier, clock.instant()));
    }

    @Override
    public boolean compareAndSetUpgradeTier(IslandId islandId, UpgradeId upgradeId, int expectedTier, int newTier) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(upgradeId, "upgradeId must not be null");
        boolean won = delegate.compareAndSetUpgradeTier(islandId, upgradeId, expectedTier, newTier);
        Key key = new Key(islandId, upgradeId);
        if (won) {
            tiers.put(key, new Entry(newTier, clock.instant()));
        } else {
            // Somebody else moved the tier, so whatever is remembered here is wrong. Forget it
            // rather than guess: the next read pays for one query and gets the truth.
            tiers.remove(key);
        }
        return won;
    }

    /** An expiry of zero is an operator saying they want no cache at all, so nothing is ever fresh. */
    private boolean isFresh(Entry cached, Instant now) {
        return !ttl.isZero() && !cached.readAt().plus(ttl).isBefore(now);
    }

    /** Forgets everything remembered about an island, for a reset or a delete. */
    public void invalidate(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        tiers.keySet().removeIf(key -> key.islandId().equals(islandId));
    }
}
