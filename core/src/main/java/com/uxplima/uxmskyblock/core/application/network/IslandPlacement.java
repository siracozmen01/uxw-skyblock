package com.uxplima.uxmskyblock.core.application.network;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.network.NodeHealth;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Which node should take an island whose lease has run out.
 *
 * <p>A recommendation only. It reads which nodes serving the island's world are alive and hands them
 * to the operator's strategy. It takes no lease and moves no epoch: the node a visitor is sent to
 * takes the island through the lease protocol, like any other.
 */
public final class IslandPlacement {

    private static final Logger LOGGER = Logger.getLogger(IslandPlacement.class.getName());

    private final PlacementStrategies strategies;
    private final String strategyName;
    private final ClusterNodesPort nodes;
    private final Function<IslandId, Optional<String>> worldOf;
    private final Function<IslandId, Optional<PrimaryGameplayRootRef>> rootOf;
    private final Duration liveness;
    private final AtomicBoolean warned = new AtomicBoolean();

    /**
     * @param strategyName the strategy the operator named; an unknown name falls back to least loaded
     * @param worldOf the world an island lies in
     * @param rootOf the gameplay root an island is, which the strategy is handed
     * @param liveness how recently a node must have been heard from to be offered
     */
    public IslandPlacement(
            PlacementStrategies strategies,
            String strategyName,
            ClusterNodesPort nodes,
            Function<IslandId, Optional<String>> worldOf,
            Function<IslandId, Optional<PrimaryGameplayRootRef>> rootOf,
            Duration liveness) {
        this.strategies = Objects.requireNonNull(strategies, "strategies must not be null");
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName must not be null");
        this.nodes = Objects.requireNonNull(nodes, "nodes must not be null");
        this.worldOf = Objects.requireNonNull(worldOf, "worldOf must not be null");
        this.rootOf = Objects.requireNonNull(rootOf, "rootOf must not be null");
        this.liveness = Objects.requireNonNull(liveness, "liveness must not be null");
    }

    /** The node the strategy recommends for the island, or empty when no live node serves its world. */
    public Optional<ServerNodeId> recommend(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Optional<String> world = worldOf.apply(islandId);
        Optional<PrimaryGameplayRootRef> root = rootOf.apply(islandId);
        if (world.isEmpty() || root.isEmpty()) {
            return Optional.empty();
        }
        List<NodeHealth> alive = nodes.serving(world.get(), liveness);
        if (alive.isEmpty()) {
            return Optional.empty();
        }
        return strategy().selectNode(root.get(), alive);
    }

    private PlacementStrategy strategy() {
        return strategies.named(strategyName).orElseGet(() -> {
            if (warned.compareAndSet(false, true)) {
                LOGGER.warning(() -> "server-node.placement.strategy names '" + strategyName
                        + "', which no plugin registered. Placing by " + PlacementStrategies.LEAST_LOADED + ". Known: "
                        + strategies.names());
            }
            return PlacementStrategy.leastLoaded();
        });
    }
}
