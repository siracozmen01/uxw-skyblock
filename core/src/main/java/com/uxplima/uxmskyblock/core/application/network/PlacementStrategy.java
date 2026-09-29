package com.uxplima.uxmskyblock.core.application.network;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.network.NodeHealth;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Heuristic placement strategy recommending candidate server nodes for newly allocated or unloaded gameplay roots.
 *
 * <p><b>Strict Invariant:</b> A placement recommendation NEVER acquires an authority lease or advances
 * the {@code authority_epoch}. Authority is granted exclusively via the distributed SQL lease protocol.
 */
@FunctionalInterface
public interface PlacementStrategy {

    Optional<ServerNodeId> selectNode(PrimaryGameplayRootRef rootRef, List<NodeHealth> candidateNodes);

    /**
     * Round-robin allocation cycling through available active nodes.
     */
    static PlacementStrategy roundRobin() {
        AtomicInteger counter = new AtomicInteger();
        return (rootRef, candidates) -> {
            List<NodeHealth> active =
                    candidates.stream().filter(NodeHealth::active).toList();
            if (active.isEmpty()) {
                return Optional.empty();
            }
            int idx = Math.abs(counter.getAndIncrement() % active.size());
            return Optional.of(active.get(idx).nodeId());
        };
    }

    /**
     * Allocates to the active node with the lowest current load factor.
     */
    static PlacementStrategy leastLoaded() {
        return (rootRef, candidates) -> candidates.stream()
                .filter(NodeHealth::active)
                .min(Comparator.comparingDouble(NodeHealth::loadFactor))
                .map(NodeHealth::nodeId);
    }

    /** The tick time above which {@link #msptAware()} avoids a node, when the operator names no number. */
    double DEFAULT_MSPT_CEILING = 45.0;

    /**
     * Allocates to the node with the lowest MSPT, avoiding nodes over {@link #DEFAULT_MSPT_CEILING}.
     */
    static PlacementStrategy msptAware() {
        return msptAware(DEFAULT_MSPT_CEILING);
    }

    /**
     * Allocates to the node with the lowest MSPT, avoiding nodes over {@code ceiling} milliseconds while
     * one under it is left.
     */
    static PlacementStrategy msptAware(double ceiling) {
        return (rootRef, candidates) -> candidates.stream()
                .filter(n -> n.active() && n.averageMspt() <= ceiling)
                .min(Comparator.comparingDouble(NodeHealth::averageMspt))
                .or(() -> candidates.stream()
                        .filter(NodeHealth::active)
                        .min(Comparator.comparingDouble(NodeHealth::averageMspt)))
                .map(NodeHealth::nodeId);
    }

    /**
     * Allocates to the node with the highest remaining capacity.
     */
    static PlacementStrategy capacityWeighted() {
        return (rootRef, candidates) -> candidates.stream()
                .filter(NodeHealth::active)
                .max(Comparator.comparingInt(n -> n.maxCapacity() - n.currentHostedCount()))
                .map(NodeHealth::nodeId);
    }
}
