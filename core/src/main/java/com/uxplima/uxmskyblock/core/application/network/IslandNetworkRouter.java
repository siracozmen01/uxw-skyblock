package com.uxplima.uxmskyblock.core.application.network;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Application service coordinating distributed island affinity, cross-server visits,
 * and fail-closed degradation as specified in Section 2.15.
 */
public final class IslandNetworkRouter {

    private static final Duration ROUTE_CACHE_TTL = Duration.ofSeconds(15);
    public static final String ERROR_CLUSTER_UNAVAILABLE = "error.network.cluster_unavailable";
    public static final String ERROR_ISLAND_NOT_FOUND = "error.network.island_not_found";

    private final ServerNodeId localNodeId;
    private final IslandAuthorityPort islandAuthorityPort;
    private final VelocityBridgePort velocityBridgePort;
    private final ClusterRoutingDirectoryPort clusterRoutingDirectoryPort;

    public IslandNetworkRouter(
            ServerNodeId localNodeId,
            IslandAuthorityPort islandAuthorityPort,
            VelocityBridgePort velocityBridgePort,
            ClusterRoutingDirectoryPort clusterRoutingDirectoryPort) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId must not be null");
        this.islandAuthorityPort = Objects.requireNonNull(islandAuthorityPort, "islandAuthorityPort must not be null");
        this.velocityBridgePort = Objects.requireNonNull(velocityBridgePort, "velocityBridgePort must not be null");
        this.clusterRoutingDirectoryPort =
                Objects.requireNonNull(clusterRoutingDirectoryPort, "clusterRoutingDirectoryPort must not be null");
    }

    /**
     * Resolves routing for an island visit. If the target island is hosted on the local node,
     * returns RouteOutcome.Local immediately. If on a remote node, routes via Velocity.
     * If cluster state is unavailable or routing fails, fails closed.
     */
    public CompletableFuture<RouteOutcome> routeVisit(PlayerUuid playerUuid, IslandId targetIslandId) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(targetIslandId, "targetIslandId must not be null");

        // 1. Check directory availability
        if (!clusterRoutingDirectoryPort.isAvailable()) {
            // Fail-closed degradation policy when cluster transport is severed
            // Check if local node holds valid unexpired lease
            Optional<IslandAuthorityRecord> localAuth = islandAuthorityPort.findAuthority(targetIslandId);
            if (localAuth.isPresent()
                    && localAuth.get().authoritativeNode().equals(localNodeId)
                    && !localAuth.get().isExpired(Instant.now())) {
                return CompletableFuture.completedFuture(new RouteOutcome.Local(targetIslandId));
            }
            return CompletableFuture.completedFuture(new RouteOutcome.Unavailable(ERROR_CLUSTER_UNAVAILABLE));
        }

        // 2. Query routing directory cache
        Optional<ServerNodeId> cachedNode = clusterRoutingDirectoryPort.findAuthoritativeNode(targetIslandId);
        if (cachedNode.isPresent()) {
            ServerNodeId targetNode = cachedNode.get();
            if (targetNode.equals(localNodeId)) {
                return CompletableFuture.completedFuture(new RouteOutcome.Local(targetIslandId));
            }
            return dispatchCrossServer(playerUuid, targetNode, targetIslandId);
        }

        // 3. Fallback to SQL authority lookup
        Optional<IslandAuthorityRecord> optAuthority = islandAuthorityPort.findAuthority(targetIslandId);
        if (optAuthority.isEmpty()) {
            return CompletableFuture.completedFuture(new RouteOutcome.Unavailable(ERROR_ISLAND_NOT_FOUND));
        }

        IslandAuthorityRecord authority = optAuthority.get();
        ServerNodeId targetNode = authority.authoritativeNode();
        // Update routing directory cache
        clusterRoutingDirectoryPort.cacheRoute(targetIslandId, targetNode, authority.authorityEpoch(), ROUTE_CACHE_TTL);

        if (targetNode.equals(localNodeId)) {
            return CompletableFuture.completedFuture(new RouteOutcome.Local(targetIslandId));
        }

        return dispatchCrossServer(playerUuid, targetNode, targetIslandId);
    }

    private CompletableFuture<RouteOutcome> dispatchCrossServer(
            PlayerUuid playerUuid, ServerNodeId targetNode, IslandId targetIslandId) {
        return velocityBridgePort
                .routePlayer(playerUuid, targetNode, targetIslandId)
                .thenApply(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        return (RouteOutcome) new RouteOutcome.CrossServer(targetNode, targetIslandId);
                    } else {
                        return (RouteOutcome) new RouteOutcome.Unavailable(ERROR_CLUSTER_UNAVAILABLE);
                    }
                })
                .exceptionally(ex -> new RouteOutcome.Unavailable(ERROR_CLUSTER_UNAVAILABLE));
    }
}
