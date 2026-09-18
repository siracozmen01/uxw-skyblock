package com.uxplima.uxmskyblock.core.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboxDeduplicatingConsumerTest {

    private StubConsumerInboxPort inboxPort;
    private List<OutboxEventRecord> processedEvents;
    private InboxDeduplicatingConsumer deduplicatingConsumer;

    @BeforeEach
    void setUp() {
        inboxPort = new StubConsumerInboxPort();
        processedEvents = new ArrayList<>();
        deduplicatingConsumer = new InboxDeduplicatingConsumer("test-worker", inboxPort, processedEvents::add);
    }

    private OutboxEventRecord createEvent(EventId id) {
        return new OutboxEventRecord(
                id,
                "ISLAND_CREATED",
                "isl-123",
                "{}",
                OutboxStatus.PENDING,
                null,
                null,
                null,
                0,
                null,
                null,
                Instant.now(),
                null);
    }

    @Test
    @DisplayName("Novel event delegates to consumer and executes acknowledge hook")
    void novelEventProcessesAndAcknowledges() throws Exception {
        EventId eventId = EventId.random();
        OutboxEventRecord event = createEvent(eventId);
        AtomicBoolean acknowledged = new AtomicBoolean(false);

        deduplicatingConsumer.onEvent(event, () -> acknowledged.set(true));

        assertThat(processedEvents).containsExactly(event);
        assertThat(acknowledged.get()).isTrue();
        assertThat(inboxPort.isProcessed("test-worker", eventId)).isTrue();
    }

    @Test
    @DisplayName("Duplicate event skips delegate but still acknowledges (idempotent no-op)")
    void duplicateEventSkipsDelegateAndAcknowledges() throws Exception {
        EventId eventId = EventId.random();
        OutboxEventRecord event = createEvent(eventId);

        // Pre-mark as already processed in inbox
        inboxPort.markProcessedIfAbsent("test-worker", eventId);

        AtomicBoolean acknowledged = new AtomicBoolean(false);
        deduplicatingConsumer.onEvent(event, () -> acknowledged.set(true));

        // Delegate should not have been called
        assertThat(processedEvents).isEmpty();
        // Acknowledge MUST be called to purge/ack the duplicate from the broker
        assertThat(acknowledged.get()).isTrue();
    }

    @Test
    @DisplayName("Delegate exception prevents acknowledgment and propagates")
    void failurePreventsAcknowledgment() {
        EventId eventId = EventId.random();
        OutboxEventRecord event = createEvent(eventId);

        InboxDeduplicatingConsumer failingConsumer = new InboxDeduplicatingConsumer("failing-worker", inboxPort, e -> {
            throw new IllegalStateException("Database deadlock simulated");
        });

        AtomicBoolean acknowledged = new AtomicBoolean(false);

        assertThatThrownBy(() -> failingConsumer.onEvent(event, () -> acknowledged.set(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database deadlock");

        assertThat(acknowledged.get()).isFalse();
    }

    private static class StubConsumerInboxPort implements ConsumerInboxPort {
        private final Set<String> processed = new HashSet<>();

        @Override
        public synchronized boolean markProcessedIfAbsent(String consumerName, EventId eventId) {
            return processed.add(consumerName + ":" + eventId.value());
        }

        @Override
        public synchronized boolean isProcessed(String consumerName, EventId eventId) {
            return processed.contains(consumerName + ":" + eventId.value());
        }
    }
}
