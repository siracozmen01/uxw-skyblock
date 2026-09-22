package com.uxplima.uxmskyblock.core.application.event;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * Stream event consumer that guarantees idempotent exactly-once processing over an
 * at-least-once transport (such as Redis Streams) using a durable {@link ConsumerInboxPort}.
 *
 * <p>Invariant: The message is only processed if it is novel in the consumer inbox.
 * Acknowledgment (e.g. XACK) is issued:
 * <ul>
 *   <li>Immediately if the event is a duplicate (idempotent no-op).</li>
 *   <li>Strictly after successful delegate processing if novel.</li>
 * </ul>
 * If delegate processing throws an exception, acknowledgment is withheld so the broker can retry.
 */
public final class InboxDeduplicatingConsumer implements StreamEventHandler {

    private static final Logger LOGGER = Logger.getLogger(InboxDeduplicatingConsumer.class.getName());

    private final String consumerName;
    private final ConsumerInboxPort inboxPort;
    private final OutboxEventConsumer delegate;

    public InboxDeduplicatingConsumer(String consumerName, ConsumerInboxPort inboxPort, OutboxEventConsumer delegate) {
        this.consumerName = Objects.requireNonNull(consumerName, "consumerName must not be null");
        this.inboxPort = Objects.requireNonNull(inboxPort, "inboxPort must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public void onEvent(OutboxEventRecord event, Runnable acknowledge) throws Exception {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(acknowledge, "acknowledge must not be null");

        boolean isNovel = inboxPort.markProcessedIfAbsent(consumerName, event.eventId());
        if (!isNovel) {
            LOGGER.fine(() -> "Skipping duplicate event " + event.eventId() + " for consumer " + consumerName);
            acknowledge.run();
            return;
        }

        try {
            delegate.consume(event);
            acknowledge.run();
        } catch (Exception e) {
            // The mark went down before the work, so a handler that threw has left a mark for work
            // that never happened. The acknowledgment is withheld so the broker retries, and the
            // retry would be read as a duplicate and dropped unless the mark is taken back first.
            try {
                inboxPort.forget(consumerName, event.eventId());
            } catch (RuntimeException failed) {
                LOGGER.log(
                        Level.SEVERE,
                        failed,
                        () -> "Event " + event.eventId() + " failed in " + consumerName + " and its inbox mark "
                                + "could not be taken back, so a retry of it will be read as a duplicate.");
            }
            LOGGER.log(Level.WARNING, "Consumer " + consumerName + " failed to process event " + event.eventId(), e);
            throw e;
        }
    }
}
