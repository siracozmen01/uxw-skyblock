package com.uxplima.uxmskyblock.core.application.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep;
import com.uxplima.uxmskyblock.core.domain.network.NodeHealth;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A placement recommends a node and takes nothing.
 *
 * <p>The game mode architecture names this test: a {@link PlacementStrategy} recommendation never
 * acquires an authority lease and never advances {@code authority_epoch}. A visitor to an island whose
 * node stopped is sent where the strategy says, and the lease stays exactly as the stopped node left
 * it until the node the visitor lands on takes it through the lease protocol.
 */
class PlacementDoesNotGrantAuthorityTest {

    private static final ServerNodeId LOCAL = ServerNodeId.of("node-local");
    private static final ServerNodeId STOPPED = ServerNodeId.of("node-stopped");
    private static final ServerNodeId BUSY = ServerNodeId.of("node-busy");
    private static final ServerNodeId QUIET = ServerNodeId.of("node-quiet");
    private static final String WORLD = "skyblock";
    private static final long EPOCH = 7;

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final PlayerUuid visitor = PlayerUuid.of(UUID.randomUUID());
    private final PrimaryGameplayRootRef root = PrimaryGameplayRootRef.forIsland(
            GameModeInstanceId.random(), islandId.value().toString(), Instant.now());

    private final WatchedAuthority authority = new WatchedAuthority();
    private final WatchedDirectory directory = new WatchedDirectory();
    private final List<ServerNodeId> sentTo = new ArrayList<>();
    private List<NodeHealth> alive =
            List.of(new NodeHealth(BUSY, true, 90, 100, 20.0), new NodeHealth(QUIET, true, 10, 100, 30.0));
    private int askedForNodes;

    @Test
    @DisplayName("A visitor to an island whose node stopped is sent where the strategy says, and no lease moves")
    void aLapsedIslandIsPlacedWithoutTakingIt() {
        authority.held = lapsed(STOPPED);

        RouteOutcome outcome = router(PlacementStrategies.LEAST_LOADED)
                .routeVisit(visitor, islandId)
                .join();

        assertThat(outcome).isEqualTo(new RouteOutcome.CrossServer(QUIET, islandId));
        assertThat(sentTo).containsExactly(QUIET);
        assertThat(authority.writes).isEmpty();
        assertThat(authority.held.authorityEpoch()).isEqualTo(EPOCH);
        assertThat(authority.held.authoritativeNode()).isEqualTo(STOPPED);
        assertThat(directory.cached)
                .describedAs("no route is cached under an epoch nobody holds")
                .isEmpty();
    }

    @Test
    @DisplayName("No shipped strategy takes a lease, whichever node it picks")
    void noShippedStrategyTakesALease() {
        authority.held = lapsed(STOPPED);

        for (String name : PlacementStrategies.shipped(PlacementStrategy.DEFAULT_MSPT_CEILING)
                .names()) {
            RouteOutcome outcome = router(name).routeVisit(visitor, islandId).join();

            assertThat(outcome).describedAs(name).isInstanceOf(RouteOutcome.CrossServer.class);
        }
        assertThat(authority.writes).isEmpty();
        assertThat(authority.held.authorityEpoch()).isEqualTo(EPOCH);
    }

    @Test
    @DisplayName("When the strategy picks this node the visitor stays here, and still nothing is taken")
    void placedHereStaysHere() {
        authority.held = lapsed(STOPPED);
        alive = List.of(new NodeHealth(LOCAL, true, 0, 100, 5.0), new NodeHealth(BUSY, true, 99, 100, 5.0));

        RouteOutcome outcome = router(PlacementStrategies.LEAST_LOADED)
                .routeVisit(visitor, islandId)
                .join();

        assertThat(outcome).isEqualTo(new RouteOutcome.Local(islandId));
        assertThat(sentTo).isEmpty();
        assertThat(authority.writes).isEmpty();
    }

    @Test
    @DisplayName("With no live node serving the world the visit is refused rather than sent to the stopped node")
    void noLiveNodeRefuses() {
        authority.held = lapsed(STOPPED);
        alive = List.of();

        RouteOutcome outcome = router(PlacementStrategies.LEAST_LOADED)
                .routeVisit(visitor, islandId)
                .join();

        assertThat(outcome).isEqualTo(new RouteOutcome.Unavailable(IslandNetworkRouter.ERROR_CLUSTER_UNAVAILABLE));
        assertThat(sentTo).isEmpty();
        assertThat(authority.writes).isEmpty();
    }

    @Test
    @DisplayName("A lease still running goes to its holder and the placement is never asked")
    void aRunningLeaseIsNotPlaced() {
        authority.held =
                new IslandAuthorityRecord(islandId, BUSY, EPOCH, Instant.now().plusSeconds(300), Instant.now());

        RouteOutcome outcome = router(PlacementStrategies.LEAST_LOADED)
                .routeVisit(visitor, islandId)
                .join();

        assertThat(outcome).isEqualTo(new RouteOutcome.CrossServer(BUSY, islandId));
        assertThat(askedForNodes).isZero();
        assertThat(directory.cached).containsExactly(BUSY);
    }

    @Test
    @DisplayName("A strategy name no plugin registered places by least loaded")
    void anUnknownNameFallsBack() {
        authority.held = lapsed(STOPPED);

        RouteOutcome outcome =
                router("no-such-strategy").routeVisit(visitor, islandId).join();

        assertThat(outcome).isEqualTo(new RouteOutcome.CrossServer(QUIET, islandId));
    }

