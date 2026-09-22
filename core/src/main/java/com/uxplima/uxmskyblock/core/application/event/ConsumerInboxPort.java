package com.uxplima.uxmskyblock.core.application.event;

import com.uxplima.uxmskyblock.core.domain.event.EventId;

/**
 * Outbound application port for idempotent consumer deduplication.
 */
public interface ConsumerInboxPort {

    /**
     * Atomically marks the event as processed for the consumer if not already recorded.
     * Returns true if newly marked (first-time delivery), or false if already processed.
     */
    boolean markProcessedIfAbsent(String consumerName, EventId eventId);

    /**
     * Returns true if the event was already processed by this consumer.
     */
    boolean isProcessed(String consumerName, EventId eventId);

    /**
     * Takes back a mark, so an event whose handling failed can be delivered again.
     *
     * <p>Marking first and handling second is the only order that keeps two deliveries from both
     * being handled, but it means a handler that throws has left a mark for work that never
     * happened. Without this the retry the transport is about to make would be read as a duplicate
     * and dropped, which is the one outcome worse than doing it twice.
     */
    void forget(String consumerName, EventId eventId);

    /**
     * Deletes marks older than {@code before}, and answers how many went.
     *
     * <p>One row per consumer per event, and nothing ever deleted one. The schema carries an index
     * on {@code processed_at} and it exists for exactly this sweep, which nothing ran.
     *
     * @param before the moment before which a mark is no longer worth keeping
     * @return how many rows went
     */
    int purgeProcessedBefore(java.time.Instant before);
}
