package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
 * A poison outbox event backs off, doubling each time, ends in the dead letter queue after its
 * retries, and never holds the rest of the queue back.
 *
 * <p>TESTING_STANDARDS names this test and its contract. There was no such test, and the backoff was
 * the same length after every failure rather than growing.
 */
class OutboxPoisonEventDeadLetterTest {

    private static final int MAX_RETRIES = 5;
    private static final Duration BASE = Duration.ofMillis(1);

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
    @DisplayName("The wait doubles after each failure, from the base for the first")
    void theBackoffDoubles() {
        Duration base = Duration.ofSeconds(5);

        assertThat(List.of(1, 2, 3, 4, 5).stream()
                        .map(attempt -> TransactionalOutboxAdapter.backoffAfter(base, attempt))
                        .toList())
                .containsExactly(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(40),
                        Duration.ofSeconds(80));
    }

    @Test
    @DisplayName("A poison event ends in the dead letter queue with its error, and the good one is delivered")
    void aPoisonEventIsDeadLetteredAndTheQueueMovesOn() throws Exception {
        EventId poison = EventId.random();
        adapter.stageEvent(poison, "BROKEN", "isl-1", "{}");
        EventId good = EventId.random();
        adapter.stageEvent(good, "FINE", "isl-2", "{}");

        List<EventId> delivered = new ArrayList<>();
        for (int round = 0; round < 40 && statusOf(poison) != OutboxStatus.DEAD_LETTER; round++) {
            OutboxClaim claim = adapter.claimPendingBatch("worker-1", Duration.ofSeconds(30), 10);
            for (OutboxEventRecord event : claim.claimedEvents()) {
                if (event.eventId().equals(poison)) {
                    adapter.recordFailure(
                            poison,
                            "worker-1",
                            claim.claimToken(),
                            "consumer threw on a malformed payload",
                            BASE,
                            MAX_RETRIES);
                } else {
                    adapter.completeClaim(event.eventId(), "worker-1", claim.claimToken());
                    delivered.add(event.eventId());
                }
            }
            Thread.sleep(40);
        }

        OutboxEventRecord dead = adapter.findById(poison).orElseThrow();
        assertThat(dead.status()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(dead.lastError()).isEqualTo("consumer threw on a malformed payload");
        assertThat(dead.retryCount()).isEqualTo(MAX_RETRIES);
        assertThat(delivered)
                .describedAs("the poison event never blocked the good one")
                .containsExactly(good);
        assertThat(adapter.claimPendingBatch("worker-1", Duration.ofSeconds(30), 10)
                        .claimedEvents())
                .describedAs("a dead letter is never claimed again")
                .isEmpty();
    }

    @Test
    @DisplayName("A failed event is not tried again before its wait is over")
    void aFailedEventWaits() {
        EventId poison = EventId.random();
        adapter.stageEvent(poison, "BROKEN", "isl-1", "{}");
        OutboxClaim claim = adapter.claimPendingBatch("worker-1", Duration.ofSeconds(30), 10);
        Instant before = Instant.now();

        adapter.recordFailure(poison, "worker-1", claim.claimToken(), "boom", Duration.ofMinutes(10), MAX_RETRIES);

        assertThat(adapter.claimPendingBatch("worker-1", Duration.ofSeconds(30), 10)
                        .claimedEvents())
                .isEmpty();
        assertThat(adapter.findById(poison).orElseThrow().nextAttemptAt()).isAfter(before.plus(Duration.ofMinutes(9)));
    }

    private OutboxStatus statusOf(EventId eventId) {
        return adapter.findById(eventId).orElseThrow().status();
    }
}
