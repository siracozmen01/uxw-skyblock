package com.uxplima.uxmskyblock.core.domain.network;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Operational health metrics for a cluster server node used in placement decisions.
 */
public record NodeHealth(
        ServerNodeId nodeId, boolean active, int currentHostedCount, int maxCapacity, double averageMspt) {

    public NodeHealth {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
    }

    public double loadFactor() {
        return maxCapacity > 0 ? (double) currentHostedCount / (double) maxCapacity : 1.0;
    }
}
