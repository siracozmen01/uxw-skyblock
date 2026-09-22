package com.uxplima.uxmskyblock.core.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An event is acted on once, however many times the dispatcher delivers it.
 *
 * <p>Delivery is at least once by design: a worker whose claim lease runs out while it is delivering
 * loses the row to another worker, which delivers it again. Nothing deduplicated that. The consumer
 * inbox was built for exactly this, its table was migrated with an index for sweeping it, and
 * nothing ever wrote a row to it, so a fenced claim meant a marker drawn twice on a web map and a
 * frame sent twice down a socket.
 */
class AnEventIsActedOnOnceHoweverOftenItArrivesTest {

    /** The inbox, in memory, with the same contract the SQL one has. */
    private static class InMemoryInbox implements ConsumerInboxPort {
        private final Set<String> marks = new HashSet<>();
        final AtomicInteger sweeps = new AtomicInteger();

        private static String key(String consumerName, EventId eventId) {
            return consumerName + ':' + eventId.value();
        }

        @Override
        public synchronized boolean markProcessedIfAbsent(String consumerName, EventId eventId) {
            return marks.add(key(consumerName, eventId));
        }

        @Override
        public synchronized boolean isProcessed(String consumerName, EventId eventId) {
            return marks.contains(key(consumerName, eventId));
        }

        @Override
        public synchronized void forget(String consumerName, EventId eventId) {
            marks.remove(key(consumerName, eventId));
        }

        @Override
        public synchronized int purgeProcessedBefore(Instant before) {
            sweeps.incrementAndGet();
            int held = marks.size();
            marks.clear();
            return held;
        }
    }

    private static OutboxEventRecord anEvent(EventId id) {
        return new OutboxEventRecord(
                id,
                "ISLAND_CREATED",
                "isl-1",
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
    @DisplayName("The same event delivered three times reaches the handler once")
    void threeDeliveriesReachTheHandlerOnce() throws Exception {
        InMemoryInbox inbox = new InMemoryInbox();
        AtomicInteger handled = new AtomicInteger();
        DeduplicatingOutboxConsumer consumer =
                new DeduplicatingOutboxConsumer("a-consumer", inbox, event -> handled.incrementAndGet());

        OutboxEventRecord event = anEvent(EventId.random());
        consumer.consume(event);
        consumer.consume(event);
        consumer.consume(event);

        assertThat(handled.get())
                .describedAs("markers drawn, frames sent, work actually done")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Two different events both reach the handler")
    void differentEventsBothArrive() throws Exception {
        InMemoryInbox inbox = new InMemoryInbox();
        AtomicInteger handled = new AtomicInteger();
        DeduplicatingOutboxConsumer consumer =
                new DeduplicatingOutboxConsumer("a-consumer", inbox, event -> handled.incrementAndGet());

        consumer.consume(anEvent(EventId.random()));
        consumer.consume(anEvent(EventId.random()));

        assertThat(handled.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Two consumers each act on the same event once, and neither hides the other")
    void twoConsumersBothAct() throws Exception {
        InMemoryInbox inbox = new InMemoryInbox();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        OutboxEventRecord event = anEvent(EventId.random());

        DeduplicatingOutboxConsumer one =
                new DeduplicatingOutboxConsumer("the-transport", inbox, e -> first.incrementAndGet());
        DeduplicatingOutboxConsumer two =
                new DeduplicatingOutboxConsumer("the-live-feed", inbox, e -> second.incrementAndGet());

        one.consume(event);
        one.consume(event);
        two.consume(event);
        two.consume(event);

        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get())
                .describedAs("a second consumer is not a duplicate of the first")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("An event whose handler threw is delivered again rather than read as a duplicate")
    void afailedEventIsNotSwallowed() throws Exception {
        InMemoryInbox inbox = new InMemoryInbox();
        AtomicInteger attempts = new AtomicInteger();
        DeduplicatingOutboxConsumer consumer = new DeduplicatingOutboxConsumer("a-consumer", inbox, event -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("the first delivery failed");
            }
        });

        OutboxEventRecord event = anEvent(EventId.random());
        assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(IllegalStateException.class);
        consumer.consume(event);

        assertThat(attempts.get())
                .describedAs("a retry the dispatcher makes must not be read as a duplicate")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("An inbox that will not take a mark back says so rather than pretending")
    void anInboxThatCannotForgetSaysSo() {
        ConsumerInboxPort stubborn = new InMemoryInbox() {
            @Override
            public synchronized void forget(String consumerName, EventId eventId) {
                throw new IllegalStateException("the database is gone");
            }
        };
        DeduplicatingOutboxConsumer consumer = new DeduplicatingOutboxConsumer("a-consumer", stubborn, event -> {
            throw new IllegalStateException("the delivery failed");
        });

        assertThatThrownBy(() -> consumer.consume(anEvent(EventId.random())))
                .describedAs("the delivery failure is what the dispatcher must see, not the inbox one")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the delivery failed");
    }
}
