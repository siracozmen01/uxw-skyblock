package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.network.ClusterNodesPort;
import com.uxplima.uxmskyblock.core.application.network.ClusterPresence;
import com.uxplima.uxmskyblock.core.application.network.IslandPlacement;
import com.uxplima.uxmskyblock.core.application.network.PlacementStrategies;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * This node's part in placing islands: it says on every heartbeat that it is alive, and it asks the
 * operator's strategy where to send a visitor whose island's node has stopped.
 */
final class ClusterPlacementWiring {

    private static final Logger LOGGER = Logger.getLogger(ClusterPlacementWiring.class.getName());

    private final PlacementStrategies strategies;
    private final ClusterPresence presence;
    private final IslandPlacement placement;

    ClusterPlacementWiring(
            ServerNodeConfiguration node,
            ClusterNodesPort nodes,
            IslandStoragePort islands,
            GameModeHierarchyService hierarchy,
            DoubleSupplier averageMspt) {
        Objects.requireNonNull(node, "node must not be null");
        ServerNodeConfiguration.Placement settings = node.placement();
        this.strategies = PlacementStrategies.shipped(settings.msptCeiling());
        if (strategies.named(settings.strategy()).isEmpty()) {
            LOGGER.info(() -> "server-node.placement.strategy names '" + settings.strategy()
                    + "', which is not shipped. Another plugin has to register it, or least-loaded is used.");
        }
        this.presence = new ClusterPresence(nodes, node.nodeId(), node.worldName(), settings.capacity(), averageMspt);
        // A node that missed one beat is still alive; one that missed two has stopped.
        Duration liveness = node.authorityHeartbeatInterval().multipliedBy(2);
        this.placement = new IslandPlacement(
                strategies,
                settings.strategy(),
                nodes,
                islandId -> islands.findLocationByIslandId(islandId).map(IslandLocation::worldName),
                islandId -> islands.findIslandById(islandId)
                        .flatMap(island -> hierarchy.rootOfIsland(islandId, island.ownerProfileId())),
                liveness);
    }

    /** The strategies a placement may name. Another plugin adds its own here. */
    PlacementStrategies strategies() {
        return strategies;
    }

    ClusterPresence presence() {
        return presence;
    }

    IslandPlacement placement() {
        return placement;
    }
}
