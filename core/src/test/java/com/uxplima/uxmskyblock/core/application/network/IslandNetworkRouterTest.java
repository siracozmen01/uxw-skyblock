package com.uxplima.uxmskyblock.core.application.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandNetworkRouterTest {

    private final ServerNodeId localNode = ServerNodeId.of("skyblock-01");
    private final ServerNodeId remoteNode = ServerNodeId.of("skyblock-02");
    private final PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
    private final IslandId islandId = IslandId.of(UUID.randomUUID());

    private IslandAuthorityPort authorityPort;
    private VelocityBridgePort velocityPort;
    private ClusterRoutingDirectoryPort directoryPort;
    private IslandNetworkRouter router;

    @BeforeEach
    void setUp() {
        authorityPort = mock(IslandAuthorityPort.class);
        velocityPort = mock(VelocityBridgePort.class);
        directoryPort = mock(ClusterRoutingDirectoryPort.class);
        when(directoryPort.isAvailable()).thenReturn(true);

        router = new IslandNetworkRouter(localNode, authorityPort, velocityPort, directoryPort);
    }

    @Test
    @DisplayName("Local island in cache returns RouteOutcome.Local immediately")
    void localIslandInCache() {
        when(directoryPort.findAuthoritativeNode(islandId)).thenReturn(Optional.of(localNode));

        RouteOutcome outcome = router.routeVisit(playerUuid, islandId).join();

        assertThat(outcome).isInstanceOf(RouteOutcome.Local.class);
        assertThat(((RouteOutcome.Local) outcome).islandId()).isEqualTo(islandId);
    }

    @Test
    @DisplayName("Remote island in cache dispatches via VelocityBridge to remote node")
    void remoteIslandInCache() {
        when(directoryPort.findAuthoritativeNode(islandId)).thenReturn(Optional.of(remoteNode));
        when(velocityPort.routePlayer(playerUuid, remoteNode, islandId))
                .thenReturn(CompletableFuture.completedFuture(true));

        RouteOutcome outcome = router.routeVisit(playerUuid, islandId).join();

        assertThat(outcome).isInstanceOf(RouteOutcome.CrossServer.class);
        RouteOutcome.CrossServer cross = (RouteOutcome.CrossServer) outcome;
        assertThat(cross.targetNode()).isEqualTo(remoteNode);
        assertThat(cross.islandId()).isEqualTo(islandId);
    }

    @Test
    @DisplayName("Cache miss falls back to SQL authority, populates cache, and routes")
    void cacheMissFallbackToSql() {
        when(directoryPort.findAuthoritativeNode(islandId)).thenReturn(Optional.empty());
        IslandAuthorityRecord record = new IslandAuthorityRecord(
                islandId, remoteNode, 10L, Instant.now().plusSeconds(60), Instant.now());
        when(authorityPort.findAuthority(islandId)).thenReturn(Optional.of(record));
        when(velocityPort.routePlayer(playerUuid, remoteNode, islandId))
                .thenReturn(CompletableFuture.completedFuture(true));

        RouteOutcome outcome = router.routeVisit(playerUuid, islandId).join();

        assertThat(outcome).isInstanceOf(RouteOutcome.CrossServer.class);
        verify(directoryPort).cacheRoute(eq(islandId), eq(remoteNode), eq(10L), any(Duration.class));
    }

    @Test
    @DisplayName("Non-existent island authority returns Unavailable with island_not_found")
    void islandNotFound() {
        when(directoryPort.findAuthoritativeNode(islandId)).thenReturn(Optional.empty());
        when(authorityPort.findAuthority(islandId)).thenReturn(Optional.empty());

        RouteOutcome outcome = router.routeVisit(playerUuid, islandId).join();

        assertThat(outcome).isInstanceOf(RouteOutcome.Unavailable.class);
        assertThat(((RouteOutcome.Unavailable) outcome).reasonCode())
                .isEqualTo(IslandNetworkRouter.ERROR_ISLAND_NOT_FOUND);
    }

    @Test
    @DisplayName("When cluster directory is unavailable, local valid lease still succeeds locally")
    void clusterUnavailableLocalLeaseSucceeds() {
        when(directoryPort.isAvailable()).thenReturn(false);
        IslandAuthorityRecord record =
                new IslandAuthorityRecord(islandId, localNode, 5L, Instant.now().plusSeconds(60), Instant.now());
        when(authorityPort.findAuthority(islandId)).thenReturn(Optional.of(record));

        RouteOutcome outcome = router.routeVisit(playerUuid, islandId).join();

        assertThat(outcome).isInstanceOf(RouteOutcome.Local.class);
    }

    @Test
    @DisplayName("When cluster directory is unavailable, remote visit fails closed")
    void clusterUnavailableRemoteVisitFailsClosed() {
        when(directoryPort.isAvailable()).thenReturn(false);
        IslandAuthorityRecord record = new IslandAuthorityRecord(
                islandId, remoteNode, 5L, Instant.now().plusSeconds(60), Instant.now());
        when(authorityPort.findAuthority(islandId)).thenReturn(Optional.of(record));

        RouteOutcome outcome = router.routeVisit(playerUuid, islandId).join();

        assertThat(outcome).isInstanceOf(RouteOutcome.Unavailable.class);
        assertThat(((RouteOutcome.Unavailable) outcome).reasonCode())
                .isEqualTo(IslandNetworkRouter.ERROR_CLUSTER_UNAVAILABLE);
    }

    @Test
    @DisplayName("Velocity dispatch failure fails closed with cluster_unavailable")
    void velocityDispatchFailure() {
        when(directoryPort.findAuthoritativeNode(islandId)).thenReturn(Optional.of(remoteNode));
        when(velocityPort.routePlayer(playerUuid, remoteNode, islandId))
                .thenReturn(CompletableFuture.completedFuture(false));

        RouteOutcome outcome = router.routeVisit(playerUuid, islandId).join();

        assertThat(outcome).isInstanceOf(RouteOutcome.Unavailable.class);
        assertThat(((RouteOutcome.Unavailable) outcome).reasonCode())
                .isEqualTo(IslandNetworkRouter.ERROR_CLUSTER_UNAVAILABLE);
    }
}
