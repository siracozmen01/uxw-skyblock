package com.uxplima.uxmskyblock.core.application.event;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * A consumer that acts on an event once, however many times it is delivered.
 *
 * <p>The dispatcher's delivery is at least once by design: a worker whose claim lease runs out while
 * it is delivering loses the row to another worker, which delivers it again. Nothing deduplicated
 * that. The consumer inbox was built for exactly this, its table was migrated, and nothing ever
 * wrote a row to it, so a fenced claim meant a marker drawn twice on a web map and a frame sent
 * twice down a socket.
 *
 * <p>The mark goes down before the work, because that is the only order in which two deliveries
 * cannot both do it. A handler that throws has therefore left a mark for work that never happened,
 * so the mark is taken back: without that, the retry the dispatcher is about to make would be read
 * as a duplicate and dropped, which is worse than doing it twice.
 */
public final class DeduplicatingOutboxConsumer implements OutboxEventConsumer {

    private static final Logger LOGGER = Logger.getLogger(DeduplicatingOutboxConsumer.class.getName());

    private final String consumerName;
    private final ConsumerInboxPort inboxPort;
    private final OutboxEventConsumer delegate;

    public DeduplicatingOutboxConsumer(String consumerName, ConsumerInboxPort inboxPort, OutboxEventConsumer delegate) {
        this.consumerName = Objects.requireNonNull(consumerName, "consumerName must not be null");
        this.inboxPort = Objects.requireNonNull(inboxPort, "inboxPort must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public void consume(OutboxEventRecord event) throws Exception {
        Objects.requireNonNull(event, "event must not be null");

        if (!inboxPort.markProcessedIfAbsent(consumerName, event.eventId())) {
            LOGGER.fine(() -> "Event " + event.eventId() + " was already handled by " + consumerName + ".");
            return;
        }

        try {
            delegate.consume(event);
        } catch (Exception e) {
            takeTheMarkBack(event);
            throw e;
        }
    }

    private void takeTheMarkBack(OutboxEventRecord event) {
        try {
            inboxPort.forget(consumerName, event.eventId());
        } catch (RuntimeException e) {
            // The mark stands and the retry will be read as a duplicate. That is worth saying out
            // loud, because the event is then handled by nobody.
            LOGGER.log(
                    Level.SEVERE,
                    e,
                    () -> "Event " + event.eventId() + " failed in " + consumerName + " and its inbox mark could "
                            + "not be taken back, so a retry of it will be read as a duplicate.");
        }
    }
}
