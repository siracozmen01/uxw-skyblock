package com.uxplima.uxmskyblock.core.application.network;

import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Outbound port for proxy routing to seamlessly dispatch players across cluster nodes via Velocity.
 */
public interface VelocityBridgePort {

    /**
     * Dispatches a player routing request to the target server node.
     *
     * @param playerUuid the target player UUID
     * @param targetNode the destination server node
     * @param targetIslandId the island ID being visited
     * @return CompletableFuture completing with true if dispatch succeeded, or false if failed
     */
    CompletableFuture<Boolean> routePlayer(
            PlayerUuid playerUuid, ServerNodeId targetNode, IslandId targetIslandId);
}
