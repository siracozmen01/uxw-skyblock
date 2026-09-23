package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxClaim;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A claim whose worker died is taken over once it expires.
 *
 * <p>TESTING_STANDARDS names this test: worker one claims and stops before it delivers, the claim
 * expires, worker two claims the same event, becomes its owner, and counts the attempt. Worker one,
 * coming back late, cannot complete what is no longer its claim.
 */
class OutboxExpiredClaimRecoveryTest {

    private Database database;
    private TransactionalOutboxAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new TransactionalOutboxAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("An expired claim passes to the next worker, which owns it and counts the attempt")
    void anExpiredClaimIsTakenOver() throws Exception {
        EventId eventId = EventId.random();
        adapter.stageEvent(eventId, "ISLAND_CREATED", "isl-1", "{}");

        OutboxClaim first = adapter.claimPendingBatch("worker-1", Duration.ofMillis(100), 10);
        assertThat(first.claimedEvents()).hasSize(1);
        assertThat(adapter.claimPendingBatch("worker-2", Duration.ofSeconds(30), 10)
                        .claimedEvents())
                .describedAs("a live claim is not taken")
                .isEmpty();

        Thread.sleep(250);
        OutboxClaim second = adapter.claimPendingBatch("worker-2", Duration.ofSeconds(30), 10);

        assertThat(second.claimedEvents())
                .extracting(OutboxEventRecord::eventId)
                .containsExactly(eventId);
        OutboxEventRecord taken = adapter.findById(eventId).orElseThrow();
        assertThat(taken.status()).isEqualTo(OutboxStatus.CLAIMED);
        assertThat(taken.claimOwner()).isEqualTo("worker-2");
        assertThat(taken.retryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("The worker whose claim expired cannot complete it after it was taken over")
    void theStaleWorkerIsFenced() throws Exception {
        EventId eventId = EventId.random();
        adapter.stageEvent(eventId, "ISLAND_CREATED", "isl-1", "{}");
        OutboxClaim first = adapter.claimPendingBatch("worker-1", Duration.ofMillis(100), 10);
        Thread.sleep(250);
        OutboxClaim second = adapter.claimPendingBatch("worker-2", Duration.ofSeconds(30), 10);

        assertThat(adapter.completeClaim(eventId, "worker-1", first.claimToken()))
                .describedAs("the late worker")
                .isFalse();
        assertThat(adapter.completeClaim(eventId, "worker-2", second.claimToken()))
                .describedAs("the owner")
                .isTrue();
        assertThat(adapter.findById(eventId).orElseThrow().status()).isEqualTo(OutboxStatus.PROCESSED);
    }
}