    @Test
    @DisplayName("A strategy name is taken once: a second registration under it is refused")
    void aNameIsTakenOnce() {
        PlacementStrategies strategies = PlacementStrategies.shipped(PlacementStrategy.DEFAULT_MSPT_CEILING);

        strategies.register("sticky", (ref, nodes) -> Optional.of(BUSY));

        assertThatThrownBy(() -> strategies.register("sticky", (ref, nodes) -> Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> strategies.register(PlacementStrategies.LEAST_LOADED, (ref, nodes) -> Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(strategies.named(" sticky ")).isPresent();
    }

    @Test
    @DisplayName("mspt-aware avoids a node over the operator's ceiling while another is under it")
    void theCeilingIsTheOperators() {
        List<NodeHealth> nodes =
                List.of(new NodeHealth(BUSY, true, 0, 100, 30.0), new NodeHealth(QUIET, true, 0, 100, 40.0));

        assertThat(PlacementStrategy.msptAware(35.0).selectNode(root, nodes)).contains(BUSY);
        assertThat(PlacementStrategy.msptAware(25.0).selectNode(root, nodes))
                .describedAs("with every node over the ceiling the fastest is still chosen")
                .contains(BUSY);
    }

    @Test
    @DisplayName("Each heartbeat publishes how many islands the node holds, and a failed publish is survived")
    void thePresenceBeats() {
        List<NodeHealth> published = new ArrayList<>();
        ClusterPresence presence = new ClusterPresence(
                new ClusterNodesPort() {
                    @Override
                    public void publish(NodeHealth health, String worldName) {
                        if (published.size() == 1) {
                            throw new IllegalStateException("database away");
                        }
                        published.add(health);
                    }

                    @Override
                    public List<NodeHealth> serving(String worldName, Duration window) {
                        return List.of();
                    }
                },
                LOCAL,
                WORLD,
                500,
                () -> 12.5);

        presence.beat(new IslandAuthoritySweep(4, 2, 1));
        presence.beat(new IslandAuthoritySweep(1, 0, 0));

        assertThat(published).containsExactly(new NodeHealth(LOCAL, true, 7, 500, 12.5));
    }

    private IslandNetworkRouter router(String strategyName) {
        IslandNetworkRouter router = new IslandNetworkRouter(
                LOCAL,
                authority,
                (player, node, island) -> {
                    sentTo.add(node);
                    return CompletableFuture.completedFuture(true);
                },
                directory);
        router.usePlacement(new IslandPlacement(
                PlacementStrategies.shipped(PlacementStrategy.DEFAULT_MSPT_CEILING),
                strategyName,
                new ClusterNodesPort() {
                    @Override
                    public void publish(NodeHealth health, String worldName) {
                        throw new AssertionError("a placement publishes nothing");
                    }

                    @Override
                    public List<NodeHealth> serving(String worldName, Duration window) {
                        askedForNodes++;
                        assertThat(worldName).isEqualTo(WORLD);
                        return alive;
                    }
                },
                id -> Optional.of(WORLD),
                id -> Optional.of(root),
                Duration.ofMinutes(4)));
        sentTo.clear();
        return router;
    }

    private IslandAuthorityRecord lapsed(ServerNodeId holder) {
        return new IslandAuthorityRecord(
                islandId,
                holder,
                EPOCH,
                Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(600));
    }

    /** An authority port that answers reads and records every write it is asked for. */
    private static final class WatchedAuthority implements IslandAuthorityPort {

        private final List<String> writes = new ArrayList<>();

        @SuppressWarnings("NullAway.Init")
        private IslandAuthorityRecord held;

        @Override
        public IslandAuthorityOutcome acquireAuthority(IslandId islandId, ServerNodeId nodeId, int leaseSeconds) {
            writes.add("acquire " + nodeId);
            return IslandAuthorityOutcome.rejected();
        }

        @Override
        public IslandAuthorityOutcome renewAuthority(
                IslandId islandId, ServerNodeId nodeId, long expectedEpoch, int leaseSeconds) {
            writes.add("renew " + nodeId);
            return IslandAuthorityOutcome.rejected();
        }

        @Override
        public IslandAuthorityOutcome takeoverAuthority(
                IslandId islandId, ServerNodeId newNodeId, long expectedEpoch, int leaseSeconds) {
            writes.add("takeover " + newNodeId);
            return IslandAuthorityOutcome.rejected();
        }

        @Override
        public Optional<IslandAuthorityRecord> findAuthority(IslandId islandId) {
            return Optional.of(held);
        }

        @Override
        public IslandAuthoritySweep sweepAuthority(ServerNodeId nodeId, String worldName, int leaseSeconds) {
            writes.add("sweep " + nodeId);
            return IslandAuthoritySweep.NOTHING;
        }
    }

    /** A routing directory that caches nothing and remembers what it was asked to cache. */
    private static final class WatchedDirectory implements ClusterRoutingDirectoryPort {

        private final List<ServerNodeId> cached = new ArrayList<>();

        @Override
        public Optional<ServerNodeId> findAuthoritativeNode(IslandId islandId) {
            return Optional.empty();
        }

        @Override
        public void cacheRoute(IslandId islandId, ServerNodeId nodeId, long epoch, Duration ttl) {
            cached.add(nodeId);
        }

        @Override
        public void invalidateRoute(IslandId islandId) {}
    }
}
