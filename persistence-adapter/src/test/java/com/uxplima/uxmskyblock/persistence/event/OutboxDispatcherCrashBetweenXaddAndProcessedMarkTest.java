package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.event.InboxDeduplicatingConsumer;
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
 * A worker that published and died before it marked the event processed costs a second delivery,
 * never a second effect.
 *
 * <p>The testing standard names this test. Worker one claims the event and puts it on the stream, and
 * stops before it marks it processed. Its lease runs out, worker two claims the event and puts it on the
 * stream again. The consuming node reads both deliveries: the first is new to its inbox and applies,
 * the second finds the inbox mark, applies nothing and is acknowledged all the same.
 */
class OutboxDispatcherCrashBetweenXaddAndProcessedMarkTest {

    private Database database;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A redelivery after a crash before the processed mark is acknowledged and applies nothing")
    void aRedeliveryAppliesOnce() throws Exception {
        TransactionalOutboxAdapter outbox = new TransactionalOutboxAdapter(database);
        EventId eventId = EventId.random();
        outbox.stageEvent(eventId, "ISLAND_CREATED", "isl-1", "{}");
        List<OutboxEventRecord> stream = new ArrayList<>();

        // Worker one: claims, publishes, and the process ends before the processed mark.
        OutboxClaim first = outbox.claimPendingBatch("worker-1", Duration.ofMillis(100), 10);
        stream.addAll(first.claimedEvents());
        Thread.sleep(250);

        // Worker two finds the lapsed claim, publishes again, and marks it processed.
        SchedulerPort scheduler = mock(SchedulerPort.class);
        when(scheduler.repeatAsync(any(), any(), any())).thenReturn(() -> {});
        TransactionalOutboxDispatcher second = new TransactionalOutboxDispatcher(outbox, scheduler, "worker-2");
        second.registerConsumer(stream::add);
        second.start();
        assertThat(second.dispatchBatch()).isEqualTo(1);
        second.close();

        assertThat(stream).extracting(OutboxEventRecord::eventId).containsExactly(eventId, eventId);
        assertThat(outbox.findById(eventId).orElseThrow().status()).isEqualTo(OutboxStatus.PROCESSED);

        // The consuming node reads the stream.
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger acknowledged = new AtomicInteger();
        InboxDeduplicatingConsumer remote = new InboxDeduplicatingConsumer(
                "island-projection", new ConsumerInboxAdapter(database), event -> applied.incrementAndGet());
        for (OutboxEventRecord delivery : stream) {
            remote.onEvent(delivery, acknowledged::incrementAndGet);
        }

        assertThat(applied).describedAs("the projection").hasValue(1);
        assertThat(acknowledged).describedAs("both deliveries leave the stream").hasValue(2);
    }
}
