package com.uxplima.uxmskyblock.persistence.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.world.RecycledSlot;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;

class SqlSpiralSlotPoolAdapterTest {

    private Database database;
    private SqlSpiralSlotPoolAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new SqlSpiralSlotPoolAdapter(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("claimNextAvailableSlot returns empty when no vacated slots exist")
    void claimNextAvailableSlotReturnsEmptyInitially() {
        Optional<RecycledSlot> claimed = adapter.claimNextAvailableSlot("skyblock_world");
        assertThat(claimed).isEmpty();
        assertThat(adapter.countAvailableSlots("skyblock_world")).isEqualTo(0);
    }

    @Test
    @DisplayName("releaseSlot makes slot available and claimNextAvailableSlot claims lowest index")
    void releaseAndClaimSlot() {
        adapter.releaseSlot(10L, "skyblock_world", 1000, 2000);
        adapter.releaseSlot(5L, "skyblock_world", 500, 500);
        adapter.releaseSlot(20L, "skyblock_world", 2000, 3000);

        assertThat(adapter.countAvailableSlots("skyblock_world")).isEqualTo(3);

        // First claim must be lowest index (5L)
        Optional<RecycledSlot> firstClaim = adapter.claimNextAvailableSlot("skyblock_world");
        assertThat(firstClaim).isPresent();
        assertThat(firstClaim.get().slotIndex()).isEqualTo(5L);
        assertThat(firstClaim.get().gridX()).isEqualTo(500);
        assertThat(firstClaim.get().gridZ()).isEqualTo(500);
        assertThat(firstClaim.get().isAllocated()).isTrue();

        assertThat(adapter.countAvailableSlots("skyblock_world")).isEqualTo(2);

        // Second claim must be next lowest (10L)
        Optional<RecycledSlot> secondClaim = adapter.claimNextAvailableSlot("skyblock_world");
        assertThat(secondClaim).isPresent();
        assertThat(secondClaim.get().slotIndex()).isEqualTo(10L);

        // Third claim must be 20L
        Optional<RecycledSlot> thirdClaim = adapter.claimNextAvailableSlot("skyblock_world");
        assertThat(thirdClaim).isPresent();
        assertThat(thirdClaim.get().slotIndex()).isEqualTo(20L);

        // Fourth claim must be empty
        assertThat(adapter.claimNextAvailableSlot("skyblock_world")).isEmpty();
        assertThat(adapter.countAvailableSlots("skyblock_world")).isEqualTo(0);
    }

    @Test
    @DisplayName("recordAllocatedSlot records slot as allocated")
    void recordAllocatedSlotTracksState() {
        adapter.recordAllocatedSlot(1L, "skyblock_world", 0, 0);

        Optional<RecycledSlot> slot = adapter.findBySlotIndex(1L);
        assertThat(slot).isPresent();
        assertThat(slot.get().isAllocated()).isTrue();
        assertThat(slot.get().vacatedAt()).isNull();
        assertThat(adapter.countAvailableSlots("skyblock_world")).isEqualTo(0);

        // Releasing it now marks it vacated
        adapter.releaseSlot(1L, "skyblock_world", 0, 0);
        Optional<RecycledSlot> released = adapter.findBySlotIndex(1L);
        assertThat(released).isPresent();
        assertThat(released.get().isAllocated()).isFalse();
        assertThat(released.get().vacatedAt()).isNotNull();
        assertThat(adapter.countAvailableSlots("skyblock_world")).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent workers claiming vacated slots receive distinct unique slots")
    void concurrentClaimsAreAtomicAndDisjoint() throws Exception {
        int totalSlots = 20;
        for (int i = 0; i < totalSlots; i++) {
            adapter.releaseSlot(i, "skyblock_world", i * 100, i * 100);
        }
        assertThat(adapter.countAvailableSlots("skyblock_world")).isEqualTo(totalSlots);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);
        List<Long> claimedIndices = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            var unused = executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 5; i++) {
                        Optional<RecycledSlot> claimed = adapter.claimNextAvailableSlot("skyblock_world");
                        claimed.ifPresent(slot -> claimedIndices.add(slot.slotIndex()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(claimedIndices).hasSize(totalSlots);
        // All claimed slots must be distinct
        assertThat(claimedIndices).doesNotHaveDuplicates();
        assertThat(adapter.countAvailableSlots("skyblock_world")).isEqualTo(0);
    }
}
