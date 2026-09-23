package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
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
 * A worker that stalled past its claim cannot finish an event another worker already delivered.
 *
 * <p>The testing standard's scenario: worker one claims an event and stalls, as in a long garbage
 * collection pause. The claim expires, worker two claims the event, delivers it and completes it.
 * Worker one wakes and tries to complete it too. That changes nothing, the delivery keeps worker
 * two's time, and worker one logs {@code STALE_OUTBOX_WORKER_FENCED}. Worker one reporting a
 * failure instead changes nothing either, which is what keeps the event from being sent a third time.
 */
class OutboxStaleWorkerCompletionFenceTest {

    private Database database;
    private TransactionalOutboxAdapter outbox;
    private final List<Runnable> cleanUp = new ArrayList<>();

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        outbox = new TransactionalOutboxAdapter(database);
    }

    @AfterEach
    void tearDown() {
        cleanUp.forEach(Runnable::run);
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A stalled worker's completion changes nothing and is logged as fenced")
    void theStalledWorkersCompletionIsFenced() throws Exception {
        EventId eventId = EventId.random();
        outbox.stageEvent(eventId, "ISLAND_CREATED", "isl-1", "{}");
        List<Instant> deliveredAt = new ArrayList<>();

        TransactionalOutboxDispatcher workerOne = new TransactionalOutboxDispatcher(
                outbox,
                mock(SchedulerPort.class),
                "worker-1",
                Duration.ofMillis(100),
                10,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                5);
        workerOne.registerConsumer(event -> {
            // The pause: long enough for the claim to lapse and for worker two to do the whole job.
            Thread.sleep(250);
            OutboxClaim taken = outbox.claimPendingBatch("worker-2", Duration.ofSeconds(30), 10);
            assertThat(taken.claimedEvents())
                    .extracting(OutboxEventRecord::eventId)
                    .containsExactly(eventId);
            assertThat(outbox.completeClaim(eventId, "worker-2", taken.claimToken()))
                    .isTrue();
            deliveredAt.add(java.util.Objects.requireNonNull(
                    outbox.findById(eventId).orElseThrow().processedAt()));
            Thread.sleep(20);
        });
        List<String> logged = listenTo(TransactionalOutboxDispatcher.class);
        workerOne.start();

        int completedByWorkerOne = workerOne.dispatchBatch();
        workerOne.close();

        assertThat(completedByWorkerOne)
                .describedAs("events worker one completed")
                .isZero();
        OutboxEventRecord after = outbox.findById(eventId).orElseThrow();
        assertThat(after.status()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(after.claimOwner()).isEqualTo("worker-2");
        assertThat(after.processedAt())
                .describedAs("worker two's delivery time, not rewritten")
                .isEqualTo(deliveredAt.get(0));
        assertThat(logged).anyMatch(line -> line.contains("STALE_OUTBOX_WORKER_FENCED"));
    }

    @Test
    @DisplayName("A stalled worker reporting a failure does not put a delivered event back in the queue")
    void theStalledWorkersFailureIsFenced() throws Exception {
        EventId eventId = EventId.random();
        outbox.stageEvent(eventId, "ISLAND_CREATED", "isl-1", "{}");
        OutboxClaim first = outbox.claimPendingBatch("worker-1", Duration.ofMillis(100), 10);
        Thread.sleep(250);
        OutboxClaim second = outbox.claimPendingBatch("worker-2", Duration.ofSeconds(30), 10);
        assertThat(outbox.completeClaim(eventId, "worker-2", second.claimToken()))
                .isTrue();

        outbox.recordFailure(eventId, "worker-1", first.claimToken(), "timed out", Duration.ofSeconds(1), 5);
        outbox.recordFailure(eventId, "worker-1", first.claimToken(), "timed out", Duration.ofSeconds(1), 0);

        OutboxEventRecord after = outbox.findById(eventId).orElseThrow();
        assertThat(after.status())
                .describedAs("neither retried nor dead lettered")
                .isEqualTo(OutboxStatus.PROCESSED);
        assertThat(outbox.claimPendingBatch("worker-3", Duration.ofSeconds(30), 10)
                        .claimedEvents())
                .describedAs("nothing is sent a third time")
                .isEmpty();
    }

    private List<String> listenTo(Class<?> type) {
        List<String> lines = new ArrayList<>();
        Logger logger = Logger.getLogger(type.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        cleanUp.add(() -> logger.removeHandler(handler));
        return lines;
    }
}
