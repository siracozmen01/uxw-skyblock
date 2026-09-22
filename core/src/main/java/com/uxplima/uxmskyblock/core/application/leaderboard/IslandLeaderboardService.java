package com.uxplima.uxmskyblock.core.application.leaderboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;

/**
 * The leaderboard, answered from memory rather than from a sort across every island.
 *
 * <p>It was answered from memory for ever. The rankings were read the first time anybody asked and
 * nothing ever read them again: {@code refresh} and {@code refreshAll} had no caller anywhere, so
 * {@code /is top} and the web endpoint showed whichever board the first player to ask happened to
 * see, for as long as the process ran. A player who climbed to first never appeared on it.
 *
 * <p>A board is worth holding, because building one is a sort across every island and the two
 * callers are a command and an HTTP endpoint that anybody may hammer. It is worth holding for a
 * while rather than for ever, so it is held for a window the operator sets.
 */
public final class IslandLeaderboardService {

    /** How long a board is worth answering from memory before it is built again. */
    public static final Duration DEFAULT_FRESHNESS = Duration.ofSeconds(60);

    private record Board(List<LeaderboardEntry> entries, Instant readAt) {}

    private final IslandLeaderboardPort leaderboardPort;
    private final int defaultCacheCapacity;
    private final Duration freshness;
    private final Clock clock;
    private final Map<LeaderboardCategory, Board> cachedRankings = new ConcurrentHashMap<>();

    public IslandLeaderboardService(IslandLeaderboardPort leaderboardPort, int defaultCacheCapacity) {
        this(leaderboardPort, defaultCacheCapacity, DEFAULT_FRESHNESS, Clock.systemUTC());
    }

    public IslandLeaderboardService(IslandLeaderboardPort leaderboardPort) {
        this(leaderboardPort, 100);
    }

    /** The canonical constructor, carrying how long a board may be answered from memory. */
    public IslandLeaderboardService(
            IslandLeaderboardPort leaderboardPort, int defaultCacheCapacity, Duration freshness, Clock clock) {
        this.leaderboardPort = Objects.requireNonNull(leaderboardPort, "leaderboardPort");
        this.defaultCacheCapacity = Math.max(10, defaultCacheCapacity);
        this.freshness = Objects.requireNonNull(freshness, "freshness must not be null");
        if (freshness.isNegative()) {
            throw new IllegalArgumentException("freshness must not be negative: " + freshness);
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Retrieves the top N ranked islands for the given category from cache,
     * refreshing on-demand if uninitialized.
     *
     * @param category leaderboard category
     * @param limit maximum entries
     * @return unmodifiable list of top entries
     */
    public List<LeaderboardEntry> getTop(LeaderboardCategory category, int limit) {
        Objects.requireNonNull(category, "category");
        if (limit <= 0) {
            return List.of();
        }

        List<LeaderboardEntry> entries = current(category);

        if (entries.isEmpty() || limit >= entries.size()) {
            return Collections.unmodifiableList(entries);
        }
        return Collections.unmodifiableList(entries.subList(0, limit));
    }

    /**
     * Looks up the rank of an island in the given category.
     *
     * @param category leaderboard category
     * @param islandId target island ID
     * @return optional containing 1-based rank if present in cached top rankings
     */
    public OptionalInt getRank(LeaderboardCategory category, IslandId islandId) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(islandId, "islandId");

        List<LeaderboardEntry> entries = getTop(category, defaultCacheCapacity);
        for (LeaderboardEntry entry : entries) {
            if (entry.islandId().equals(islandId)) {
                return OptionalInt.of(entry.rank());
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Forces an immediate reload of the specified leaderboard category from storage.
     *
     * @param category leaderboard category to refresh
     */
    public void refresh(LeaderboardCategory category) {
        Objects.requireNonNull(category, "category");
        List<LeaderboardEntry> freshlyFetched = leaderboardPort.fetchTopIslands(category, defaultCacheCapacity);
        cachedRankings.put(category, new Board(freshlyFetched, clock.instant()));
    }

    /**
     * The board as it stands, rebuilt when the one in hand has run out of time.
     *
     * <p>Good strictly before it runs out, so a window of nothing really does build it every time,
     * which is what an operator who writes zero is asking for.
     */
    private List<LeaderboardEntry> current(LeaderboardCategory category) {
        Instant now = clock.instant();
        Board held = cachedRankings.get(category);
        if (held != null && held.readAt().plus(freshness).isAfter(now)) {
            return held.entries();
        }
        List<LeaderboardEntry> fresh = leaderboardPort.fetchTopIslands(category, defaultCacheCapacity);
        cachedRankings.put(category, new Board(fresh, now));
        return fresh;
    }

    /** How many boards this node is holding, for a caller that wants to say so. */
    public int boardsHeld() {
        return cachedRankings.size();
    }

    /**
     * Forces an immediate reload of all leaderboard categories from storage.
     */
    public void refreshAll() {
        for (LeaderboardCategory category : LeaderboardCategory.values()) {
            refresh(category);
        }
    }

    /**
     * Updates an island's level score and net worth in persistent storage and refreshes caches.
     *
     * @param islandId target island ID
     * @param levelScore calculated level score
     * @param netWorthMinorUnits calculated net worth in minor units
     */
    public void recordIslandScore(IslandId islandId, long levelScore, long netWorthMinorUnits) {
        Objects.requireNonNull(islandId, "islandId");
        leaderboardPort.updateIslandScore(islandId, levelScore, netWorthMinorUnits);
        refresh(LeaderboardCategory.LEVEL);
        refresh(LeaderboardCategory.WORTH);
    }
}
