package com.uxplima.uxmskyblock.core.application.network;

import java.time.Duration;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Outbound port for caching and querying the cluster-wide island routing directory.
 * Typically backed by Redis with local fail-closed degradation.
 */
public interface ClusterRoutingDirectoryPort {

    /**
     * Looks up the cached authoritative node for the given island.
     *
     * @param islandId target island ID
     * @return optional containing the target node if cached
     */
    Optional<ServerNodeId> findAuthoritativeNode(IslandId islandId);

    /**
     * Caches the authoritative node mapping for an island.
     *
     * @param islandId target island ID
     * @param nodeId authoritative server node ID
     * @param epoch authority fencing epoch
     * @param ttl cache time-to-live
     */
    void cacheRoute(IslandId islandId, ServerNodeId nodeId, long epoch, Duration ttl);

    /**
     * Evicts or invalidates the cached routing directory entry for an island.
     *
     * @param islandId target island ID
     */
    void invalidateRoute(IslandId islandId);

    /**
     * Whether the cluster routing directory is currently reachable.
     */
    default boolean isAvailable() {
        return true;
    }
}
