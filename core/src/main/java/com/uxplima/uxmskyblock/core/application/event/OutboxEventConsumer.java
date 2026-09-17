package com.uxplima.uxmskyblock.core.application.event;

import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * Functional consumer contract for processing outbox events dispatched by {@link TransactionalOutboxDispatcher}.
 */
@FunctionalInterface
public interface OutboxEventConsumer {

    /**
     * Consumes an outbox event.
     *
     * @param event the outbox event record
     * @throws Exception if processing fails, triggering backoff or dead-letter queue escalation
     */
    void consume(OutboxEventRecord event) throws Exception;
}
