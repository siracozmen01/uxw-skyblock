package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A node renewing its live lease beats a node trying to take the island over.
 *
 * <p>TESTING_STANDARDS names this test, Scenario B: node A renews while node B tries to take over with
 * the epoch it last saw, or while the lease is still live. A's renewal lands and keeps the epoch; B's
 * takeover changes nothing. There was no such test. The same scenarios run against MariaDB and
 * PostgreSQL in {@link AuthorityRenewVsTakeoverRaceIntegrationTest}.
 */
class AuthorityRenewVsTakeoverRaceTest {

    static final ServerNodeId NODE_A = ServerNodeId.of("node-a");
    static final ServerNodeId NODE_B = ServerNodeId.of("node-b");
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_SPOT =
            new java.util.concurrent.atomic.AtomicInteger();

    private Database database;
    private PlayerIslandStorageAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        adapter = new PlayerIslandStorageAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Renewals racing takeovers of a live lease all land, and no takeover does")
    void renewalsBeatTakeovers() throws Exception {
        renewalsBeatTakeoversOfALiveLease(database, adapter);
    }

    @Test
    @DisplayName("A takeover with the epoch from before another takeover changes nothing")
    void aStaleEpochTakesNothing() throws Exception {
        aTakeoverWithAStaleEpochTakesNothing(database, adapter);
    }

    @Test
    @DisplayName("Takeovers racing for an expired lease leave one owner and one new epoch")
    void oneTakeoverWins() throws Exception {
        racingTakeoversOfAnExpiredLeaseLeaveOneOwner(database, adapter);
    }

    static void renewalsBeatTakeoversOfALiveLease(Database db, PlayerIslandStorageAdapter adapter) throws Exception {
        IslandId islandId = newIsland(db, adapter);
        assertThat(adapter.acquireAuthority(islandId, NODE_A, 600).isSuccess()).isTrue();

        List<IslandAuthorityOutcome> renewals = new ArrayList<>();
        List<IslandAuthorityOutcome> takeovers = new ArrayList<>();
        race(
                50,
                () -> renewals.add(adapter.renewAuthority(islandId, NODE_A, 1L, 600)),
                () -> takeovers.add(adapter.takeoverAuthority(islandId, NODE_B, 1L, 600)));

        assertThat(renewals).allMatch(IslandAuthorityOutcome::isSuccess);
        assertThat(takeovers).allMatch(IslandAuthorityOutcome::isRejected);
        var record = adapter.findAuthority(islandId).orElseThrow();
        assertThat(record.authoritativeNode()).isEqualTo(NODE_A);
        assertThat(record.authorityEpoch())
                .describedAs("a renewal fences nobody off")
                .isEqualTo(1L);
    }

    static void aTakeoverWithAStaleEpochTakesNothing(Database db, PlayerIslandStorageAdapter adapter) throws Exception {
        IslandId islandId = newIsland(db, adapter);
        adapter.acquireAuthority(islandId, NODE_A, 1);
        Thread.sleep(2200);
        assertThat(adapter.takeoverAuthority(islandId, NODE_B, 1L, 1).isSuccess())
                .isTrue();
        Thread.sleep(2200);

        assertThat(adapter.takeoverAuthority(islandId, NODE_A, 1L, 600).isRejected())
                .describedAs("node A still believes the epoch is one")
                .isTrue();
        assertThat(adapter.renewAuthority(islandId, NODE_A, 1L, 600).isRejected())
                .describedAs("nor can it renew what it lost")
                .isTrue();
        var record = adapter.findAuthority(islandId).orElseThrow();
        assertThat(record.authoritativeNode()).isEqualTo(NODE_B);
        assertThat(record.authorityEpoch()).isEqualTo(2L);
    }

    static void racingTakeoversOfAnExpiredLeaseLeaveOneOwner(Database db, PlayerIslandStorageAdapter adapter)
            throws Exception {
        IslandId islandId = newIsland(db, adapter);
        adapter.acquireAuthority(islandId, NODE_A, 1);
        Thread.sleep(2200);

        List<IslandAuthorityOutcome> fromB = new ArrayList<>();
        List<IslandAuthorityOutcome> fromC = new ArrayList<>();
        ServerNodeId nodeC = ServerNodeId.of("node-c");
        race(
                1,
                () -> fromB.add(adapter.takeoverAuthority(islandId, NODE_B, 1L, 600)),
                () -> fromC.add(adapter.takeoverAuthority(islandId, nodeC, 1L, 600)));

        long winners = List.of(fromB.getFirst(), fromC.getFirst()).stream()
                .filter(IslandAuthorityOutcome::isSuccess)
                .count();
        assertThat(winners).describedAs("exactly one node takes the island").isEqualTo(1);
        assertThat(adapter.findAuthority(islandId).orElseThrow().authorityEpoch())
                .describedAs("the epoch moved once")
                .isEqualTo(2L);
    }

    /** Runs the two actions against each other, round after round, released together each time. */
    private static void race(int rounds, Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> both = List.of(
                        pool.submit(() -> {
                            start.await();
                            first.run();
                            return null;
                        }),
                        pool.submit(() -> {
                            start.await();
                            second.run();
                            return null;
                        }));
                start.countDown();
                for (Future<?> each : both) {
                    each.get(30, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    static IslandId newIsland(Database db, PlayerIslandStorageAdapter adapter) throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        PlayerUuid owner = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        try (Connection conn = db.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                stmt.setString(1, owner.value().toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                stmt.setString(1, profile.value().toString());
                stmt.setString(2, owner.value().toString());
                stmt.executeUpdate();
            }
        }
        // Every island its own spot: the location table holds one island per place.
        int x = NEXT_SPOT.getAndAdd(1_000);
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(x, 0, 100), owner, profile, Instant.now());
        adapter.saveIsland(island, IslandLocation.fromCenterAndRadius(islandId, "skyblock", x, 0, 100));
        return islandId;
    }
}
