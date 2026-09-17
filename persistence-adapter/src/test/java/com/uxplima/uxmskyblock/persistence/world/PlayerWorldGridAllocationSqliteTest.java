package com.uxplima.uxmskyblock.persistence.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
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
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerWorldGridAllocationSqliteTest {

    private Database database;
    private PlayerWorldGridAllocationAdapter adapter;

    private final ServerNodeId node1 = new ServerNodeId("node-1");
    private final ServerNodeId node2 = new ServerNodeId("node-2");

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerWorldGridAllocationAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("allocates next sequence index monotonically on clean database")
    void allocatesNextSequenceFromCleanDatabase() {
        IslandId island1 = IslandId.of(UUID.randomUUID());
        WorldGridAllocation alloc1 = adapter.allocateNext(node1, "world", island1);

        assertThat(alloc1.sequenceIndex()).isEqualTo(0L);
        assertThat(alloc1.worldName()).isEqualTo("world");
        assertThat(alloc1.centerX()).isEqualTo(0);
        assertThat(alloc1.centerZ()).isEqualTo(0);
        assertThat(alloc1.islandId()).contains(island1);
        assertThat(alloc1.allocatedByNode()).isEqualTo(node1);

        IslandId island2 = IslandId.of(UUID.randomUUID());
        WorldGridAllocation alloc2 = adapter.allocateNext(node2, "world", island2);

        assertThat(alloc2.sequenceIndex()).isEqualTo(1L);
        assertThat(alloc2.centerX()).isEqualTo(5120);
        assertThat(alloc2.centerZ()).isEqualTo(0);
        assertThat(alloc2.islandId()).contains(island2);
        assertThat(alloc2.allocatedByNode()).isEqualTo(node2);
    }

    @Test
    @DisplayName("reserves next sequence with manually specified coordinates")
    void reservesNextSequenceWithSpecifiedCoordinates() {
        IslandId island1 = IslandId.of(UUID.randomUUID());
        long seq1 = adapter.reserveNextSequence(node1, "world", 1000, 2000, island1);
        assertThat(seq1).isEqualTo(0L);

        long seq2 = adapter.reserveNextSequence(node2, "world", 3000, 4000, null);
        assertThat(seq2).isEqualTo(1L);

        Optional<WorldGridAllocation> lookup = adapter.findBySequenceIndex(1L);
        assertThat(lookup).isPresent();
        assertThat(lookup.get().centerX()).isEqualTo(3000);
        assertThat(lookup.get().centerZ()).isEqualTo(4000);
        assertThat(lookup.get().islandId()).isEmpty();
    }

    @Test
    @DisplayName("looks up allocations by sequence index, island ID, coordinates, and max sequence")
    void lookupsBySequenceIslandAndCoordinates() {
        IslandId island = IslandId.of(UUID.randomUUID());
        WorldGridAllocation alloc = adapter.allocateNext(node1, "world", island);

        assertThat(adapter.findBySequenceIndex(alloc.sequenceIndex())).contains(alloc);
        assertThat(adapter.findByIslandId(island)).contains(alloc);
        assertThat(adapter.findByCoordinates("world", alloc.centerX(), alloc.centerZ()))
                .contains(alloc);
        assertThat(adapter.findMaxSequenceIndex("world")).contains(alloc.sequenceIndex());

        assertThat(adapter.findBySequenceIndex(999L)).isEmpty();
        assertThat(adapter.findByIslandId(IslandId.of(UUID.randomUUID()))).isEmpty();
        assertThat(adapter.findByCoordinates("world", 99999, 99999)).isEmpty();
        assertThat(adapter.findMaxSequenceIndex("other_world")).isEmpty();
    }

    @Test
    @DisplayName("binds island ID to pre-allocated grid slot")
    void bindIslandUpdatesUnboundAllocation() {
        WorldGridAllocation alloc = adapter.allocateNext(node1, "world", null);
        assertThat(alloc.islandId()).isEmpty();

        IslandId newIsland = IslandId.of(UUID.randomUUID());
        adapter.bindIsland(alloc.sequenceIndex(), newIsland);

        Optional<WorldGridAllocation> updated = adapter.findBySequenceIndex(alloc.sequenceIndex());
        assertThat(updated).isPresent();
        assertThat(updated.get().islandId()).contains(newIsland);
    }

    @Test
    @DisplayName("concurrent allocations produce unique sequential indexes without collision")
    void concurrentAllocationsProduceUniqueSequentialIndexes() throws Exception {
        int threads = 8;
        int allocationsPerThread = 5;
        int totalAllocations = threads * allocationsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        Set<Long> sequenceIndexes = Collections.newSetFromMap(new ConcurrentHashMap<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            var unused = executor.submit(() -> {
                try {
                    startLatch.await();
                    ServerNodeId node = new ServerNodeId("node-" + threadIdx);
                    for (int i = 0; i < allocationsPerThread; i++) {
                        WorldGridAllocation alloc = adapter.allocateNext(node, "world", IslandId.of(UUID.randomUUID()));
                        sequenceIndexes.add(alloc.sequenceIndex());
                    }
                } catch (Throwable ex) {
                    errors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(sequenceIndexes).hasSize(totalAllocations);
        assertThat(sequenceIndexes)
                .containsExactlyInAnyOrderElementsOf(java.util.stream.LongStream.range(0, totalAllocations)
                        .boxed()
                        .toList());
    }
}
