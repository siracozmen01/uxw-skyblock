package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;

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

class TransactionalOutboxSqliteTest {

    private Database database;
    private TransactionalOutboxAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new TransactionalOutboxAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("stageEvent inserts pending record and increments pending count")
    void stageEvent() {
        EventId eventId = EventId.random();
        assertThat(adapter.getPendingCount()).isEqualTo(0);

        adapter.stageEvent(eventId, "ISLAND_CREATED", "isl-1", "{\"owner\":\"p1\"}");

        assertThat(adapter.getPendingCount()).isEqualTo(1);
        Optional<OutboxEventRecord> record = adapter.findById(eventId);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(record.get().eventType()).isEqualTo("ISLAND_CREATED");
        assertThat(record.get().aggregateId()).isEqualTo("isl-1");
        assertThat(record.get().payload()).isEqualTo("{\"owner\":\"p1\"}");
        assertThat(record.get().retryCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("claimPendingBatch locks event and fences concurrent worker until lease expires")
    void claimPendingBatch() {
        EventId eventId = EventId.random();
        adapter.stageEvent(eventId, "BANK_DEPOSIT", "isl-1", "{\"amount\":500}");

        // Worker 1 claims with 1 second lease
        OutboxClaim claim1 = adapter.claimPendingBatch("worker-1", Duration.ofSeconds(1), 10);
        assertThat(claim1.claimedEvents()).hasSize(1);
        assertThat(claim1.workerId()).isEqualTo("worker-1");
        assertThat(claim1.claimedEvents().get(0).eventId()).isEqualTo(eventId);
        assertThat(claim1.claimedEvents().get(0).status()).isEqualTo(OutboxStatus.CLAIMED);

        // Worker 2 attempts to claim while lease is active -> 0 events
        OutboxClaim claim2 = adapter.claimPendingBatch("worker-2", Duration.ofSeconds(5), 10);
        assertThat(claim2.claimedEvents()).isEmpty();

        // Expire the lease manually in database
        try (var conn = database.connection();
                var ps = conn.prepareStatement("UPDATE outbox_events SET claim_expires_at = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Worker 2 claims again after lease expiration -> successfully reclaims
        OutboxClaim claim3 = adapter.claimPendingBatch("worker-2", Duration.ofSeconds(5), 10);
        assertThat(claim3.claimedEvents()).hasSize(1);
        assertThat(claim3.workerId()).isEqualTo("worker-2");
        assertThat(claim3.claimedEvents().get(0).retryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("completeClaim transitions to PROCESSED and rejects stale worker claims")
    void completeClaimLifecycle() {
        EventId eventId = EventId.random();
        adapter.stageEvent(eventId, "ROLE_ASSIGNED", "isl-1", "{}");

        OutboxClaim claim = adapter.claimPendingBatch("worker-1", Duration.ofSeconds(30), 10);
        assertThat(claim.claimedEvents()).hasSize(1);

        // Wrong token fails
        boolean wrongToken = adapter.completeClaim(eventId, "worker-1", "invalid-token");
        assertThat(wrongToken).isFalse();

        // Wrong worker fails
        boolean wrongWorker = adapter.completeClaim(eventId, "worker-2", claim.claimToken());
        assertThat(wrongWorker).isFalse();

        // Legitimate completion succeeds
        boolean success = adapter.completeClaim(eventId, "worker-1", claim.claimToken());
        assertThat(success).isTrue();

        Optional<OutboxEventRecord> processed = adapter.findById(eventId);
        assertThat(processed).isPresent();
        assertThat(processed.get().status()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(processed.get().processedAt()).isNotNull();

        // Subsequent completion is rejected (already processed)
        boolean repeat = adapter.completeClaim(eventId, "worker-1", claim.claimToken());
        assertThat(repeat).isFalse();
    }

    @Test
    @DisplayName("recordFailure schedules retry under threshold and moves to DEAD_LETTER when threshold reached")
    void failureAndDeadLetter() {
        EventId eventId = EventId.random();
        adapter.stageEvent(eventId, "ISLAND_DELETED", "isl-1", "{}");

        // Claim 1
        OutboxClaim claim1 = adapter.claimPendingBatch("worker-1", Duration.ofSeconds(30), 10);
        // Fail with maxRetries = 2 -> should reschedule as PENDING
        adapter.recordFailure(eventId, "worker-1", claim1.claimToken(), "Network timeout", Duration.ofSeconds(10), 2);

        Optional<OutboxEventRecord> retried = adapter.findById(eventId);
        assertThat(retried).isPresent();
        assertThat(retried.get().status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.get().claimOwner()).isNull();
        assertThat(retried.get().nextAttemptAt()).isNotNull();
        assertThat(retried.get().lastError()).isEqualTo("Network timeout");

        // Clear next_attempt_at so it can be claimed again immediately
        try (var conn = database.connection();
                var stmt = conn.createStatement()) {
            stmt.execute("UPDATE outbox_events SET next_attempt_at = NULL");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Claim 2
        OutboxClaim claim2 = adapter.claimPendingBatch("worker-2", Duration.ofSeconds(30), 10);
        assertThat(claim2.claimedEvents()).hasSize(1);
        assertThat(claim2.claimedEvents().get(0).retryCount()).isEqualTo(2);

        // Fail with maxRetries = 2 -> should move to DEAD_LETTER
        adapter.recordFailure(
                eventId, "worker-2", claim2.claimToken(), "Fatal serialization bug", Duration.ofSeconds(10), 2);

        Optional<OutboxEventRecord> deadLetter = adapter.findById(eventId);
        assertThat(deadLetter).isPresent();
        assertThat(deadLetter.get().status()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(deadLetter.get().lastError()).isEqualTo("Fatal serialization bug");
    }
}
