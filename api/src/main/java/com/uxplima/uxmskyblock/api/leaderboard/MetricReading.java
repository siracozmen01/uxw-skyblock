package com.uxplima.uxmskyblock.api.leaderboard;

import java.util.Objects;

/**
 * One gameplay root's value on a metric, as the metric's owner reads it.
 *
 * @param rootId the root's id: an island's UUID, or whatever id the owner's root type uses
 * @param displayName what the board calls the root
 * @param value the value, whole and exact: a fraction is the owner's to scale, as money is in cents
 */
public record MetricReading(String rootId, String displayName, long value) {

    public MetricReading {
        Objects.requireNonNull(rootId, "rootId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
    }
}
