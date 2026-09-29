package com.uxplima.uxmskyblock.core.application.network;

import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep;
import com.uxplima.uxmskyblock.core.domain.network.NodeHealth;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * This node telling the cluster it is alive, on every authority heartbeat.
 *
 * <p>A placement strategy chose among nodes it was handed, and nothing ever handed it one. The
 * heartbeat already knows how many islands this node holds, so each beat publishes that, the node's
 * capacity and its tick time.
 */
public final class ClusterPresence {

    private static final Logger LOGGER = Logger.getLogger(ClusterPresence.class.getName());

    private final ClusterNodesPort nodes;
    private final ServerNodeId nodeId;
    private final String worldName;
    private final int capacity;
    private final DoubleSupplier averageMspt;

    public ClusterPresence(
            ClusterNodesPort nodes, ServerNodeId nodeId, String worldName, int capacity, DoubleSupplier averageMspt) {
        this.nodes = Objects.requireNonNull(nodes, "nodes must not be null");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.averageMspt = Objects.requireNonNull(averageMspt, "averageMspt must not be null");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
    }

    /** Publishes this node's health after a heartbeat pass. A failure is logged and the next beat tries again. */
    public void beat(IslandAuthoritySweep swept) {
        Objects.requireNonNull(swept, "swept must not be null");
        try {
            int hosted = swept.renewed() + swept.takenOver() + swept.acquired();
            nodes.publish(new NodeHealth(nodeId, true, hosted, capacity, averageMspt.getAsDouble()), worldName);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Publishing this node's health failed. The next beat tries again.");
        }
    }
}
