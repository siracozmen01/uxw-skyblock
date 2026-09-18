package com.uxplima.uxmskyblock.core.application.event;

import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * Handler interface for stream consumer events with explicit acknowledgment (e.g. Redis Streams XACK).
 */
@FunctionalInterface
public interface StreamEventHandler {

    /**
     * Invoked when an event arrives from the stream.
     *
     * @param event the received event record
     * @param acknowledge callback that must be executed to acknowledge (XACK) the message
     * @throws Exception if processing fails, preventing acknowledgment
     */
    void onEvent(OutboxEventRecord event, Runnable acknowledge) throws Exception;
}
