package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.network.ClusterRoutingDirectoryPort;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.network.RouteOutcome;
import com.uxplima.uxmskyblock.core.application.network.VelocityBridgePort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A route a node still holds for an island it has lost gives it no right to write the island.
 *
 * <p>The testing standard names this test. The routing directory is a cache: a route is held for a
 * window after the island moved, and a node that believes the stale route takes a visitor as local.
 * What it may then write is decided by SQL, where the island's lease and epoch now belong to the
 * node that took it over, and the stale node's write is refused.
 */
class RoutingCacheDoesNotOverrideSqlAuthorityTest {

    private static final ServerNodeId OLD = ServerNodeId.of("node-alpha");
    private static final ServerNodeId NEW = ServerNodeId.of("node-beta");

    private Database database;
    private PlayerIslandStorageAdapter islands;
    private PlayerIslandBankAdapter banks;
    private IslandId islandId;
    private PlayerUuid owner;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        islands = new PlayerIslandStorageAdapter(database);
        banks = new PlayerIslandBankAdapter(database);
        owner = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                ps.setString(1, owner.value().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                ps.setString(1, profile.value().toString());
                ps.setString(2, owner.value().toString());
                ps.executeUpdate();
            }
        }
        islandId = IslandId.of(UUID.randomUUID());
        islands.saveIsland(
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 100), owner, profile, Instant.now()),
                IslandLocation.fromCenterAndRadius(islandId, "skyblock", 0, 0, 100));
        banks.createBank(islandId);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A node routed by a stale cache takes the visitor, and SQL refuses the node's write")
    void aStaleRouteDoesNotGrantTheWrite() throws Exception {
        assertThat(islands.acquireAuthority(islandId, OLD, 1)).isInstanceOf(IslandAuthorityOutcome.Success.class);
        long oldEpoch = islands.findAuthority(islandId).orElseThrow().authorityEpoch();
        Thread.sleep(2200);
        assertThat(islands.takeoverAuthority(islandId, NEW, oldEpoch, 600))
                .isInstanceOf(IslandAuthorityOutcome.Success.class);
        assertThat(islands.findAuthority(islandId).orElseThrow().authorityEpoch())
                .describedAs("the takeover advanced the epoch")
                .isGreaterThan(oldEpoch);

        RouteOutcome route = new IslandNetworkRouter(OLD, islands, mock(VelocityBridgePort.class), staleRouteTo(OLD))
                .routeVisit(owner, islandId)
                .get();
        assertThat(route).describedAs("the cache still names the old node").isInstanceOf(RouteOutcome.Local.class);

        BankTransactionOutcome byTheOldNode = banks.executeTransaction(
                islandId,
                owner.value(),
                "PRIMARY",
                2,
                500L,
                "stale deposit",
                OLD.value(),
                oldEpoch,
                banks.findBankByIslandId(islandId).orElseThrow().version(),
                UUID.randomUUID(),
                "stale-" + UUID.randomUUID());
        assertThat(byTheOldNode).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        assertThat(new IslandBankService(banks, islands, islands).depositToIsland(islandId, owner, 500L, OLD))
                .describedAs("the same deposit asked of the service on the old node")
                .isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        assertThat(banks.findBankByIslandId(islandId).orElseThrow().primaryBalanceMinorUnits())
                .isZero();

        assertThat(new IslandBankService(banks, islands, islands).depositToIsland(islandId, owner, 500L, NEW))
                .describedAs("the node SQL names")
                .isInstanceOf(BankTransactionOutcome.Success.class);
    }

    /** A directory that answers with the route it cached before the island moved. */
    private static ClusterRoutingDirectoryPort staleRouteTo(ServerNodeId node) {
        return new ClusterRoutingDirectoryPort() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Optional<ServerNodeId> findAuthoritativeNode(IslandId islandId) {
                return Optional.of(node);
            }

            @Override
            public void cacheRoute(IslandId islandId, ServerNodeId nodeId, long epoch, Duration ttl) {}

            @Override
            public void invalidateRoute(IslandId islandId) {}
        };
    }
}
