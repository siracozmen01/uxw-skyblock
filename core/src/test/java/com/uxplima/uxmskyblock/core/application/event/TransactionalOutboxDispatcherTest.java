package com.uxplima.uxmskyblock.core.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxClaim;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionalOutboxDispatcherTest {

    private InMemoryOutboxPort outboxPort;
    private StubSchedulerPort schedulerPort;
    private TransactionalOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        outboxPort = new InMemoryOutboxPort();
        schedulerPort = new StubSchedulerPort();
        dispatcher = new TransactionalOutboxDispatcher(
                outboxPort,
                schedulerPort,
                "worker-node-1",
                Duration.ofSeconds(30),
                25,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                3);
    }

    @Test
    @DisplayName("dispatchBatch dispatches claimed events to consumers and completes claims")
    void dispatchesAndCompletesClaims() {
        EventId e1 = EventId.random();
        EventId e2 = EventId.random();
        outboxPort.stageEvent(e1, "ISLAND_CREATED", "isl-1", "{}");
        outboxPort.stageEvent(e2, "ISLAND_UPGRADED", "isl-1", "{}");

        List<EventId> consumed = new ArrayList<>();
        dispatcher.registerConsumer(event -> consumed.add(event.eventId()));

        dispatcher.start();
        int processed = dispatcher.dispatchBatch();

        assertThat(processed).isEqualTo(2);
        assertThat(consumed).containsExactly(e1, e2);
        assertThat(outboxPort.completedEvents).containsExactlyInAnyOrder(e1, e2);
        assertThat(outboxPort.failedEvents).isEmpty();
    }

    @Test
    @DisplayName("dispatchBatch records failure and backoff when consumer throws")
    void recordsFailureOnConsumerException() {
        EventId e1 = EventId.random();
        outboxPort.stageEvent(e1, "ISLAND_DELETED", "isl-2", "{}");

        dispatcher.registerConsumer(event -> {
            throw new RuntimeException("Consumer downstream unavailable");
        });

        dispatcher.start();
        int processed = dispatcher.dispatchBatch();

        assertThat(processed).isEqualTo(0);
        assertThat(outboxPort.completedEvents).isEmpty();
        assertThat(outboxPort.failedEvents).containsKey(e1);
        assertThat(outboxPort.failedEvents.get(e1)).contains("Consumer downstream unavailable");
    }

    @Test
    @DisplayName("dispatchBatch returns 0 when no events are pending")
    void returnsZeroWhenEmpty() {
        dispatcher.start();
        int processed = dispatcher.dispatchBatch();

        assertThat(processed).isEqualTo(0);
        assertThat(outboxPort.completedEvents).isEmpty();
    }

    @Test
    @DisplayName("dispatchBatch returns 0 and does not complete events when consumers are empty")
    void returnsZeroWhenNoConsumersRegistered() {
        EventId e1 = EventId.random();
        outboxPort.stageEvent(e1, "ISLAND_CREATED", "isl-1", "{}");

        dispatcher.start();
        int processed = dispatcher.dispatchBatch();

        assertThat(processed).isEqualTo(0);
        assertThat(outboxPort.completedEvents).isEmpty();
        assertThat(outboxPort.failedEvents).isEmpty();
    }

    @Test
    @DisplayName("start and close manages scheduled repeating task cleanly")
    void startAndCloseLifecycle() {
        assertThat(dispatcher.isRunning()).isFalse();
        dispatcher.start();
        assertThat(dispatcher.isRunning()).isTrue();
        assertThat(schedulerPort.repeatingTaskScheduled).isTrue();

        dispatcher.close();
        assertThat(dispatcher.isRunning()).isFalse();
        assertThat(schedulerPort.taskClosed).isTrue();

        // Dispatch after close does nothing
        int processed = dispatcher.dispatchBatch();
        assertThat(processed).isEqualTo(0);
    }

    @Test
    @DisplayName("dispatchBatch respects stale claim fence when completion is rejected")
    void handlesFencedCompletion() {
        EventId e1 = EventId.random();
        outboxPort.stageEvent(e1, "ISLAND_BANK_DEPOSIT", "isl-3", "{}");
        outboxPort.rejectCompletions = true;

        List<EventId> consumed = new ArrayList<>();
        dispatcher.registerConsumer(event -> consumed.add(event.eventId()));

        dispatcher.start();
        int processed = dispatcher.dispatchBatch();

        assertThat(consumed).containsExactly(e1);
        assertThat(processed).isEqualTo(0);
    }

    @Test
    @DisplayName("Delivered events are swept, and the cutoff is the retention behind now")
    void deliveredEventsAreSwept() {
        InMemoryOutboxPort port = new InMemoryOutboxPort();
        TransactionalOutboxDispatcher dispatcher = new TransactionalOutboxDispatcher(
                port,
                new StubSchedulerPort(),
                "worker-1",
                java.time.Duration.ofSeconds(30),
                50,
                java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(5),
                5,
                java.time.Duration.ofDays(7),
                java.time.Duration.ofHours(1));

        java.time.Instant before = java.time.Instant.now();
        dispatcher.purgeDelivered();

        assertThat(port.purgesAskedFor).describedAs("sweeps asked for").hasSize(1);
        assertThat(port.purgesAskedFor.get(0))
                .describedAs("a week behind the moment of the sweep")
                .isBetween(
                        before.minus(java.time.Duration.ofDays(7)).minusSeconds(5),
                        java.time.Instant.now()
                                .minus(java.time.Duration.ofDays(7))
                                .plusSeconds(5));
    }

    @Test
    @DisplayName("A sweep that throws does not stop the dispatcher")
    void aFailingSweepIsSurvived() {
        OutboxPort angry = new InMemoryOutboxPort() {
            @Override
            public synchronized int purgeProcessedBefore(java.time.Instant before) {
                throw new IllegalStateException("the database is gone");
            }
        };
        TransactionalOutboxDispatcher dispatcher =
                new TransactionalOutboxDispatcher(angry, new StubSchedulerPort(), "worker-1");

        assertThat(dispatcher.purgeDelivered())
                .describedAs("nothing swept, and no exception out")
                .isZero();
    }

    private static class InMemoryOutboxPort implements OutboxPort {
        private final List<OutboxEventRecord> events = new ArrayList<>();
        final List<EventId> completedEvents = new ArrayList<>();
        final Map<EventId, String> failedEvents = new ConcurrentHashMap<>();
        boolean rejectCompletions = false;

        @Override
        public synchronized void stageEvent(EventId eventId, String eventType, String aggregateId, String payload) {
            events.add(new OutboxEventRecord(
                    eventId,
                    eventType,
                    aggregateId,
                    payload,
                    OutboxStatus.PENDING,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    Instant.now(),
                    null));
        }

        @Override
        public synchronized OutboxClaim claimPendingBatch(String workerId, Duration leaseDuration, int batchSize) {
            List<OutboxEventRecord> claimed = new ArrayList<>();
            for (OutboxEventRecord r : events) {
                if (r.status() == OutboxStatus.PENDING && claimed.size() < batchSize) {
                    claimed.add(r);
                }
            }
            return new OutboxClaim(workerId, "token-1", Instant.now().plus(leaseDuration), claimed);
        }

        @Override
        public synchronized boolean completeClaim(EventId eventId, String workerId, String claimToken) {
            if (rejectCompletions) {
                return false;
            }
            completedEvents.add(eventId);
            return true;
        }

        @Override
        public synchronized void recordFailure(
                EventId eventId,
                String workerId,
                String claimToken,
                String errorMessage,
                Duration retryBackoff,
                int maxRetries) {
            failedEvents.put(eventId, errorMessage);
        }

        @Override
        public Optional<OutboxEventRecord> findById(EventId eventId) {
            return events.stream().filter(e -> e.eventId().equals(eventId)).findFirst();
        }

        @Override
        public int getPendingCount() {
            return (int) events.stream()
                    .filter(e -> e.status() == OutboxStatus.PENDING)
                    .count();
        }

        final List<java.time.Instant> purgesAskedFor = new ArrayList<>();

        @Override
        public synchronized int purgeProcessedBefore(java.time.Instant before) {
            purgesAskedFor.add(before);
            return 0;
        }
    }

    private static class StubSchedulerPort implements SchedulerPort {
        boolean repeatingTaskScheduled = false;
        boolean taskClosed = false;

        @Override
        public void onGlobal(Runnable task) {}

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {}

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {}

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {}

        @Override
        public void laterGlobal(Duration delay, Runnable task) {}

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            repeatingTaskScheduled = true;
            return () -> taskClosed = true;
        }
    }
}
