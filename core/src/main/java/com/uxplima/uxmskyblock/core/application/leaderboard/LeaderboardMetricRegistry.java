package com.uxplima.uxmskyblock.core.application.leaderboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.api.NamespacedId;
import com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider;
import com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetrics;
import com.uxplima.uxmskyblock.api.leaderboard.MetricReading;
import com.uxplima.uxmskyblock.api.leaderboard.RankedReading;
import com.uxplima.uxmskyblock.api.leaderboard.SortDirection;

/**
 * Every metric the plugin ranks, by id.
 *
 * <p>The registry sorts and numbers; it never stores a value. Each board is read from its owner
 * when it is asked for, so the owner stays the source of truth for its own metric.
 */
public final class LeaderboardMetricRegistry implements LeaderboardMetrics {

    private static final Logger LOGGER = Logger.getLogger(LeaderboardMetricRegistry.class.getName());

    private final Map<NamespacedId, LeaderboardMetricProvider> providers = new LinkedHashMap<>();

    @Override
    public synchronized void register(LeaderboardMetricProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        NamespacedId id = Objects.requireNonNull(provider.metricId(), "metricId must not be null");
        String raw = id.asString();
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon == raw.length() - 1 || !raw.equals(raw.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "A metric id is a lowercase namespace and key, such as season:score, not " + raw);
        }
        if (providers.putIfAbsent(id, provider) != null) {
            throw new IllegalArgumentException("The metric " + raw + " is already registered");
        }
    }

    @Override
    public synchronized Optional<LeaderboardMetricProvider> provider(NamespacedId metricId) {
        Objects.requireNonNull(metricId, "metricId must not be null");
        return Optional.ofNullable(providers.get(metricId));
    }

    @Override
    public synchronized Set<NamespacedId> metrics() {
        return Set.copyOf(providers.keySet());
    }

    @Override
    public List<RankedReading> ranked(NamespacedId metricId, int limit) {
        Objects.requireNonNull(metricId, "metricId must not be null");
        Optional<LeaderboardMetricProvider> found = provider(metricId);
        if (found.isEmpty() || limit <= 0) {
            return List.of();
        }
        LeaderboardMetricProvider provider = found.get();
        List<MetricReading> read;
        try {
            read = new ArrayList<>(provider.read(limit));
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    e,
                    () -> "The owner of the metric " + metricId.asString() + " could not be read. Its board is empty.");
            return List.of();
        }
        Comparator<MetricReading> byValue = Comparator.comparingLong(MetricReading::value);
        if (provider.sortDirection() == SortDirection.HIGHEST_FIRST) {
            byValue = byValue.reversed();
        }
        read.sort(byValue.thenComparing(MetricReading::rootId));
        List<RankedReading> board = new ArrayList<>(Math.min(limit, read.size()));
        for (MetricReading reading : read.subList(0, Math.min(limit, read.size()))) {
            board.add(new RankedReading(board.size() + 1, reading, provider.format(reading.value())));
        }
        return List.copyOf(board);
    }
}
