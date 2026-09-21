package com.uxplima.uxmskyblock.core.application.limit;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.limit.IslandLimitCheckResult;
import com.uxplima.uxmskyblock.core.domain.limit.LimitQuota;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * Pure domain application service tracking and enforcing tile and entity placement caps
 * per island with dynamic upgrade scaling (Section 2.31).
 */
public final class IslandLimitService {

    /**
     * Which tier of an upgrade an island holds.
     *
     * <p>This used to be the storage port itself, and {@link #getEffectiveLimit} is asked on every
     * block a player places. That is a query per placement on the thread running the game for
     * everybody in the region, and a player building runs several placements a second. The upgrade
     * service answers the same question through its own cache, and the cache is warmed off the hot
     * path rather than filled by the first block somebody puts down.
     */
    private final TierLookup tierLookup;

    private final Map<LimitType, LimitQuota> quotas;
    private final ConcurrentMap<IslandId, ConcurrentMap<LimitType, AtomicInteger>> islandCounts =
            new ConcurrentHashMap<>();

    /** Which tier of one upgrade an island holds. */
    @FunctionalInterface
    public interface TierLookup {
        int tierOf(IslandId islandId, UpgradeId upgradeId);
    }

    public IslandLimitService(TierLookup tierLookup, Map<LimitType, LimitQuota> quotas) {
        this.tierLookup = Objects.requireNonNull(tierLookup, "tierLookup must not be null");
        Objects.requireNonNull(quotas, "quotas must not be null");
        this.quotas = quotas.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(quotas));
    }

    /**
     * Asks the storage port directly, which is one query per block placed.
     *
     * <p>Here for a caller that has no upgrade service to ask, and for the tests that had one
     * before this took a lookup. Production passes the cached one.
     */
    public IslandLimitService(IslandUpgradeStoragePort upgradeStoragePort, Map<LimitType, LimitQuota> quotas) {
        this(Objects.requireNonNull(upgradeStoragePort, "upgradeStoragePort must not be null")::getUpgradeTier, quotas);
    }

    /**
     * Computes effective limit for the given island and type based on base quota and upgrade tiers.
     */
    public int getEffectiveLimit(IslandId islandId, LimitType type) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(type, "type must not be null");

        LimitQuota quota = quotas.get(type);
        if (quota == null) {
            return Integer.MAX_VALUE;
        }

        int tier = 0;
        if (quota.upgradeId() != null) {
            tier = tierLookup.tierOf(islandId, quota.upgradeId());
        }

        return quota.baseLimit() + (tier * quota.perTierBonus());
    }

    /**
     * Gets current tracked count for the given island and type.
     */
    public int getCount(IslandId islandId, LimitType type) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(type, "type must not be null");

        ConcurrentMap<LimitType, AtomicInteger> counts = islandCounts.get(islandId);
        if (counts == null) {
            return 0;
        }
        AtomicInteger counter = counts.get(type);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Checks if a placement or spawn is permitted without modifying state.
     */
    public IslandLimitCheckResult checkPlacement(IslandId islandId, LimitType type, boolean hasBypassPermission) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(type, "type must not be null");

        if (hasBypassPermission) {
            return new IslandLimitCheckResult.Bypassed(type);
        }

        int limit = getEffectiveLimit(islandId, type);
        int current = getCount(islandId, type);

        if (current >= limit) {
            return new IslandLimitCheckResult.LimitReached(type, current, limit);
        }

        return new IslandLimitCheckResult.Allowed(current, limit);
    }

    /**
     * Atomically increments the counter if below limit or if bypassed.
     *
     * @return true if successfully incremented, false if limit reached
     */
    public boolean tryIncrement(IslandId islandId, LimitType type, boolean hasBypassPermission) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(type, "type must not be null");

        AtomicInteger counter = getCounter(islandId, type);

        if (hasBypassPermission) {
            counter.incrementAndGet();
            return true;
        }

        int limit = getEffectiveLimit(islandId, type);
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Atomically decrements the counter down to 0.
     */
    public void decrement(IslandId islandId, LimitType type) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(type, "type must not be null");

        ConcurrentMap<LimitType, AtomicInteger> counts = islandCounts.get(islandId);
        if (counts == null) {
            return;
        }
        AtomicInteger counter = counts.get(type);
        if (counter != null) {
            counter.updateAndGet(val -> Math.max(0, val - 1));
        }
    }

    /**
     * Sets exact count for reconciliation scans.
     */
    public void setCount(IslandId islandId, LimitType type, int count) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        getCounter(islandId, type).set(Math.max(0, count));
    }

    /**
     * Clears all tracked counts for an island upon deletion/reset.
     */
    public void clearIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        islandCounts.remove(islandId);
    }

    /**
     * Gets immutable snapshot of all current counts for an island.
     */
    public Map<LimitType, Integer> getCounts(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Map<LimitType, Integer> result = new EnumMap<>(LimitType.class);
        ConcurrentMap<LimitType, AtomicInteger> counts = islandCounts.get(islandId);
        for (LimitType type : LimitType.values()) {
            int val = (counts != null && counts.get(type) != null)
                    ? counts.get(type).get()
                    : 0;
            result.put(type, val);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Gets immutable snapshot of effective limits for all configured types.
     */
    public Map<LimitType, Integer> getLimits(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Map<LimitType, Integer> result = new EnumMap<>(LimitType.class);
        for (LimitType type : quotas.keySet()) {
            result.put(type, getEffectiveLimit(islandId, type));
        }
        return Collections.unmodifiableMap(result);
    }

    private AtomicInteger getCounter(IslandId islandId, LimitType type) {
        return islandCounts
                .computeIfAbsent(islandId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, k -> new AtomicInteger(0));
    }
}
