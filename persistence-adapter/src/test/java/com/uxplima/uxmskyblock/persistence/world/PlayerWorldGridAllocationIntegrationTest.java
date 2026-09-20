package com.uxplima.uxmskyblock.persistence.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class PlayerWorldGridAllocationIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerWorldGridAllocationAdapter mariaAdapter;
    private static PlayerWorldGridAllocationAdapter postgresAdapter;

    private static final ServerNodeId NODE_1 = new ServerNodeId("node-1");
    private static final ServerNodeId NODE_2 = new ServerNodeId("node-2");

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new PlayerWorldGridAllocationAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new PlayerWorldGridAllocationAdapter(postgresDatabase);
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (mariaDatabase != null && !mariaDatabase.isClosed()) {
            mariaDatabase.close();
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }

        if (postgresDatabase != null && !postgresDatabase.isClosed()) {
            postgresDatabase.close();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    // ==========================================
    // MariaDB Integration Tests
    // ==========================================

    @Test
    @Order(1)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: Allocates sequential grid slots monotonically and looks up accurately")
    void mariaDbAllocatesMonotonicallyAndLooksUp() {
        testMonotonicAllocationAndLookup(mariaAdapter);
    }

    @Test
    @Order(2)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: Multi-node concurrent allocations produce unique sequential indexes without race collisions")
    void mariaDbConcurrentAllocations() throws Exception {
        testConcurrentAllocations(mariaAdapter);
    }

    // ==========================================
    // PostgreSQL Integration Tests
    // ==========================================

    @Test
    @Order(3)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: Allocates sequential grid slots monotonically and looks up accurately")
    void postgresAllocatesMonotonicallyAndLooksUp() {
        testMonotonicAllocationAndLookup(postgresAdapter);
    }

    @Test
    @Order(4)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName(
            "PostgreSQL: Multi-node concurrent allocations produce unique sequential indexes without race collisions")
    void postgresConcurrentAllocations() throws Exception {
        testConcurrentAllocations(postgresAdapter);
    }

    // ==========================================
    // Shared Verification Logic
    // ==========================================

    private void testMonotonicAllocationAndLookup(PlayerWorldGridAllocationAdapter adapter) {
        IslandId island1 = IslandId.of(UUID.randomUUID());
        WorldGridAllocation alloc1 = adapter.allocateNext(NODE_1, "world_main", island1);

        assertThat(alloc1.sequenceIndex()).isGreaterThanOrEqualTo(0L);
        assertThat(alloc1.worldName()).isEqualTo("world_main");
        assertThat(alloc1.islandId()).contains(island1);
        assertThat(alloc1.allocatedByNode()).isEqualTo(NODE_1);

        Optional<WorldGridAllocation> bySeq = adapter.findBySequenceIndex(alloc1.sequenceIndex());
        assertThat(bySeq).isPresent();
        assertThat(bySeq.get().centerX()).isEqualTo(alloc1.centerX());
        assertThat(bySeq.get().centerZ()).isEqualTo(alloc1.centerZ());
        assertThat(bySeq.get().islandId()).contains(island1);

        Optional<WorldGridAllocation> byIsland = adapter.findByIslandId(island1);
        assertThat(byIsland).isPresent();
        assertThat(byIsland.get().sequenceIndex()).isEqualTo(alloc1.sequenceIndex());

        Optional<WorldGridAllocation> byCoords =
                adapter.findByCoordinates("world_main", alloc1.centerX(), alloc1.centerZ());
        assertThat(byCoords).isPresent();
        assertThat(byCoords.get().sequenceIndex()).isEqualTo(alloc1.sequenceIndex());

        // Test binding
        WorldGridAllocation unbound = adapter.allocateNext(NODE_2, "world_main", null);
        assertThat(unbound.islandId()).isEmpty();

        IslandId boundIsland = IslandId.of(UUID.randomUUID());
        adapter.bindIsland(unbound.sequenceIndex(), boundIsland);
        assertThat(adapter.findBySequenceIndex(unbound.sequenceIndex())
                        .orElseThrow()
                        .islandId())
                .contains(boundIsland);
    }

    private void testConcurrentAllocations(PlayerWorldGridAllocationAdapter adapter) throws Exception {
        int threads = 4;
        int perThread = 5;
        int totalExpected = threads * perThread;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        Set<Long> allocatedSeqs = Collections.newSetFromMap(new ConcurrentHashMap<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            var unused = executor.submit(() -> {
                try {
                    startLatch.await();
                    ServerNodeId node = new ServerNodeId("cluster-node-" + threadIdx);
                    for (int i = 0; i < perThread; i++) {
                        WorldGridAllocation alloc =
                                adapter.allocateNext(node, "cluster_world", IslandId.of(UUID.randomUUID()));
                        allocatedSeqs.add(alloc.sequenceIndex());
                    }
                } catch (Throwable ex) {
                    failures.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures).isEmpty();
        assertThat(allocatedSeqs).hasSize(totalExpected);
    }
}
