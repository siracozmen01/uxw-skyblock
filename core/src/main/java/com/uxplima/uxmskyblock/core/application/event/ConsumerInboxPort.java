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
}
