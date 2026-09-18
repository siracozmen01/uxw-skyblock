package com.uxplima.uxmskyblock.core.domain.season;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Immutable snapshot record of an island's final placement in a season metric.
 */
public record SeasonSnapshotEntry(
        SeasonId seasonId,
        SeasonMetric metric,
        int rank,
        IslandId islandId,
        PlayerUuid ownerUuid,
        long score,
        Instant snapshotTimestamp) {

    public SeasonSnapshotEntry {
        Objects.requireNonNull(seasonId, "seasonId cannot be null");
        Objects.requireNonNull(metric, "metric cannot be null");
        Objects.requireNonNull(islandId, "islandId cannot be null");
        Objects.requireNonNull(ownerUuid, "ownerUuid cannot be null");
        Objects.requireNonNull(snapshotTimestamp, "snapshotTimestamp cannot be null");
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive: " + rank);
        }
    }
}
